package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;

public class SignalBlocks {

	private final Map<UUID, Set<SignalBlock>> railToSignalBlocks = new HashMap<>();
	public final List<SignalBlock> signalBlocks = new ArrayList<>();

	/**
	 * Where each train is and what is holding it up, so a train facing a claimed rail can tell whether the train in
	 * there is one it is following, one coming the other way, or one waiting on it in turn.
	 *
	 * This lives here rather than beside the rail claims because an addon shadows those and calls the simulation
	 * methods by their exact signature; hanging it off the object that is already passed around avoids changing any.
	 */
	private final Map<Long, TrainReport> trainReports = new HashMap<>();

	// ponytail: entries expire on a timestamp rather than being cleared by the tick loop, which would mean
	// threading another call through the simulation. Swap to an explicit per-tick clear if this ever gets hot.
	private static final long TRAIN_REPORT_STALE_MILLIS = 1000;

	public void setTrainPosition(long trainId, Vec3 head, Vec3 tail, Vec3 direction, boolean onRoute) {
		final TrainReport report = trainReports.computeIfAbsent(trainId, key -> new TrainReport());
		report.head = head;
		report.tail = tail;
		report.direction = direction;
		report.onRoute = onRoute;
		report.recordedAt = System.currentTimeMillis();
	}

	/**
	 * Which way a train is pointing, as it reported itself, or null if it has not reported recently enough.
	 *
	 * Published rather than worked out from the two ends, because a train standing at the start of its path has
	 * both ends in the same place and no direction can be read from the outside. The train itself can always answer
	 * from its own path, so it is the one that answers.
	 */
	public Vec3 getTrainDirection(long trainId) {
		final TrainReport report = current(trainId);
		return report == null ? null : report.direction;
	}

	/**
	 * A one-line account of what a train is doing, for the message a held train prints about whatever is holding it.
	 *
	 * A hold that will not clear looks the same from the outside whatever is causing it, and the causes want
	 * completely different fixes: a train parked across a junction, a train whose position cannot be read, and a
	 * ring of trains each waiting on the next are three different railways to go and look at.
	 */
	public String describeTrain(long trainId) {
		final TrainReport report = trainReports.get(trainId);
		if (report == null) {
			return "never reported in";
		}
		final long age = System.currentTimeMillis() - report.recordedAt;
		final StringBuilder description = new StringBuilder();
		description.append(report.onRoute ? "on route" : "not on route");
		if (age > TRAIN_REPORT_STALE_MILLIS) {
			description.append(", last reported ").append(age / 1000).append("s ago");
		} else if (report.head == null || report.tail == null) {
			description.append(", position unknown");
		} else if (report.direction == null) {
			description.append(", direction unreadable");
		}
		if (report.blocked && System.currentTimeMillis() - report.blockedAt <= TRAIN_REPORT_STALE_MILLIS) {
			description.append(", itself held by ").append(report.blockedBy);
		}
		return description.toString();
	}

	/**
	 * Records which train is currently holding this one up.
	 *
	 * Two trains that block each other are stuck for good on their own: each is waiting for track the other is
	 * standing on, and neither can reverse out. Publishing the blocker is what lets the other side notice the
	 * circle and break it.
	 *
	 * Blocked or not is a flag rather than a reserved id, because every long is a train id somebody could have.
	 */
	public void setTrainBlockedBy(long trainId, long blockingTrainId) {
		final TrainReport report = trainReports.computeIfAbsent(trainId, key -> new TrainReport());
		report.blocked = true;
		report.blockedBy = blockingTrainId;
		report.blockedAt = System.currentTimeMillis();
	}

	public void clearTrainBlocked(long trainId) {
		final TrainReport report = trainReports.get(trainId);
		if (report != null) {
			report.blocked = false;
		}
	}

	/** The back of a train, or null if that train has not reported in recently enough to be trusted. */
	public Vec3 getTrainTail(long trainId) {
		final TrainReport report = current(trainId);
		return report == null ? null : report.tail;
	}

	/** The front of a train, or null if that train has not reported in recently enough to be trusted. */
	public Vec3 getTrainHead(long trainId) {
		final TrainReport report = current(trainId);
		return report == null ? null : report.head;
	}

	/** Whether the given train is being held up by the given other train, as of its own last tick. */
	public boolean isTrainBlockedBy(long trainId, long blockingTrainId) {
		return blockedBy(trainId) == blockingTrainId;
	}

	/**
	 * What is holding the given train up, or 0 if it is not held or has not reported recently enough to trust.
	 *
	 * Following this from train to train is what turns a standoff between two into a ring of any size: A waits on
	 * B, B on C, C back on A. Nobody in such a ring is waiting on the train directly in front of them in the way
	 * a pair is, so a rule that only looks one step ahead never sees it.
	 */
	public long blockedBy(long trainId) {
		final TrainReport report = trainReports.get(trainId);
		if (report == null || !report.blocked || System.currentTimeMillis() - report.blockedAt > TRAIN_REPORT_STALE_MILLIS) {
			return 0;
		}
		return report.blockedBy;
	}

