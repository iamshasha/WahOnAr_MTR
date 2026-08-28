import mtr.data.Rail;
import mtr.data.RailType;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Drives a rail through the real deserializer and asserts that its speed is written onto it, and that the rail
 * says so.
 *
 * The saying-so matters as much as the stamping. The saver decides what to write by hashing the object it holds
 * and comparing that with the hash it recorded when it read the file. Stamping happens while reading, so by the
 * time the hash is taken the object already carries the stamp, the two agree, and the file is never rewritten:
 * the number lives in memory for the lifetime of the server and is gone on the next restart. The flag is what
 * lets the saver notice. A version of this that only checked the speed would have passed against the code that
 * had this bug.
 *
 * Needs an intermediary-mapped Minecraft on the classpath, because Rail names Minecraft types.
 */
public class RailStampCheck {

	public static void main(String[] args) {
		// A rail saved before 3.4.0: it has the type name and no speed of its own
		assertRail("NETHERITE", 0, RailType.NETHERITE, 450, true);
		assertRail("BEDROCK", 0, RailType.BEDROCK, 800, true);
		assertRail("PURPUR", 0, RailType.PURPUR, 500, true);

		// Read a second time, as it would be after the stamp has reached the disk. Nothing left to do, so the
		// saver must not be told the file is stale -- otherwise every rail is rewritten on every load, forever.
		assertRail("NETHERITE", 450, RailType.NETHERITE, 450, false);
		assertRail("BEDROCK", 800, RailType.BEDROCK, 800, false);

		// An ordinary rail is left alone, stamp and flag both
		assertRail("IRON", 0, RailType.IRON, 0, false);
		assertRail("DIAMOND", 0, RailType.DIAMOND, 0, false);

		// A limit a builder set by hand is theirs, and is not overwritten with the type's own speed
		assertRail("NETHERITE", 30, RailType.NETHERITE, 30, false);
		assertRail("IRON", 40, RailType.IRON, 40, false);

		// The name is gone, but the speed it left behind still names the type
		assertRail("SOME_TYPE_THIS_BUILD_LOST", 450, RailType.NETHERITE, 450, false);
		// ...and with nothing left behind, the old fallback stands
		assertRail("SOME_TYPE_THIS_BUILD_LOST", 0, RailType.IRON, 0, false);

		System.out.println("RailStamp ok");
	}

	private static void assertRail(String savedName, int savedSpeedLimit, RailType expectedType,
								   int expectedSpeedLimit, boolean expectedStamped) {
		final Rail rail = new Rail(railMap(savedName, savedSpeedLimit));
		final String what = String.format("rail saved as %s with speed_limit %d", savedName, savedSpeedLimit);

		if (rail.railType != expectedType) {
			throw new AssertionError(what + " read back as " + rail.railType + ", expected " + expectedType);
		}
		if (rail.speedLimitKmh != expectedSpeedLimit) {
			throw new AssertionError(what + " kept speed limit " + rail.speedLimitKmh
					+ ", expected " + expectedSpeedLimit);
		}
		if (rail.stampedOnLoad != expectedStamped) {
			throw new AssertionError(what + (expectedStamped
					? " did not report that loading changed it, so the saver would never write the stamp out"
					: " reported that loading changed it when it did not, so it would be rewritten on every load"));
		}
	}

	/** The parts of a saved rail this cares about; everything else may default. */
	private static Map<String, Value> railMap(String railType, int speedLimit) {
		final Map<String, Value> map = new HashMap<>();
		map.put("rail_type", ValueFactory.newString(railType));
		map.put("speed_limit", ValueFactory.newInteger(speedLimit));
		map.put("transport_mode", ValueFactory.newString("TRAIN"));
		return map;
	}
}
