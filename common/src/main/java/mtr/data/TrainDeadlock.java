package mtr.data;

/**
 * The rule for breaking a ring of trains that are each waiting on track another of them is standing on.
 *
 * Kept apart from {@link TrainServer} so it can be exercised on its own: the property that matters is that exactly
 * one train in a ring proceeds. Let two through and they pass each other in the middle of a section; let none and
 * the deadlock the rule exists to break simply survives it.
 *
 * The rule used to look one step ahead — it broke a standoff only when the other train named this one as what was
 * holding it up. That is true of a pair and of nothing else. In a ring of three or more, every train is waiting on
 * the one in front and none is waiting on the one behind, so no pair ever matched and the ring stood until the
 * server was restarted. A server log from a live railway had seven such rings in one session, the largest of
 * thirteen trains, several standing for the best part of three hours.
 *
 * A pair is just the shortest ring, and comes out of this rule with exactly the answer it had before.
 */
public final class TrainDeadlock {

	/**
	 * How far to follow the chain before giving up.
	 *
	 * Not a limit on how large a ring may be broken so much as a guard on how long this may spend looking, since
	 * it runs per blocked train per tick. Well past the largest ring seen on a real railway.
	 */
	private static final int LONGEST_RING = 64;

	/**
	 * What is holding a train up, or 0 if it is not held.
	 *
	 * Zero means "nothing", which is how {@link SignalBlocks} already reported it, so a train whose id is
	 * genuinely 0 reads as free and its ring is left standing. Ids come from the same generator as everything
	 * else MTR keys by id and 0 has never been one of them; the alternative is boxing a Long on every step of
	 * every walk, on every blocked train, every tick.
	 */
	@FunctionalInterface
	public interface BlockedBy {
		long of(long trainId);
	}

	private TrainDeadlock() {
	}

	/**
	 * Whether this train is the one that proceeds through a train blocking it.
	 *
	 * Follows the chain of what-waits-on-what from the blocking train. If it leads back here, every train on the
	 * way round is part of one ring, and the lowest id in it goes. The tie-break is arbitrary, but every train in
	 * the ring walks the same chain and finds the same lowest id, so exactly one of them moves.
	 *
	 * A chain that ends, or that is longer than this will follow, is not a ring: something at the far end is free
	 * to move, and this is an ordinary queue that will clear itself. Nothing is forced through in that case.
	 *
	 * @param trainId      the train deciding whether to move
	 * @param otherTrainId the train standing on the track it wants
	 * @param blockedBy    what is holding a given train up, or 0
	 */
	public static boolean proceeds(long trainId, long otherTrainId, BlockedBy blockedBy) {
		long lowest = trainId;
		long cursor = otherTrainId;

		for (int step = 0; step < LONGEST_RING; step++) {
			if (cursor == 0) {
				// The chain ran out: the train at the end is not waiting on anybody, so the queue can clear
				return false;
			}
			if (cursor == trainId) {
				return lowest == trainId;
			}
			lowest = Math.min(lowest, cursor);
			cursor = blockedBy.of(cursor);
		}

		return false;
	}
}
