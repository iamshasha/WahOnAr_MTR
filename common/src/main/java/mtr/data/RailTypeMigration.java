package mtr.data;

import java.util.EnumSet;

/**
 * Reading a saved rail's type, and keeping its speed readable after its type is not.
 *
 * Deliberately its own class rather than static methods on {@link RailType}. The High Speed Rails addon shipped
 * its own copy of {@code mtr.data.RailType} compiled in 2023, and Fabric loads mods in id order, so on a client
 * that still has that addon installed its copy is the one everything resolves against -- ours never loads. Any
 * method added to RailType would therefore be missing at exactly the moment {@link Rail} calls it, and the
 * upgrade would end in NoSuchMethodError for anyone who had not removed the addon yet. Nothing shadows this
 * class, so it is there either way, and the constants it names are in both copies under the same names.
 */
public interface RailTypeMigration {

	/**
	 * The types that came from the High Speed Rails addon rather than from upstream.
	 *
	 * Kept as a set rather than a flag on the constant because it is a fact about where a type came from, not
	 * about how it behaves: every one of them runs exactly like an ordinary rail.
	 */
	EnumSet<RailType> FROM_HIGH_SPEED_RAILS_ADDON =
			EnumSet.of(RailType.NETHERITE, RailType.PURPUR, RailType.REINFORCED_DEEPSLATE,
					RailType.BARRIER, RailType.BEDROCK);

	/**
	 * The type a saved rail meant, recovering one this build does not recognise from the speed saved beside it.
	 *
	 * A rail records its type by name, and an unreadable name falls back to IRON -- silently, and at 80 km/h. Track
	 * laid with a type that some later build no longer has would therefore not vanish, which would at least be
	 * visible; it would quietly become slow track, which is far harder to notice and impossible to undo, because by
	 * then the original name is gone. Every rail carrying one of these types has its speed stamped onto it by
	 * {@link #saveSpeedLimit}, so the number survives the name and the type can be worked back out from it.
	 */
	static RailType readSaved(String savedName, int savedSpeedLimitKmh) {
		final RailType railType = EnumHelper.valueOf(RailType.IRON, savedName);
		if (railType != RailType.IRON || RailType.IRON.name().equals(savedName)) {
			// Either the name was understood, or it genuinely said IRON. Nothing to recover.
			return railType;
		}
		for (final RailType candidate : FROM_HIGH_SPEED_RAILS_ADDON) {
			if (candidate.speedLimit == savedSpeedLimitKmh) {
				return candidate;
			}
		}
		return railType;
	}

	/**
	 * The speed limit to save on a rail, which for these types is their own speed rather than nothing.
	 *
	 * Zero means "whatever the type allows", which is right for every rail whose type is not in danger of being
	 * lost. Writing the number out for these costs nothing today -- the limit only ever caps a train, and capping
	 * it at exactly the speed its own rail already permits changes nothing -- and it is the only thing that lets
	 * {@link #readSaved} put the type back afterwards. An operator's own lower limit is left alone.
	 */
	static int saveSpeedLimit(RailType railType, int speedLimitKmh) {
		return speedLimitKmh == 0 && FROM_HIGH_SPEED_RAILS_ADDON.contains(railType) ? railType.speedLimit : speedLimitKmh;
	}
}
