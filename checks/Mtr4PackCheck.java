import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.client.Mtr4CustomResources;

import java.util.HashMap;
import java.util.Map;

/**
 * Drives a pack written for MTR 4 through the converter and asserts that what comes out is what MTR 3 would
 * have been given had the pack been written for MTR 3 in the first place.
 *
 * The cases that matter most are the ones that must NOT happen. An MTR 3 pack must still be read as an MTR 3
 * pack -- the two formats share a file name, so the only thing standing between an existing pack and the wrong
 * reader is the shape test. A pack that is malformed, or that asks for something this build cannot draw, must
 * be dropped and said so, never quietly turned into a train that is subtly wrong: a missing part is something a
 * pack author can see and report, and a part in the wrong place at the wrong moment is not.
 *
 * The fixture below is cut down from a real MTR 4 pack (Seoul Metro 4000, 4th batch), keeping the shapes that
 * differ from MTR 3: several models per vehicle across more than one texture, positions held in a file of their
 * own, doors stated as a travel rather than as a named side, and text displays that draw no geometry.
 */
public class Mtr4PackCheck {

	public static void main(String[] args) {
		formatIsRecognisedByShapeAlone();
		aVehicleBecomesATrain();
		modelsAreGatheredByTexture();
		doorsKeepTheirSideAndTravel();
		whatCannotBeDrawnIsDroppedAndSaid();
		malformedEntriesDoNotTakeTheRestWithThem();
		blockbench5ModelsAreFlattened();

		System.out.println("Mtr4Pack ok");
	}

	/** An MTR 3 pack must go to the MTR 3 reader, whatever else is in the file. */
	private static void formatIsRecognisedByShapeAlone() {
		assertFalse(Mtr4CustomResources.isMtr4Format(parse("{\"custom_trains\":{\"a\":{}}}")),
				"an MTR 3 pack was taken for an MTR 4 pack");
		assertFalse(Mtr4CustomResources.isMtr4Format(parse("{\"custom_signs\":{\"a\":{}}}")),
				"an MTR 3 sign pack was taken for an MTR 4 pack");
		// A pack that somehow carries both is MTR 3's: its reader ignores what it does not know, so an MTR 3
		// train still loads, where handing the file to the MTR 4 reader would lose it.
		assertFalse(Mtr4CustomResources.isMtr4Format(parse("{\"custom_trains\":{\"a\":{}},\"vehicles\":[]}")),
				"a file carrying both formats was taken away from the MTR 3 reader");
		assertFalse(Mtr4CustomResources.isMtr4Format(parse("{}")), "an empty file was taken for an MTR 4 pack");
		assertFalse(Mtr4CustomResources.isMtr4Format(parse("{\"vehicles\":{}}")),
				"a file whose vehicles are not a list was taken for an MTR 4 pack");
		assertTrue(Mtr4CustomResources.isMtr4Format(parse(PACK)), "a real MTR 4 pack was not recognised");
		assertTrue(Mtr4CustomResources.isMtr4Format(parse("{\"signs\":[]}")),
				"an MTR 4 pack with only signs was not recognised");
	}

	private static void aVehicleBecomesATrain() {
		final Mtr4CustomResources.Result result = convert();
		assertEquals(1, result.trains.size(), "vehicle count");

		final Mtr4CustomResources.Train train = result.trains.get(0);
		assertEquals("mtr_custom_train_seoul_4000_trailer", train.id, "train id");
		// MTR 4 measures coupling to coupling and MTR 3 does not, so 20 there is 19 here
		assertEquals("train_19_2", train.baseTrainType, "base train type");
		assertEquals(0x2EA2C9, train.color, "colour");
		assertEquals(6f, train.bogiePosition, "bogie position");
		assertEquals(123, train.speedSoundCount, "speed sound count");
		assertFalse(train.useBveSound, "a train with no BVE sound base asked for BVE sounds");
		assertEquals("sp1900", train.speedSoundBaseId, "speed sound base");
		// The gangway faces are named one by one in MTR 4; MTR 3 wants the stem they share
		assertEquals("mtr:4000/d4000", train.gangwayConnectionId, "gangway connection id");
		assertEquals("", train.trainBarrierId, "a train with no barrier was given one");

		final JsonObject sign = result.customSigns.getAsJsonObject("my_sign");
		assertTrue(sign != null, "the sign was not converted");
		assertEquals("mtr:signs/my_sign.png", sign.get("texture_id").getAsString(), "sign texture");
		assertEquals(true, sign.get("small").getAsBoolean(), "sign smallness");
	}

