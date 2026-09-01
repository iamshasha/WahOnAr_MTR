package mtr.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Settings that belong to whoever runs the server rather than to whoever is looking at it.
 *
 * {@link mtr.client.Config} is the client's, and reaches for Minecraft's own instance, so it cannot be used here.
 * This is deliberately a plain file with no packet and no screen: it decides how much work the server does, and
 * that is not a decision a connecting client should be able to make.
 *
 * Written back out on first read, so the file documents itself by existing.
 */
public final class ServerConfig {

	private static final Path CONFIG_FILE_PATH = Paths.get("config", "mtr-server.json");

	private static final String PRELOAD_SECONDS = "vehicle_chunk_preload_seconds";
	private static final String PRELOAD_MAX_BLOCKS = "vehicle_chunk_preload_max_blocks";
	private static final String DECIDE_STABLING_AT_DISPATCH = "decide_stabling_at_dispatch";

	/**
	 * How far ahead of a vehicle carrying passengers to keep the ground loaded, in seconds of running at its
	 * current speed. Zero turns it off.
	 *
	 * Seconds rather than a distance because the distance that matters is the one the vehicle is about to cover: a
	 * fixed ninety-six blocks is a comfortable margin at walking pace and about a second and a half at two hundred
	 * and forty kilometres an hour, which is where it is actually needed.
	 */
	private static double preloadSeconds = 6;
	/** Ceiling on that, so one very fast vehicle cannot ask for an unbounded stretch of map at once. */
	private static int preloadMaxBlocks = 512;
	/**
	 * Whether a vehicle settles once, when it is given its departure, whether it stables at the end of that lap.
	 *
	 * Off is the older behaviour: the boards guess the answer while the vehicle waits in its siding, and the real
	 * decision taken as it leaves the origin replaces the guess. The two are not derived from the same thing -- the
	 * guess reads the timetable as it stands, the decision uses the departure it claims at that moment, and other
	 * vehicles claim departures in between -- so for some runs they disagree, and the destination on the boards
	 * changes as the vehicle pulls away.
	 *
	 * On, there is no guess. The answer is taken once for each lap, at the moment the departure for that lap is
	 * claimed, and everything downstream reads it. One answer cannot disagree with itself.
	 *
	 * Defaulted off because it changes when dispatch decides things, and dispatch is worth changing carefully.
	 * Turn it on, watch a few departures, and turn it off again if anything looks wrong -- nothing is written to
	 * the world either way.
	 */
	private static boolean decideStablingAtDispatch = false;

	private static boolean loaded;

	private ServerConfig() {
	}

	/**
	 * Reads the file, and writes it back if it was not there.
	 *
	 * Called as the server starts so that the file exists to be found. Everything here is read lazily otherwise,
	 * and the first read happens when a vehicle carrying passengers first moves -- so on a server where that had
	 * not happened yet, the settings existed but the file did not, and the only way to learn what could be
	 * configured was to already know.
	 */
	public static void init() {
		load();
	}

	public static double preloadSeconds() {
		load();
		return preloadSeconds;
	}

	public static int preloadMaxBlocks() {
		load();
		return preloadMaxBlocks;
	}

	public static boolean decideStablingAtDispatch() {
		load();
		return decideStablingAtDispatch;
	}

	private static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			if (Files.exists(CONFIG_FILE_PATH)) {
				final JsonObject json = new JsonParser().parse(String.join("", Files.readAllLines(CONFIG_FILE_PATH, StandardCharsets.UTF_8))).getAsJsonObject();
				preloadSeconds = readDouble(json, PRELOAD_SECONDS, preloadSeconds);
				preloadMaxBlocks = (int) readDouble(json, PRELOAD_MAX_BLOCKS, preloadMaxBlocks);
				decideStablingAtDispatch = readBoolean(json, DECIDE_STABLING_AT_DISPATCH, decideStablingAtDispatch);
			}
		} catch (Exception e) {
			// A malformed file falls back to the defaults rather than stopping the server from starting
			System.out.println("Could not read " + CONFIG_FILE_PATH + ", using defaults: " + e.getMessage());
		}
		// Clamp before writing back, so the file never records a value this would refuse to use
		preloadSeconds = Math.max(0, Math.min(60, preloadSeconds));
		preloadMaxBlocks = Math.max(0, Math.min(4096, preloadMaxBlocks));
		write();
	}

	private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
		try {
			return json.get(key).getAsBoolean();
		} catch (Exception e) {
			return fallback;
		}
	}

	private static double readDouble(JsonObject json, String key, double fallback) {
		try {
			return json.get(key).getAsDouble();
		} catch (Exception e) {
			return fallback;
		}
	}

	private static void write() {
		try {
			final JsonObject json = new JsonObject();
			json.addProperty(PRELOAD_SECONDS, preloadSeconds);
			json.addProperty(PRELOAD_MAX_BLOCKS, preloadMaxBlocks);
			json.addProperty(DECIDE_STABLING_AT_DISPATCH, decideStablingAtDispatch);
			Files.createDirectories(CONFIG_FILE_PATH.getParent());
			Files.write(CONFIG_FILE_PATH, RailwayData.prettyPrint(json).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
