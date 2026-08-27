package mtr.data;

import mtr.TrigCache;
import mtr.block.*;
import mtr.mappings.Utilities;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Unit;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.msgpack.value.Value;

import java.util.*;
import java.util.function.Consumer;

public class TrainServer extends Train {

	private boolean canDeploy;
	private Depot currentDepot;
	private boolean returningToDepot;
	private long timetableDepartureMillis = -1;
	/** Cached because the path never changes for a given train, and the catch-up check reads it every tick. */
	private int originIndex = -2;
	private boolean wasHoldingAtOrigin;
	private SignalBlocks signalBlocks;
	private List<Map<UUID, Long>> trainPositions;
	private Map<Player, Set<TrainServer>> trainsInPlayerRange = new HashMap<>();
	private Map<Long, Map<BlockPos, TrainDelay>> trainDelays = new HashMap<>();
	private long routeId;
	private int updateRailProgressCounter;
	private int manualCoolDown;

	private final List<Siding.TimeSegment> timeSegments;

	private static final int TRAIN_UPDATE_DISTANCE = 128;
	private static final int TICKS_TO_SEND_RAIL_PROGRESS = 40;
	/**
	 * How much room to leave between the front of one train and the back of the next before holding back. Generous
	 * enough to cover the braking curve at line speed, and the ceiling on how close trains will ever bunch up.
	 */
	private static final double SAFE_FOLLOWING_DISTANCE = 48;
	/** How far apart the points ahead of a carrying vehicle are, and how many of them, when warming chunks. */
	private static final int PRELOAD_STEP_BLOCKS = 32;
	private static final int PRELOAD_STEPS = 3;
	/** Expires on its own after ten seconds, so nothing here ever has to be released. */
	private static final TicketType<Unit> PRELOAD_TICKET = TicketType.create("mtr_vehicle_ahead", (a, b) -> 0, 200);
	/** The chunk this vehicle last warmed from, so it asks once per chunk crossed rather than once per tick. */
	private long lastPreloadedChunk = Long.MIN_VALUE;
	/** Path rails scanned ahead when looking for a train to follow. */
	private static final int FOLLOWING_LOOKAHEAD = 48;

	public TrainServer(long id, long sidingId, float railLength, String trainId, String baseTrainType, int trainCars, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, List<Siding.TimeSegment> timeSegments, boolean isManual, int maxManualSpeed, int manualToAutomaticTime) {
		super(id, sidingId, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime);
		this.timeSegments = timeSegments;
	}

	public TrainServer(
			long sidingId, float railLength, List<Siding.TimeSegment> timeSegments,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManual, int maxManualSpeed, int manualToAutomaticTime,
			Map<String, Value> map
	) {
		super(sidingId, railLength, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime, map);
		this.timeSegments = timeSegments;
	}

	@Deprecated
	public TrainServer(
			long sidingId, float railLength, List<Siding.TimeSegment> timeSegments,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManual, int maxManualSpeed, int manualToAutomaticTime,
			CompoundTag compoundTag
	) {
		super(sidingId, railLength, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime, compoundTag);
		this.timeSegments = timeSegments;
	}

	@Override
	protected void startUp(Level world, int trainCars, int trainSpacing, boolean isOppositeRail) {
		canDeploy = false;
		isOnRoute = true;
		elapsedDwellTicks = 0;
		speed = Train.ACCELERATION_DEFAULT;
		if (isOppositeRail) {
			railProgress += trainCars * trainSpacing;
			reversed = !reversed;
		}
		nextStoppingIndex = getNextStoppingIndex();
		super.startUp(world, trainCars, trainSpacing, isOppositeRail);
	}

	@Override
	protected boolean openDoors() {
		if (isCurrentlyManual) {
			return doorTarget;
		} else {
			if (transportMode.continuousMovement) {
				final int index = getIndex(railProgress, false);
				if (path.get(index).dwellTime > 0 && index > 0) {
					final double doorValue1 = (railProgress - distances.get(index - 1)) * 0.5;
					final double doorValue2 = (distances.get(index) - railProgress) * 0.5;
					return doorValue1 > 0 && (doorValue2 > doorValue1 || doorValue2 > 1);
				} else {
					return false;
				}
			} else {
				// Identical to the platform's own dwell normally, but at a timetabled origin this is the stretched
				// wait, so the doors stay open until the booked departure instead of shutting after the nominal dwell
				final int dwellTicks = getTotalDwellTicks();
				final float maxDoorMoveTime = Math.min(DOOR_MOVE_TIME, dwellTicks / 2 - DOOR_DELAY);
				return elapsedDwellTicks >= DOOR_DELAY && elapsedDwellTicks < dwellTicks - DOOR_DELAY - maxDoorMoveTime;
			}
		}
	}