	/**
	 * MTR 4 lists one model entry per properties file, so the same Blockbench file appears more than once. MTR 3
	 * draws a model with one texture, so the entries have to collapse onto the file and texture they share --
	 * and a vehicle that reaches for a second texture has to keep it rather than lose everything after the first.
	 */
	private static void modelsAreGatheredByTexture() {
		final Mtr4CustomResources.Train train = convert().trains.get(0);
		assertEquals(2, train.layers.size(), "model layer count");
		assertEquals("mtr:4000/4000_4gen.bbmodel", train.layers.get(0).modelResource, "first model");
		assertEquals("mtr:4000/4000_4thgen", train.layers.get(0).textureId, "first texture");
		assertEquals("mtr:4000/labels.bbmodel", train.layers.get(1).modelResource, "second model");
		assertEquals("mtr:4000/labels", train.layers.get(1).textureId, "second texture");

		// The body's two properties files both land in the first layer: the window on each side of the car, the
		// two doors, and the end light from the second file. The floor marker carries no geometry and is gone.
		final JsonArray bodyParts = train.layers.get(0).properties.getAsJsonArray("parts");
		assertEquals(5, bodyParts.size(), "parts gathered onto the body model");
		assertEquals("window", bodyParts.get(0).getAsJsonObject().get("name").getAsString(), "first part name");
		assertEquals("LIGHTS", bodyParts.get(4).getAsJsonObject().get("stage").getAsString(),
				"MTR 4's LIGHT stage did not become MTR 3's LIGHTS");

		// The window is listed once but its positions come in two lists, one per side of the car. MTR 3 spells
		// the second as a mirrored copy, and negates a mirrored part's across-car coordinate itself.
		final JsonObject mirrored = bodyParts.get(1).getAsJsonObject();
		assertEquals("window", mirrored.get("name").getAsString(), "mirrored part name");
		assertEquals(true, mirrored.get("mirror").getAsBoolean(), "the flipped positions were not mirrored");
		assertEquals(-3d, mirrored.getAsJsonArray("positions").get(0).getAsJsonArray().get(0).getAsDouble(),
				"a mirrored position was not negated, so it would be drawn on the wrong side");

		// A display draws only its text in MTR 4. No MTR 3 render stage is named this, so the geometry pass
		// skips it and the text pass, which does not look at the stage, still draws it.
		final JsonObject display = train.layers.get(1).properties.getAsJsonArray("parts").get(0).getAsJsonObject();
		assertEquals("TEXT_ONLY", display.get("stage").getAsString(), "a display part would draw its own panel");
		assertEquals("NEXT_STATION_PLAIN", display.getAsJsonObject("display").get("type").getAsString(),
				"MTR 4's NEXT_STATION did not become MTR 3's NEXT_STATION_PLAIN");
		assertEquals(true, display.getAsJsonObject("display").get("should_scroll").getAsBoolean(),
				"a scrolling display stopped scrolling");
	}

	/**
	 * MTR 4 states a door as how far and which way the part travels; MTR 3 names one of four combinations of
	 * side and direction, and holds one travel and one animation for the whole train. The two sides of a car
	 * open opposite ways, which is what lets the sign of the travel stand in for the side.
	 */
	private static void doorsKeepTheirSideAndTravel() {
		final Mtr4CustomResources.Train train = convert().trains.get(0);
		final JsonArray parts = train.layers.get(0).properties.getAsJsonArray("parts");

		assertEquals("LEFT_NEGATIVE", parts.get(2).getAsJsonObject().get("door_offset").getAsString(),
				"a door travelling towards -z was not read as the left door");
		assertEquals("RIGHT_POSITIVE", parts.get(3).getAsJsonObject().get("door_offset").getAsString(),
				"a door travelling towards +z was not read as the right door");
		assertEquals("NONE", parts.get(0).getAsJsonObject().get("door_offset").getAsString(),
				"a part that does not move was given a door offset");

		// Both layers animate together, so both carry the same travel -- rounded, because MTR 3 counts in whole
		// Blockbench units
		assertEquals(11, train.layers.get(0).properties.get("door_max").getAsInt(), "door travel");
		assertEquals(11, train.layers.get(1).properties.get("door_max").getAsInt(), "door travel on the second layer");
		assertEquals("CONSTANT", train.doorAnimationType.toString(), "door animation");
	}

