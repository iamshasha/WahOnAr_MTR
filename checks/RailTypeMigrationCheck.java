import mtr.data.RailType;
import mtr.data.RailTypeMigration;

/**
 * Asserts that a rail keeps its speed when the name of its type stops being readable.
 *
 * A rail records its type by name and an unreadable name falls back to IRON, silently, at 80 km/h. That is what
 * would have happened to every high speed rail on the server the moment the High Speed Rails addon was removed,
 * and it is what MTR 4's converter does today for anything outside its own list of sixteen -- except it falls
 * back to WOODEN, at 20. Neither case announces itself: the track is still there, still connected, just slow.
 *
 * So the speed is written onto the rail as well as into its type name, and the type is worked back out from it.
 * These are the cases that has to get right, including the ones where it must NOT act.
 *
 * Needs an intermediary-mapped Minecraft on the classpath, because RailType names a MaterialColor.
 */
public class RailTypeMigrationCheck {

	public static void main(String[] args) {
		// A name this build knows is used as-is, whatever is stamped beside it
		assertType(RailType.NETHERITE, "NETHERITE", 0);
		assertType(RailType.NETHERITE, "NETHERITE", 450);
		assertType(RailType.BEDROCK, "BEDROCK", 800);
		// A rail that genuinely says IRON stays IRON rather than being "recovered" into something else
		assertType(RailType.IRON, "IRON", 0);
		assertType(RailType.IRON, "IRON", 450);
		// The case this exists for: the name is gone, the stamped speed puts the type back
		assertType(RailType.NETHERITE, "SOME_TYPE_THIS_BUILD_LOST", 450);
		assertType(RailType.PURPUR, "SOME_TYPE_THIS_BUILD_LOST", 500);
		assertType(RailType.REINFORCED_DEEPSLATE, "SOME_TYPE_THIS_BUILD_LOST", 600);
		assertType(RailType.BARRIER, "SOME_TYPE_THIS_BUILD_LOST", 700);
		assertType(RailType.BEDROCK, "SOME_TYPE_THIS_BUILD_LOST", 800);
		// An unknown name with nothing stamped, or a speed matching nothing, has to stay the old fallback rather
		// than guess at the nearest type
		assertType(RailType.IRON, "SOME_TYPE_THIS_BUILD_LOST", 0);
		assertType(RailType.IRON, "SOME_TYPE_THIS_BUILD_LOST", 137);
		// An upstream type's speed must not be recoverable this way: 300 is DIAMOND, and a lost name carrying 300
		// says nothing about whether it was a diamond rail
		assertType(RailType.IRON, "SOME_TYPE_THIS_BUILD_LOST", 300);

		// Stamping: only the types at risk, only when the builder has not set their own limit
		assertStamp(450, RailType.NETHERITE, 0);
		assertStamp(800, RailType.BEDROCK, 0);
		assertStamp(30, RailType.NETHERITE, 30);
		assertStamp(0, RailType.IRON, 0);
		assertStamp(0, RailType.DIAMOND, 0);
		assertStamp(60, RailType.DIAMOND, 60);

		// A stamped rail must survive a round trip through a build that has lost the name, which is the whole point
		for (final RailType railType : new RailType[]{RailType.NETHERITE, RailType.PURPUR,
				RailType.REINFORCED_DEEPSLATE, RailType.BARRIER, RailType.BEDROCK}) {
			final int stamped = RailTypeMigration.saveSpeedLimit(railType, 0);
			final RailType recovered = RailTypeMigration.readSaved("NAME_NO_LONGER_KNOWN", stamped);
			if (recovered.speedLimit != railType.speedLimit) {
				throw new AssertionError(railType + " came back as " + recovered + ", losing "
						+ (railType.speedLimit - recovered.speedLimit) + " km/h");
			}
		}

		System.out.println("RailTypeMigration ok");
	}

	private static void assertType(RailType expected, String savedName, int savedSpeedLimit) {
		final RailType actual = RailTypeMigration.readSaved(savedName, savedSpeedLimit);
		if (actual != expected) {
			throw new AssertionError("readSaved(\"" + savedName + "\", " + savedSpeedLimit + ") gave " + actual
					+ ", expected " + expected);
		}
	}

	private static void assertStamp(int expected, RailType railType, int savedSpeedLimit) {
		final int actual = RailTypeMigration.saveSpeedLimit(railType, savedSpeedLimit);
		if (actual != expected) {
			throw new AssertionError("saveSpeedLimit(" + railType + ", " + savedSpeedLimit + ") gave " + actual
					+ ", expected " + expected);
		}
	}
}
