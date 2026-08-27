package mtr.data;

/**
 * The rule for breaking a pair of trains that are each waiting on track the other is standing on.
 *
 * Kept apart from {@link TrainServer} so it can be exercised on its own: the property that matters is that exactly
 * one of a stuck pair proceeds. Let both through and they pass each other in the middle of a section; let neither
 * and the deadlock the rule exists to break simply survives it.
 */
public final class TrainDeadlock {

	private TrainDeadlock() {
	}

	/**
	 * Whether this train is the one that proceeds through a train blocking it.
	 *
	 * @param trainId          the train deciding whether to move
	 * @param otherTrainId     the train standing on the track it wants
	 * @param otherWaitsOnThis whether that train has published this one as what is holding it up
	 */
	public static boolean proceeds(long trainId, long otherTrainId, boolean otherWaitsOnThis) {
		// Only a genuine standoff is broken. The lower id is an arbitrary tie-break, but both sides compute it
		// identically, so exactly one of them moves.
		return otherWaitsOnThis && trainId < otherTrainId;
	}
}