	private static void whatCannotBeDrawnIsDroppedAndSaid() {
		final Mtr4CustomResources.Result result = convert();

		for (final Mtr4CustomResources.Layer layer : result.trains.get(0).layers) {
			final JsonArray parts = layer.properties.getAsJsonArray("parts");
			for (int i = 0; i < parts.size(); i++) {
				final String name = parts.get(i).getAsJsonObject().get("name").getAsString();
				assertFalse("end_floor".equals(name), "a floor marker was drawn as geometry");
				assertFalse("depot_light".equals(name), "a part only ever lit at a depot was drawn all the time");
				assertFalse("display_num".equals(name), "a departure number display was kept with nothing to show");
			}
		}

		assertNoted(result, "floor, doorway and seat markers are ignored");
		assertNoted(result, "parts drawn only at a depot are dropped");
		assertNoted(result, "displays showing a departure number or a route colour are dropped");
		assertNoted(result, "display option SPACE_CJK is ignored");
		assertNoted(result, "1 custom rail models were ignored");
		assertNoted(result, "2 eyecandy objects were ignored");
		assertNoted(result, "bogie models are ignored");
	}

	/** One bad entry must cost that entry and nothing else, and must never pass for a working train. */
	private static void malformedEntriesDoNotTakeTheRestWithThem() {
		final Mtr4CustomResources.Result result = Mtr4CustomResources.convert(parse(BROKEN_PACK), Mtr4PackCheck::readResource);

		assertEquals(1, result.trains.size(), "only the one sound vehicle should have survived");
		assertEquals("mtr_custom_train_good", result.trains.get(0).id, "the surviving vehicle");
		assertNoted(result, "a vehicle has no id and was skipped");
		assertNoted(result, "has no usable length or width and was skipped");
		assertNoted(result, "names model properties the pack does not ship");
		assertNoted(result, "has no model this build can draw and was skipped");
		assertNoted(result, "only Blockbench models are supported");
	}

	/**
	 * Blockbench 5 moved the outliner's group definitions into a list of their own. MTR 3 reads the outliner and
	 * nothing else, so the two have to be folded back together or the model loads as nothing at all.
	 */
	private static void blockbench5ModelsAreFlattened() {
		final JsonObject model = parse("{\"resolution\":{\"width\":16,\"height\":16},"
				+ "\"elements\":[{\"uuid\":\"e1\",\"from\":[0,4,0],\"to\":[1,5,1],\"origin\":[0,4,0]}],"
				+ "\"groups\":[{\"name\":\"body\",\"uuid\":\"g1\",\"children\":[]}],"
				+ "\"outliner\":[{\"name\":\"body\",\"uuid\":\"g1\",\"children\":[\"e1\"]}]}");
		Mtr4CustomResources.normalizeBlockbenchModel(model, 1);

		final JsonArray outliner = model.getAsJsonArray("outliner");
		assertEquals(1, outliner.size(), "the group and its outliner entry were not folded together");
		assertEquals("e1", outliner.get(0).getAsJsonObject().getAsJsonArray("children").get(0).getAsString(),
				"the group lost the elements the outliner held for it");

		// MTR 3's own baseline is a Y offset of one block, which is what MTR 4 stamps onto every MTR 3 model it
		// converts. A pack asking for anything else has to have the difference built into the geometry.
		final JsonObject shifted = parse("{\"elements\":[{\"from\":[0,4,0],\"to\":[1,5,1],\"origin\":[0,4,0]}]}");
		Mtr4CustomResources.normalizeBlockbenchModel(shifted, 2);
		assertEquals(20d, shifted.getAsJsonArray("elements").get(0).getAsJsonObject()
				.getAsJsonArray("from").get(1).getAsDouble(), "the model was not shifted to MTR 3's baseline");

		final JsonObject unshifted = parse("{\"elements\":[{\"from\":[0,4,0],\"to\":[1,5,1],\"origin\":[0,4,0]}]}");
		Mtr4CustomResources.normalizeBlockbenchModel(unshifted, 1);
		assertEquals(4d, unshifted.getAsJsonArray("elements").get(0).getAsJsonObject()
				.getAsJsonArray("from").get(1).getAsDouble(), "a model already on MTR 3's baseline was moved");
	}

	private static Mtr4CustomResources.Result convert() {
		return Mtr4CustomResources.convert(parse(PACK), Mtr4PackCheck::readResource);
	}

	private static JsonObject readResource(String path) {
		final String contents = RESOURCES.get(path);
		return contents == null ? null : parse(contents);
	}

