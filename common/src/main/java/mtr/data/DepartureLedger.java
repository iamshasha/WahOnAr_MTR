package mtr.data;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The timetable arithmetic, and the record of which departures already have a train.
 *
 * Deliberately free of Minecraft types so it can be run on its own. This is the part that put two trains on one
 * departure, and the failure only appears across several sidings with different lead times — a running server is a
 * poor place to find that out, and {@link Depot} cannot be loaded outside the game to ask.
 */
public class DepartureLedger {

	/** Mirrors {@link Depot#MILLISECONDS_PER_DAY}, which cannot be referenced here without dragging Minecraft in. */
	public static final int MILLISECONDS_PER_DAY = 24 * 60 * 60 * 1000;

	/**
	 * Every booked departure a train has already been sent for, as wall clock times.
	 *
	 * A set, not the most recent one. Sidings have different lead times, so they read the timetable from different
	 * moments and their bookings interleave: a long-lead siding books 09:05 while a short-lead siding is still
	 * working toward 09:00, and a single "last booked" then reads 09:05 and lets 09:00 look free again.
	 */
	private final Set<Long> consumed = new HashSet<>();
	/** Everything at or before this wall clock time is spent, whether or not it is in {@link #consumed}. */
	private long floor = -1;

	/**
	 * The departure nearest the reference time, or the first at or after it.
	 *
	 * Yesterday and tomorrow are both in range because the reference can sit near either end of the day.
	 */
	public static long findDeparture(List<Integer> departures, long referenceMillis, boolean atOrAfter) {
		long best = -1;
		final long dayStart = referenceMillis - Math.floorMod(referenceMillis, (long) MILLISECONDS_PER_DAY);
		for (final int departure : departures) {
			for (int day = -1; day <= 1; day++) {
				final long candidate = dayStart + (long) day * MILLISECONDS_PER_DAY + departure;
				if (atOrAfter && candidate < referenceMillis) {
					continue;
				}
				if (best < 0 || Math.abs(candidate - referenceMillis) < Math.abs(best - referenceMillis)) {
					best = candidate;
				}
			}
		}
		return best;
	}

	public static long findLastDepartureAtOrBefore(List<Integer> departures, long referenceMillis) {
		long best = -1;
		final long dayStart = referenceMillis - Math.floorMod(referenceMillis, (long) MILLISECONDS_PER_DAY);
		for (final int departure : departures) {
			for (int day = -1; day <= 1; day++) {
				final long candidate = dayStart + (long) day * MILLISECONDS_PER_DAY + departure;
				if (candidate <= referenceMillis && candidate > best) {
					best = candidate;
				}
			}
		}
		return best;
	}

	/**
	 * How long a vehicle should stand at a timetabled origin, in ticks.
	 *
	 * The booked time is when it leaves, so an early vehicle waits for it: the stop stretches to cover whatever is
	 * left. Expressed as a dwell length rather than as a block because the doors are driven off the dwell, and
	 * anything that reads the dwell then closes them in time of its own accord.
	 *
	 * A vehicle that is already late gets the ordinary stop, not a shortened one. Cutting the stop to nothing to
	 * claw back time sounds right and is not: the doors are told to shut in the same tick the dwell ends, so the
	 * vehicle pulls away while they are still moving, and the closing sound plays behind a train that has already
	 * left. It also gives passengers no time to board, which is what the stop is for.
	 */
	public static int timetabledDwellTicks(int baseDwellTicks, double elapsedDwellTicks, long untilDepartureMillis, double millisPerTick) {
		final double untilDepartureTicks = untilDepartureMillis / millisPerTick;
		// Constant for the whole stop: elapsed climbs by exactly what remaining loses, so this stays at however
		// many ticks the booked departure was away when the vehicle pulled in. Once that is behind it, the sum
		// falls below the ordinary dwell and the ordinary dwell is what is left.
		return Math.max(baseDwellTicks, (int) Math.ceil(elapsedDwellTicks + untilDepartureTicks));
	}

	public boolean isSpent(long departure) {
		return departure <= floor || consumed.contains(departure);
	}

	/**
	 * Marks a departure as having had its train, and forgets the ones too old to matter.
	 *
	 * The set only has to answer for departures still inside a siding's lead, so a day is already far more history
	 * than any question can reach back through.
	 */
	public void consume(long departure, long nowMillis, int departureCount) {
		consumed.add(departure);
		if (consumed.size() > departureCount + 8) {
			final long cutoff = nowMillis - MILLISECONDS_PER_DAY;
			consumed.removeIf(spent -> spent < cutoff);
		}
	}

	/**
	 * The nth departure after the reference time that no train has been sent for, or -1 if there is none.
	 *
	 * Ordinals start at one. A vehicle that answers "which departure am I for?" from the timetable alone names the
	 * one another vehicle was already released for and is out on the line running towards, which puts a second,
	 * identical arrival on every display along its route.
	 */
	public long findUnclaimedDeparture(List<Integer> departures, long referenceMillis, int ordinal) {
		// A day is further ahead than any siding's lead can reach, so running past it means everything left is
		// claimed and there is nothing to name
		final long horizon = referenceMillis + MILLISECONDS_PER_DAY;
		long candidate = referenceMillis;
		for (int found = 0; found < ordinal; ) {
			candidate = findDeparture(departures, candidate + 1, true);
			if (candidate < 0 || candidate > horizon) {
				return -1;
			}
			if (!isSpent(candidate)) {
				found++;
			}
		}
		return candidate;
	}

	/** Everything already in the past is spent, once, when the depot first has a train able to go. */
	/**
	 * The first unclaimed departure after the given one that a train could actually be back in time for.
	 *
	 * A train leaving the origin takes the next departure as its own next trip. Taking it without asking whether
	 * it can be back by then is what let a train claim a departure six minutes away when its own lap takes twenty:
	 * the wait it then measured was negative, which read as "no wait at all", so it stayed out for a trip it could
	 * never make and the departure it was really due back for went unserved.
	 *
	 * A departure it cannot reach is left alone rather than skipped over quietly -- some other train, or the depot
	 * itself, is in a position to run it.
	 *
	 * @param departures    the timetable, as milliseconds into the day
	 * @param afterMillis   the departure just run; the answer is strictly later than this
	 * @param readyAtMillis the earliest this train could be standing at the origin ready to leave
	 * @return the departure, or -1 if nothing within a day is both free and reachable
	 */
	public long findReachableDeparture(List<Integer> departures, long afterMillis, long readyAtMillis) {
		long candidate = afterMillis;
		// Bounded by the timetable itself: going round it once is enough to know nothing in it fits
		for (int examined = 0; examined <= departures.size(); examined++) {
			candidate = findUnclaimedDeparture(departures, candidate, 1);
			if (candidate < 0) {
				return -1;
			}
			if (candidate >= readyAtMillis) {
				return candidate;
			}
		}
		return -1;
	}

	/**
	 * Gives a claimed departure back, for a train that turned out not to be running it.
	 *
	 * Claiming is what stops two trains being sent for one departure. The cost of claiming is that a claim nobody
	 * honours is a departure nobody runs, so every claim needs a way back: a train that stables instead, and a
	 * train whose departure went by while it was still out on the line, both hand it back here.
	 */
	public void release(long departure) {
		consumed.remove(departure);
	}

	public void settle(long departure) {
		floor = departure;
	}

	public long getFloor() {
		return floor;
	}
}
