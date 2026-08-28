import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reports what rail types a saved world actually contains, and whether each one still has somewhere to go.
 *
 * Answers two questions that are otherwise guesswork before removing an addon or migrating:
 *
 *   1. Would any rail lose its speed if a mod were removed? A rail records its type by name, and a name nothing
 *      recognises falls back to iron at 80 km/h — silently, and unrecoverably, because the name was the only
 *      record of what it was. This lists every type name present and says who owns it.
 *   2. Has the speed been written onto the rails themselves yet? MTR 3.4.0 stamps a rail's own speed alongside
 *      its type name so the number survives the name. Stamping happens as a world is read and reaches the disk on
 *      the next save, so there is a window where it is true in memory and not yet true on disk.
 *
 * Reads the files directly and changes nothing. Safe to point at a live world, though a copy is better manners.
 *
 *   java -cp "MTR-fabric-1.19.4-3.4.0.jar;tools" RailAudit "<world>/mtr"
 *
 * The path may be the world folder, its mtr folder, or a single dimension folder; all three are searched.
 */
public class RailAudit {

	/** The types upstream MTR has always had. Anything else came from somewhere, and that somewhere matters. */
	private static final Set<String> UPSTREAM = new HashSet<>(List.of(
			"WOODEN", "STONE", "EMERALD", "IRON", "OBSIDIAN", "BLAZE", "QUARTZ", "DIAMOND", "PLATFORM", "SIDING",
			"TURN_BACK", "CABLE_CAR", "CABLE_CAR_STATION", "RUNWAY", "AIRPLANE_DUMMY", "NONE"));

	/** Absorbed into this fork by 3.4.0, so these are safe without the High Speed Rails addon installed. */
	private static final Map<String, Integer> ABSORBED = Map.of(
			"NETHERITE", 450, "PURPUR", 500, "REINFORCED_DEEPSLATE", 600, "BARRIER", 700, "BEDROCK", 800);

	private static final String KEY_RAIL_CONNECTIONS = "rail_connections";
	private static final String KEY_RAIL_TYPE = "rail_type";
	private static final String KEY_SPEED_LIMIT = "speed_limit";

	private static final Map<String, long[]> COUNTS = new TreeMap<>();

	public static void main(String[] args) throws IOException {
		if (args.length == 0) {
			System.out.println("usage: RailAudit <path to a world, its mtr folder, or one dimension folder>");
			System.exit(2);
		}

		final List<Path> railFolders = new ArrayList<>();
		findRailFolders(Paths.get(args[0]), railFolders, 0);
		if (railFolders.isEmpty()) {
			System.out.println("no rails folder found under " + args[0]);
			System.exit(2);
		}

		for (final Path railFolder : railFolders) {
			System.out.println("reading " + railFolder);
			readRails(railFolder);
		}

		report();
	}

	/** The layout is <world>/mtr/<namespace>/<dimension>/rails, so the folder can be given at any level. */
	private static void findRailFolders(Path path, List<Path> found, int depth) throws IOException {
		if (depth > 4 || !Files.isDirectory(path)) {
			return;
		}
		if (path.getFileName() != null && path.getFileName().toString().equals("rails")) {
			found.add(path);
			return;
		}
		try (final Stream<Path> children = Files.list(path)) {
			for (final Path child : children.toList()) {
				findRailFolders(child, found, depth + 1);
			}
		}
	}

	private static void readRails(Path railFolder) throws IOException {
		try (final Stream<Path> idFolders = Files.list(railFolder)) {
			for (final Path idFolder : idFolders.toList()) {
				if (!Files.isDirectory(idFolder)) {
					continue;
				}
				try (final Stream<Path> idFiles = Files.list(idFolder)) {
					for (final Path idFile : idFiles.toList()) {
						readRailEntry(idFile);
					}
				}
			}
		}
	}

	private static void readRailEntry(Path idFile) {
		try (final InputStream inputStream = Files.newInputStream(idFile);
			 final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(inputStream)) {
			final int size = unpacker.unpackMapHeader();
			for (int i = 0; i < size; i++) {
				final String key = unpacker.unpackString();
				final Value value = unpacker.unpackValue();
				if (key.equals(KEY_RAIL_CONNECTIONS) && value.isArrayValue()) {
					value.asArrayValue().forEach(RailAudit::countConnection);
				}
			}
		} catch (Exception e) {
			System.out.println("  could not read " + idFile + ": " + e);
		}
	}

	private static void countConnection(Value connection) {
		if (!connection.isMapValue()) {
			return;
		}
		String railType = "";
		long speedLimit = 0;
		for (final Map.Entry<Value, Value> entry : connection.asMapValue().entrySet()) {
			final String key = entry.getKey().toString();
			if (key.equals(KEY_RAIL_TYPE)) {
				railType = entry.getValue().asStringValue().asString();
			} else if (key.equals(KEY_SPEED_LIMIT) && entry.getValue().isIntegerValue()) {
				speedLimit = entry.getValue().asIntegerValue().toLong();
			}
		}
		if (!railType.isEmpty()) {
			// [total, how many carry their own speed]
			final long[] counts = COUNTS.computeIfAbsent(railType, key -> new long[2]);
			counts[0]++;
			if (speedLimit > 0) {
				counts[1]++;
			}
		}
	}

	private static void report() {
		System.out.println();
		System.out.printf("%-24s %10s %10s  %s%n", "RAIL TYPE", "RAILS", "STAMPED", "WHERE IT COMES FROM");

		long absorbedTotal = 0;
		long absorbedStamped = 0;
		long unknownTotal = 0;
		long unknownStamped = 0;

		for (final Map.Entry<String, long[]> entry : COUNTS.entrySet()) {
			final String railType = entry.getKey();
			final long total = entry.getValue()[0];
			final long stamped = entry.getValue()[1];
			final String origin;

			if (UPSTREAM.contains(railType)) {
				origin = "upstream MTR";
			} else if (ABSORBED.containsKey(railType)) {
				origin = "absorbed into 3.4.0 (" + ABSORBED.get(railType) + " km/h)";
				absorbedTotal += total;
				absorbedStamped += stamped;
			} else if (railType.matches("P-?\\d+")) {
				origin = "MTR-ANTE synthetic type";
				unknownTotal += total;
				unknownStamped += stamped;
			} else {
				origin = "UNKNOWN — would fall back to IRON, 80 km/h";
				unknownTotal += total;
				unknownStamped += stamped;
			}

			System.out.printf("%-24s %10d %10d  %s%n", railType, total, stamped, origin);
		}

		System.out.println();
		if (absorbedTotal == 0) {
			System.out.println("No rail uses a High Speed Rails type. Removing that addon cannot affect this world.");
		} else {
			System.out.printf("%d rails use a type absorbed into 3.4.0. The mod owns those names itself now, so%n"
					+ "removing the High Speed Rails addon does not change them.%n", absorbedTotal);
			System.out.printf("%d of them also carry their own speed on the rail. That is insurance for a future%n"
					+ "version that no longer knows the name; it is not needed to remove the addon.%n", absorbedStamped);
			if (absorbedStamped < absorbedTotal) {
				System.out.println("Stamping reaches the disk on the save after the world is first read by 3.4.1 or");
				System.out.println("newer. Run this again after a save to watch that number reach the one above it.");
			}
		}

		if (unknownTotal > 0) {
			System.out.printf("%n%d rails use a type this build does not define. They work while the mod that adds%n"
					+ "them is installed. %d of those carry their own speed and would survive without it.%n",
					unknownTotal, unknownStamped);
		}
	}
}
