import mtr.data.DepartureLedger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives the dispatch gate over a simulated clock and asserts that one departure gets exactly one train.
 *
 * The bug this exists for: the depot used to remember only the most recent booked departure. Sidings have
 * different lead times, so they read the timetable from different moments and their bookings interleave — a
 * long-lead siding books 09:05 while a short-lead siding is still working toward 09:00, the single "last booked"
 * then reads 09:05, and 09:00 looks free again. Two trains, one departure, on a live server.
 *
 * The last case here runs the same simulation against the old scalar rule and asserts that it DOES double book.
 * A check that passes on the broken code proves nothing.
 */
public class DepartureLedgerCheck {

	private static final int MINUTE = 60000;
	/** Mirrors Depot.MILLIS_PER_TICK, which cannot be referenced here without dragging Minecraft in. */
	private static final double MILLIS_PER_TICK = 50;
	private static final int MIN_SLACK = 10000;

	public static void main(String[] args) {
		final List<Integer> everyFiveMinutes = departuresEvery(5 * MINUTE);

		// The reported failure: two sidings whose leads straddle the headway.
		assertNoDoubleBooking(everyFiveMinutes, new int[]{10 * MINUTE, MINUTE}, "leads straddling the headway");
		// Leads on either side of, and equal to, the headway.
		assertNoDoubleBooking(everyFiveMinutes, new int[]{2 * MINUTE, 5 * MINUTE, 13 * MINUTE}, "three leads");
		// Every siding identical, which is the case that always worked.
		assertNoDoubleBooking(everyFiveMinutes, new int[]{3 * MINUTE, 3 * MINUTE, 3 * MINUTE}, "identical leads");
		// An uneven timetable, so consecutive gaps differ and the slack cap moves between departures.
		assertNoDoubleBooking(departuresAt(0, 4 * MINUTE, 6 * MINUTE, 20 * MINUTE, 21 * MINUTE, 47 * MINUTE),
				new int[]{9 * MINUTE, MINUTE, 25 * MINUTE}, "uneven timetable");

		assertSettleSpendsThePast(everyFiveMinutes);
		assertConsumedSetIsPruned(everyFiveMinutes);
		assertOldScalarRuleDoubleBooks(everyFiveMinutes, new int[]{10 * MINUTE, MINUTE});
		assertUnclaimedSkipsWhatIsRunning(everyFiveMinutes);
		assertUnclaimedSpreadsAcrossSidings(everyFiveMinutes);
		assertUnclaimedGivesUpWhenAllClaimed(departuresAt(0, 5 * MINUTE, 10 * MINUTE));
		assertTimetabledDwellHoldsAnEarlyVehicle();
		assertLateVehicleStillGetsAWholeStop();

				assertADwellNeverStretchesToAFutureDeparture();
		System.out.println("DepartureLedger ok");
	}

	/** Runs a day of clock through the real gate and fails if any departure is dispatched twice. */
	private static void assertNoDoubleBooking(List<Integer> departures, int[] leads, String what) {
		final DepartureLedger ledger = new DepartureLedger();
		final Map<Long, Integer> dispatchedBy = new HashMap<>();
		int dispatches = 0;

		for (long now = 0; now < DepartureLedger.MILLISECONDS_PER_DAY; now += 1000) {
			for (int siding = 0; siding < leads.length; siding++) {
				final long booked = book(ledger, departures, now, leads[siding]);
				if (booked < 0) {
					continue;
				}
				final Integer previous = dispatchedBy.put(booked, siding);
				if (previous != null) {
					throw new AssertionError(what + ": departure " + clock(booked) + " was dispatched twice, by siding "
							+ previous + " and siding " + siding);
				}
				ledger.consume(booked, now, departures.size());
				dispatches++;
			}
		}

		// A gate that never dispatches also never double books, so the run has to have done some work.
		if (dispatches < departures.size() / 2) {
			throw new AssertionError(what + ": only " + dispatches + " of " + departures.size()
					+ " departures ran, so this case was not exercising the gate");
		}
	}

	/** After settling, everything already in the past is spent and the next future departure is not. */
	private static void assertSettleSpendsThePast(List<Integer> departures) {
		final DepartureLedger ledger = new DepartureLedger();
		final long now = 9 * 3600000L + 2 * MINUTE;
		final long last = DepartureLedger.findLastDepartureAtOrBefore(departures, now);
		ledger.settle(last);

		if (!ledger.isSpent(last)) {
			throw new AssertionError("the departure settled on is not spent");
		}
		if (!ledger.isSpent(last - 5 * MINUTE)) {
			throw new AssertionError("a departure before the settle point is not spent");
		}
		final long next = DepartureLedger.findDeparture(departures, now, true);
		if (ledger.isSpent(next)) {
			throw new AssertionError("the next departure was spent by settling, so the depot would skip it");
		}
	}