	private TrainReport current(long trainId) {
		final TrainReport report = trainReports.get(trainId);
		return report == null || System.currentTimeMillis() - report.recordedAt > TRAIN_REPORT_STALE_MILLIS ? null : report;
	}

	private static class TrainReport {

		private Vec3 head;
		private Vec3 tail;
		private Vec3 direction;
		private boolean onRoute;
		private long recordedAt;
		private boolean blocked;
		private long blockedBy;
		private long blockedAt;
	}

	public long add(long id, DyeColor color, UUID rail) {
		final List<SignalBlock> connectedSignalBlocks = new ArrayList<>();
		signalBlocks.forEach(signalBlock -> {
			if (signalBlock.color == color && signalBlock.isConnected(rail)) {
				connectedSignalBlocks.add(signalBlock);
			}
		});

		if (connectedSignalBlocks.isEmpty()) {
			final SignalBlock newSignalBlock = new SignalBlock(id, color, rail);
			signalBlocks.add(newSignalBlock);
			writeCache();
			return newSignalBlock.id;
		} else {
			Collections.sort(connectedSignalBlocks);
			final SignalBlock firstSignalBlock = connectedSignalBlocks.remove(0);
			firstSignalBlock.rails.add(rail);
			connectedSignalBlocks.forEach(signalBlock -> firstSignalBlock.rails.addAll(signalBlock.rails));
			signalBlocks.removeIf(connectedSignalBlocks::contains);
			writeCache();
			return 0;
		}
	}

	public long remove(long id, DyeColor color, UUID rail) {
		SignalBlock connectedSignalBlock = null;
		for (final SignalBlock signalBlock : signalBlocks) {
			if (signalBlock.color == color && signalBlock.isConnected(rail)) {
				connectedSignalBlock = signalBlock;
				break;
			}
		}

		if (connectedSignalBlock != null) {
			signalBlocks.remove(connectedSignalBlock);
			connectedSignalBlock.rails.remove(rail);

			if (!connectedSignalBlock.rails.isEmpty()) {
				final List<UUID> rails = new ArrayList<>(connectedSignalBlock.rails);
				Collections.sort(rails);
				add(connectedSignalBlock.id, color, rails.remove(0));

				long returnId = 0;
				for (final UUID existingRail : rails) {
					final long newId = add(id, color, existingRail);
					if (newId != connectedSignalBlock.id) {
						returnId = newId;
					}
				}

				writeCache();
				return returnId;
			}
		}

		writeCache();
		return 0;
	}

	public void occupy(UUID currentRail, List<Map<UUID, Long>> trainPositions, long trainId) {
		if (trainPositions.size() < 2) {
			return;
		}

		final Set<UUID> railsToAdd = new HashSet<>();
		railsToAdd.add(currentRail);

		if (railToSignalBlocks.containsKey(currentRail)) {
			railToSignalBlocks.get(currentRail).forEach(signalBlock -> {
				railsToAdd.addAll(signalBlock.rails);
				signalBlock.occupied = 2;
			});
		}

		for (final Map<UUID, Long> trainPositionsMap : trainPositions) {
			if (railsToAdd.stream().anyMatch(rail -> trainPositionsMap.containsKey(rail) && trainPositionsMap.get(rail) != trainId)) {
				return;
			}
		}

		railsToAdd.forEach(rail -> trainPositions.get(1).put(rail, trainId));
	}

	public void resetOccupied() {
		signalBlocks.forEach(signalBlock -> {
			if (signalBlock.isOccupied()) {
				signalBlock.occupied--;
			}
		});
	}

	public List<SignalBlock> getSignalBlocksAtTrack(UUID rail) {
		if (railToSignalBlocks.containsKey(rail)) {
			final List<SignalBlock> matchingSignalBlocks = new ArrayList<>(railToSignalBlocks.get(rail));
			matchingSignalBlocks.sort(Comparator.comparingInt(signalBlock -> signalBlock.color.ordinal()));
			return matchingSignalBlocks;
		} else {
			return new ArrayList<>();
		}
	}

	public boolean isOccupied(UUID rail) {
		if (railToSignalBlocks.containsKey(rail)) {
			return railToSignalBlocks.get(rail).stream().anyMatch(SignalBlock::isOccupied);
		} else {
			return false;
		}
	}

	public void getSignalBlockStatus(Map<Long, Boolean> signalBlockStatus, UUID rail) {
		if (railToSignalBlocks.containsKey(rail)) {
			railToSignalBlocks.get(rail).forEach(signalBlock -> signalBlockStatus.put(signalBlock.id, signalBlock.isOccupied()));
		}
	}

	public void writeSignalBlockStatus(Map<Long, Boolean> signalBlockStatus) {
		signalBlockStatus.forEach((id, occupied) -> signalBlocks.forEach(signalBlock -> {
			if (signalBlock.id == id) {
				signalBlock.occupied = occupied ? 2 : 0;
			}
		}));
	}

