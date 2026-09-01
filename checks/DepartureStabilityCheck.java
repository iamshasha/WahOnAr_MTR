import mtr.data.DepartureLedger;

import java.util.Arrays;
import java.util.List;

/**
 * A question asked twice has to come back the same, and asking must not spend anything.
 *
 * This is the shape of a fault that survived four attempts to fix it. The display asked "will this vehicle stable
 * after its lap", and built the answer on the depot's dispatch gate -- which advances a counter every time it is
 * called, so that several sidings asking in the same tick are handed different departures. That is right for
 * dispatch and wrong for a question: asked once a second, it named a different departure every second, so the
 * answer flipped while the vehicle stood still and the boards flickered with it.
 *
 * The other half was the same mistake in the other direction: taking the decision claimed a departure, and the
 * decision was taken every time the vehicle was judged to have left the origin -- which can happen more than once
 * as it eases out. Each pass consumed another departure that nobody then ran.
 *
 * So: lookups are pure and repeatable, claims are not, and nothing that only wants to know something may use a
 * claim to find out.
 */
public class DepartureStabilityCheck {

	private static final int MINUTE = 60 * 1000;
	private static final List<Integer> TIMETABLE = Arrays.asList(
			8 * 60 * MINUTE, 8 * 60 * MINUTE + 6 * MINUTE, 8 * 60 * MINUTE + 12 * MINUTE, 8 * 60 * MINUTE + 18 * MINUTE);

	public static void main(String[] args) {
		assertTheLookupRepeats();
		assertPeekingDoesNotClaim();
		assertClaimingIsWhatSpends();
		System.out.println("DepartureStability ok");
	}

	/** The plain lookup gives the same answer to the same clock, however often it is asked. */
	private static void assertTheLookupRepeats() {
		final long now = TIMETABLE.get(0) + MINUTE;
		final long first = DepartureLedger.findDeparture(TIMETABLE, now, true);
		for (int i = 0; i < 50; i++) {
			final long again = DepartureLedger.findDeparture(TIMETABLE, now, true);
			if (again != first) {
				throw new AssertionError("the lookup moved on its own: " + first + " then " + again);
			}
		}
	}

	/**
	 * Peeking is what a question uses, so asking it fifty times must leave the timetable exactly as it was.
	 *
	 * If this ever fails, a display has started spending departures to find out what to draw.
	 */
	private static void assertPeekingDoesNotClaim() {
		final DepartureLedger ledger = new DepartureLedger();
		final long departsAt = TIMETABLE.get(0);
		final long backAt = departsAt + 4 * MINUTE;

		final long first = ledger.findReachableDeparture(TIMETABLE, departsAt, backAt);
		for (int i = 0; i < 50; i++) {
			final long again = ledger.findReachableDeparture(TIMETABLE, departsAt, backAt);
			if (again != first) {
				throw new AssertionError("asking again changed the answer: " + first + " then " + again);
			}
		}
		for (final int departure : TIMETABLE) {
			if (ledger.isSpent(departure)) {
				throw new AssertionError("asking spent the departure at " + departure);
			}
		}
	}

	/** And claiming still does spend, because that is the half that is meant to. */
	private static void assertClaimingIsWhatSpends() {
		final DepartureLedger ledger = new DepartureLedger();
		final long departure = TIMETABLE.get(1);
		ledger.consume(departure, TIMETABLE.get(0), TIMETABLE.size());
		if (!ledger.isSpent(departure)) {
			throw new AssertionError("a claimed departure did not read as claimed");
		}
		if (ledger.findReachableDeparture(TIMETABLE, TIMETABLE.get(0), TIMETABLE.get(0)) == departure) {
			throw new AssertionError("a claimed departure was offered again");
		}
	}
}
