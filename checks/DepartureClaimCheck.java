import mtr.data.DepartureLedger;

import java.util.Arrays;
import java.util.List;

/**
 * Which departure a train already out on the line takes as its next trip.
 *
 * A train leaving the origin picks up its next departure there and then. Picking one it cannot be back for is
 * what caused the fault this covers: with departures six minutes apart and a lap of twenty, the train leaving on
 * the first took the second as well, measured a negative wait to it, read that as "no wait" and stayed out. The
 * departure it had taken was then refused to the train that could have run it, so that service simply did not
 * run.
 *
 * The cases that matter are the ones where the answer is "not this one": a departure too soon to reach, and a
 * departure another train already holds.
 */
public class DepartureClaimCheck {

	private static final int MINUTE = 60 * 1000;

	/** 16:26 and 16:32, the timetable from the report. */
	private static final List<Integer> TWO_SIX_MINUTES_APART =
			Arrays.asList(16 * 60 * MINUTE + 26 * MINUTE, 16 * 60 * MINUTE + 32 * MINUTE);

	public static void main(String[] args) {
		assertSkipsADepartureItCannotReach();
		assertTakesADepartureItCanReach();
		assertSkipsADepartureAnotherTrainHolds();
		assertGivesUpWhenNothingFits();
		assertAReleasedDepartureComesBack();
		assertASidingKnowsItWillStableBeforeItLeaves();
		assertASidingKnowsItWillNotStableBeforeItLeaves();
		System.out.println("DepartureClaim ok");
	}

	/** The reported fault: a twenty-minute lap cannot serve the departure six minutes away. */
	private static void assertSkipsADepartureItCannotReach() {
		final DepartureLedger ledger = new DepartureLedger();
		final long ranAt = departure(0);
		final long backAt = ranAt + 20 * MINUTE;

		final long next = ledger.findReachableDeparture(TWO_SIX_MINUTES_APART, ranAt, backAt);

		if (next == departure(1)) {
			throw new AssertionError("took the departure six minutes away with a twenty minute lap to run first");
		}
		// Tomorrow's first departure is the soonest it could actually work, which is a long wait -- and a long
		// wait is exactly what tells the train to go to the depot instead
		if (next != departure(0) + DepartureLedger.MILLISECONDS_PER_DAY) {
			throw new AssertionError("expected tomorrow's first departure, got " + next);
		}
	}

	/** A short lap can serve the next departure, and should: that is one unit working two trips. */
	private static void assertTakesADepartureItCanReach() {
		final DepartureLedger ledger = new DepartureLedger();
		final long ranAt = departure(0);
		final long backAt = ranAt + 4 * MINUTE;

		final long next = ledger.findReachableDeparture(TWO_SIX_MINUTES_APART, ranAt, backAt);
		if (next != departure(1)) {
			throw new AssertionError("a train back in four minutes did not take the departure six minutes away");
		}
	}

	/** Two trains must never be sent for one departure, which is what claiming is for. */
	private static void assertSkipsADepartureAnotherTrainHolds() {
		final DepartureLedger ledger = new DepartureLedger();
		ledger.consume(departure(1), departure(0), TWO_SIX_MINUTES_APART.size());

		final long next = ledger.findReachableDeparture(TWO_SIX_MINUTES_APART, departure(0), departure(0) + 4 * MINUTE);
		if (next == departure(1)) {
			throw new AssertionError("took a departure another train had already claimed");
		}
	}

	/** Every departure claimed and nothing reachable: the train has nothing to stay out for. */
	private static void assertGivesUpWhenNothingFits() {
		final DepartureLedger ledger = new DepartureLedger();
		for (int day = 0; day <= 1; day++) {
			for (int i = 0; i < TWO_SIX_MINUTES_APART.size(); i++) {
				ledger.consume(departure(i) + (long) day * DepartureLedger.MILLISECONDS_PER_DAY,
						departure(0), TWO_SIX_MINUTES_APART.size() * 4);
			}
		}

		final long next = ledger.findReachableDeparture(TWO_SIX_MINUTES_APART, departure(0), departure(0));
		if (next >= 0) {
			throw new AssertionError("found a departure when every one of them was claimed: " + next);
		}
	}

	/**
	 * A claim handed back is available again. Without this a train that stabled, or one whose departure went by
	 * while it was still out, would hold a departure nobody could run -- which is worse than the double-booking
	 * claiming was introduced to prevent.
	 */
	private static void assertAReleasedDepartureComesBack() {
		final DepartureLedger ledger = new DepartureLedger();
		ledger.consume(departure(1), departure(0), TWO_SIX_MINUTES_APART.size());

		if (!ledger.isSpent(departure(1))) {
			throw new AssertionError("a claimed departure did not read as claimed");
		}

		ledger.release(departure(1));

		if (ledger.isSpent(departure(1))) {
			throw new AssertionError("a released departure still reads as claimed");
		}
		final long next = ledger.findReachableDeparture(TWO_SIX_MINUTES_APART, departure(0), departure(0) + 4 * MINUTE);
		if (next != departure(1)) {
			throw new AssertionError("a released departure was not offered to the next train");
		}
	}

	/**
	 * The same answer, one lap earlier: a train still in the siding can say it will stable.
	 *
	 * This is what puts the origin's name on the displays and marks the run as the one that goes back to the depot.
	 * Answering it only after the train had left meant the same run was advertised one way before it pulled away
	 * and another way after.
	 */
	private static void assertASidingKnowsItWillStableBeforeItLeaves() {
		final DepartureLedger ledger = new DepartureLedger();
		final long departsAt = departure(0);
		final long backAt = departsAt + 20 * MINUTE;

		final long next = ledger.findReachableDeparture(TWO_SIX_MINUTES_APART, departsAt, backAt);
		if (!stables(next, backAt)) {
			throw new AssertionError("a train that cannot be back for anything did not read as stabling: " + next);
		}
	}

	/** The other way round: a lap short enough to make the next departure is a train that stays out. */
	private static void assertASidingKnowsItWillNotStableBeforeItLeaves() {
		final DepartureLedger ledger = new DepartureLedger();
		final long departsAt = departure(0);
		final long backAt = departsAt + 4 * MINUTE;

		final long next = ledger.findReachableDeparture(TWO_SIX_MINUTES_APART, departsAt, backAt);
		if (next != departure(1)) {
			throw new AssertionError("a train back in four minutes did not find the departure six minutes away");
		}
		if (stables(next, backAt)) {
			throw new AssertionError("a train with a departure two minutes after it gets back read as stabling");
		}
	}

	/**
	 * The rule the train uses: nothing to come back for, or a wait past the depot's threshold.
	 *
	 * Four minutes is the threshold the reported railway is set to.
	 */
	private static boolean stables(long next, long backAt) {
		return next < 0 || next - backAt > 4L * MINUTE;
	}

	/** The given departure of the timetable, as an absolute moment on day zero. */
	private static long departure(int index) {
		return TWO_SIX_MINUTES_APART.get(index);
	}
}