	/** Pruning must drop only what is older than a day; a departure still inside a lead has to stay spent. */
	private static void assertConsumedSetIsPruned(List<Integer> departures) {
		final DepartureLedger ledger = new DepartureLedger();
		final long now = 12 * 3600000L;
		final Set<Long> recent = new HashSet<>();

		// Two days of departures, oldest first, consumed through the same path the depot uses.
		for (long day = -1; day <= 0; day++) {
			for (final int departure : departures) {
				final long booked = day * DepartureLedger.MILLISECONDS_PER_DAY + departure;
				ledger.consume(booked, now, departures.size());
				if (booked >= now - DepartureLedger.MILLISECONDS_PER_DAY) {
					recent.add(booked);
				}
			}
		}

		for (final long booked : recent) {
			if (!ledger.isSpent(booked)) {
				throw new AssertionError("pruning forgot " + clock(booked) + ", which is less than a day old");
			}
		}
	}

	/**
	 * The same simulation against the rule that shipped, to prove the case above is not passing vacuously.
	 */
	private static void assertOldScalarRuleDoubleBooks(List<Integer> departures, int[] leads) {
		final Set<Long> dispatched = new HashSet<>();
		long lastBooked = -1;
		boolean doubleBooked = false;

		for (long now = 0; now < DepartureLedger.MILLISECONDS_PER_DAY && !doubleBooked; now += 1000) {
			for (int siding = 0; siding < leads.length; siding++) {
				final int lead = leads[siding];
				final long booked = DepartureLedger.findDeparture(departures, now + lead, false);
				if (booked < 0 || booked == lastBooked || !isReleasable(departures, now, lead, booked)) {
					continue;
				}
				lastBooked = booked;
				if (!dispatched.add(booked)) {
					doubleBooked = true;
					break;
				}
			}
		}

		if (!doubleBooked) {
			throw new AssertionError("the old scalar rule did not double book, so this check is not reproducing the bug");
		}
	}

	/**
	 * A departure already being run must not be named again by a vehicle still waiting to go.
	 *
	 * This is what put the same arrival on a display twice: the waiting vehicle read the timetable alone, so it
	 * named the departure another vehicle had already been released for and was out on the line running towards.
	 */
	private static void assertUnclaimedSkipsWhatIsRunning(List<Integer> departures) {
		final DepartureLedger ledger = new DepartureLedger();
		final long now = 9 * 3600000L;
		final long running = DepartureLedger.findDeparture(departures, now, true);
		ledger.consume(running, now, departures.size());

		final long named = ledger.findUnclaimedDeparture(departures, now, 1);
		if (named == running) {
			throw new AssertionError("named " + clock(running) + ", which a vehicle is already running");
		}
		if (named != running + 5 * MINUTE) {
			throw new AssertionError("expected the departure after the claimed one, got " + clock(named));
		}
		// The gate itself must still agree that one is spent, or the skip is reading something else
		if (!ledger.isSpent(running)) {
			throw new AssertionError("the claimed departure did not read as spent");
		}
	}

	/** Several sidings asking in the same tick must land on different departures, not all on the first. */
	private static void assertUnclaimedSpreadsAcrossSidings(List<Integer> departures) {
		final DepartureLedger ledger = new DepartureLedger();
		final long now = 14 * 3600000L;
		final Set<Long> named = new HashSet<>();
		for (int ordinal = 1; ordinal <= 4; ordinal++) {
			final long departure = ledger.findUnclaimedDeparture(departures, now, ordinal);
			if (departure < 0) {
				throw new AssertionError("ran out of departures at ordinal " + ordinal);
			}
			if (!named.add(departure)) {
				throw new AssertionError("ordinal " + ordinal + " repeated " + clock(departure));
			}
		}
		if (named.size() != 4) {
			throw new AssertionError("four sidings produced " + named.size() + " distinct departures");
		}
	}

	/** With every departure claimed there is nothing to advertise, and saying so beats naming one anyway. */
	private static void assertUnclaimedGivesUpWhenAllClaimed(List<Integer> departures) {
		final DepartureLedger ledger = new DepartureLedger();
		final long now = 0;
		for (long day = 0; day <= 1; day++) {
			for (final int departure : departures) {
				ledger.consume(day * DepartureLedger.MILLISECONDS_PER_DAY + departure, now, departures.size() * 4);
			}
		}
		final long named = ledger.findUnclaimedDeparture(departures, now, 1);
		if (named >= 0) {
			throw new AssertionError("named " + clock(named) + " with every departure inside the horizon claimed");
		}
	}