	public FriendlyByteBuf getValidationPacket(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final List<UUID> railsToRemove = new ArrayList<>();
		final List<DyeColor> colorsToRemove = new ArrayList<>();

		signalBlocks.forEach(signalBlock -> signalBlock.rails.forEach(rail -> {
			final BlockPos pos1 = BlockPos.of(rail.getLeastSignificantBits());
			final BlockPos pos2 = BlockPos.of(rail.getMostSignificantBits());
			if (!RailwayData.containsRail(rails, pos1, pos2)) {
				railsToRemove.add(rail);
				colorsToRemove.add(signalBlock.color);
			}
		}));

		if (railsToRemove.isEmpty()) {
			return null;
		} else {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeInt(railsToRemove.size());
			for (int i = 0; i < railsToRemove.size(); i++) {
				final DyeColor color = colorsToRemove.get(i);
				final UUID rail = railsToRemove.get(i);
				packet.writeLong(remove(0, color, rail));
				packet.writeInt(color.ordinal());
				packet.writeUUID(rail);
			}
			return packet;
		}
	}

	public void writeCache() {
		railToSignalBlocks.clear();
		signalBlocks.forEach(signalBlock -> signalBlock.rails.forEach(rail -> {
			if (!railToSignalBlocks.containsKey(rail)) {
				railToSignalBlocks.put(rail, new HashSet<>());
			}
			railToSignalBlocks.get(rail).add(signalBlock);
		}));
	}

	public static class SignalBlock extends NameColorDataBase {

		public final DyeColor color;
		private final Set<UUID> rails = new HashSet<>();
		private int occupied = 0;

		private static final String KEY_COLOR = "color";
		private static final String KEY_RAILS = "rails";

		private SignalBlock(long id, DyeColor color, UUID rail) {
			super(id);
			this.color = color;
			rails.add(rail);
		}

		public SignalBlock(Map<String, Value> map) {
			super(map);
			final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
			DyeColor savedColor;
			try {
				savedColor = DyeColor.values()[messagePackHelper.getInt(KEY_COLOR)];
			} catch (Exception e) {
				e.printStackTrace();
				savedColor = DyeColor.RED;
			}
			color = savedColor;

			map.get(KEY_RAILS).asArrayValue().forEach(value -> rails.add(UUID.fromString(value.asStringValue().asString())));
		}

		@Deprecated
		public SignalBlock(CompoundTag compoundTag) {
			super(compoundTag);
			DyeColor savedColor;
			try {
				savedColor = DyeColor.values()[compoundTag.getInt(KEY_COLOR)];
			} catch (Exception e) {
				e.printStackTrace();
				savedColor = DyeColor.RED;
			}
			color = savedColor;
			final CompoundTag compoundTagRails = compoundTag.getCompound(KEY_RAILS);
			compoundTagRails.getAllKeys().forEach(key -> rails.add(compoundTagRails.getUUID(key)));
		}

		public SignalBlock(FriendlyByteBuf packet) {
			super(packet);
			DyeColor savedColor;
			try {
				savedColor = DyeColor.values()[packet.readInt()];
			} catch (Exception e) {
				e.printStackTrace();
				savedColor = DyeColor.RED;
			}
			color = savedColor;
			final int railCount = packet.readInt();
			for (int i = 0; i < railCount; i++) {
				rails.add(packet.readUUID());
			}
		}

		@Override
		public void toMessagePack(MessagePacker messagePacker) throws IOException {
			super.toMessagePack(messagePacker);

			messagePacker.packString(KEY_COLOR).packInt(color.ordinal());
			messagePacker.packString(KEY_RAILS).packArrayHeader(rails.size());
			for (final UUID rail : rails) {
				messagePacker.packString(rail.toString());
			}
		}

		@Override
		public int messagePackLength() {
			return super.messagePackLength() + 2;
		}

		@Override
		public void writePacket(FriendlyByteBuf packet) {
			super.writePacket(packet);
			packet.writeInt(color.ordinal());
			packet.writeInt(rails.size());
			rails.forEach(packet::writeUUID);
		}

		@Override
		protected boolean hasTransportMode() {
			return false;
		}

		public boolean isOccupied() {
			return occupied > 0;
		}

		private boolean isConnected(UUID checkRail) {
			final long checkPos1 = checkRail.getLeastSignificantBits();
			final long checkPos2 = checkRail.getMostSignificantBits();
			return rails.stream().anyMatch(rail -> {
				final long pos1 = rail.getLeastSignificantBits();
				final long pos2 = rail.getMostSignificantBits();
				return checkPos1 == pos1 || checkPos1 == pos2 || checkPos2 == pos1 || checkPos2 == pos2;
			});
		}

		@Override
		public int compareTo(NameColorDataBase compare) {
			return Long.compare(id, compare.id);
		}
	}
}