	@Override
	protected void simulateCar(
			Level world, int ridingCar, float ticksElapsed,
			double carX, double carY, double carZ, float carYaw, float carPitch,
			double prevCarX, double prevCarY, double prevCarZ, float prevCarYaw, float prevCarPitch,
			boolean doorLeftOpen, boolean doorRightOpen, double realSpacing
	) {
		VehicleRidingServer.mountRider(world, ridingEntities, id, routeId, carX, carY, carZ, realSpacing, width, carYaw, carPitch, doorLeftOpen || doorRightOpen, isManualAllowed || doorLeftOpen || doorRightOpen, ridingCar, PACKET_UPDATE_TRAIN_PASSENGERS, player -> !isManualAllowed || doorLeftOpen || doorRightOpen || Train.isHoldingKey(player), player -> {
			if (isHoldingKey(player)) {
				manualCoolDown = 0;
			}
		});
	}

	@Override
	protected boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed) {
		final AABB trainAABB = new AABB(positions[0], positions[positions.length - 1]).inflate(TRAIN_UPDATE_DISTANCE);
		final boolean[] playerNearby = {false};
		world.players().forEach(player -> {
			if (isPlayerRiding(player) || trainAABB.contains(player.position())) {
				if (!trainsInPlayerRange.containsKey(player)) {
					trainsInPlayerRange.put(player, new HashSet<>());
				}
				trainsInPlayerRange.get(player).add(this);
				playerNearby[0] = true;
			}
		});

		final BlockPos frontPos = RailwayData.newBlockPos(positions[reversed ? positions.length - 1 : 0]);
		if (RailwayData.chunkLoaded(world, frontPos)) {
			checkBlock(frontPos, checkPos -> {
				if (RailwayData.chunkLoaded(world, checkPos)) {
					final BlockState state = world.getBlockState(checkPos);
					final Block block = state.getBlock();

					if (block instanceof BlockTrainRedstoneSensor && BlockTrainSensorBase.matchesFilter(world, checkPos, routeId, speed)) {
						((BlockTrainRedstoneSensor) block).power(world, state, checkPos);
					}

					if ((block instanceof BlockTrainCargoLoader || block instanceof BlockTrainCargoUnloader) && BlockTrainSensorBase.matchesFilter(world, checkPos, routeId, speed)) {
						for (final Direction direction : Direction.values()) {
							final Container nearbyInventory = HopperBlockEntity.getContainerAt(world, checkPos.relative(direction));
							if (nearbyInventory != null) {
								if (block instanceof BlockTrainCargoLoader) {
									transferItems(nearbyInventory, inventory);
								} else {
									transferItems(inventory, nearbyInventory);
								}
							}
						}
					}
				}
			});
		}

		if (!ridingEntities.isEmpty() && RailwayData.chunkLoaded(world, frontPos)) {
			checkBlock(frontPos, checkPos -> {
				if (RailwayData.chunkLoaded(world, checkPos) && world.getBlockState(checkPos).getBlock() instanceof BlockTrainAnnouncer) {
					final BlockEntity entity = world.getBlockEntity(checkPos);
					if (entity instanceof BlockTrainAnnouncer.TileEntityTrainAnnouncer && ((BlockTrainAnnouncer.TileEntityTrainAnnouncer) entity).matchesFilter(routeId, speed)) {
						ridingEntities.forEach(uuid -> ((BlockTrainAnnouncer.TileEntityTrainAnnouncer) entity).announce(world.getPlayerByUUID(uuid)));
					}
				}
			});
		}

		return playerNearby[0];
	}

	@Override
	protected boolean canDeploy(Depot depot) {
		if (path.size() > 1 && depot != null) {
			depot.requestDeploy(sidingId, this);
		}
		return canDeploy;
	}

	/** The vehicle a hold was last reported against, so a stuck train says so once rather than twenty times a second. */
	private long lastHoldReported = -1;
	/** When that report went out, because a train pinned against a block never satisfies "it started moving again". */
	private long lastHoldReportedAt = 0;
	private static final long HOLD_REPORT_INTERVAL_MILLIS = 30000;

	/**
	 * Says once, out loud, that this train is being held and by what.
	 *
	 * A train that will not leave looks identical to a depot that will not dispatch, and the two have completely
	 * different causes. Without this the only way to tell them apart is to guess.
	 */
	private void reportHold(long blockingTrainId, String reason) {
		final long now = System.currentTimeMillis();
		// Both conditions, not either. Reporting once per blocker was meant to be enough, but a train pinned against
		// a block is not stationary: the following limit lets it creep, the block check stops it, and it does that
		// several times a second. Every creep counted as having started moving again, so the "say it once" reset
		// fired every other tick and the same line went out twenty times a second until the disk filled.
		if (lastHoldReported == blockingTrainId && now - lastHoldReportedAt < HOLD_REPORT_INTERVAL_MILLIS) {
			return;
		}
		lastHoldReported = blockingTrainId;
		lastHoldReportedAt = now;
		final String blocker = signalBlocks == null ? "" : " [" + signalBlocks.describeTrain(blockingTrainId) + "]";
		System.out.println("Vehicle " + id + " on siding " + sidingId + " is held by vehicle " + blockingTrainId
				+ blocker + ": " + reason);
	}

	@Override
	protected boolean isRailBlocked(int checkIndex) {
		if (!transportMode.continuousMovement && trainPositions != null && checkIndex < path.size()) {
			final PathData pathData = path.get(checkIndex);
			final UUID railProduct = pathData.getRailProduct();
			final Vec3 myDirection = myTravelDirection();
			for (final Map<UUID, Long> trainPositionsMap : trainPositions) {
				final Long occupyingTrainId = trainPositionsMap.get(railProduct);
				if (occupyingTrainId != null && occupyingTrainId != id) {
					// A long section held end to end is what makes traffic queue at its mouth. If the train in
					// there is going the same way and is still most of a section away, keep closing on it instead
					// of stopping at the entrance. Closing on a train coming the other way would only put the two
					// of them nose to nose in the middle, so opposing traffic keeps the whole-section claim.
					if (isSameDirection(myDirection, occupyingTrainId) && hasClearanceBehind(occupyingTrainId)) {
						continue;
					}
					if (yieldsToMe(occupyingTrainId)) {
						continue;
					}
					if (signalBlocks != null) {
						signalBlocks.setTrainBlockedBy(id, occupyingTrainId);
					}
					reportHold(occupyingTrainId, isSameDirection(myDirection, occupyingTrainId)
							? "it is ahead on the same track and too close to follow"
							: "it is on the same track facing the other way, or its position is not known");
					if (routeId != 0) {
						if (!trainDelays.containsKey(routeId)) {
							trainDelays.put(routeId, new HashMap<>());
						}
						if (!trainDelays.get(routeId).containsKey(pathData.startingPos)) {
							trainDelays.get(routeId).put(pathData.startingPos, new TrainDelay());
						}
						trainDelays.get(routeId).get(pathData.startingPos).delaying();
					}
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Caps this train's speed by how close the train in front is, so that a long section carries several trains
	 * running nose to tail rather than one train at a time.
	 *
	 * A signal block is claimed whole, so scanning the path ahead finds the boundary of an occupied block, not the
	 * train inside it. The occupancy tells us which train to look at; where it actually is comes from its own
	 * reported tail. Straight-line distance can only understate the separation along a winding track, never
	 * overstate it, so the limit errs towards braking early.
	 *
	 * The result is the fastest speed the train could still pull up from inside the gap it can see, less the margin
	 * it means to keep, which is a brake curve rather than a stop line: the train eases off as it closes and comes
	 * to rest a margin short only if the train ahead never moves.
	 */
	@Override
	protected float getFollowingSpeedLimit() {
		if (transportMode.continuousMovement || trainPositions == null || signalBlocks == null) {
			return Float.MAX_VALUE;
		}

		final Vec3 myHead = getHeadPosition();
		final Vec3 myDirection = travelDirection(myHead, getTailPosition());
		if (myHead == null || myDirection == null) {
			return Float.MAX_VALUE;
		}

		double nearestGap = -1;
		final int startIndex = getIndex(0, spacing, false);
		for (int i = startIndex; i < path.size() && i < startIndex + FOLLOWING_LOOKAHEAD; i++) {
			final UUID railProduct = path.get(i).getRailProduct();
			for (final Map<UUID, Long> trainPositionsMap : trainPositions) {
				final Long occupyingTrainId = trainPositionsMap.get(railProduct);
				if (occupyingTrainId == null || occupyingTrainId == id) {
					continue;
				}
				// Opposing traffic is stopped at the section boundary by the claim rather than paced, and a train
				// being passed to break a deadlock is one this train is deliberately not waiting for
				if (!isSameDirection(myDirection, occupyingTrainId) || yieldsToMe(occupyingTrainId)) {
					continue;
				}
				final Vec3 leaderTail = signalBlocks.getTrainTail(occupyingTrainId);
				if (leaderTail == null) {
					continue;
				}
				final double gap = myHead.distanceTo(leaderTail);
				if (nearestGap < 0 || gap < nearestGap) {
					nearestGap = gap;
				}
			}
		}

		if (nearestGap < 0) {
			return Float.MAX_VALUE;
		}
		final double usableGap = nearestGap - SAFE_FOLLOWING_DISTANCE;
		// Never zero. Deciding that a train may not move at all belongs to the block check, which knows about
		// claims and reversing and yielding; this only decides how fast it may close on the train in front. A cap
		// of zero here takes the train's own movement away from the one place that can give it back, and a train
		// leaving a siding within the margin of its stabled neighbours could not start at all.
		return usableGap <= 0 ? Train.ACCELERATION_DEFAULT : Math.max(Train.ACCELERATION_DEFAULT, (float) Math.sqrt(2 * accelerationConstant * usableGap));
	}

	/**
	 * Whether the train occupying the rail ahead is travelling the same way as this one.
	 *
	 * Both trains report their own front and back, and rail progress only ever grows along a path, so front minus
	 * back is the direction of travel whichever rail of a pair the train is drawn on. Two trains approaching each
	 * other therefore point opposite ways and the dot product is negative.
	 *
	 * Unknown counts as opposing. Being wrong that way costs a train a wait at a section boundary; being wrong the
	 * other way puts two trains nose to nose in the middle of a section neither can back out of.
	 */
	private boolean isSameDirection(Vec3 myDirection, long otherTrainId) {
		if (signalBlocks == null || myDirection == null) {
			return false;
		}
		final Vec3 theirs = travelDirection(signalBlocks.getTrainHead(otherTrainId), signalBlocks.getTrainTail(otherTrainId));
		return theirs != null && myDirection.dot(theirs) > 0;
	}

	/** This train's own direction of travel. Reading both ends walks the path, so callers work it out once. */
	private Vec3 myTravelDirection() {
		return travelDirection(getHeadPosition(), getTailPosition());
	}

	private static Vec3 travelDirection(Vec3 head, Vec3 tail) {
		if (head == null || tail == null) {
			return null;
		}
		final Vec3 direction = head.subtract(tail);
		// A train is at least one car long, so anything shorter than a block is bad data rather than a short train
		return direction.lengthSqr() < 1 ? null : direction;
	}

	/**
	 * Whether the train ahead is itself waiting on this one, and this one has the lower id.
	 *
	 * Two trains holding the track the other needs are stuck permanently: neither can reverse out of a path, so
	 * without an outside decision they stand there until someone breaks the railway up by hand. Each train
	 * publishes what is holding it up, so the pair can be recognised from either side, and the lower id proceeds —
	 * an arbitrary rule, but one both sides compute identically, so exactly one of them moves. Trains have no
	 * collision, so passing costs a moment of overlap and returns the line to service.
	 *
	 * Only a circle of two is broken. Three trains blocking in a ring stay stuck, which is a layout to fix rather
	 * than a case to paper over.
	 */
	private boolean yieldsToMe(long otherTrainId) {
		return signalBlocks != null && TrainDeadlock.proceeds(id, otherTrainId, signalBlocks.isTrainBlockedBy(otherTrainId, id));
	}

	/**
	 * The speed this train needs to average to reach its booked departure on time, in blocks per tick.
	 *
	 * Zero unless it is actually behind: on time or early, the ordinary limits are what apply. When it is behind,
	 * this is the exact average that closes the gap and no more, so a train recovers a late start without ever
	 * running faster than it has to. It is recomputed every tick, so it eases back down as the deficit shrinks.
	 *
	 * The rail type's own maximum still caps it. Only the per-rail limits a builder set are overridden, and only
	 * for as long as the train is behind.
	 */
	@Override
	protected float getCatchUpSpeed() {
		if (currentDepot == null || !currentDepot.strictTimetable || transportMode.continuousMovement) {
			return 0;
		}
		if (!isOnRoute || timetableDepartureMillis < 0 || distances.isEmpty()) {
			return 0;
		}

		final int origin = getOriginIndex();
		if (origin < 0 || origin >= distances.size()) {
			return 0;
		}

		final double remaining = distanceToOrigin(origin);
		if (remaining <= 0) {
			return 0;
		}

		// It has to be standing at the platform a dwell before it is due away, not merely arriving as it departs
		final long dwellMillis = (long) (path.get(origin).dwellTime * 10 * Depot.MILLIS_PER_TICK);
		final long remainingMillis = timetableDepartureMillis - dwellMillis - System.currentTimeMillis();
		if (remainingMillis <= 0) {
			// Beyond recovering by speed alone; the shortened dwell at the origin is what is left
			return 0;
		}

		// Time spent standing at the stops in between is time not available for running
		final double runningTicks = remainingMillis / (double) Depot.MILLIS_PER_TICK - intermediateDwellTicks(origin);
		return TrainCatchUp.peakSpeedToArriveIn(remaining, runningTicks, speed, accelerationConstant);
	}

	/** Dwell still to be served at the stops between here and the origin, in ticks. */
	private double intermediateDwellTicks(int origin) {
		final int index = getIndex(0, spacing, true);
		double ticks = 0;
		if (index <= origin) {
			for (int i = index + 1; i < origin; i++) {
				ticks += path.get(i).dwellTime * 10;
			}
			return ticks;
		}
		if (!isRepeat() || repeatIndex2 <= 0 || repeatIndex2 >= path.size()) {
			return 0;
		}
		// Round the rest of the loop, then from where the wrap puts it back up to the origin
		for (int i = index + 1; i <= repeatIndex2; i++) {
			ticks += path.get(i).dwellTime * 10;
		}
		for (int i = repeatIndex1; i < origin; i++) {
			ticks += path.get(i).dwellTime * 10;
		}
		return ticks;
	}

	/** How far this train still has to run before it is standing at the origin platform again. */
	private double distanceToOrigin(int origin) {
		final int index = getIndex(0, spacing, true);
		if (index <= origin) {
			return distances.get(origin) - railProgress;
		}
		if (!isRepeat() || repeatIndex2 <= 0 || repeatIndex2 >= distances.size()) {
			// A route that ends rather than loops has nothing further to be on time for
			return 0;
		}
		// Round the rest of the loop, then back up to the origin from where the wrap puts it
		final double wrapPoint = distances.get(repeatIndex1 > 0 ? repeatIndex1 - 1 : 0);
		return distances.get(repeatIndex2) - railProgress + Math.max(0, distances.get(origin) - wrapPoint);
	}

	/**
	 * Whether the train occupying the rail ahead is far enough away to keep approaching it.
	 *
	 * Straight-line distance can only ever understate how far apart two points on a track are, never overstate it, so
	 * treating it as the separation errs towards holding back. Two trains closing head on both see each other inside
	 * the margin and both stop, which is the behaviour they had before rather than anything worse.
	 *
	 * The stopping target while moving is the end of the train's current rail, and that moves forward with it, so a
	 * train cleared to proceed creeps in a rail at a time and comes to rest within about one rail of the margin.
	 */
	private boolean hasClearanceBehind(long occupyingTrainId) {
		if (signalBlocks == null) {
			return false;
		}
		final Vec3 leaderTail = signalBlocks.getTrainTail(occupyingTrainId);
		final Vec3 myHead = getHeadPosition();
		if (leaderTail == null || myHead == null) {
			return false;
		}
		return myHead.distanceTo(leaderTail) > SAFE_FOLLOWING_DISTANCE;
	}

	/**
	 * A repeating train under a strict timetable stops repeating when its next departure is far off, so that it runs
	 * to the end of its path, comes off route and stables in the depot, where the ordinary dispatch gate already
	 * knows how to hold it. Short gaps are better spent standing at the origin, which {@link #getTotalDwellTicks}
	 * covers; only a long wait is worth clearing the platform for.
	 *
	 * Overriding isRepeat is what makes the train fall out of the loop, and it is deliberately latched rather than
	 * recomputed every tick: the decision is taken once, while the train stands at the end of the loop, so that the
	 * projected schedule does not flicker as the clock moves.
	 */
	@Override
	protected boolean isRepeat() {
		return super.isRepeat() && !returningToDepot;
	}

	private void updateTimetableStabling() {
		if (currentDepot == null || !currentDepot.strictTimetable || transportMode.continuousMovement) {
			returningToDepot = false;
			timetableDepartureMillis = -1;
			wasHoldingAtOrigin = false;
			return;
		}

		updateTimetableTarget();

		if (!super.isRepeat()) {
			returningToDepot = false;
		} else if (!isOnRoute) {
			// Back in the depot, so the decision has done its job and the normal dispatch gate takes over
			returningToDepot = false;
		} else if (speed <= 0 && getIndex(railProgress, false) >= repeatIndex2) {
			// Standing at the end of the loop, the moment before it would otherwise wrap round
			// The target already stepped on when the train pulled away from the origin, so it names the departure
			// this train would run next. A train that is merely late has a target in the past and keeps going.
			final long now = System.currentTimeMillis();
			final long next = timetableDepartureMillis >= 0 ? timetableDepartureMillis : currentDepot.findDeparture(now, true);
			returningToDepot = next >= 0 && next - now > currentDepot.getStablingThresholdMillis();
		}
	}

	/**
	 * Keeps {@link #timetableDepartureMillis} pointing at the booked departure this train is currently running to.
	 *
	 * The dispatch stamps the first one on the way out of the siding. After that the train never touches the depot
	 * again while it repeats, so each time it pulls away from the origin the target steps on to the departure after
	 * the one it just ran. A train that finds itself at the origin with no target at all — a server restart, or a
	 * timetable switched on while it was already running — adopts the next departure due.
	 */
	private void updateTimetableTarget() {
		if (!isOnRoute) {
			// Stabled: the dispatch gate owns the choice, and stamps it as the train leaves
			wasHoldingAtOrigin = false;
			return;
		}

		if (isAtTimetabledOrigin()) {
			if (timetableDepartureMillis < 0) {
				timetableDepartureMillis = currentDepot.findDeparture(System.currentTimeMillis(), true);
			}
			wasHoldingAtOrigin = true;
		} else if (wasHoldingAtOrigin) {
			wasHoldingAtOrigin = false;
			if (timetableDepartureMillis >= 0) {
				timetableDepartureMillis = currentDepot.findDeparture(timetableDepartureMillis + 1, true);
			}
		}
	}

	/**
	 * Whether this train is standing at the origin platform of a depot that runs to its timetable.
	 *
	 * A repeating train jumps railProgress back to the start of the loop and never returns to the depot, so the
	 * departure list that governs dispatch is never consulted a second time without this.
	 */
	private boolean isAtTimetabledOrigin() {
		if (currentDepot == null || !currentDepot.strictTimetable || transportMode.continuousMovement) {
			return false;
		}
		if (!isOnRoute || speed > 0) {
			return false;
		}
		final int origin = getOriginIndex();
		// Only the first platform of the route; every other stop keeps its ordinary dwell
		return origin >= 0 && Math.abs(getIndex(0, spacing, true) - origin) <= 1;
	}

	/**
	 * Where the route's first platform sits in the path, or -1 if the path has none.
	 *
	 * A repeating route wraps back to this same point, so one rule covers both: it is the first stop that is a
	 * platform rather than the siding the train started from. Using the repeat index instead would have left every
	 * non-repeating route without an origin at all.
	 */
	private int getOriginIndex() {
		if (originIndex == -2) {
			originIndex = -1;
			for (int i = 0; i < path.size(); i++) {
				final PathData pathData = path.get(i);
				if (pathData.dwellTime > 0 && pathData.savedRailBaseId != 0 && pathData.savedRailBaseId != sidingId) {
					originIndex = i;
					break;
				}
			}
		}
		return originIndex;
	}

	/**
	 * Stretches the dwell at a timetabled origin so the train leaves exactly on its booked departure.
	 *
	 * The scheduled time is what the train departs on, not the dwell: once it is due it leaves even if it has stood
	 * for less than the platform's dwell time, and if it is early it keeps waiting with its doors open past that
	 * dwell. Expressing the wait as a dwell length rather than as a block means addons that replace the movement
	 * loop still honour it, because they read the dwell from here either way.
	 */
	@Override
	public int getTotalDwellTicks() {
		final int base = super.getTotalDwellTicks();
		if (!isAtTimetabledOrigin()) {
			return base;
		}

		if (timetableDepartureMillis < 0) {
			// No timetable to follow, so behave normally
			return base;
		}

		final long untilDeparture = timetableDepartureMillis - System.currentTimeMillis();
		if (untilDeparture <= 0) {
			// Already past its booked time, so the stop is what is costing it. It leaves straight away without
			// dwelling and makes the rest up on the run; standing here would only push it further behind.
			return 0;
		}
		// Hold exactly as long as is left, measured from where the dwell has already got to. The doors look after
		// themselves: openDoors shuts them a door cycle before this total runs out, so they close on the booked
		// departure rather than after the nominal dwell.
		return Math.max(base, (int) Math.ceil(elapsedDwellTicks + (double) untilDeparture / Depot.MILLIS_PER_TICK));
	}

	@Override
	protected boolean skipScanBlocks(Level world, double trainX, double trainY, double trainZ) {
		return world.getNearestPlayer(trainX, trainY, trainZ, MAX_CHECK_DISTANCE, entity -> true) == null;
	}

	@Override
	protected boolean openDoors(Level world, Block block, BlockPos checkPos, int dwellTicks) {
		if (block instanceof BlockPSDAPGDoorBase) {
			for (int i = -1; i <= 1; i++) {
				final BlockPos doorPos = checkPos.above(i);
				final BlockState state = world.getBlockState(doorPos);
				final Block doorBlock = state.getBlock();
				final BlockEntity entity = world.getBlockEntity(doorPos);

				if (doorBlock instanceof BlockPSDAPGDoorBase && entity instanceof BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase && IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.UNLOCKED)) {
					final int doorStateValue = (int) Mth.clamp(doorValue * DOOR_MOVE_TIME, 0, BlockPSDAPGDoorBase.MAX_OPEN_VALUE);
					((BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase) entity).setOpen(doorStateValue);

					if (doorStateValue > 0 && !world.getBlockTicks().hasScheduledTick(doorPos, doorBlock)) {
						/* This schedules the block tick to the door (Ensures the door will be closed when the train passes by) */
						Utilities.scheduleBlockTick(world, doorPos, doorBlock, dwellTicks);
					}
				}
			}
		}

		return false;
	}

	@Override
	protected double asin(double value) {
		return TrigCache.asin(value);
	}

	public boolean simulateTrain(Level world, float ticksElapsed, Depot depot, DataCache dataCache, List<Map<UUID, Long>> trainPositions, Map<Player, Set<TrainServer>> trainsInPlayerRange, Map<Long, List<ScheduleEntry>> schedulesForPlatform, Map<Long, Map<BlockPos, TrainDelay>> trainDelays) {
		this.trainPositions = trainPositions;
		this.trainsInPlayerRange = trainsInPlayerRange;
		this.trainDelays = trainDelays;
		this.currentDepot = depot;
		updateTimetableStabling();
		if (signalBlocks != null) {
			// Cleared before this train's own checks run; isRailBlocked sets it again if it is still held up
			signalBlocks.clearTrainBlocked(id);
		}
		keepChunksAheadLoaded(world);
		if (speed > Train.ACCELERATION_DEFAULT) {
			// Genuinely under way, rather than creeping against a block, so the next hold is a new event
			lastHoldReported = -1;
		}
		final int oldStoppingIndex = nextStoppingIndex;
		final int oldPassengerCount = ridingEntities.size();
		final boolean oldIsCurrentlyManual = isCurrentlyManual;
		final boolean oldStopped = speed == 0;
		final boolean oldDoorOpen = doorTarget;

		simulateTrain(world, ticksElapsed, depot);

		final int nextDepartureTicks = isOnRoute ? 0 : depot.getNextDepartureMillis();
		final long currentMillis = System.currentTimeMillis() - (long) (elapsedDwellTicks * Depot.MILLIS_PER_TICK) + (long) Math.max(0, nextDepartureTicks);

		double currentTime = -1;
		int startingIndex = 0;
		for (final Siding.TimeSegment timeSegment : timeSegments) {
			if (RailwayData.isBetween(railProgress, timeSegment.startRailProgress, timeSegment.endRailProgress)) {
				currentTime = timeSegment.getTime(railProgress);
				break;
			}
			startingIndex++;
		}

		if (currentTime >= 0) {
			float offsetTime = 0;
			float offsetTimeTemp = 0;
			boolean secondRound = false;
			Runnable addSchedule = null;
			routeId = 0;
			for (int i = startingIndex; i < timeSegments.size() + (isRepeat() ? timeSegments.size() : 0); i++) {
				final Siding.TimeSegment timeSegment = timeSegments.get(i % timeSegments.size());

				if (timeSegment.savedRailBaseId != 0) {
					if (timeSegment.routeId == 0) {
						RailwayData.useRoutesAndStationsFromIndex(path.get(getIndex(timeSegment.endRailProgress, true)).stopIndex - 1, depot.routeIds, dataCache, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
							timeSegment.routeId = thisRoute == null ? 0 : thisRoute.id;
							timeSegment.currentStationIndex = currentStationIndex;
						});
					}

					final long platformId = timeSegment.savedRailBaseId;
					if (!schedulesForPlatform.containsKey(platformId)) {
						schedulesForPlatform.put(platformId, new ArrayList<>());
					}

					if (secondRound) {
						offsetTime = offsetTimeTemp - timeSegment.endTime;
						secondRound = false;
					} else if (addSchedule != null) {
						addSchedule.run();
					}

					if (isOnRoute || nextDepartureTicks >= 0) {
						final long arrivalMillis = currentMillis + (long) ((timeSegment.endTime + offsetTime - currentTime) * Depot.MILLIS_PER_TICK);
						addSchedule = () -> schedulesForPlatform.get(platformId).add(new ScheduleEntry(arrivalMillis, trainCars, timeSegment.routeId, timeSegment.currentStationIndex));
						if (!isRepeat()) {
							addSchedule.run();
							addSchedule = null;
						}
					}

					offsetTimeTemp = timeSegment.endTime;
				}

				if (routeId == 0) {
					routeId = timeSegment.routeId;
				}

				if (i == timeSegments.size() - 1) {
					secondRound = true;
				}
			}
		}

		updateRailProgressCounter++;
		if (updateRailProgressCounter == TICKS_TO_SEND_RAIL_PROGRESS) {
			updateRailProgressCounter = 0;
		}

		if (isManualAllowed) {
			if (isOnRoute) {
				if (manualCoolDown >= manualToAutomaticTime * 10) {
					if (isCurrentlyManual) {
						final int dwellTicks = nextStoppingIndex >= path.size() ? 0 : path.get(nextStoppingIndex).dwellTime * 10;
						elapsedDwellTicks = doorTarget ? dwellTicks / 2F : dwellTicks;
					}
					isCurrentlyManual = false;
				} else {
					manualCoolDown++;
					isCurrentlyManual = true;
				}
			} else {
				manualCoolDown = 0;
				isCurrentlyManual = true;
			}
		} else {
			isCurrentlyManual = false;
		}

		return oldPassengerCount > ridingEntities.size() || oldStoppingIndex != nextStoppingIndex || oldIsCurrentlyManual != isCurrentlyManual || oldStopped && speed != 0 || oldDoorOpen != doorTarget;
	}

	public void writeTrainPositions(List<Map<UUID, Long>> trainPositions, SignalBlocks signalBlocks) {
		// Kept so isRailBlocked can ask where the train ahead is; this is the only place it is handed to us
		this.signalBlocks = signalBlocks;
		final Vec3 head = getHeadPosition();
		final Vec3 tail = getTailPosition();
		if (head != null && tail != null) {
			signalBlocks.setTrainPosition(id, head, tail, isOnRoute);
		}
		if (!path.isEmpty()) {
			final int headIndex = getIndex(0, spacing, true);
			final int tailIndex = getIndex(trainCars, spacing, false);
			for (int i = tailIndex; i <= headIndex; i++) {
				final PathData pathData = path.get(i);
				if (i > 0 && pathData.savedRailBaseId != sidingId && pathData.rail.railType.hasSignal) {
					signalBlocks.occupy(pathData.getRailProduct(), trainPositions, id);
				}
			}
		}
	}

	/**
	 * Asks the server to keep the chunks a short way ahead of a carrying vehicle in memory.
	 *
	 * This does not make a client receive chunks any faster — that is the game's own business and nothing here can
	 * change it. What it does fix is the server being caught loading ground off disk at the moment a rider arrives
	 * on it, which is what a long fast run does: the vehicle outruns everything the server has warm, and each new
	 * chunk is fetched while somebody is already standing on top of it.
	 *
	 * Deliberately small. Only vehicles actually carrying somebody, only a few points along the way, and on a
	 * ticket that expires by itself, so nothing has to be released and a forgotten vehicle cannot pin the map open.
	 * Straight-line extrapolation is enough at this range: it is aiming at chunks, not at track.
	 */
	private void keepChunksAheadLoaded(Level world) {
		if (ridingEntities.isEmpty() || speed <= 0 || !(world instanceof ServerLevel)) {
			return;
		}
		final Vec3 head = getHeadPosition();
		final Vec3 direction = myTravelDirection();
		if (head == null || direction == null) {
			return;
		}
		final ChunkPos here = new ChunkPos(RailwayData.newBlockPos(head));
		if (here.toLong() == lastPreloadedChunk) {
			// Only when the vehicle crosses into a new chunk, rather than every tick it spends in one
			return;
		}
		lastPreloadedChunk = here.toLong();
		final Vec3 step = direction.normalize().scale(PRELOAD_STEP_BLOCKS);
		Vec3 ahead = head;
		for (int i = 0; i < PRELOAD_STEPS; i++) {
			ahead = ahead.add(step);
			((ServerLevel) world).getChunkSource().addRegionTicket(
					PRELOAD_TICKET, new ChunkPos(RailwayData.newBlockPos(ahead)), 0, Unit.INSTANCE);
		}
	}

	public void deployTrain() {
		canDeploy = true;
	}

	/** Called by the siding each tick, because this is not something the train carries in its own saved data. */
	public void setDoorsWithoutPlatform(boolean doorsWithoutPlatform) {
		this.doorsWithoutPlatform = doorsWithoutPlatform;
	}

	/** Called by the dispatch gate with the wall clock time of the departure this run is booked against. */
	public void setTimetableDeparture(long departureMillis) {
		timetableDepartureMillis = departureMillis;
		wasHoldingAtOrigin = false;
	}

	private int getNextStoppingIndex() {
		final int headIndex = getIndex(0, 0, false);
		for (int i = headIndex; i < path.size(); i++) {
			if (path.get(i).dwellTime > 0) {
				return i;
			}
		}
		return path.size() - 1;
	}

	private void checkBlock(BlockPos pos, Consumer<BlockPos> callback) {
		final int checkRadius = (int) Math.floor(speed);
		for (int x = -checkRadius; x <= checkRadius; x++) {
			for (int z = -checkRadius; z <= checkRadius; z++) {
				for (int y = 0; y <= 3; y++) {
					callback.accept(pos.offset(x, -y, z));
				}
			}
		}
	}

	private static void transferItems(Container inventoryFrom, Container inventoryTo) {
		for (int i = 0; i < inventoryFrom.getContainerSize(); i++) {
			if (!inventoryFrom.getItem(i).isEmpty()) {
				final ItemStack insertItem = new ItemStack(inventoryFrom.getItem(i).getItem(), 1);
				insertItem.setTag(inventoryFrom.getItem(i).getOrCreateTag());

				final ItemStack remainingStack = HopperBlockEntity.addItem(null, inventoryTo, insertItem, null);
				if (remainingStack.isEmpty()) {
					inventoryFrom.removeItem(i, 1);
					return;
				}
			}
		}
	}
}
