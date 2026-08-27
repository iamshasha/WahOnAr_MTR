package mtr.data;

/**
 * Works out how fast a train has to run to arrive somewhere at a given moment.
 *
 * Kept apart from {@link TrainServer} so it can be exercised against a forward simulation on its own: the answer
 * is a closed form, and a closed form that is subtly wrong looks exactly like one that is right.
 */
public final class TrainCatchUp {

	/**
	 * Relative slack on the discriminant, so an exactly-reachable run is not called impossible.
	 *
	 * Sized for float rather than double: the acceleration arrives as a float and the distance is accumulated in
	 * double, so the two disagree in the seventh digit and a run that is exactly on the limit lands a hair either
	 * side of zero. In distance terms this slack is a small fraction of a block.
	 */
	private static final double ROUNDING_TOLERANCE = 1e-6;

	private TrainCatchUp() {
	}

	/**
	 * The speed a train must reach to cover {@code distance} in {@code ticks} and still be stopped at the end.
	 *
	 * Distance over time answers a different question — the average — and a train cannot hold its average, because
	 * it starts from wherever it is and has to be stationary when it arrives. The time it spends getting up to
	 * speed and back down again is time spent below the average, so a train told to run at the average arrives late
	 * every time.
	 *
	 * Take the run as accelerate to some peak {@code v}, hold it, then brake to a stand. The distance that covers
	 * in time {@code T} from start speed {@code u} at acceleration {@code a} is
	 *
	 * <pre>  D = vT + (2uv - u² - 2v²) / 2a</pre>
	 *
	 * which rearranges to {@code 2v² - 2v(aT + u) + (u² + 2aD) = 0}. Of its two roots the smaller is the gentler
	 * way to do it, so that is the one taken.
	 *
	 * @return {@link Float#MAX_VALUE} when the distance cannot be covered in the time however hard the train runs,
	 * leaving the caller's own ceiling — the rail type's maximum — to decide what it does instead.
	 */
	public static float peakSpeedToArriveIn(double distance, double ticks, float startSpeed, float acceleration) {
		if (distance <= 0 || ticks <= 0 || acceleration <= 0) {
			return 0;
		}
		final double b = acceleration * ticks + startSpeed;
		final double discriminant = b * b - 2 * startSpeed * startSpeed - 4 * acceleration * distance;
		// A run right on the edge of what is possible comes out a hair negative through rounding alone, and the
		// answer there is the honest one — flat out, arriving exactly on time — not "cannot be done"
		if (discriminant < -ROUNDING_TOLERANCE * Math.max(1, b * b)) {
			return Float.MAX_VALUE;
		}
		return (float) ((b - Math.sqrt(Math.max(0, discriminant))) / 2);
	}
}