	private static JsonObject parse(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	private static void assertNoted(Mtr4CustomResources.Result result, String fragment) {
		for (final String note : result.notes) {
			if (note.contains(fragment)) {
				return;
			}
		}
		throw new AssertionError("nothing was said about \"" + fragment + "\"; the notes were " + result.notes);
	}

	private static void assertEquals(Object expected, Object actual, String what) {
		if (!String.valueOf(expected).equals(String.valueOf(actual))) {
			throw new AssertionError(what + " was " + actual + ", expected " + expected);
		}
	}

	private static void assertTrue(boolean value, String what) {
		if (!value) {
			throw new AssertionError(what);
		}
	}

	private static void assertFalse(boolean value, String what) {
		if (value) {
			throw new AssertionError(what);
		}
	}

	private static final Map<String, String> RESOURCES = new HashMap<>();

	private static final String PACK = "{"
			+ "\"vehicles\":[{"
			+ "  \"id\":\"seoul_4000_trailer\",\"name\":\"Seoul Metro 4000\",\"color\":\"2EA2C9\","
			+ "  \"transportMode\":\"TRAIN\",\"length\":20,\"width\":2,"
			+ "  \"bogie1Position\":-6,\"bogie2Position\":6,\"couplingPadding1\":0,\"couplingPadding2\":0,"
			+ "  \"description\":\"\",\"wikipediaArticle\":\"\","
			+ "  \"hasGangway1\":true,\"hasGangway2\":true,\"hasBarrier1\":false,\"hasBarrier2\":false,"
			+ "  \"legacyRiderOffset\":0,\"bveSoundBaseResource\":\"\","
			+ "  \"legacySpeedSoundBaseResource\":\"sp1900\",\"legacySpeedSoundCount\":123,"
			+ "  \"legacyUseAccelerationSoundsWhenCoasting\":false,\"legacyConstantPlaybackSpeed\":false,"
			+ "  \"legacyDoorSoundBaseResource\":\"sp1900\",\"legacyDoorCloseSoundTime\":0.5,"
			+ "  \"models\":["
			+ "    {\"modelResource\":\"mtr:4000/4000_4gen.bbmodel\",\"textureResource\":\"mtr:4000/4000_4thgen.png\","
			+ "     \"modelPropertiesResource\":\"mtr:properties/common.json\","
			+ "     \"positionDefinitionsResource\":\"mtr:definitions/common.json\",\"flipTextureV\":true},"
			+ "    {\"modelResource\":\"mtr:4000/labels.bbmodel\",\"textureResource\":\"mtr:4000/labels.png\","
			+ "     \"modelPropertiesResource\":\"mtr:properties/labels.json\","
			+ "     \"positionDefinitionsResource\":\"mtr:definitions/common.json\",\"flipTextureV\":true},"
			+ "    {\"modelResource\":\"mtr:4000/4000_4gen.bbmodel\",\"textureResource\":\"mtr:4000/4000_4thgen.png\","
			+ "     \"modelPropertiesResource\":\"mtr:properties/end.json\","
			+ "     \"positionDefinitionsResource\":\"mtr:definitions/common.json\",\"flipTextureV\":true}"
			+ "  ],"
			+ "  \"bogie1Models\":[{\"modelResource\":\"mtr:models/vehicle/bogie_1.bbmodel\"}]"
			+ "}],"
			+ "\"signs\":[{\"id\":\"my_sign\",\"textureResource\":\"mtr:signs/my_sign\",\"flipTexture\":false,"
			+ "  \"customText\":\"\",\"flipCustomText\":false,\"small\":true,\"backgroundColor\":\"000000\"}],"
			+ "\"rails\":[{\"id\":\"a\"}],"
			+ "\"objects\":[{\"id\":\"a\"},{\"id\":\"b\"}],"
			+ "\"lifts\":[]"
			+ "}";

	private static final String BROKEN_PACK = "{\"vehicles\":["
			+ "{\"name\":\"no id at all\",\"length\":20,\"width\":2},"
			+ "{\"id\":\"no_size\",\"length\":0,\"width\":0},"
			+ "{\"id\":\"missing_properties\",\"length\":20,\"width\":2,\"models\":["
			+ "  {\"modelResource\":\"mtr:4000/4000_4gen.bbmodel\",\"textureResource\":\"mtr:a.png\","
			+ "   \"modelPropertiesResource\":\"mtr:properties/gone.json\","
			+ "   \"positionDefinitionsResource\":\"mtr:definitions/common.json\"}]},"
			+ "{\"id\":\"wrong_model_format\",\"length\":20,\"width\":2,\"models\":["
			+ "  {\"modelResource\":\"mtr:4000/body.obj\",\"textureResource\":\"mtr:a.png\","
			+ "   \"modelPropertiesResource\":\"mtr:properties/common.json\","
			+ "   \"positionDefinitionsResource\":\"mtr:definitions/common.json\"}]},"
			+ "{\"id\":\"good\",\"length\":20,\"width\":2,\"models\":["
			+ "  {\"modelResource\":\"mtr:4000/4000_4gen.bbmodel\",\"textureResource\":\"mtr:a.png\","
			+ "   \"modelPropertiesResource\":\"mtr:properties/common.json\","
			+ "   \"positionDefinitionsResource\":\"mtr:definitions/common.json\"}]}"
			+ "]}";

	static {
		RESOURCES.put("mtr:properties/common.json", "{"
				+ "\"modelYOffset\":1,"
				+ "\"gangwayInnerSideResource\":\"mtr:4000/d4000_side.png\","
				+ "\"gangwayInnerTopResource\":\"mtr:4000/d4000_roof.png\","
				+ "\"gangwayInnerBottomResource\":\"mtr:4000/d4000_floor.png\","
				+ "\"gangwayOuterSideResource\":\"mtr:4000/d4000_exterior.png\","
				+ "\"barrierInnerSideResource\":\"\","
				+ "\"gangwayWidth\":1.5,\"gangwayHeight\":2.25,"
				+ "\"parts\":["
				+ "  {\"names\":[\"window\"],\"positionDefinitions\":[\"windows\"],\"condition\":\"NORMAL\","
				+ "   \"renderStage\":\"EXTERIOR\",\"type\":\"NORMAL\",\"doorXMultiplier\":0,\"doorZMultiplier\":0,"
				+ "   \"doorAnimationType\":\"STANDARD\"},"
				+ "  {\"names\":[\"door_left\"],\"positionDefinitions\":[\"doors\"],\"condition\":\"NORMAL\","
				+ "   \"renderStage\":\"INTERIOR\",\"type\":\"NORMAL\",\"doorXMultiplier\":0,\"doorZMultiplier\":-10.5,"
				+ "   \"doorAnimationType\":\"CONSTANT\"},"
				+ "  {\"names\":[\"door_right\"],\"positionDefinitions\":[\"doors\"],\"condition\":\"NORMAL\","
				+ "   \"renderStage\":\"INTERIOR\",\"type\":\"NORMAL\",\"doorXMultiplier\":0,\"doorZMultiplier\":10.5,"
				+ "   \"doorAnimationType\":\"CONSTANT\"},"
				+ "  {\"names\":[\"depot_light\"],\"positionDefinitions\":[\"doors\"],\"condition\":\"AT_DEPOT\","
				+ "   \"renderStage\":\"ALWAYS_ON_LIGHT\",\"type\":\"NORMAL\"}"
				+ "]}");

		RESOURCES.put("mtr:properties/end.json", "{\"modelYOffset\":1,\"parts\":["
				+ "  {\"names\":[\"end_light\"],\"positionDefinitions\":[\"doors\"],\"condition\":\"NORMAL\","
				+ "   \"renderStage\":\"LIGHT\",\"type\":\"NORMAL\"},"
				+ "  {\"names\":[\"end_floor\"],\"positionDefinitions\":[\"doors\"],\"condition\":\"NORMAL\","
				+ "   \"renderStage\":\"EXTERIOR\",\"type\":\"FLOOR\"}"
				+ "]}");

		RESOURCES.put("mtr:properties/labels.json", "{\"modelYOffset\":1,\"parts\":["
				+ "  {\"names\":[\"display_des\"],\"positionDefinitions\":[\"doors\"],\"condition\":\"NORMAL\","
				+ "   \"renderStage\":\"EXTERIOR\",\"type\":\"DISPLAY\",\"displayType\":\"NEXT_STATION\","
				+ "   \"displayColor\":\"FFFFFF\",\"displayColorCjk\":\"FFFFFF\",\"displayCjkSizeRatio\":2,"
				+ "   \"displayOptions\":[\"SCROLL_NORMAL\",\"SPACE_CJK\"]},"
				+ "  {\"names\":[\"display_num\"],\"positionDefinitions\":[\"doors\"],\"condition\":\"NORMAL\","
				+ "   \"renderStage\":\"EXTERIOR\",\"type\":\"DISPLAY\",\"displayType\":\"DEPARTURE_INDEX\"}"
				+ "]}");

		RESOURCES.put("mtr:definitions/common.json", "{\"positionDefinitions\":["
				+ "  {\"name\":\"windows\",\"positions\":[{\"x\":3,\"y\":0,\"z\":80}],"
				+ "   \"positionsFlipped\":[{\"x\":3,\"y\":0,\"z\":80}]},"
				+ "  {\"name\":\"doors\",\"positions\":[{\"x\":0,\"y\":0,\"z\":24.25}],\"positionsFlipped\":[]}"
				+ "]}");
	}
}
