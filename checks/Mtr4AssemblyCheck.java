import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.client.Mtr4CustomResources;

/**
 * Covers which vehicles of an MTR 4 pack are coupled into one train, and — more to the point — which are not.
 *
 * MTR 4 ships a vehicle per carriage type and lets a driver couple them in the depot. MTR 3 has no such step, so
 * the shape of the train has to be worked out when the pack is read. It is worked out from the couplings: a
 * gangway is where one carriage joins the next, so an end without one is an end of the train.
 *
 * The cases that matter most are the refusals. Coupling the front cab of one train to the trailer of another
 * produces a train that looks deliberate and is wrong, and nothing downstream can tell that it happened — so a
 * pack whose vehicles cannot be told apart must be left alone rather than guessed at.
 */
public class Mtr4AssemblyCheck {

	public static void main(String[] args) {
		assertAssembles();
		assertRefusesUnrelatedVehicles();
		assertIgnoresPacksWithNothingToCouple();
		assertSizeSeparatesTrains();
		System.out.println("Mtr4Assembly ok");
	}

	/** The real shape of the Seoul Metro 4000 pack: two cabs, two trailers and a double cab, all 20 by 2. */
	private static void assertAssembles() {
		final Mtr4CustomResources.Result result = convert(
				vehicle("seoul_metro_4000_4_trailer", "Seoul Metro 4000 Series (4th Batch, trailer)", 20, 2, true, true),
				vehicle("seoul_metro_4000_4_trailer_pan", "Seoul Metro 4000 Series (4th Batch, trailer with pantograph)", 20, 2, true, true),
				vehicle("seoul_metro_4000_4_cab_1", "Seoul Metro 4000 Series (4th Batch, front cab)", 20, 2, false, true),
				vehicle("seoul_metro_4000_4_cab_2", "Seoul Metro 4000 Series (4th Batch, back cab)", 20, 2, true, false),
				vehicle("seoul_metro_4000_4_cab_3", "Seoul Metro 4000 Series (4th Batch, Double cab)", 20, 2, false, false));

		if (result.assemblies.size() != 1) {
			throw new AssertionError("expected one assembled train, got " + result.assemblies.size()
					+ "; trains read: " + result.trains.size() + "; notes: " + result.notes);
		}

		final Mtr4CustomResources.Assembly assembly = result.assemblies.get(0);
		expect("front cab", "mtr_custom_train_seoul_metro_4000_4_cab_1", assembly.frontTrainId);
		expect("back cab", "mtr_custom_train_seoul_metro_4000_4_cab_2", assembly.backTrainId);
		expect("trailer", "mtr_custom_train_seoul_metro_4000_4_trailer", assembly.middleTrainId);
		// The double cab is a train by itself and must not be mistaken for an end of this one
		if (assembly.frontTrainId.endsWith("cab_3") || assembly.backTrainId.endsWith("cab_3")) {
			throw new AssertionError("the double cab was used as an end of a longer train");
		}
		// Named after the train, not after the carriage the name was taken from
		expect("name", "Seoul Metro 4000 Series", assembly.name);
		// ...and every carriage is still there to be picked on its own
		if (result.trains.size() != 5) {
			throw new AssertionError("carriages were replaced rather than added to: " + result.trains.size() + " left");
		}
	}

	/** Two unrelated trains that happen to be the same size must not be coupled to each other. */
	private static void assertRefusesUnrelatedVehicles() {
		final Mtr4CustomResources.Result result = convert(
				vehicle("alpha_line_cab_front", "Alpha (front)", 20, 2, false, true),
				vehicle("zulu_works_trailer", "Zulu (trailer)", 20, 2, true, true),
				vehicle("zulu_works_cab_rear", "Zulu (rear)", 20, 2, true, false));

		if (!result.assemblies.isEmpty()) {
			throw new AssertionError("coupled vehicles from two different trains: " + result.assemblies.get(0).name);
		}
		if (result.notes.stream().noneMatch(note -> note.contains("common name"))) {
			throw new AssertionError("refused to couple them but did not say why");
		}
	}

