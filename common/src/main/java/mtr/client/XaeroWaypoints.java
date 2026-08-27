package mtr.client;

import mtr.data.Platform;
import mtr.data.Station;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts every station on Xaero's minimap in one go.
 *
 * Entirely by reflection, and deliberately so. Xaero ships no API package, and its one supported integration —
 * a chat line beginning {@code xaero_waypoint_add:} — opens a confirmation screen for each waypoint, which is fine
 * for one and unusable for a network's worth. Writing its waypoint files by hand would mean reproducing how it
 * names a server's folder and then fighting whatever it has cached in memory; going through the live session
 * instead means the waypoints land in whatever world and set the player actually has open.
 *
 * The cost is that this is pinned to Xaero's internals rather than to a promise. Every step is checked, and a
 * single failure anywhere reports that it could not be done rather than half-filling someone's minimap.
 */
public final class XaeroWaypoints {

	private static final String MODULES_CLASS = "xaero.hud.minimap.BuiltInHudModules";
	private static final String WAYPOINT_CLASS = "xaero.common.minimap.waypoints.Waypoint";
	private static final String WAYPOINT_SET_CLASS = "xaero.common.minimap.waypoints.WaypointSet";

	/** Xaero's own palette, indexed. Station colours are free-form, so the nearest of these has to do. */
	private static final int[] WAYPOINT_COLORS = {
			0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
	};

	private XaeroWaypoints() {
	}

	public static boolean isAvailable() {
		try {
			Class.forName(MODULES_CLASS);
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	/**
	 * Adds a waypoint for every station, skipping any that is already there by name.
	 *
	 * @return how many were added, or -1 if Xaero could not be reached at all
	 */
	@SuppressWarnings("unchecked")
	public static int addAllStations() {
		try {
			final Class<?> waypointClass = Class.forName(WAYPOINT_CLASS);
			final Constructor<?> waypointConstructor = waypointClass.getConstructor(
					int.class, int.class, int.class, String.class, String.class, int.class);

			final Field minimapField = Class.forName(MODULES_CLASS).getField("MINIMAP");
			final Object minimapModule = minimapField.get(null);
			final Object session = callNoArg(minimapModule, "getCurrentSession");
			final Object worldManager = callNoArg(session, "getWorldManager");
			final Object world = callNoArg(worldManager, "getCurrentWorld");
			final Object waypointSet = callNoArg(world, "getCurrentWaypointSet");
			if (waypointSet == null || !Class.forName(WAYPOINT_SET_CLASS).isInstance(waypointSet)) {
				return -1;
			}

			final List<Object> waypoints = (List<Object>) callNoArg(waypointSet, "getList");
			if (waypoints == null) {
				return -1;
			}

			final Method getName = waypointClass.getMethod("getName");
			final List<String> existing = new ArrayList<>();
			for (final Object waypoint : waypoints) {
				existing.add(String.valueOf(getName.invoke(waypoint)));
			}

			int added = 0;
			for (final Station station : ClientData.STATIONS) {
				if (station.corner1 == null || station.corner2 == null) {
					continue;
				}
				final String name = station.name.replace('|', ' ');
				if (existing.contains(name)) {
					continue;
				}
				final int x = (station.corner1.getA() + station.corner2.getA()) / 2;
				final int z = (station.corner1.getB() + station.corner2.getB()) / 2;
				final int y = platformLevel(station);
				waypoints.add(waypointConstructor.newInstance(x, y, z, name, initials(name), nearestColor(station.color)));
				added++;
			}
			return added;
		} catch (Exception e) {
			return -1;
		}
	}

	/**
	 * The height to put a station's waypoint at: the average of its own platforms.
	 *
	 * A station is a rectangle on the map with no height of its own, so there is nothing to read directly. Its
	 * platforms are the thing a player is actually heading for, and averaging them puts the waypoint at track level
	 * whether the station is cut-and-cover, elevated, or on the surface. A station with no platforms built yet has
	 * nothing to average, and falls back to where the player is standing.
	 */
	private static int platformLevel(Station station) {
		long total = 0;
		int count = 0;
		for (final Platform platform : ClientData.PLATFORMS) {
			final BlockPos pos = platform.getMidPos();
			if (station.inArea(pos.getX(), pos.getZ())) {
				total += pos.getY();
				count++;
			}
		}
		if (count > 0) {
			return (int) (total / count);
		}
		return Minecraft.getInstance().player == null ? 64 : (int) Minecraft.getInstance().player.getY();
	}

	private static Object callNoArg(Object target, String methodName) throws Exception {
		if (target == null) {
			return null;
		}
		final Method method = target.getClass().getMethod(methodName);
		method.setAccessible(true);
		return method.invoke(target);
	}

	/** Xaero draws these on the map itself, so they have to stay short enough to read at map scale. */
	private static String initials(String name) {
		final StringBuilder builder = new StringBuilder();
		for (final String word : name.split("[ _-]+")) {
			if (!word.isEmpty() && builder.length() < 2) {
				builder.append(word.charAt(0));
			}
		}
		return builder.length() == 0 ? "S" : builder.toString();
	}

	private static int nearestColor(int color) {
		int best = 0;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = 0; i < WAYPOINT_COLORS.length; i++) {
			final int dr = ((color >> 16) & 0xFF) - ((WAYPOINT_COLORS[i] >> 16) & 0xFF);
			final int dg = ((color >> 8) & 0xFF) - ((WAYPOINT_COLORS[i] >> 8) & 0xFF);
			final int db = (color & 0xFF) - (WAYPOINT_COLORS[i] & 0xFF);
			final int distance = dr * dr + dg * dg + db * db;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}
}
