import mtr.data.TrainDeadlock;

import java.util.HashMap;
import java.util.Map;

/**
 * One property, in rings of every size: of a set of trains each waiting on the next, exactly one proceeds.
 *
 * Letting two through puts them nose to nose in the middle of a section. Letting none through means the ring
 * stands until the server restarts, which is what a live railway's log showed for the best part of three hours.
 */
public class TrainDeadlockCheck {

	public static void main(String[] args) {
		final int pairs = assertPairsStillResolve();
		assertRingsResolve();
		assertQueuesAreLeftAlone();
		assertUnreasonablyLongChainsDoNotHang();
		System.out.println("TrainDeadlock ok (" + pairs + " pairs, rings up to 13, queues left alone)");
	}

	/** A pair is the shortest ring, and has to keep giving exactly the answer it always gave. */
	private static int assertPairsStillResolve() {
		final long[] ids = {Long.MIN_VALUE, -7L, -1L, 1L, 42L, Long.MAX_VALUE};
		int pairs = 0;
		for (final long a : ids) {
			for (final long b : ids) {
				if (a == b) {
					continue;
				}
				final Map<Long, Long> ring = new HashMap<>();
				ring.put(a, b);
				ring.put(b, a);

				final boolean aGoes = TrainDeadlock.proceeds(a, b, blockedBy(ring));
				final boolean bGoes = TrainDeadlock.proceeds(b, a, blockedBy(ring));
				if (aGoes == bGoes) {
					throw new AssertionError("both or neither proceed: " + a + " and " + b);
				}
				// The lower id going is the rule the previous version had, and other trains may rely on it
				if (aGoes != (a < b)) {
					throw new AssertionError("the lower id no longer proceeds: " + a + " and " + b);
				}
				pairs++;
			}
		}
		return pairs;
	}

	/** Three, five and thirteen: the sizes a real railway produced, none of which the old pair rule could break. */
	private static void assertRingsResolve() {
		for (final int size : new int[]{3, 5, 7, 13}) {
			final long[] ids = new long[size];
			for (int i = 0; i < size; i++) {
				// Deliberately not in order, and straddling zero, so "lowest" cannot be confused with "first"
				ids[i] = (i % 2 == 0 ? 1L : -1L) * (long) (i * 7919 + 3);
			}

			final Map<Long, Long> ring = new HashMap<>();
			for (int i = 0; i < size; i++) {
				ring.put(ids[i], ids[(i + 1) % size]);
			}

			int proceeding = 0;
			long whoProceeds = 0;
			for (int i = 0; i < size; i++) {
				if (TrainDeadlock.proceeds(ids[i], ids[(i + 1) % size], blockedBy(ring))) {
					proceeding++;
					whoProceeds = ids[i];
				}
			}

			if (proceeding != 1) {
				throw new AssertionError("ring of " + size + ": " + proceeding + " trains proceed, expected exactly 1");
			}

			long lowest = ids[0];
			for (final long id : ids) {
				lowest = Math.min(lowest, id);
			}
			if (whoProceeds != lowest) {
				throw new AssertionError("ring of " + size + ": " + whoProceeds + " proceeded, expected " + lowest);
			}
		}
	}

	/**
	 * A queue is not a ring. The train at the front is not waiting on anybody, so it will move and the rest will
	 * follow; forcing someone through the middle of that would drive them into the back of the train ahead.
	 */
	private static void assertQueuesAreLeftAlone() {
		final Map<Long, Long> queue = new HashMap<>();
		queue.put(10L, 20L);
		queue.put(20L, 30L);
		// 30 is not blocked by anyone, so it is absent, which blockedBy reports as 0

		if (TrainDeadlock.proceeds(10L, 20L, blockedBy(queue))) {
			throw new AssertionError("forced a train through a queue that was going to clear itself");
		}
		if (TrainDeadlock.proceeds(20L, 30L, blockedBy(queue))) {
			throw new AssertionError("forced a train through the front of a queue");
		}
	}

	/**
	 * A chain far longer than any real ring must end the walk rather than run round it forever. This runs for
	 * every blocked train on every tick, so an unbounded walk would not be a wrong answer but a stopped server.
	 */
	private static void assertUnreasonablyLongChainsDoNotHang() {
		final int size = 5000;
		final Map<Long, Long> ring = new HashMap<>();
		for (int i = 0; i < size; i++) {
			ring.put((long) i, (long) ((i + 1) % size));
		}

		// Whatever it answers, it has to answer. The assertion is that this returns at all.
		TrainDeadlock.proceeds(0L, 1L, blockedBy(ring));
	}

	/** Absent means not blocked, which is how SignalBlocks reports a train that is running normally. */
	private static TrainDeadlock.BlockedBy blockedBy(Map<Long, Long> chain) {
		return trainId -> chain.getOrDefault(trainId, 0L);
	}
}
