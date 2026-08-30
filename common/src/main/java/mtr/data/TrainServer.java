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
	/** Whether this lap's stabling question has already been answered, so it is answered once and stays answered. */
	private boolean stablingDecided;
	/**
	 * The answer worked out in the siding, kept until the real decision replaces it.
	 *
	 * Only the projected schedule reads this. It exists because the question can be answered before departure and
	 * after it, but not in between, and a marker that disappears halfway is worse than one that never appeared.
	 */
	private boolean predictedStabling;
	/** When {@link #predictedStabling} was last worked out, so that it is not worked out again on the next tick. */
	private long predictedStablingAt;
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
	/** How far apart the points ahead of a carrying vehicle are when warming chunks — two per chunk crossed. */
	private static final int PRELOAD_STEP_BLOCKS = 8;

	/** Expires on its own after ten seconds, so nothing here ever has to be released. */
	private static final TicketType<Unit> PRELOAD_TICKET = TicketType.create("mtr_vehicle_ahead", (a, b) -> 0, 200);
	/** The chunk this vehicle last warmed from, so it asks once per chunk crossed rather than once per tick. */
	private long lastPreloadedChunk = Long.MIN_VALUE;
	/** Set by {@link #isRailBlocked} while this tick's checks run, read once they are done. */
	private boolean blockedThisTick;
	/** When this train first found itself held, or 0 if it is running. Reset the moment it gets a clear tick. */
	private long blockedSince;
	/**
	 * How long this vehicle has been standing still because something is in its way.
	 *
	 * Arrival times are projected along the timetable from where the vehicle currently is, and that model has no
	 * idea a vehicle can stop. A held vehicle does not advance, so the projection stops changing and the platform
	 * display sits there promising a train in ten seconds for as long as the hold lasts. Counting the time lost
	 * makes the estimate move in the only direction it honestly can.
	 */
	private int heldTicks;
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

	/** When this train last said it was held, because a train pinned against a block never starts moving again. */
	private long lastHoldReportedAt = 0;
	/**
	 * Often enough to see a standoff form and break, which one a minute was not.
	 *
	 * This was thirty seconds while the reports went to the console, where they cost the server thread a
	 * synchronised write apiece. They go to their own file now, written by a thread of its own, so a report costs
	 * a string added to a list and the rate can be what the diagnostic actually wants.
	 */
	private static final long HOLD_REPORT_INTERVAL_MILLIS = 2000;

	/**
	 * Says once, out loud, that this train is being held and by what.
	 *
	 * A train that will not leave looks identical to a depot that will not dispatch, and the two have completely
	 * different causes. Without this the only way to tell them apart is to guess.
	 */
	private void reportHold(long blockingTrainId, String reason) {
		final long now = System.currentTimeMillis();
		// The interval alone decides, and it decides regardless of which train is in the way.
		//
		// This used to let a report through whenever the blocker changed, on the reasoning that a new blocker is
		// new information. It is, but it is not worth what it costs: once deadlock rings began to break, trains
		// shuffled and the train in front changed from tick to tick, so the exemption applied every tick and the
		// throttle stopped throttling. Every blocked train then printed twenty lines a second, each one building a
		// description and taking the lock on System.out from the server thread. A railway with a hundred held
		// trains put the server on the floor -- 2 TPS -- for what is only a diagnostic.
		//
		// A hold now says itself at most twice a minute per train, whatever is in front of it, and names whatever
		// is in front of it at the moment it speaks.
		if (now - lastHoldReportedAt < HOLD_REPORT_INTERVAL_MILLIS) {
			return;
		}
		lastHoldReportedAt = now;
		final String blocker = signalBlocks == null ? "" : " [" + signalBlocks.describeTrain(blockingTrainId) + "]";
		HoldLog.write(now + " Vehicle " + id + " on siding " + sidingId + " is held by vehicle " + blockingTrainId
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
					blockedThisTick = true;
					if (blockedSince == 0) {
						blockedSince = System.currentTimeMillis();
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
		// Each train publishes its own direction, because a train standing at the start of its path has both ends
		// in the same place and nothing outside it can work out which way it faces.
		final Vec3 theirs = signalBlocks.getTrainDirection(otherTrainId);
		return theirs != null && myDirection.dot(theirs) > 0;
	}

	/**
	 * This train's own direction of travel. Reading both ends walks the path, so callers work it out once.
	 *
	 * Falls back to the path when the two ends give nothing, which is the case for a train standing at the very
	 * start of its run. Without the fallback such a train reads every occupied rail ahead as opposing traffic and
	 * is held where it stands for good — which is exactly what was happening to trains trying to leave a siding.
	 */
	private Vec3 myTravelDirection() {
		final Vec3 fromEnds = travelDirection(getHeadPosition(), getTailPosition());
		return fromEnds != null ? fromEnds : getPathDirection();
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
	/**
	 * How long a standoff has to have stood before anyone is forced through it.
	 *
	 * Traffic produces rings that last a moment and clear themselves: a train pauses at a junction, the one behind
	 * it waits, and a tick later everybody is moving. Forcing a train through one of those achieves nothing and
	 * costs the work of looking. A deadlock, by contrast, does not clear -- the ones found on the railway stood for
	 * three hours -- so waiting a few seconds before intervening loses nothing and keeps this off the hot path
	 * for all the ordinary cases.
	 */
	private static final long DEADLOCK_SETTLE_MILLIS = 5000;

	private boolean yieldsToMe(long otherTrainId) {
		if (signalBlocks == null || blockedSince == 0 || System.currentTimeMillis() - blockedSince < DEADLOCK_SETTLE_MILLIS) {
			return false;
		}
		return TrainDeadlock.proceeds(id, otherTrainId, signalBlocks::blockedBy);
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
	 * Overriding isRepeat is what makes the train fall out of the loop, and the decision is taken once per lap, as
	 * the train pulls away from the origin. It used to be taken at the far end of the loop instead, which was too
	 * late to be honest about: isRepeat also decides whether the projected schedule runs a second time round, so
	 * until the train reached the end it kept advertising arrivals for a lap it had already been booked not to run.
	 * Those arrivals then vanished all at once, which is the ghost departure, and every display showing one jumped
	 * to a later train the moment it went.
	 */
	@Override
	protected boolean isRepeat() {
		return super.isRepeat() && !returningToDepot;
	}

	private void updateTimetableStabling() {
		if (currentDepot == null || !currentDepot.strictTimetable || transportMode.continuousMovement) {
			returningToDepot = false;
			stablingDecided = false;
			timetableDepartureMillis = -1;
			wasHoldingAtOrigin = false;
			return;
		}

		releaseLapsedClaim();
		updateTimetableTarget();

		if (!super.isRepeat()) {
			returningToDepot = false;
			stablingDecided = false;
		} else if (!isOnRoute) {
			// Back in the depot, so the decision has done its job and the normal dispatch gate takes over
			returningToDepot = false;
			stablingDecided = false;
		} else if (!stablingDecided && speed <= 0 && getIndex(railProgress, false) >= repeatIndex2) {
			// Fallback for a train that never held at the origin this lap — a restart, or a timetable switched on
			// mid-run. Deciding here is late, but it is better than not deciding.
			decideStabling(0);
		}
	}

	/**
	 * Settles whether this train stables after the lap it is starting.
	 *
	 * The question is how long it would be left standing at the origin once it gets back there, so the answer needs
	 * the run still ahead of it, not just the clock. Measuring from now alone would have the answer drift as the lap
	 * went by, and the schedule drift with it.
	 */
	private void decideStabling(float remainingTicks) {
		stablingDecided = true;
		final long now = System.currentTimeMillis();
		final long next = timetableDepartureMillis >= 0 ? timetableDepartureMillis : currentDepot.findDeparture(now, true);
		if (next < 0) {
			// Nothing left for it to run, so there is nothing for it to stay out for
			returningToDepot = true;
			return;
		}
		// The stop the train is meant to make anyway is not idle time. Counting it as idle meant a train arriving a
		// couple of minutes before its departure — which is simply a train on time, with a stop to make — read as
		// having a long wait ahead of it and ran to the depot instead of stopping at all.
		final long idle = next - now
				- (long) (remainingTicks * Depot.MILLIS_PER_TICK)
				- (long) (originDwellTicks() * Depot.MILLIS_PER_TICK);
		returningToDepot = idle > currentDepot.getStablingThresholdMillis();

		if (returningToDepot && timetableDepartureMillis >= 0) {
			// Stabling, so the departure just claimed belongs to whichever train is still out or still in the
			// siding. A claim held by a train on its way to the depot is a departure nobody runs.
			currentDepot.releaseDeparture(timetableDepartureMillis);
			timetableDepartureMillis = -1;
		}
	}

	/**
	 * Whether the projected schedule runs the loop a second time.
	 *
	 * Not the same question as {@link #isRepeat()}, which is about what the train does. A train that will stable at
	 * the end of its lap projects one lap, and the last thing on that lap is its arrival back at the origin -- which
	 * is what puts the origin's own name on the boards and marks the run as the one that goes back to the depot.
	 *
	 * That marker used to appear only once the train was already out, because the flag behind it is deliberately
	 * cleared while a train sits in a siding, where the ordinary dispatch gate owns the decision. So the same run
	 * was advertised one way before it left and another way after, and an arrival appeared or vanished at the
	 * moment it pulled away. Asking the question here, of a train that has not moved yet, costs nothing and changes
	 * nothing about what the train does: no departure is claimed, no route is altered, and the answer is replaced by
	 * the real decision as the train leaves the origin.
	 */
	private boolean projectsRepeat(int nextDepartureTicks) {
		if (!isRepeat()) {
			// The real decision has been taken and it is to stable
			return false;
		}
		if (stablingDecided) {
			// The real decision has been taken and it is to keep going round
			return true;
		}
		if (!isOnRoute) {
			// Not on every tick. Answering this walks the depot's timetable looking for a departure this vehicle
			// could be back for, and a day of five-minute headways is a few hundred entries to walk. The answer is
			// about a lap that has not started, measured in minutes, so a second-old answer is the same answer --
			// and a vehicle standing in a siding is the case where it is asked most and changes least.
			final long now = System.currentTimeMillis();
			if (now - predictedStablingAt >= PREDICTION_INTERVAL_MILLIS) {
				predictedStablingAt = now;
				predictedStabling = willStableAfterNextLap(nextDepartureTicks);
			}
		}
		// Between leaving the siding and pulling away from the origin the question cannot be asked again -- the
		// vehicle is out, so there is no siding departure to reason from, and the real decision has not been taken
		// yet. Holding on to the answer from the siding is what keeps the boards saying one thing throughout. Ask
		// it fresh each tick and the marker appears in the siding and vanishes the moment the vehicle moves, which
		// is the same fault as before with the moment shifted.
		return !predictedStabling;
	}

	/** How often the siding's answer is worked out again. Far shorter than anything it can change in. */
	private static final long PREDICTION_INTERVAL_MILLIS = 1000;

	/**
	 * Whether a train still standing in a siding is already booked to stable at the end of the lap it has not begun.
	 *
	 * Answered the same way {@link #decideStabling} answers it, one lap earlier: take the departure it is waiting
	 * for, add the lap and the stop it makes at the origin, and see whether there is a departure it could still be
	 * back for. Nothing here is claimed -- the claim belongs to whichever train is actually released.
	 *
	 * A true answer is what puts the origin's own name on the boards, because a vehicle that stables runs its path
	 * once and the last thing on that path is its arrival back where it started. That is the whole marker: a run
	 * showing the far terminus is one that carries on, and a run showing the origin is the one that goes home.
	 */
	private boolean willStableAfterNextLap(int nextDepartureTicks) {
		if (isOnRoute || currentDepot == null || !currentDepot.strictTimetable
				|| transportMode.continuousMovement || nextDepartureTicks < 0 || timeSegments.isEmpty()) {
			return false;
		}
		final long departsAt = System.currentTimeMillis() + Math.max(0, nextDepartureTicks);
		final long backAt = departsAt
				+ (long) (timeSegments.get(timeSegments.size() - 1).endTime * Depot.MILLIS_PER_TICK)
				+ (long) (originDwellTicks() * Depot.MILLIS_PER_TICK);
		final long next = currentDepot.peekReachableDeparture(departsAt, backAt);
		// Nothing left it could be back for is the clearest case of all
		return next < 0 || next - backAt > currentDepot.getStablingThresholdMillis();
	}

	/** The earliest this train could be standing at the origin with its stop made, ready to leave again. */
	private long readyAtMillis(float remainingTicks) {
		return System.currentTimeMillis()
				+ (long) (remainingTicks * Depot.MILLIS_PER_TICK)
				+ (long) (originDwellTicks() * Depot.MILLIS_PER_TICK);
	}

	/** The stop the origin platform is scheduled for, which the train would be making whether or not it stabled. */
	private int originDwellTicks() {
		final int origin = getOriginIndex();
		return origin >= 0 && origin < path.size() ? path.get(origin).dwellTime * 10 : 0;
	}

	/** Ticks of run left before this train reaches the end of its path, from the timing model it was built with. */
	private float remainingRunTicks() {
		if (timeSegments.isEmpty()) {
			return 0;
		}
		for (final Siding.TimeSegment timeSegment : timeSegments) {
			if (RailwayData.isBetween(railProgress, timeSegment.startRailProgress, timeSegment.endRailProgress)) {
				return Math.max(0, timeSegments.get(timeSegments.size() - 1).endTime - (float) timeSegment.getTime(railProgress));
			}
		}
		return 0;
	}

	/**
	 * Keeps {@link #timetableDepartureMillis} pointing at the booked departure this train is currently running to.
	 *
	 * The dispatch stamps the first one on the way out of the siding. After that the train never touches the depot
	 * again while it repeats, so each time it pulls away from the origin the target steps on to the departure after
	 * the one it just ran. A train that finds itself at the origin with no target at all — a server restart, or a
	 * timetable switched on while it was already running — adopts the next departure due.
	 */
	/**
	 * Gives up a claimed departure that has gone by while this train was still out on the line.
	 *
	 * Claiming a departure is what stops the depot sending a second train for it. The price is that a claim
	 * nobody honours is a departure nobody runs: a train held up somewhere on its lap would otherwise sit on a
	 * departure it has already missed, and the siding, seeing it spoken for, would decline to send the train that
	 * could have run it.
	 *
	 * The claim is only let go once the departure is far enough past that this train plainly did not run it. A
	 * train standing at the origin working through its stop has not missed anything and keeps what it has.
	 */
	private void releaseLapsedClaim() {
		if (!isOnRoute || timetableDepartureMillis < 0 || isAtOriginPlatform()) {
			return;
		}
		if (System.currentTimeMillis() > timetableDepartureMillis + currentDepot.getDepartureLapseMillis()) {
			currentDepot.releaseDeparture(timetableDepartureMillis);
			// Picked up again at the origin, from whatever is free by then
			timetableDepartureMillis = -1;
		}
	}

	private void updateTimetableTarget() {
		if (!isOnRoute) {
			// Stabled: the dispatch gate owns the choice, and stamps it as the train leaves
			wasHoldingAtOrigin = false;
			return;
		}

		if (isAtOriginPlatform()) {
			if (timetableDepartureMillis < 0) {
				timetableDepartureMillis = currentDepot.findDeparture(System.currentTimeMillis(), true);
			}
			wasHoldingAtOrigin = true;
		} else if (wasHoldingAtOrigin) {
			wasHoldingAtOrigin = false;
			final float remainingTicks = remainingRunTicks();
			if (timetableDepartureMillis >= 0) {
				// Only a departure this train could be standing at the origin for. Taking the next one on the list
				// regardless is what let a train six minutes from its own twenty-minute lap claim the departure
				// after this one: the wait it measured came out negative, negative is not greater than the
				// stabling threshold, so it stayed out for a trip it could never make -- and the departure it was
				// really due back for was left to a train that was never sent, because this one had taken it.
				timetableDepartureMillis = currentDepot.claimReachableDeparture(timetableDepartureMillis, readyAtMillis(remainingTicks));
			}
			// Pulling away from the origin with the next departure now known, which is the earliest the question
			// can be answered and the last moment it can be answered without having already lied about it.
			decideStabling(remainingTicks);
		}
	}

	/**
	 * Whether this train is standing at the origin platform of a depot that runs to its timetable.
	 *
	 * A repeating train jumps railProgress back to the start of the loop and never returns to the depot, so the
	 * departure list that governs dispatch is never consulted a second time without this.
	 */
	/** Standing still at the origin platform, which is when the timetable holds a train rather than the schedule. */
	private boolean isAtTimetabledOrigin() {
		return speed <= 0 && isAtOriginPlatform();
	}

	/**
	 * At the origin platform, whether or not it is moving.
	 *
	 * Distinct from {@link #isAtTimetabledOrigin()} because "has it left yet" and "is it waiting" are different
	 * questions and only one of them is about speed. A train pulling out of the platform, or edging forward
	 * against a signal a few blocks up, has not left: it is still there, on the departure it is running.
	 *
	 * Answering that with the stopped-at-origin test is what made a train about to leave suddenly need another
	 * five minutes. The moment it moved, that test went false, the target stepped on to the next departure, and
	 * if it then stopped again -- which a train leaving a platform very often does -- it was back at the origin
	 * holding for a departure two trips away, and said so on every display.
	 */
	private boolean isAtOriginPlatform() {
		if (currentDepot == null || !currentDepot.strictTimetable || transportMode.continuousMovement) {
			return false;
		}
		if (!isOnRoute) {
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

		return DepartureLedger.timetabledDwellTicks(base, elapsedDwellTicks,
				timetableDepartureMillis - System.currentTimeMillis(), Depot.MILLIS_PER_TICK);
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

	/**
	 * When along its own run this vehicle reaches the first platform of the route, in ticks from leaving the siding.
	 *
	 * Read out of the same timings the arrival projection walks, rather than measured separately, so the anchor and
	 * the arrivals hung off it cannot drift apart. Negative if the run has no platform, which a siding sitting on an
	 * unfinished route does have.
	 */
	private float originPathTicks() {
		for (final Siding.TimeSegment timeSegment : timeSegments) {
			if (timeSegment.savedRailBaseId != 0 && timeSegment.savedRailBaseId != sidingId) {
				return timeSegment.endTime;
			}
		}
		return -1;
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
		if (!blockedThisTick) {
			// A clear tick ends the standoff, so the next one starts its own clock rather than inheriting this one
			blockedSince = 0;
		}
		blockedThisTick = false;
		final int oldStoppingIndex = nextStoppingIndex;
		final int oldPassengerCount = ridingEntities.size();
		final boolean oldIsCurrentlyManual = isCurrentlyManual;
		final boolean oldStopped = speed == 0;
		final boolean oldDoorOpen = doorTarget;

		simulateTrain(world, ticksElapsed, depot);

		if (blockedThisTick && speed <= Train.ACCELERATION_DEFAULT) {
			heldTicks += Math.max(1, (int) ticksElapsed);
		} else if (speed > Train.ACCELERATION_DEFAULT) {
			heldTicks = 0;
		}

		// Under a strict timetable the departures already claimed have to be skipped, or a vehicle waiting in a
		// siding advertises the one another vehicle is out on the line running towards, and every display along
		// the route shows that arrival twice
		final int nextDepartureTicks = isOnRoute ? 0
				: depot.strictTimetable ? depot.getNextUnclaimedDepartureMillis() : depot.getNextDepartureMillis();

		double currentTime = -1;
		int startingIndex = 0;
		for (final Siding.TimeSegment timeSegment : timeSegments) {
			if (RailwayData.isBetween(railProgress, timeSegment.startRailProgress, timeSegment.endRailProgress)) {
				currentTime = timeSegment.getTime(railProgress);
				break;
			}
			startingIndex++;
		}

		// The clock is wound back by however long this vehicle has been standing, because the position it reads its
		// own progress from stops moving during a stop while the clock does not. That holds only while the stop is
		// the one the timings were built from: a timetabled origin stretches its stop to hold the vehicle until its
		// booked departure, and winding back by the whole of that wait projects every arrival down the line as if
		// it had already left. Displays then count all the way down, and past zero, to a vehicle still sitting at
		// the platform in front of them -- worst on the first departures after a restart, where the wait is
		// longest. Only as much of the stop as the timings actually recorded may be given back.
		final float countedDwellTicks = Math.min(elapsedDwellTicks, super.getTotalDwellTicks());
		// A vehicle waiting in a siding projects its arrivals forward from the moment it expects to be let go, and
		// the run out to the origin is then counted on top of that. Under a strict timetable the booked time is the
		// one the vehicle leaves the origin platform, not the one it leaves the siding, so counting the approach on
		// top put every arrival a whole approach late and hung a departure on the boards that the timetable does
		// not contain, a minute or so after each one that it does. Backing the approach out again anchors the wait
		// so the vehicle reaches the origin on its booked time.
		final double approachTicks = depot.strictTimetable && !isOnRoute && currentTime >= 0
				? Math.max(0, originPathTicks() - currentTime) : 0;
		final long currentMillis = System.currentTimeMillis() - (long) (countedDwellTicks * Depot.MILLIS_PER_TICK)
				+ (long) Math.max(0, nextDepartureTicks) - (long) (approachTicks * Depot.MILLIS_PER_TICK);

		if (currentTime >= 0) {
			float offsetTime = 0;
			float offsetTimeTemp = 0;
			boolean secondRound = false;
			Runnable addSchedule = null;
			routeId = 0;
			// Asked once. It was being asked in the loop condition, which is re-read on every pass, and again for
			// every platform on the way round -- and answering it walks the depot's timetable looking for a
			// departure this vehicle could be back for, which is not a cheap walk. One vehicle standing in a siding
			// was doing that a few hundred times a tick, every tick, and a depot full of them put the server at
			// 28ms a tick with nobody playing.
			final boolean projectsRepeat = projectsRepeat(nextDepartureTicks);
			for (int i = startingIndex; i < timeSegments.size() + (projectsRepeat ? timeSegments.size() : 0); i++) {
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
						// Plus whatever this vehicle has already lost standing still: the projection itself cannot
						// see a hold, so without this the display counts down to a train that is not coming.
						final long arrivalMillis = currentMillis + (long) ((timeSegment.endTime + offsetTime - currentTime + heldTicks) * Depot.MILLIS_PER_TICK);
						addSchedule = () -> schedulesForPlatform.get(platformId).add(new ScheduleEntry(arrivalMillis, trainCars, timeSegment.routeId, timeSegment.currentStationIndex));
						if (!projectsRepeat) {
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
			signalBlocks.setTrainPosition(id, head, tail, myTravelDirection(), isOnRoute);
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
	 * Only vehicles actually carrying somebody, and on a ticket that expires by itself, so nothing has to be
	 * released and a forgotten vehicle cannot pin the map open. How far ahead is read as seconds of running rather
	 * than a fixed distance, because the distance that matters is the one the vehicle is about to cover, and both
	 * that and its ceiling are the server operator's to set — see {@link ServerConfig}, where zero turns it off.
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
		final double preloadSeconds = ServerConfig.preloadSeconds();
		if (preloadSeconds <= 0) {
			return;
		}
		final double lookAhead = Math.min(ServerConfig.preloadMaxBlocks(), Math.max(PRELOAD_STEP_BLOCKS * 4, speed * preloadSeconds * 20));
		final Vec3 step = direction.normalize().scale(PRELOAD_STEP_BLOCKS);
		final ServerLevel serverLevel = (ServerLevel) world;
		Vec3 ahead = head;
		long lastAsked = here.toLong();
		for (double travelled = 0; travelled < lookAhead; travelled += PRELOAD_STEP_BLOCKS) {
			ahead = ahead.add(step);
			final ChunkPos chunkPos = new ChunkPos(RailwayData.newBlockPos(ahead));
			if (chunkPos.toLong() == lastAsked) {
				// Stepping along the line lands in the same chunk repeatedly; ask once per chunk, not once per step
				continue;
			}
			lastAsked = chunkPos.toLong();
			serverLevel.getChunkSource().addRegionTicket(PRELOAD_TICKET, chunkPos, 0, Unit.INSTANCE);
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