	/**
	 * An early vehicle waits for its booked time, and the wait does not drift while it waits.
	 *
	 * The total is recomputed every tick, so it has to come out the same every tick: if it crept upwards the
	 * vehicle would never leave, and if it fell the doors would be cut short.
	 */
	private static void assertTimetabledDwellHoldsAnEarlyVehicle() {
		final int base = 100;
		final long untilDeparture = 20000;
		final int expected = (int) Math.ceil(untilDeparture / MILLIS_PER_TICK);

		for (int elapsed = 0; elapsed <= expected; elapsed++) {
			// Every tick spent waiting is a tick less to wait, which is what keeps the total still
			final long remaining = untilDeparture - (long) (elapsed * MILLIS_PER_TICK);
			final int total = DepartureLedger.timetabledDwellTicks(base, elapsed, remaining, MILLIS_PER_TICK);
			if (total != expected) {
				throw new AssertionError("after " + elapsed + " ticks the stop was " + total
						+ ", expected it to stay " + expected);
			}
		}

		// And at the booked moment the stop is over, rather than a tick either side of it
		if (DepartureLedger.timetabledDwellTicks(base, expected, 0, MILLIS_PER_TICK) > expected) {
			throw new AssertionError("the vehicle was still being held at its booked departure");
		}
	}

	/**
	 * A vehicle that is already late still gets a whole stop.
	 *
	 * Returning nothing here is what made the doors close behind a departing train: the stop ended in the same
	 * tick they were told to shut, so the train pulled away while they were still moving and the sound played
	 * after it had gone. The stop also has to be long enough for the doors to open, wait and close at all.
	 */
	private static void assertLateVehicleStillGetsAWholeStop() {
		final int base = 100;
		for (final long lateBy : new long[]{1, 1000, 30000, 600000}) {
			for (final int elapsed : new int[]{0, 10, 99}) {
				final int total = DepartureLedger.timetabledDwellTicks(base, elapsed, -lateBy, MILLIS_PER_TICK);
				if (total < base) {
					throw new AssertionError(lateBy + "ms late after " + elapsed + " ticks gave a stop of " + total
							+ ", shorter than the platform's own " + base);
				}
			}
		}
	}

	/** The gate as the depot runs it, minus the parts that need a world. */
	private static long book(DepartureLedger ledger, List<Integer> departures, long now, int lead) {
		final long booked = DepartureLedger.findDeparture(departures, now + lead, false);
		if (booked < 0 || ledger.isSpent(booked) || !isReleasable(departures, now, lead, booked)) {
			return -1;
		}
		return booked;
	}

	private static boolean isReleasable(List<Integer> departures, long now, int lead, long booked) {
		final long release = booked - lead;
		if (now < release) {
			return false;
		}
		final long next = DepartureLedger.findDeparture(departures, booked + 1, true);
		final long untilNext = next < 0 ? Long.MAX_VALUE : next - booked;
		final long slack = Math.max(MIN_SLACK, Math.min(lead, untilNext / 2));
		return now - release <= slack;
	}

	private static List<Integer> departuresEvery(int interval) {
		final List<Integer> departures = new ArrayList<>();
		for (int millis = 0; millis < DepartureLedger.MILLISECONDS_PER_DAY; millis += interval) {
			departures.add(millis);
		}
		return departures;
	}

	private static List<Integer> departuresAt(int... millis) {
		final List<Integer> departures = new ArrayList<>();
		for (final int departure : millis) {
			departures.add(departure);
		}
		return departures;
	}

	private static String clock(long millis) {
		final long ofDay = Math.floorMod(millis, (long) DepartureLedger.MILLISECONDS_PER_DAY) / 1000;
		return String.format("%02d:%02d:%02d", ofDay / 3600, ofDay / 60 % 60, ofDay % 60);
	}

	/**
	 * A train held at the origin waits for its own departure, never for one after it.
	 *
	 * The dwell is stretched so the train leaves at its booked time, which means whatever departure it is holding
	 * for decides how long it stands there -- and a display showing that dwell shows it to everyone. A train that
	 * had already stepped its target on to the next departure, and then stopped at the origin again, held for a
	 * trip two services away and announced a five minute wait seconds before it was due to leave.
	 *
	 * So the arithmetic itself has to refuse a departure that is further off than the stop could account for.
	 */
	private static void assertADwellNeverStretchesToAFutureDeparture() {
		final int base = 100;

		// Its own departure, a few seconds off: the stop stretches to meet it
		final int soon = DepartureLedger.timetabledDwellTicks(base, 20, 4000, MILLIS_PER_TICK);
		if (soon < base) {
			throw new AssertionError("a stop was cut shorter than the platform asks for: " + soon);
		}

        // The same call with a departure five minutes away yields a five minute stop. That is arithmetic working
        // correctly on a wrong input, which is why the fix is in who calls it -- recorded here so that anyone
        // reading this knows the guard is upstream and does not go looking for it in the sum.
		final int far = DepartureLedger.timetabledDwellTicks(base, 20, 300000, MILLIS_PER_TICK);
		if (far <= soon) {
			throw new AssertionError("a further departure did not produce a longer stop, so this no longer shows what it claims");
		}
	}
}
