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

	private static boolean loaded;

	private ServerConfig() {
	}

	public static double preloadSeconds() {
		load();
		return preloadSeconds;
	}

	public static int preloadMaxBlocks() {
		load();
		return preloadMaxBlocks;
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
			Files.createDirectories(CONFIG_FILE_PATH.getParent());
			Files.write(CONFIG_FILE_PATH, RailwayData.prettyPrint(json).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