	/** A pack of whole trains, or one that ships only cabs, has nothing to assemble and needs no complaint. */
	private static void assertIgnoresPacksWithNothingToCouple() {
		final Mtr4CustomResources.Result whole = convert(
				vehicle("some_train_a", "A", 20, 2, false, false),
				vehicle("some_train_b", "B", 20, 2, false, false));
		if (!whole.assemblies.isEmpty()) {
			throw new AssertionError("assembled a train out of vehicles that are whole trains already");
		}

		final Mtr4CustomResources.Result cabsOnly = convert(
				vehicle("some_train_cab_1", "A", 20, 2, false, true),
				vehicle("some_train_cab_2", "B", 20, 2, true, false));
		if (!cabsOnly.assemblies.isEmpty()) {
			throw new AssertionError("assembled a train with no trailer to put in the middle");
		}
	}

	/** A carriage of a different size belongs to a different train, whatever it is called. */
	private static void assertSizeSeparatesTrains() {
		final Mtr4CustomResources.Result result = convert(
				vehicle("metro_line_cab_1", "front", 20, 2, false, true),
				vehicle("metro_line_cab_2", "back", 20, 2, true, false),
				vehicle("metro_line_trailer", "trailer", 24, 2, true, true));

		if (!result.assemblies.isEmpty()) {
			throw new AssertionError("coupled a 24-long trailer between 20-long cabs");
		}
	}

	private static void expect(String what, String expected, String actual) {
		if (!expected.equals(actual)) {
			throw new AssertionError(what + ": expected " + expected + ", got " + actual);
		}
	}

	private static Mtr4CustomResources.Result convert(JsonObject... vehicles) {
		final JsonArray array = new JsonArray();
		for (final JsonObject vehicle : vehicles) {
			array.add(vehicle);
		}
		final JsonObject config = new JsonObject();
		config.add("vehicles", array);
		// Every vehicle names the same model, which the reader is handed below; nothing here depends on its shape
		return Mtr4CustomResources.convert(config, path -> model());
	}

	private static JsonObject vehicle(String id, String name, int length, int width, boolean gangway1, boolean gangway2) {
		final JsonObject vehicle = new JsonObject();
		vehicle.addProperty("id", id);
		vehicle.addProperty("name", name);
		// MTR 4 measures coupling to coupling, so this is one more than the body MTR 3 asks for
		vehicle.addProperty("length", length + 1);
		vehicle.addProperty("width", width);
		vehicle.addProperty("hasGangway1", gangway1);
		vehicle.addProperty("hasGangway2", gangway2);
		vehicle.addProperty("bogie1Position", -6);
		vehicle.addProperty("bogie2Position", 6);

		final JsonObject entry = new JsonObject();
		entry.addProperty("modelResource", "mtr:models/" + id + ".bbmodel");
		entry.addProperty("textureResource", "mtr:textures/" + id + ".png");
		entry.addProperty("modelPropertiesResource", "mtr:models/" + id + ".json");
		entry.addProperty("positionDefinitionsResource", "mtr:models/" + id + "_positions.json");
		final JsonArray models = new JsonArray();
		models.add(entry);
		vehicle.add("models", models);
		return vehicle;
	}

	/**
	 * The smallest thing the reader accepts, standing in for all three files a vehicle names: the Blockbench
	 * model, its properties, and its position definitions. A vehicle whose parts all fall away is skipped as
	 * undrawable, so there has to be one real part here or nothing would be assembled and the check would pass
	 * for the wrong reason.
	 */
	private static JsonObject model() {
		final JsonObject json = new JsonObject();
		json.add("outliner", new JsonArray());
		json.add("elements", new JsonArray());

		final JsonArray positions = new JsonArray();
		final JsonObject position = new JsonObject();
		position.addProperty("x", 0);
		position.addProperty("z", 0);
		positions.add(position);

		final JsonObject definition = new JsonObject();
		definition.addProperty("name", "whole_car");
		definition.add("positions", positions);
		definition.add("positionsFlipped", positions);
		final JsonArray definitions = new JsonArray();
		definitions.add(definition);
		json.add("positionDefinitions", definitions);

		final JsonObject part = new JsonObject();
		part.addProperty("type", "NORMAL");
		part.addProperty("condition", "NORMAL");
		part.addProperty("stage", "EXTERIOR");
		final JsonArray names = new JsonArray();
		names.add("body");
		part.add("names", names);
		final JsonArray used = new JsonArray();
		used.add("whole_car");
		part.add("positionDefinitions", used);

		final JsonArray parts = new JsonArray();
		parts.add(part);
		json.add("parts", parts);
		return json;
	}
}
