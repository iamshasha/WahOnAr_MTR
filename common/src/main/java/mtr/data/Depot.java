package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.packet.PacketTrainDataGuiServer;
import mtr.path.PathData;
import mtr.path.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Depot extends AreaBase implements IReducedSaveData {

	public int clientPathGenerationSuccessfulSegments;
	public long lastDeployedMillis;
	public boolean useRealTime;
	public boolean repeatInfinitely;
	public int cruisingAltitude = DEFAULT_CRUISING_ALTITUDE;
	/** Ceiling on the cars of every train spawned from this depot's sidings. 0 leaves each siding at whatever its rail length allows. */
	public int maxTrainCars;
	/** Hold a looping train at the origin until its booked departure, instead of letting it roll straight round again. */
	public boolean strictTimetable;
	/** How long a wait, in minutes, is worth clearing the origin platform for. */
	public int stablingThresholdMinutes = DEFAULT_STABLING_THRESHOLD_MINUTES;
	/** False until this depot has first had a train actually ready to go since loading. Not saved; it is about this run only. */
	private boolean timetableSettled;
	/** Which departures already have a train, and the timetable arithmetic that answers it. */
	private final DepartureLedger ledger = new DepartureLedger();
	/** The departure a skip was last reported for, so a refusal is logged once rather than twenty times a second. */
	private long lastSkipReported = -1;
	private int deployIndex;
	private int departureOffset;
	private boolean isDirty = true;

	public final List<Long> routeIds = new ArrayList<>();
	public final Map<Long, Map<Long, Float>> platformTimes = new HashMap<>();
	public final List<Integer> departures = new ArrayList<>();
	public final List<Integer> tempDepartures = new ArrayList<>();

	private final int[] frequencies = new int[HOURS_IN_DAY];
	private final Map<Long, TrainServer> deployableSidings = new HashMap<>();

	public static final int HOURS_IN_DAY = 24;
	public static final int TRAIN_FREQUENCY_MULTIPLIER = 4;
	public static final int TICKS_PER_HOUR = 1000;
	public static final int MILLIS_PER_TICK = 50;
	public static final int MILLISECONDS_PER_DAY = HOURS_IN_DAY * 60 * 60 * 1000;
	public static final int DEFAULT_CRUISING_ALTITUDE = 256;
	public static final int MAX_TRAIN_CARS_LIMIT = 32;
	/**
	 * Default for how long a wait has to be before a strict-timetable train stables in the depot instead of standing
	 * at the origin. Departure spacing is roughly 50s of real time per unit of frequency, so a couple of minutes is
	 * several headways: long enough that holding a platform would block the route, short enough that a turnaround waits.
	 */
	public static final int DEFAULT_STABLING_THRESHOLD_MINUTES = 2;
	public static final int MAX_STABLING_THRESHOLD_MINUTES = 120;
	/**
	 * How late a dispatch may be and still count as having made its booked departure.
	 *
	 * A flat ten seconds was far too strict. The release moment is a single instant, and a siding only gets to act
	 * on it while it actually has a train standing in it — so a train that came back a few seconds after its window
	 * closed missed that departure, and the one after, and every one after that, and the depot simply never
	 * dispatched. The allowance now scales with the run: a quarter of the time the train needs to reach the
	 * platform, which is generous enough that a train present anywhere near its moment goes, and still short enough
	 * that one arriving most of a headway late waits for the next departure rather than running hopelessly behind.
	 */
	private static final int MIN_DEPARTURE_SLACK_MILLIS = 10000;
	private static final int TICKS_PER_DAY = HOURS_IN_DAY * TICKS_PER_HOUR;
	private static final int CONTINUOUS_MOVEMENT_FREQUENCY = 8000;
	private static final int THRESHOLD_ABOVE_MAX_BUILD_HEIGHT = 64;

	private static final String KEY_ROUTE_IDS = "route_ids";
	private static final String KEY_USE_REAL_TIME = "use_real_time";
	private static final String KEY_FREQUENCIES = "frequencies";
	private static final String KEY_DEPARTURES = "departures";
	private static final String KEY_LAST_DEPLOYED = "last_deployed";
	private static final String KEY_DEPLOY_INDEX = "deploy_index";
	private static final String KEY_REPEAT_INFINITELY = "repeat_infinitely";
	private static final String KEY_CRUISING_ALTITUDE = "cruising_altitude";
	private static final String KEY_MAX_TRAIN_CARS = "max_train_cars";
	private static final String KEY_STRICT_TIMETABLE = "strict_timetable";
	private static final String KEY_STABLING_THRESHOLD = "stabling_threshold_minutes";

	public Depot(TransportMode transportMode) {
		super(transportMode);
	}

	public Depot(long id, TransportMode transportMode) {
		super(id, transportMode);
	}

	public Depot(Map<String, Value> map) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		messagePackHelper.iterateArrayValue(KEY_ROUTE_IDS, routeId -> routeIds.add(routeId.asIntegerValue().asLong()));
		useRealTime = messagePackHelper.getBoolean(KEY_USE_REAL_TIME);

		try {
			final ArrayValue frequenciesArray = map.get(KEY_FREQUENCIES).asArrayValue();
			for (int i = 0; i < HOURS_IN_DAY; i++) {
				frequencies[i] = frequenciesArray.get(i).asIntegerValue().asInt();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		messagePackHelper.iterateArrayValue(KEY_DEPARTURES, departure -> departures.add(departure.asIntegerValue().asInt()));

		deployIndex = messagePackHelper.getInt(KEY_DEPLOY_INDEX);
		repeatInfinitely = messagePackHelper.getBoolean(KEY_REPEAT_INFINITELY);
		cruisingAltitude = messagePackHelper.getInt(KEY_CRUISING_ALTITUDE);
		maxTrainCars = messagePackHelper.getInt(KEY_MAX_TRAIN_CARS);
		strictTimetable = messagePackHelper.getBoolean(KEY_STRICT_TIMETABLE);
		stablingThresholdMinutes = clampStablingThreshold(messagePackHelper.getInt(KEY_STABLING_THRESHOLD));
		lastDeployedMillis = System.currentTimeMillis() - messagePackHelper.getLong(KEY_LAST_DEPLOYED);
	}

	@Deprecated
	public Depot(CompoundTag compoundTag) {
		super(compoundTag);

		final long[] routeIdsArray = compoundTag.getLongArray(KEY_ROUTE_IDS);
		for (final long routeId : routeIdsArray) {
			routeIds.add(routeId);
		}

		for (int i = 0; i < HOURS_IN_DAY; i++) {
			frequencies[i] = compoundTag.getInt(KEY_FREQUENCIES + i);
		}

		lastDeployedMillis = System.currentTimeMillis() - compoundTag.getLong(KEY_LAST_DEPLOYED);
		deployIndex = compoundTag.getInt(KEY_DEPLOY_INDEX);
		repeatInfinitely = compoundTag.getBoolean(KEY_REPEAT_INFINITELY);
		cruisingAltitude = compoundTag.getInt(KEY_CRUISING_ALTITUDE);
		maxTrainCars = compoundTag.getInt(KEY_MAX_TRAIN_CARS);
		strictTimetable = compoundTag.getBoolean(KEY_STRICT_TIMETABLE);
		stablingThresholdMinutes = clampStablingThreshold(compoundTag.getInt(KEY_STABLING_THRESHOLD));
	}

	public Depot(FriendlyByteBuf packet) {
		super(packet);

		final int routeIdCount = packet.readInt();
		for (int i = 0; i < routeIdCount; i++) {
			routeIds.add(packet.readLong());
		}

		useRealTime = packet.readBoolean();

		for (int i = 0; i < HOURS_IN_DAY; i++) {
			frequencies[i] = packet.readInt();
		}

		final int departuresCount = packet.readInt();
		for (int i = 0; i < departuresCount; i++) {
			departures.add(packet.readInt());
		}

		lastDeployedMillis = packet.readLong();
		deployIndex = packet.readInt();
		repeatInfinitely = packet.readBoolean();
		cruisingAltitude = packet.readInt();
		maxTrainCars = packet.readInt();
		strictTimetable = packet.readBoolean();
		stablingThresholdMinutes = clampStablingThreshold(packet.readInt());
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		toReducedMessagePack(messagePacker);
		messagePacker.packString(KEY_DEPLOY_INDEX).packInt(deployIndex);
		messagePacker.packString(KEY_LAST_DEPLOYED).packLong(System.currentTimeMillis() - lastDeployedMillis);
	}

	@Override
	public void toReducedMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_ROUTE_IDS).packArrayHeader(routeIds.size());
		for (final long routeId : routeIds) {
			messagePacker.packLong(routeId);
		}

		messagePacker.packString(KEY_USE_REAL_TIME).packBoolean(useRealTime);
		messagePacker.packString(KEY_REPEAT_INFINITELY).packBoolean(repeatInfinitely);
		messagePacker.packString(KEY_CRUISING_ALTITUDE).packInt(cruisingAltitude);
		messagePacker.packString(KEY_MAX_TRAIN_CARS).packInt(maxTrainCars);
		messagePacker.packString(KEY_STRICT_TIMETABLE).packBoolean(strictTimetable);
		messagePacker.packString(KEY_STABLING_THRESHOLD).packInt(stablingThresholdMinutes);

		messagePacker.packString(KEY_FREQUENCIES).packArrayHeader(HOURS_IN_DAY);
		for (int i = 0; i < HOURS_IN_DAY; i++) {
			messagePacker.packInt(frequencies[i]);
		}

		messagePacker.packString(KEY_DEPARTURES).packArrayHeader(departures.size());
		for (final int departure : departures) {
			messagePacker.packInt(departure);
		}
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 10;
	}

	@Override
	public int reducedMessagePackLength() {
		return messagePackLength() - 2;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);

		packet.writeInt(routeIds.size());
		routeIds.forEach(packet::writeLong);

		packet.writeBoolean(useRealTime);

		for (final int frequency : frequencies) {
			packet.writeInt(frequency);
		}

		packet.writeInt(departures.size());
		departures.forEach(packet::writeInt);

		packet.writeLong(lastDeployedMillis);
		packet.writeInt(deployIndex);
		packet.writeBoolean(repeatInfinitely);
		packet.writeInt(cruisingAltitude);
		packet.writeInt(maxTrainCars);
		packet.writeBoolean(strictTimetable);
		packet.writeInt(stablingThresholdMinutes);
	}

	@Override
	protected boolean hasTransportMode() {
		return true;
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		if (KEY_FREQUENCIES.equals(key)) {
			name = packet.readUtf(PACKET_STRING_READ_LENGTH);
			color = packet.readInt();
			useRealTime = packet.readBoolean();
			for (int i = 0; i < HOURS_IN_DAY; i++) {
				frequencies[i] = packet.readInt();
			}
			departures.clear();
			final int departuresCount = packet.readInt();
			for (int i = 0; i < departuresCount; i++) {
				departures.add(packet.readInt());
			}
			routeIds.clear();
			final int routeIdCount = packet.readInt();
			for (int i = 0; i < routeIdCount; i++) {
				routeIds.add(packet.readLong());
			}
			repeatInfinitely = packet.readBoolean();
			cruisingAltitude = packet.readInt();
			maxTrainCars = packet.readInt();
			strictTimetable = packet.readBoolean();
			stablingThresholdMinutes = clampStablingThreshold(packet.readInt());
		} else {
			super.update(key, packet);
		}
		isDirty = true;
	}

	public int getFrequency(int index) {
		if (index >= 0 && index < frequencies.length) {
			return frequencies[index];
		} else {
			return 0;
		}
	}

	public void setFrequency(int newFrequency, int index) {
		if (index >= 0 && index < frequencies.length) {
			frequencies[index] = newFrequency;
		}
		isDirty = true;
	}

	public void setData(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_FREQUENCIES);
		packet.writeUtf(name);
		packet.writeInt(color);
		packet.writeBoolean(useRealTime);
		for (final int frequency : frequencies) {
			packet.writeInt(frequency);
		}
		departures.replaceAll(departure -> departure % MILLISECONDS_PER_DAY);
		departures.removeIf(departure -> departure % 1000 != 0);
		departures.sort(Integer::compareTo);
		packet.writeInt(departures.size());
		departures.forEach(packet::writeInt);
		packet.writeInt(routeIds.size());
		routeIds.forEach(packet::writeLong);
		packet.writeBoolean(repeatInfinitely);
		packet.writeInt(cruisingAltitude);
		packet.writeInt(maxTrainCars);
		packet.writeBoolean(strictTimetable);
		packet.writeInt(stablingThresholdMinutes);
		sendPacket.accept(packet);
	}

	/**
	 * The platforms this depot's route visits, in order and without a platform repeated back to back. Path generation
	 * and the "teleport to where the path broke" lookup both read this, so they cannot disagree about which hop failed.
	 */
	public List<SavedRailBase> getPlatformsInRoute(DataCache dataCache) {
		final List<SavedRailBase> platformsInRoute = new ArrayList<>();

		routeIds.forEach(routeId -> {
			final Route route = dataCache.routeIdMap.get(routeId);
			if (route != null) {
				route.platformIds.forEach(platformId -> {
					final Platform platform = dataCache.platformIdMap.get(platformId.platformId);
					if (platform != null && (platformsInRoute.isEmpty() || platform.id != platformsInRoute.get(platformsInRoute.size() - 1).id)) {
						platformsInRoute.add(platform);
					}
				});
			}
		});

		return platformsInRoute;
	}

	/**
	 * Where the path generator gave up, as the start of the hop it could not complete. successfulSegments counts the
	 * hops that did work, so the offending one begins two entries back. Returns null if there is nothing to point at.
	 */
	public BlockPos getPathFailurePos(DataCache dataCache, int successfulSegments) {
		final List<SavedRailBase> platformsInRoute = getPlatformsInRoute(dataCache);
		if (platformsInRoute.isEmpty() || successfulSegments <= 0) {
			return null;
		}
		return platformsInRoute.get(Math.max(0, Math.min(successfulSegments - 2, platformsInRoute.size() - 1))).getMidPos();
	}

	public void generateMainRoute(MinecraftServer minecraftServer, Level world, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Set<Siding> sidings, Consumer<Thread> callback) {
		final List<SavedRailBase> platformsInRoute = getPlatformsInRoute(dataCache);

		final boolean useFastSpeed = cruisingAltitude >= world.getMaxBuildHeight() + THRESHOLD_ABOVE_MAX_BUILD_HEIGHT;

		final Thread thread = new Thread(() -> {
			try {
				// A rail can refuse a train by length, so the route through the network depends on how long the
				// train is. Sidings that spawn the same number of cars share a path; only a depot that mixes
				// lengths pays for more than one, and a network with no length conditions anywhere still
				// generates exactly one.
				final Map<Integer, List<PathData>> mainPaths = new HashMap<>();
				final Map<Integer, Integer> mainSegments = new HashMap<>();
				// Kept beside the paths for the same reason: working out which rails a length may use means
				// walking the whole network, and a depot with thirty sidings would otherwise walk it thirty times
				final Map<Integer, Map<BlockPos, Map<BlockPos, Rail>>> usableRailsByCars = new HashMap<>();
				final int[] successfulSegments = new int[]{Integer.MAX_VALUE};

				sidings.forEach(siding -> {
					final BlockPos sidingMidPos = siding.getMidPos();
					if (siding.isTransportMode(transportMode) && inArea(sidingMidPos.getX(), sidingMidPos.getZ())) {
						final int trainCars = siding.effectiveTrainCars();
						// The same filtered map builds the siding's own legs, not only the main route
						final Map<BlockPos, Map<BlockPos, Rail>> usableRails =
								usableRailsByCars.computeIfAbsent(trainCars, cars -> Rail.filterByTrainCars(rails, cars));

						if (!mainPaths.containsKey(trainCars)) {
							final List<PathData> tempPath = new ArrayList<>();
							mainSegments.put(trainCars, PathFinder.findPath(tempPath, usableRails, platformsInRoute, 1, cruisingAltitude, useFastSpeed));
							mainPaths.put(trainCars, tempPath);
						}

						final SavedRailBase firstPlatform = platformsInRoute.isEmpty() ? null : platformsInRoute.get(0);
						final SavedRailBase lastPlatform = platformsInRoute.isEmpty() ? null : platformsInRoute.get(platformsInRoute.size() - 1);
						final int result = siding.generateRoute(minecraftServer, mainPaths.get(trainCars), mainSegments.get(trainCars), usableRails, firstPlatform, lastPlatform, repeatInfinitely, cruisingAltitude, useFastSpeed);
						if (result < successfulSegments[0]) {
							successfulSegments[0] = result;
						}
					}
				});

				PacketTrainDataGuiServer.generatePathS2C(world, id, successfulSegments[0]);
				System.out.println("Finished path generation" + (name.isEmpty() ? "" : " for " + name));
			} catch (Exception e) {
				e.printStackTrace();
				PacketTrainDataGuiServer.generatePathS2C(world, id, 0);
				System.out.println("Failed to generate path" + (name.isEmpty() ? "" : " for " + name));
			}
		});
		callback.accept(thread);
		thread.start();
	}

	public void requestDeploy(long sidingId, TrainServer train) {
		deployableSidings.put(sidingId, train);
	}

	public void deployTrain(RailwayData railwayData, Level world) {
		if (isDirty) {
			generateTempDepartures(world);
		}

		if (!deployableSidings.isEmpty()) {
			// Settled here rather than on the first tick: at server start the sidings are still generating paths,
			// so for the first seconds nothing can be dispatched at all. Settling then would burn the departures
			// that pass during the warm-up, and the depot would come up with its timetable already behind.
			settleTimetableOnLoad();

			final List<Siding> sidingsInDepot = railwayData.sidings.stream().filter(siding -> {
				final BlockPos sidingPos = siding.getMidPos();
				return siding.isTransportMode(transportMode) && inArea(sidingPos.getX(), sidingPos.getZ());
			}).sorted().collect(Collectors.toList());

			final int sidingsInDepotSize = sidingsInDepot.size();
			for (int i = deployIndex; i < deployIndex + sidingsInDepotSize; i++) {
				final Siding siding = sidingsInDepot.get(i % sidingsInDepotSize);
				final TrainServer train = deployableSidings.get(siding.id);
				if (train != null) {
					// A timetable names the time the train leaves the origin platform, so the siding has to let go
					// of it a run and a stop earlier. The lead differs per siding, so the timing is asked per siding
					// rather than once for the whole depot.
					if (strictTimetable) {
						final long booked = bookedDepartureFor(siding);
						if (booked < 0) {
							continue;
						}
						ledger.consume(booked, System.currentTimeMillis(), tempDepartures.size());
						// Stamp the departure being run, not the moment the train physically left: the early
						// moment would drag the generated timetable earlier by the lead on every dispatch
						lastDeployedMillis = booked;
						train.setTimetableDeparture(booked);
					} else if (getMillisUntilDeploy(1, 0) != 0) {
						continue;
					} else {
						lastDeployedMillis = System.currentTimeMillis();
					}
					deployIndex++;
					if (deployIndex >= sidingsInDepotSize) {
						deployIndex = 0;
					}
					train.deployTrain();
					break;
				}
			}
		}

		departureOffset = 0;
		deployableSidings.clear();
	}

	/**
	 * The booked departure this siding should be releasing a train for right now, or -1 if it should not.
	 *
	 * Asked per siding because the lead — the run to the origin plus the stop there — differs between them, so two
	 * sidings on the same timetable let go at different moments for the same departure. Everything the gate has to
	 * be sure of is here: that the departure exists, that no train has already been sent for it, that it is time,
	 * and that it is not so far past time that the train could no longer make it.
	 */
	private long bookedDepartureFor(Siding siding) {
		final long now = System.currentTimeMillis();
		final int lead = siding.getDispatchLeadMillis();
		final long booked = findDeparture(now + lead, false);
		if (booked < 0 || ledger.isSpent(booked)) {
			// No timetable, or this departure already has its train
			return -1;
		}

		final long release = booked - lead;
		if (now < release) {
			return -1;
		}
		if (now - release > departureSlackMillis(lead, booked)) {
			// Too late to reach the platform in time. Releasing anyway would leave a train standing at the origin
			// for most of a headway, so the siding waits for the next departure instead.
			//
			// A train was standing in the siding ready to go and was not sent, which is worth saying out loud: it
			// is indistinguishable from the depot being broken unless something explains it.
			if (lastSkipReported != booked) {
				lastSkipReported = booked;
				System.out.println("Depot" + (name.isEmpty() ? "" : " " + name) + ": skipping the departure at "
						+ formatTimeOfDay(booked) + " — a train was ready but the siding needed to release it "
						+ (lead / 1000) + "s earlier, and that moment passed " + ((now - release) / 1000) + "s ago.");
			}
			return -1;
		}
		return booked;
	}

	private static String formatTimeOfDay(long millis) {
		final long ofDay = Math.floorMod(millis, (long) MILLISECONDS_PER_DAY) / 1000;
		return String.format("%02d:%02d:%02d", ofDay / 3600, ofDay / 60 % 60, ofDay % 60);
	}

	/**
	 * How long after its release moment a siding may still let a train go for a given departure.
	 *
	 * At most the run itself: a train released at its booked departure time still runs that service, it just runs
	 * it a run-length late, which is the ordinary late-train case and far better than not running it. Never more
	 * than halfway to the next departure, so one service can never eat the window belonging to the next.
	 */
	private long departureSlackMillis(int lead, long booked) {
		final long next = findDeparture(booked + 1, true);
		final long untilNext = next < 0 ? Long.MAX_VALUE : next - booked;
		return Math.max(MIN_DEPARTURE_SLACK_MILLIS, Math.min(lead, untilNext / 2));
	}

	/**
	 * Marks everything already in the past as spent, once, when the depot first runs after loading.
	 *
	 * A world that loads mid-day has every departure since its last save sitting unconsumed, and the dispatch gate
	 * reads all of them as due now. Left alone the depot empties its sidings in a burst trying to catch up, and the
	 * rest of the day runs against a timetable that no longer matches the clock.
	 */
	private void settleTimetableOnLoad() {
		if (timetableSettled || !strictTimetable) {
			return;
		}
		timetableSettled = true;
		final long booked = findLastDepartureAtOrBefore(System.currentTimeMillis());
		if (booked >= 0) {
			lastDeployedMillis = booked;
			ledger.settle(booked);
			System.out.println("Depot" + (name.isEmpty() ? "" : " " + name) + ": timetable resumed at "
					+ formatTimeOfDay(booked) + "; anything booked before that was not run this session.");
		}
	}


	public int getNextDepartureMillis() {
		departureOffset++;
		final int millisUntilDeploy = getMillisUntilDeploy(departureOffset);
		return millisUntilDeploy >= 0 ? millisUntilDeploy : -1;
	}

	/**
	 * How long until the departure a waiting vehicle should be aiming at, skipping the ones already claimed.
	 *
	 * {@link #getNextDepartureMillis()} answers from the timetable alone, which is right when vehicles are let go
	 * on a headway and nothing is booked ahead of time. Under a strict timetable a departure is claimed the moment
	 * its vehicle is released, and that vehicle is then out on the line running towards it -- so a vehicle still
	 * standing in a siding that answered from the timetable alone would name the same departure as the one already
	 * running it, and put a second, identical arrival on every display along the route.
	 *
	 * Counts off {@link #departureOffset} unclaimed departures, so several sidings asking in the same tick spread
	 * across different ones rather than all naming the first.
	 */
	public int getNextUnclaimedDepartureMillis() {
		departureOffset++;
		final long now = System.currentTimeMillis();
		final long departure = ledger.findUnclaimedDeparture(tempDepartures, now, departureOffset);
		return departure < 0 ? -1 : (int) (departure - now);
	}

	public int getMillisUntilDeploy(int offset) {
		return getMillisUntilDeploy(offset, 0);
	}

	public int getMillisUntilDeploy(int offset, int currentTimeOffset) {
		final long millis = (System.currentTimeMillis() + currentTimeOffset) % MILLISECONDS_PER_DAY;
		for (int i = 0; i < tempDepartures.size(); i++) {
			final long thisDeparture = tempDepartures.get(i);
			final long nextDeparture = wrapTime(tempDepartures.get((i + 1) % tempDepartures.size()), thisDeparture);
			final long newMillis = wrapTime(millis, thisDeparture);
			if (newMillis > thisDeparture && newMillis <= nextDeparture) {
				if (offset > 1) {
					if (offset <= tempDepartures.size()) {
						return (int) (wrapTime(tempDepartures.get((i + offset) % tempDepartures.size()), millis) - millis);
					}
				} else {
					return wrapTime(lastDeployedMillis + currentTimeOffset, newMillis) - MILLISECONDS_PER_DAY >= thisDeparture ? (int) (nextDeparture - newMillis) : 0;
				}
			}
		}
		return -1;
	}

	/**
	 * Wall clock time of a booked departure, picked against {@code referenceMillis}: the first one at or after it
	 * when {@code atOrAfter} is set, otherwise the closest one in either direction. -1 if there is no timetable.
	 *
	 * {@link #getMillisUntilDeploy} answers a different question — whether the dispatch gate may release a train
	 * now — and it consumes slots through {@link #lastDeployedMillis}. A train standing at the origin platform must
	 * not ask that question: the slot it is waiting for was marked used by the dispatch that sent it, so the answer
	 * would point at the following departure and the train would sit through its own booked time. Reading the
	 * timetable straight avoids that entirely.
	 */
	/** How long a strict-timetable train may wait at the origin before it is worth sending it back to the depot. */
	/**
	 * The next departure this train should be running, claimed for it, or -1 if there is none it can reach.
	 *
	 * Claimed here as well as at the dispatch gate, because a train already out on the line is as much a claim on
	 * a departure as one being let out of the siding, and until this existed only the siding said so. That is how
	 * one departure could be given to two trains: the running one took it as its next trip without telling
	 * anybody, and the depot, seeing it unspoken for, sent a second train.
	 */
	public long claimReachableDeparture(long afterMillis, long readyAtMillis) {
		final long departure = ledger.findReachableDeparture(tempDepartures, afterMillis, readyAtMillis);
		if (departure >= 0) {
			ledger.consume(departure, System.currentTimeMillis(), tempDepartures.size());
		}
		return departure;
	}

	/**
	 * The same answer as {@link #claimReachableDeparture}, without taking the departure.
	 *
	 * A vehicle still in a siding has to be able to say what it will do after the lap it has not started yet, so
	 * that the displays say the same thing before it leaves as after. It must not claim anything to find that out:
	 * the claim belongs to the vehicle that is actually released, and is taken as it pulls away from the origin.
	 */
	public long peekReachableDeparture(long afterMillis, long readyAtMillis) {
		return ledger.findReachableDeparture(tempDepartures, afterMillis, readyAtMillis);
	}

	/** Hands a claimed departure back, for a train that stabled instead or let it go by. */
	public void releaseDeparture(long departure) {
		ledger.release(departure);
	}

	/**
	 * How long after a departure a train still out on the line keeps its claim on it.
	 *
	 * Past this it has plainly not run it, and holding on would mean a departure claimed by a train that is not
	 * coming and refused to one that is.
	 */
	public long getDepartureLapseMillis() {
		return MIN_DEPARTURE_SLACK_MILLIS;
	}

	public long getStablingThresholdMillis() {
		return stablingThresholdMinutes * 60L * 1000;
	}

	private static int clampStablingThreshold(int minutes) {
		// Zero comes from data saved before this setting existed, and from a depot that has never been edited
		return minutes <= 0 ? DEFAULT_STABLING_THRESHOLD_MINUTES : Math.min(minutes, MAX_STABLING_THRESHOLD_MINUTES);
	}

	/** The most recent booked departure at or before the given time, or -1 if there is no timetable. */
	public long findLastDepartureAtOrBefore(long referenceMillis) {
		return DepartureLedger.findLastDepartureAtOrBefore(tempDepartures, referenceMillis);
	}

	public long findDeparture(long referenceMillis, boolean atOrAfter) {
		return DepartureLedger.findDeparture(tempDepartures, referenceMillis, atOrAfter);
	}

	public void generateTempDepartures(Level world) {
		tempDepartures.clear();
		if (useRealTime && !transportMode.continuousMovement) {
			tempDepartures.addAll(departures);
		} else if (world != null) {
			int millisOffset = 0;
			while (millisOffset < MILLISECONDS_PER_DAY) {
				final int tempFrequency = getFrequency(getHour(world, millisOffset));
				if (tempFrequency == 0 && !transportMode.continuousMovement) {
					millisOffset = (int) (Math.floor((float) millisOffset / MILLIS_PER_TICK / TICKS_PER_HOUR) + 1) * TICKS_PER_HOUR * MILLIS_PER_TICK;
				} else {
					tempDepartures.add((int) ((lastDeployedMillis + millisOffset) % MILLISECONDS_PER_DAY));
					millisOffset += transportMode.continuousMovement ? CONTINUOUS_MOVEMENT_FREQUENCY : TICKS_PER_HOUR * MILLIS_PER_TICK * TRAIN_FREQUENCY_MULTIPLIER / tempFrequency;
				}
			}
			tempDepartures.sort(Integer::compareTo);
		}
		isDirty = false;
	}

	private static int getHour(Level world, int offsetMillis) {
		return (int) wrapTime(world.getDayTime() + (float) offsetMillis / MILLIS_PER_TICK) / TICKS_PER_HOUR;
	}

	private static float wrapTime(float time) {
		return (time + 6000 + TICKS_PER_DAY) % TICKS_PER_DAY;
	}

	private static long wrapTime(long time, long mustBeGreaterThan) {
		long newTime = time % MILLISECONDS_PER_DAY;
		while (newTime <= mustBeGreaterThan) {
			newTime += MILLISECONDS_PER_DAY;
		}
		return newTime;
	}
}
