package mtr.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mtr.data.EnumHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the MTR 4 flavour of {@code mtr_custom_resources.json} and restates it in the terms MTR 3 already
 * understands, so that a pack authored for MTR 4 can be loaded by this build without its author converting it.
 *
 * This is the inverse of MTR 4's own {@code CustomResourcesConverter}, which reads MTR 3 packs and builds MTR 4
 * objects from them. Wherever the two formats disagree about what corresponds to what, that upstream converter is
 * treated as the authority, because it was written by the people who changed the format.
 *
 * MTR 4 is the richer format, and a few of its ideas have no MTR 3 counterpart at all. Those are dropped and
 * named in {@link Result#notes} rather than approximated: a train that is quietly wrong is worse than one that is
 * visibly missing a feature, and the note is the only thing that tells a pack author which it is.
 *
 * Nothing here reads a file or draws anything, so the whole conversion can be driven from a check.
 */
public final class Mtr4CustomResources {

	/** The sections an MTR 4 pack may declare. Any one of them, as an array, identifies the format. */
	private static final String[] SECTIONS = {"vehicles", "signs", "rails", "objects", "lifts"};

	/**
	 * MTR 3 draws a display part's geometry as well as its text; MTR 4 draws only the text and leaves the panel
	 * to an ordinary part. No MTR 3 render stage is named this, so the geometry pass — which compares the stage
	 * against each {@code RenderStage} in turn — never matches it, while the text pass, which does not look at
	 * the stage at all, still draws. That is exactly MTR 4's behaviour, with no change to the renderer.
	 */
	private static final String STAGE_TEXT_ONLY = "TEXT_ONLY";
	/** Short enough not to reject real packs, long enough that an accidental overlap does not pass. */
	private static final int MINIMUM_FAMILY_PREFIX = 3;

	private Mtr4CustomResources() {
	}

	/** One MTR 3 model: a Blockbench file, the single texture it is drawn with, and the parts that use it. */
	public static final class Layer {

		public final String modelResource;
		public final String textureId;
		public final double modelYOffset;
		public final JsonObject properties;

		private Layer(String modelResource, String textureId, double modelYOffset, JsonObject properties) {
			this.modelResource = modelResource;
			this.textureId = textureId;
			this.modelYOffset = modelYOffset;
			this.properties = properties;
		}
	}

	/** Everything MTR 3 needs to register one train, gathered from one MTR 4 vehicle. */
	public static final class Train {

		public final String id;
		public final String baseTrainType;
		public final String name;
		public final String description;
		public final String wikipediaArticle;
		public final int color;
		public final String gangwayConnectionId;
		public final String trainBarrierId;
		public final DoorAnimationType doorAnimationType;
		public final float riderOffset;
		public final float bogiePosition;
		public final boolean useBveSound;
		public final String bveSoundBaseId;
		public final String speedSoundBaseId;
		public final String doorSoundBaseId;
		public final int speedSoundCount;
		public final float doorCloseSoundTime;
		public final boolean accelSoundAtCoast;
		public final boolean constPlaybackSpeed;
		public final List<Layer> layers;

		private Train(
				String id, String baseTrainType, String name, String description, String wikipediaArticle, int color,
				String gangwayConnectionId, String trainBarrierId, DoorAnimationType doorAnimationType,
				float riderOffset, float bogiePosition, boolean useBveSound, String bveSoundBaseId,
				String speedSoundBaseId, String doorSoundBaseId, int speedSoundCount, float doorCloseSoundTime,
				boolean accelSoundAtCoast, boolean constPlaybackSpeed, List<Layer> layers
		) {
			this.id = id;
			this.baseTrainType = baseTrainType;
			this.name = name;
			this.description = description;
			this.wikipediaArticle = wikipediaArticle;
			this.color = color;
			this.gangwayConnectionId = gangwayConnectionId;
			this.trainBarrierId = trainBarrierId;
			this.doorAnimationType = doorAnimationType;
			this.riderOffset = riderOffset;
			this.bogiePosition = bogiePosition;
			this.useBveSound = useBveSound;
			this.bveSoundBaseId = bveSoundBaseId;
			this.speedSoundBaseId = speedSoundBaseId;
			this.doorSoundBaseId = doorSoundBaseId;
			this.speedSoundCount = speedSoundCount;
			this.doorCloseSoundTime = doorCloseSoundTime;
			this.accelSoundAtCoast = accelSoundAtCoast;
			this.constPlaybackSpeed = constPlaybackSpeed;
			this.layers = layers;
		}
	}

	/**
	 * Where a vehicle sits in a train, taken from its couplings.
	 *
	 * A gangway is where one carriage joins the next, so an end without one is an end of the train. That makes
	 * the shape of a consist readable from the pack itself: closed-open is a front cab, open-open is a trailer,
	 * open-closed is a back cab, and closed-closed is a vehicle that is a whole train by itself. No part of this
	 * reads the vehicle's name, which is free text and in the pack author's own language.
	 */
	public static final class Shape {

		public final String trainId;
		public final String rawId;
		public final int length;
		public final int width;
		public final boolean open1;
		public final boolean open2;

		private Shape(String trainId, String rawId, int length, int width, boolean open1, boolean open2) {
			this.trainId = trainId;
			this.rawId = rawId;
			this.length = length;
			this.width = width;
			this.open1 = open1;
			this.open2 = open2;
		}

		boolean isFrontCab() { return !open1 && open2; }

		boolean isBackCab() { return open1 && !open2; }

		boolean isMiddle() { return open1 && open2; }
	}

	/** A whole train, made of vehicles the pack ships separately. */
	public static final class Assembly {

		public final String id;
		public final String name;
		public final String frontTrainId;
		public final String middleTrainId;
		public final String backTrainId;

		private Assembly(String id, String name, String frontTrainId, String middleTrainId, String backTrainId) {
			this.id = id;
			this.name = name;
			this.frontTrainId = frontTrainId;
			this.middleTrainId = middleTrainId;
			this.backTrainId = backTrainId;
		}
	}

	public static final class Result {

		public final List<Train> trains = new ArrayList<>();
		/** One per set of vehicles that form a train together; empty when a pack ships whole trains already. */
		public final List<Assembly> assemblies = new ArrayList<>();
		/** Beside the trains rather than inside them: only needed while working out what joins to what. */
		public final List<Shape> shapes = new ArrayList<>();
		/** Signs, already in MTR 3's shape, so that the existing sign loader reads them unchanged. */
		public final JsonObject customSigns = new JsonObject();
		/** What could not be carried across, in the pack author's terms. A set, because most of it repeats. */
		public final Set<String> notes = new LinkedHashSet<>();
	}

	/**
	 * MTR 3 keys its trains and signs by id inside an object; MTR 4 lists them in arrays under different names.
	 * A file that declares neither is not an MTR 4 pack and is left to the MTR 3 reader, which is what makes an
	 * empty or malformed file behave exactly as it does today.
	 */
	public static boolean isMtr4Format(JsonObject config) {
		if (config.has(ICustomResources.CUSTOM_TRAINS_KEY) || config.has(ICustomResources.CUSTOM_SIGNS_KEY)) {
			return false;
		}

		for (final String section : SECTIONS) {
			if (config.has(section) && config.get(section).isJsonArray()) {
				return true;
			}
		}

		return false;
	}

	/**
	 * @param resourceReader reads a resource named the MTR 4 way — namespaced, possibly without its extension —
	 *                       and parses it, returning null when the pack does not ship it
	 */
	public static Result convert(JsonObject config, Function<String, JsonObject> resourceReader) {
		final Result result = new Result();

		for (final JsonElement element : array(config, "vehicles")) {
			try {
				convertVehicle(element.getAsJsonObject(), resourceReader, result);
			} catch (Exception e) {
				result.notes.add("a vehicle entry could not be read (" + e + ") and was skipped");
			}
		}

		for (final JsonElement element : array(config, "signs")) {
			try {
				convertSign(element.getAsJsonObject(), result.customSigns);
			} catch (Exception e) {
				result.notes.add("a sign entry could not be read (" + e + ") and was skipped");
			}
		}

		assemble(result);

		noteUnsupportedSection(config, "rails", "custom rail models", result);
		noteUnsupportedSection(config, "objects", "eyecandy objects", result);
		noteUnsupportedSection(config, "lifts", "lift skins", result);

		return result;
	}

	/**
	 * Works out which vehicles are carriages of the same train, and records one whole train for each set.
	 *
	 * An MTR 4 pack ships a vehicle per carriage type and lets a driver couple them in the depot. MTR 3 has no
	 * such step: a train is one type from end to end. Left alone, that leaves a pack's front cab, trailer and
	 * back cab sitting in the train list as three separate trains, none of which is the train the pack is of.
	 *
	 * Vehicles are grouped by the size they are, then by the name they share. Sharing a size is not enough on its
	 * own -- two unrelated trains in one pack may well both be 20 by 2 -- so the group also has to agree on a
	 * common start to its ids, which is how pack authors name carriages of one train in practice. When they do
	 * not agree, no train is assembled and the log says so, because a front cab of one train coupled to the
	 * trailer of another is worse than leaving the carriages as they were.
	 */
	private static void assemble(Result result) {
		final Map<String, List<Shape>> bySize = new LinkedHashMap<>();
		for (final Shape shape : result.shapes) {
			bySize.computeIfAbsent(shape.length + "x" + shape.width, key -> new ArrayList<>()).add(shape);
		}

		for (final List<Shape> group : bySize.values()) {
			Shape front = null;
			Shape middle = null;
			Shape back = null;
			for (final Shape shape : group) {
				if (front == null && shape.isFrontCab()) {
					front = shape;
				} else if (back == null && shape.isBackCab()) {
					back = shape;
				} else if (middle == null && shape.isMiddle()) {
					middle = shape;
				}
			}

			// A pack whose vehicles are already whole trains has nothing to assemble, and neither has one that
			// ships only cabs or only trailers. Both are ordinary, so neither is worth a note.
			if (front == null || back == null || middle == null) {
				continue;
			}

			final String prefix = commonPrefix(group);
			if (prefix.length() < MINIMUM_FAMILY_PREFIX) {
				result.notes.add("vehicles of the same size do not share a common name, so it is not clear which "
						+ "are carriages of one train; they are listed separately rather than coupled by guesswork");
				continue;
			}

			result.assemblies.add(new Assembly(
					ICustomResources.CUSTOM_TRAIN_ID_PREFIX + prefix,
					trainName(result, front.trainId, prefix),
					front.trainId, middle.trainId, back.trainId
			));
		}
	}

	/** The start every id in the group shares, cut back to a separator so a name is never left half-written. */
	private static String commonPrefix(List<Shape> group) {
		String prefix = group.get(0).rawId;
		for (final Shape shape : group) {
			int i = 0;
			while (i < prefix.length() && i < shape.rawId.length() && prefix.charAt(i) == shape.rawId.charAt(i)) {
				i++;
			}
			prefix = prefix.substring(0, i);
		}
		final int lastSeparator = Math.max(prefix.lastIndexOf('_'), prefix.lastIndexOf('-'));
		return lastSeparator > 0 ? prefix.substring(0, lastSeparator) : prefix;
	}

	/**
	 * Names the assembled train after its front cab, with whatever the pack put in brackets to tell the
	 * carriages apart taken off -- "Seoul Metro 4000 Series (4th Batch, front cab)" is the front cab's name, not
	 * the train's. If that leaves nothing, the shared id stands in.
	 */
	private static String trainName(Result result, String frontTrainId, String prefix) {
		for (final Train train : result.trains) {
			if (train.id.equals(frontTrainId)) {
				final int bracket = train.name.lastIndexOf('(');
				final String trimmed = (bracket > 0 ? train.name.substring(0, bracket) : train.name).trim();
				return trimmed.isEmpty() ? prefix : trimmed;
			}
		}
		return prefix;
	}

	/**
	 * Blockbench 5 moved the outliner's group definitions into a {@code groups} array of their own and left the
	 * outliner holding the tree that references them. MTR 3 only ever reads {@code outliner}, so fold the two
	 * back together the way MTR 4 does, and shift the model if the pack asks for a Y offset other than the one
	 * MTR 3 builds in — MTR 4's converter stamps {@code modelYOffset} 1 onto every MTR 3 model it reads, which
	 * says what MTR 3's own baseline is.
	 */
	public static void normalizeBlockbenchModel(JsonObject model, double modelYOffset) {
		if (model.has("groups") && model.get("groups").isJsonArray()) {
			final JsonArray groups = model.getAsJsonArray("groups");

			for (final JsonElement element : array(model, "outliner")) {
				if (!element.isJsonObject()) {
					continue;
				}

				final JsonObject outline = element.getAsJsonObject();
				final JsonArray children = array(outline, "children");
				boolean merged = false;

				for (final JsonElement groupElement : groups) {
					final JsonObject group = groupElement.getAsJsonObject();
					if (!string(outline, "uuid").isEmpty() && string(outline, "uuid").equals(string(group, "uuid"))) {
						if (!group.has("children") || !group.get("children").isJsonArray()) {
							group.add("children", new JsonArray());
						}
						group.getAsJsonArray("children").addAll(children);
						merged = true;
					}
				}

				if (!merged) {
					groups.add(outline);
				}
			}

			model.add("outliner", groups);
		}

		final double shift = (modelYOffset - 1) * 16;
		if (shift != 0) {
			for (final JsonElement element : array(model, "elements")) {
				final JsonObject cube = element.getAsJsonObject();
				shiftY(cube, "from", shift);
				shiftY(cube, "to", shift);
				shiftY(cube, "origin", shift);
			}
		}
	}

	private static void shiftY(JsonObject cube, String key, double shift) {
		if (cube.has(key) && cube.get(key).isJsonArray()) {
			final JsonArray values = cube.getAsJsonArray(key);
			if (values.size() > 1) {
				values.set(1, new JsonPrimitive(values.get(1).getAsDouble() + shift));
			}
		}
	}

	private static void convertVehicle(JsonObject vehicle, Function<String, JsonObject> resourceReader, Result result) {
		final String rawId = string(vehicle, "id");
		if (rawId.isEmpty()) {
			result.notes.add("a vehicle has no id and was skipped");
			return;
		}

		final String id = rawId.startsWith(ICustomResources.CUSTOM_TRAIN_ID_PREFIX)
				? rawId : ICustomResources.CUSTOM_TRAIN_ID_PREFIX + rawId;

		// MTR 4 measures a vehicle from coupling to coupling; MTR 3's length is the body and its spacing adds the
		// gap back. MTR 4's own converter adds one going the other way, so take one off coming back.
		final int length = (int) Math.round(number(vehicle, "length", 0)) - 1;
		final int width = (int) Math.round(number(vehicle, "width", 0));
		if (length < 1 || width < 1) {
			result.notes.add(rawId + " has no usable length or width and was skipped");
			return;
		}

		final String transportMode = string(vehicle, "transportMode").isEmpty()
				? "TRAIN" : string(vehicle, "transportMode").toUpperCase(Locale.ENGLISH);
		final String baseTrainType = String.format("%s_%s_%s", transportMode, length, width).toLowerCase(Locale.ENGLISH);

		final List<Layer> layers = new ArrayList<>();
		final VehicleState state = new VehicleState();
		readModels(vehicle, resourceReader, rawId, layers, state, result.notes);

		if (layers.isEmpty()) {
			result.notes.add(rawId + " has no model this build can draw and was skipped");
			return;
		}

		// MTR 3 holds one door travel and one door animation for the whole train, so every layer is given the
		// widest travel any of their parts asked for.
		final int doorMax = (int) Math.round(state.doorMax);
		layers.forEach(layer -> layer.properties.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DOOR_MAX, doorMax));

		if (number(vehicle, "couplingPadding1", 0) != 0 || number(vehicle, "couplingPadding2", 0) != 0) {
			result.notes.add("coupling padding is ignored: MTR 3 spaces cars by the train type's length alone");
		}
		if (bool(vehicle, "hasGangway1", false) != bool(vehicle, "hasGangway2", false)) {
			result.notes.add("a gangway on one end only is ignored: MTR 3 draws gangways between every car or none");
		}
		if (vehicle.has("bogie1Models") || vehicle.has("bogie2Models")) {
			result.notes.add("bogie models are ignored: MTR 3 draws its own bogie under every car");
		}

		final String bveSoundBaseId = string(vehicle, "bveSoundBaseResource");
		final boolean hasGangway = bool(vehicle, "hasGangway1", false) || bool(vehicle, "hasGangway2", false);
		final boolean hasBarrier = bool(vehicle, "hasBarrier1", false) || bool(vehicle, "hasBarrier2", false);

		result.shapes.add(new Shape(id, rawId, length, width,
				bool(vehicle, "hasGangway1", false), bool(vehicle, "hasGangway2", false)));

		result.trains.add(new Train(
				id,
				baseTrainType,
				string(vehicle, "name").isEmpty() ? rawId : string(vehicle, "name"),
				string(vehicle, "description"),
				string(vehicle, "wikipediaArticle"),
				CustomResources.colorStringToInt(string(vehicle, "color")),
				hasGangway ? faceTextureBase(state.firstProperties, "gangwayInnerSideResource", "_side", "_connector", result.notes) : "",
				hasBarrier ? faceTextureBase(state.firstProperties, "barrierInnerSideResource", "_exterior", "_barrier", result.notes) : "",
				state.doorAnimationType,
				(float) number(vehicle, "legacyRiderOffset", 0),
				(float) ((Math.abs(number(vehicle, "bogie1Position", 0)) + Math.abs(number(vehicle, "bogie2Position", 0))) / 2),
				!bveSoundBaseId.isEmpty(),
				bveSoundBaseId,
				string(vehicle, "legacySpeedSoundBaseResource"),
				string(vehicle, "legacyDoorSoundBaseResource"),
				(int) Math.round(number(vehicle, "legacySpeedSoundCount", 0)),
				(float) number(vehicle, "legacyDoorCloseSoundTime", 0.5),
				bool(vehicle, "legacyUseAccelerationSoundsWhenCoasting", false),
				bool(vehicle, "legacyConstantPlaybackSpeed", false),
				layers
		));
	}

	/**
	 * MTR 4 lists a vehicle's models one entry per properties file, so the same Blockbench file appears several
	 * times over with a different selection of parts each time. MTR 3 draws a model with one texture, so the
	 * entries are gathered by the file and texture they share: each distinct pair becomes one MTR 3 model, and a
	 * vehicle that reaches for a second texture becomes a second model drawn over the first.
	 */
	private static void readModels(JsonObject vehicle, Function<String, JsonObject> resourceReader, String rawId, List<Layer> layers, VehicleState state, Set<String> notes) {
		final Map<String, JsonObject> partsByModelAndTexture = new LinkedHashMap<>();

		for (final JsonElement element : array(vehicle, "models")) {
			final JsonObject model = element.getAsJsonObject();
			final String rawModelResource = string(model, "modelResource").toLowerCase(Locale.ENGLISH);

			// MTR 4 also loads Wavefront and Metasequoia models. Judge by the name the pack wrote rather than by
			// the one with an extension put back on it, or an .obj becomes an .obj.bbmodel and is read as one.
			if (rawModelResource.contains(".") && !rawModelResource.endsWith(".bbmodel")) {
				notes.add("only Blockbench models are supported; " + rawModelResource + " was skipped");
				continue;
			}

			final String modelResource = formatResource(rawModelResource, "bbmodel");

			final JsonObject properties = resourceReader.apply(formatResource(string(model, "modelPropertiesResource"), "json"));
			if (properties == null) {
				notes.add(rawId + " names model properties the pack does not ship: " + string(model, "modelPropertiesResource"));
				continue;
			}

			// The gangway and barrier textures belong to a model in MTR 4 and to the whole train in MTR 3, so
			// the first model that has any is the one the train is given.
			if (state.firstProperties == null) {
				state.firstProperties = properties;
			}

			final JsonObject definitions = resourceReader.apply(formatResource(string(model, "positionDefinitionsResource"), "json"));
			final String textureId = textureId(string(model, "textureResource"));
			final String key = modelResource + "|" + textureId;

			JsonObject layerProperties = partsByModelAndTexture.get(key);
			if (layerProperties == null) {
				layerProperties = new JsonObject();
				layerProperties.add(IResourcePackCreatorProperties.KEY_PROPERTIES_PARTS, new JsonArray());
				partsByModelAndTexture.put(key, layerProperties);
				layers.add(new Layer(modelResource, textureId, number(properties, "modelYOffset", 1), layerProperties));
			}

			convertParts(properties, definitions, layerProperties.getAsJsonArray(IResourcePackCreatorProperties.KEY_PROPERTIES_PARTS), state, notes);
		}

		layers.removeIf(layer -> {
			final boolean empty = layer.properties.getAsJsonArray(IResourcePackCreatorProperties.KEY_PROPERTIES_PARTS).size() == 0;
			if (empty) {
				notes.add(rawId + " lists a model with no parts this build can draw: " + layer.modelResource);
			}
			return empty;
		});
	}

	private static void convertParts(JsonObject properties, JsonObject definitions, JsonArray parts, VehicleState state, Set<String> notes) {
		final Map<String, JsonObject> positionDefinitions = new LinkedHashMap<>();
		if (definitions != null) {
			for (final JsonElement element : array(definitions, "positionDefinitions")) {
				final JsonObject definition = element.getAsJsonObject();
				positionDefinitions.put(string(definition, "name"), definition);
			}
		}

		for (final JsonElement element : array(properties, "parts")) {
			convertPart(element.getAsJsonObject(), positionDefinitions, parts, state, notes);
		}
	}

	private static void convertPart(JsonObject part, Map<String, JsonObject> positionDefinitions, JsonArray parts, VehicleState state, Set<String> notes) {
		final String type = string(part, "type").toUpperCase(Locale.ENGLISH);
		switch (type) {
			case "FLOOR":
			case "DOORWAY":
			case "SEAT":
				// These carry no geometry in MTR 4 either -- they mark out where riders may stand and sit, which
				// MTR 3 takes from the train type's own size. Dropping them loses nothing that was ever drawn.
				notes.add("floor, doorway and seat markers are ignored: MTR 3 boards and seats riders by the train's size");
				return;
			default:
				break;
		}

		final String renderCondition = renderCondition(string(part, "condition").toUpperCase(Locale.ENGLISH), notes);
		if (renderCondition == null) {
			return;
		}

		final boolean isDisplay = "DISPLAY".equals(type);
		final JsonObject display;
		if (isDisplay) {
			display = display(part, notes);
			if (display == null) {
				return;
			}
		} else {
			display = null;
		}

		final double doorX = number(part, "doorXMultiplier", 0);
		final double doorZ = number(part, "doorZMultiplier", 0);
		final String doorAnimationType = string(part, "doorAnimationType").toUpperCase(Locale.ENGLISH);
		if (doorZ != 0) {
			state.record(Math.abs(doorZ), doorAnimationType, notes);
		} else if (doorX != 0 && doorAnimationType.startsWith("PLUG")) {
			notes.add("a door that only swings outwards is drawn shut: MTR 3 cannot move a part sideways without also sliding it");
		}

		final String stage = isDisplay ? STAGE_TEXT_ONLY : renderStage(string(part, "renderStage").toUpperCase(Locale.ENGLISH));

		for (final JsonElement nameElement : array(part, "names")) {
			final String name = nameElement.getAsString();
			addPart(parts, part, positionDefinitions, name, stage, renderCondition, display, doorX, doorZ, false);
			addPart(parts, part, positionDefinitions, name, stage, renderCondition, display, doorX, doorZ, true);
		}
	}

	/**
	 * MTR 4 gives a part two lists of positions, one for each side of the car, and rotates the second by half a
	 * turn. MTR 3 spells that as a second part with its mirror flag set — and since MTR 3 negates a mirrored
	 * part's across-car coordinate itself, the coordinate is negated here first so that the two cancel.
	 */
	private static void addPart(JsonArray parts, JsonObject sourcePart, Map<String, JsonObject> positionDefinitions, String name, String stage, String renderCondition, JsonObject display, double doorX, double doorZ, boolean mirror) {
		final JsonArray positions = new JsonArray();

		for (final JsonElement definitionElement : array(sourcePart, "positionDefinitions")) {
			final JsonObject definition = positionDefinitions.get(definitionElement.getAsString());
			if (definition == null) {
				continue;
			}

			for (final JsonElement positionElement : array(definition, mirror ? "positionsFlipped" : "positions")) {
				final JsonObject position = positionElement.getAsJsonObject();
				final JsonArray xz = new JsonArray();
				xz.add(mirror ? -number(position, "x", 0) : number(position, "x", 0));
				xz.add(number(position, "z", 0));
				positions.add(xz);
			}
		}

		if (positions.size() == 0) {
			return;
		}

		final JsonObject converted = new JsonObject();
		converted.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_NAME, name);
		converted.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_STAGE, stage);
		converted.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_MIRROR, mirror);
		converted.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_SKIP_RENDERING_IF_TOO_FAR, false);
		converted.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_RENDER_CONDITION, renderCondition);
		converted.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DOOR_OFFSET, doorOffset(doorX, doorZ, mirror));
		converted.add(IResourcePackCreatorProperties.KEY_PROPERTIES_POSITIONS, positions);
		converted.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_WHITELISTED_CARS, "");
		converted.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_BLACKLISTED_CARS, "");
		if (display != null) {
			converted.add(IResourcePackCreatorProperties.KEY_PROPERTIES_DISPLAY, display.deepCopy());
		}
		parts.add(converted);
	}

	/**
	 * MTR 4 states a door as how far and which way the part travels; MTR 3 names one of four combinations of
	 * side and direction. The two sides of a car open opposite ways, which is what lets the sign of the travel
	 * stand in for the side when the part does not also swing outwards.
	 */
	private static String doorOffset(double doorX, double doorZ, boolean mirror) {
		if (doorZ == 0) {
			return ResourcePackCreatorProperties.DoorOffset.NONE.toString();
		}

		final boolean left = doorX == 0 ? doorZ < 0 : (doorX > 0) != mirror;
		return (left ? "LEFT_" : "RIGHT_") + (doorZ > 0 ? "POSITIVE" : "NEGATIVE");
	}

	/** Null when the part is only ever drawn in a situation MTR 3 does not know about. */
	private static String renderCondition(String condition, Set<String> notes) {
		switch (condition) {
			case "DOORS_OPENED":
				return ResourcePackCreatorProperties.RenderCondition.DOORS_OPEN.toString();
			case "DOORS_CLOSED":
				return ResourcePackCreatorProperties.RenderCondition.DOORS_CLOSED.toString();
			case "ON_ROUTE_FORWARDS":
				return ResourcePackCreatorProperties.RenderCondition.MOVING_FORWARDS.toString();
			case "ON_ROUTE_BACKWARDS":
				return ResourcePackCreatorProperties.RenderCondition.MOVING_BACKWARDS.toString();
			case "AT_DEPOT":
				notes.add("parts drawn only at a depot are dropped: MTR 3 does not tell a stabled train from a running one");
				return null;
			case "CHRISTMAS_LIGHT_RED":
			case "CHRISTMAS_LIGHT_YELLOW":
			case "CHRISTMAS_LIGHT_GREEN":
			case "CHRISTMAS_LIGHT_BLUE":
				notes.add("Christmas lights are dropped: MTR 3 has no cycling light condition");
				return null;
			default:
				// MTR 4 falls back to NORMAL for a condition it does not recognise, and so does this.
				return ResourcePackCreatorProperties.RenderCondition.ALL.toString();
		}
	}

	private static String renderStage(String renderStage) {
		switch (renderStage) {
			case "LIGHT":
				return "LIGHTS";
			case "ALWAYS_ON_LIGHT":
				return "ALWAYS_ON_LIGHTS";
			case "INTERIOR":
			case "INTERIOR_TRANSLUCENT":
				return renderStage;
			default:
				return "EXTERIOR";
		}
	}

	/** Null when MTR 3 has nothing to show on this display, in which case the part is dropped whole. */
	private static JsonObject display(JsonObject part, Set<String> notes) {
		final String displayType;
		switch (string(part, "displayType").toUpperCase(Locale.ENGLISH)) {
			case "ROUTE_NUMBER":
				displayType = ResourcePackCreatorProperties.DisplayType.ROUTE_NUMBER.toString();
				break;
			case "NEXT_STATION":
				displayType = ResourcePackCreatorProperties.DisplayType.NEXT_STATION_PLAIN.toString();
				break;
			case "NEXT_STATION_KCR":
				displayType = ResourcePackCreatorProperties.DisplayType.NEXT_STATION_KCR.toString();
				break;
			case "NEXT_STATION_MTR":
				displayType = ResourcePackCreatorProperties.DisplayType.NEXT_STATION_MTR.toString();
				break;
			case "NEXT_STATION_UK":
				displayType = ResourcePackCreatorProperties.DisplayType.NEXT_STATION_UK.toString();
				break;
			case "DEPARTURE_INDEX":
			case "ROUTE_COLOR":
			case "ROUTE_COLOR_ROUNDED":
				notes.add("displays showing a departure number or a route colour are dropped: MTR 3 shows neither");
				return null;
			default:
				displayType = ResourcePackCreatorProperties.DisplayType.DESTINATION.toString();
				break;
		}

		boolean shouldScroll = false;
		boolean upperCase = false;
		boolean singleLine = false;

		for (final JsonElement element : array(part, "displayOptions")) {
			switch (element.getAsString().toUpperCase(Locale.ENGLISH)) {
				case "SCROLL_NORMAL":
				case "SCROLL_LIGHT_RAIL":
					shouldScroll = true;
					break;
				case "UPPER_CASE":
					upperCase = true;
					break;
				case "SINGLE_LINE":
					singleLine = true;
					break;
				default:
					notes.add("display option " + element.getAsString() + " is ignored: MTR 3 only scrolls, upper-cases and joins lines");
					break;
			}
		}

		final JsonObject display = new JsonObject();
		display.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DISPLAY_TYPE, displayType);
		display.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DISPLAY_X_PADDING, number(part, "displayXPadding", 0));
		display.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DISPLAY_Y_PADDING, number(part, "displayYPadding", 0));
		display.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DISPLAY_CJK_SIZE_RATIO, number(part, "displayCjkSizeRatio", 0));
		display.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DISPLAY_COLOR_CJK, string(part, "displayColorCjk"));
		display.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DISPLAY_COLOR, string(part, "displayColor"));
		display.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DISPLAY_SHOULD_SCROLL, shouldScroll);
		display.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DISPLAY_FORCE_UPPER_CASE, upperCase);
		display.addProperty(IResourcePackCreatorProperties.KEY_PROPERTIES_DISPLAY_FORCE_SINGLE_LINE, singleLine);
		return display;
	}

	private static void convertSign(JsonObject sign, JsonObject customSigns) {
		final String rawId = string(sign, "id");
		final String id = rawId.startsWith(ICustomResources.CUSTOM_SIGN_ID_PREFIX)
				? rawId.substring(ICustomResources.CUSTOM_SIGN_ID_PREFIX.length()) : rawId;

		final JsonObject converted = new JsonObject();
		converted.addProperty(ICustomResources.CUSTOM_SIGNS_TEXTURE_ID, formatResource(string(sign, "textureResource"), "png"));
		converted.addProperty(ICustomResources.CUSTOM_SIGNS_FLIP_TEXTURE, bool(sign, "flipTexture", false));
		converted.addProperty(ICustomResources.CUSTOM_SIGNS_CUSTOM_TEXT, string(sign, "customText"));
		converted.addProperty(ICustomResources.CUSTOM_SIGNS_FLIP_CUSTOM_TEXT, bool(sign, "flipCustomText", false));
		converted.addProperty(ICustomResources.CUSTOM_SIGNS_SMALL, bool(sign, "small", false));
		converted.addProperty(ICustomResources.CUSTOM_SIGNS_BACKGROUND_COLOR, string(sign, "backgroundColor"));
		customSigns.add(id, converted);
	}

	private static void noteUnsupportedSection(JsonObject config, String section, String description, Result result) {
		final int size = array(config, section).size();
		if (size > 0) {
			result.notes.add(size + " " + description + " were ignored: this build has nothing to load them into");
		}
	}

	/**
	 * MTR 3 builds a gangway's four faces and a barrier's one from a single id and a fixed naming convention.
	 * MTR 4 names every face outright, so the convention has to be read back out of one of them — and MTR 4's
	 * own packs leave out the infix MTR 3 inserts, which {@code JonModelTrainRenderer} falls back over.
	 */
	private static String faceTextureBase(JsonObject properties, String key, String faceSuffix, String infix, Set<String> notes) {
		final String resource = textureId(string(properties, key));
		if (resource.isEmpty()) {
			return "";
		}

		String base = resource;
		if (base.endsWith(faceSuffix)) {
			base = base.substring(0, base.length() - faceSuffix.length());
		}
		if (base.endsWith(infix)) {
			base = base.substring(0, base.length() - infix.length());
		}

		notes.add("gangway and barrier faces are named by convention in MTR 3, so only one texture set per train is kept, at MTR 3's fixed size and offset");
		return base;
	}

	/** MTR 3 stores a texture without its extension and appends one itself. */
	private static String textureId(String resource) {
		final String formatted = formatResource(resource, "png");
		return formatted.endsWith(".png") ? formatted.substring(0, formatted.length() - ".png".length()) : formatted;
	}

	/**
	 * MTR 4 lets a resource go unnamed by extension and is relaxed about case; a Minecraft identifier is neither,
	 * so apply the same normalisation MTR 4 does before the name reaches a {@code ResourceLocation}.
	 */
	static String formatResource(String resource, String extension) {
		if (resource.isEmpty()) {
			return "";
		}

		final String lowerCase = resource.toLowerCase(Locale.ENGLISH);
		return lowerCase.endsWith("." + extension) ? lowerCase : lowerCase + "." + extension;
	}

	/**
	 * What MTR 3 holds once per train but MTR 4 states per part or per model: the door travel, the door
	 * animation, and the gangway and barrier textures, which are read off whichever model comes first.
	 */
	private static final class VehicleState {

		private JsonObject firstProperties;
		private double doorMax;
		private DoorAnimationType doorAnimationType = DoorAnimationType.STANDARD;
		private boolean seen;

		private void record(double travel, String animationType, Set<String> notes) {
			doorMax = Math.max(doorMax, travel);
			final DoorAnimationType converted = EnumHelper.valueOf(DoorAnimationType.STANDARD, animationType);

			if (seen && converted != doorAnimationType) {
				notes.add("doors that open in more than one way are all given the first: MTR 3 holds one door animation per train");
			} else {
				doorAnimationType = converted;
				seen = true;
			}
		}
	}

	private static JsonArray array(JsonObject jsonObject, String key) {
		return jsonObject.has(key) && jsonObject.get(key).isJsonArray() ? jsonObject.getAsJsonArray(key) : new JsonArray();
	}

	private static String string(JsonObject jsonObject, String key) {
		try {
			return jsonObject.get(key).getAsString();
		} catch (Exception ignored) {
			return "";
		}
	}

	private static double number(JsonObject jsonObject, String key, double defaultValue) {
		try {
			return jsonObject.get(key).getAsDouble();
		} catch (Exception ignored) {
			return defaultValue;
		}
	}

	private static boolean bool(JsonObject jsonObject, String key, boolean defaultValue) {
		try {
			return jsonObject.get(key).getAsBoolean();
		} catch (Exception ignored) {
			return defaultValue;
		}
	}
}
