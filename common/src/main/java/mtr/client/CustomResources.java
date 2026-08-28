package mtr.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.MTR;
import mtr.data.EnumHelper;
import mtr.mappings.Utilities;
import mtr.mappings.UtilitiesClient;
import mtr.model.ModelSimpleTrainBase;
import mtr.model.ModelTrainBase;
import mtr.render.JonModelTrainRenderer;
import mtr.render.RenderTrains;
import mtr.sound.JonTrainSound;
import mtr.sound.bve.BveTrainSound;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

public class CustomResources implements IResourcePackCreatorProperties, ICustomResources {

	public static final Map<String, CustomSign> CUSTOM_SIGNS = new HashMap<>();
	private static final List<Consumer<ResourceManager>> RELOAD_LISTENERS = new ArrayList<>();

	public static void reload(ResourceManager manager) {
		TrainClientRegistry.reset();
		RenderTrains.clearTextureAvailability();
		ClientData.DATA_CACHE.resetFonts();
		CUSTOM_SIGNS.clear();
		final List<String> customTrains = new ArrayList<>();

		readResource(manager, MTR.MOD_ID + ":" + CUSTOM_RESOURCES_ID + ".json", jsonConfig -> {
			// A pack written for MTR 4 uses the same file name and nothing else in common, so it is recognised
			// here and restated in MTR 3's own terms before any of the loading below sees it.
			if (Mtr4CustomResources.isMtr4Format(jsonConfig)) {
				loadMtr4Resources(manager, jsonConfig, customTrains);
				return;
			}

			try {
				jsonConfig.get(CUSTOM_TRAINS_KEY).getAsJsonObject().entrySet().forEach(entry -> {
					try {
						final JsonObject jsonObject = entry.getValue().getAsJsonObject();
						final String name = getOrDefault(jsonObject, CUSTOM_TRAINS_NAME, entry.getKey(), JsonElement::getAsString);
						final int color = getOrDefault(jsonObject, CUSTOM_TRAINS_COLOR, 0, jsonElement -> colorStringToInt(jsonElement.getAsString()));
						final String trainId = CUSTOM_TRAIN_ID_PREFIX + entry.getKey();

						final String baseTrainType = getOrDefault(jsonObject, CUSTOM_TRAINS_BASE_TRAIN_TYPE, "", JsonElement::getAsString);
						final TrainProperties baseTrainProperties = TrainClientRegistry.getTrainProperties(baseTrainType);
						final String description = getOrDefault(jsonObject, CUSTOM_TRAINS_DESCRIPTION, baseTrainProperties.description, JsonElement::getAsString);
						final String wikipediaArticle = getOrDefault(jsonObject, CUSTOM_TRAINS_WIKIPEDIA_ARTICLE, baseTrainProperties.wikipediaArticle, JsonElement::getAsString);

						final JonModelTrainRenderer jonRendererOrDefault = baseTrainProperties.renderer instanceof JonModelTrainRenderer ? (JonModelTrainRenderer) baseTrainProperties.renderer : new JonModelTrainRenderer(null, "", "", "");
						final JonTrainSound jonSoundOrDefault = baseTrainProperties.sound instanceof JonTrainSound ? (JonTrainSound) baseTrainProperties.sound : new JonTrainSound("", new JonTrainSound.JonTrainSoundConfig(null, 0, 0.5F, false, false));
						final String baseBveSoundBaseId = baseTrainProperties.sound instanceof BveTrainSound ? ((BveTrainSound) baseTrainProperties.sound).config.baseName : "";
						final ModelSimpleTrainBase<?> modelSimpleTrainBase = jonRendererOrDefault.model instanceof ModelSimpleTrainBase<?> ? ((ModelSimpleTrainBase<?>) jonRendererOrDefault.model) : null;

						final String textureId = getOrDefault(jsonObject, CUSTOM_TRAINS_TEXTURE_ID, jonRendererOrDefault.textureId, JsonElement::getAsString);
						final String gangwayConnectionId = getOrDefault(jsonObject, CUSTOM_TRAINS_GANGWAY_CONNECTION_ID, jonRendererOrDefault.gangwayConnectionId, JsonElement::getAsString);
						final String trainBarrierId = getOrDefault(jsonObject, CUSTOM_TRAINS_TRAIN_BARRIER_ID, jonRendererOrDefault.trainBarrierId, JsonElement::getAsString);
						final DoorAnimationType doorAnimationType = EnumHelper.valueOf(modelSimpleTrainBase == null ? DoorAnimationType.STANDARD : modelSimpleTrainBase.doorAnimationType, getOrDefault(jsonObject, CUSTOM_TRAINS_DOOR_ANIMATION_TYPE, "", JsonElement::getAsString));
						final boolean renderDoorOverlay = getOrDefault(jsonObject, CUSTOM_TRAINS_RENDER_DOOR_OVERLAY, modelSimpleTrainBase != null, JsonElement::getAsBoolean);
						final float riderOffset = getOrDefault(jsonObject, CUSTOM_TRAINS_RIDER_OFFSET, baseTrainProperties.riderOffset, JsonElement::getAsFloat);
						final String bveSoundBaseId = getOrDefault(jsonObject, CUSTOM_TRAINS_BVE_SOUND_BASE_ID, baseBveSoundBaseId, JsonElement::getAsString);
						final int speedSoundCount = getOrDefault(jsonObject, CUSTOM_TRAINS_SPEED_SOUND_COUNT, jonSoundOrDefault.config.speedSoundCount, JsonElement::getAsInt);
						final String speedSoundBaseId = getOrDefault(jsonObject, CUSTOM_TRAINS_SPEED_SOUND_BASE_ID, jonSoundOrDefault.soundId, JsonElement::getAsString);
						final String doorSoundBaseId = getOrDefault(jsonObject, CUSTOM_TRAINS_DOOR_SOUND_BASE_ID, jonSoundOrDefault.config.doorSoundBaseId, JsonElement::getAsString);
						final float doorCloseSoundTime = getOrDefault(jsonObject, CUSTOM_TRAINS_DOOR_CLOSE_SOUND_TIME, jonSoundOrDefault.config.doorCloseSoundTime, JsonElement::getAsFloat);
						final boolean accelSoundAtCoast = getOrDefault(jsonObject, CUSTOM_TRAINS_ACCEL_SOUND_AT_COAST, jonSoundOrDefault.config.useAccelerationSoundsWhenCoasting, JsonElement::getAsBoolean);
						final boolean constPlaybackSpeed = getOrDefault(jsonObject, CUSTOM_TRAINS_CONST_PLAYBACK_SPEED, jonSoundOrDefault.config.constantPlaybackSpeed, JsonElement::getAsBoolean);

						final boolean useBveSound;
						if (StringUtils.isEmpty(bveSoundBaseId)) {
							useBveSound = false;
						} else {
							if (jsonObject.has(CUSTOM_TRAINS_BVE_SOUND_BASE_ID)) {
								useBveSound = true;
							} else if (jsonObject.has(CUSTOM_TRAINS_SPEED_SOUND_BASE_ID)) {
								useBveSound = false;
							} else {
								useBveSound = baseTrainProperties.sound instanceof BveTrainSound;
							}
						}

						if (!baseTrainProperties.baseTrainType.isEmpty()) {
							final ModelTrainBase model = modelSimpleTrainBase == null ? jonRendererOrDefault.model : (ModelTrainBase) modelSimpleTrainBase.createNew(doorAnimationType, renderDoorOverlay);
							final String soundBaseId = useBveSound ? bveSoundBaseId : speedSoundBaseId;
							final JonTrainSound.JonTrainSoundConfig soundConfig = useBveSound ? null : new JonTrainSound.JonTrainSoundConfig(doorSoundBaseId, speedSoundCount, doorCloseSoundTime, accelSoundAtCoast, constPlaybackSpeed);
							TrainClientRegistry.register(trainId, baseTrainType, name, description, wikipediaArticle, model, textureId, color, gangwayConnectionId, trainBarrierId, riderOffset, riderOffset, baseTrainProperties.bogiePosition, baseTrainProperties.isJacobsBogie, soundBaseId, soundConfig);
							customTrains.add(trainId);
						}

						if (jsonObject.has(CUSTOM_TRAINS_MODEL) && jsonObject.has(CUSTOM_TRAINS_MODEL_PROPERTIES)) {
							readResource(manager, jsonObject.get(CUSTOM_TRAINS_MODEL).getAsString(), jsonModel -> readResource(manager, jsonObject.get(CUSTOM_TRAINS_MODEL_PROPERTIES).getAsString(), jsonProperties -> {
								IResourcePackCreatorProperties.checkSchema(jsonProperties);
								final String newBaseTrainType = String.format("%s_%s_%s", jsonProperties.get(KEY_PROPERTIES_TRANSPORT_MODE).getAsString(), jsonProperties.get(KEY_PROPERTIES_LENGTH).getAsInt(), jsonProperties.get(KEY_PROPERTIES_WIDTH).getAsInt());

								// TODO temporary code for backwards compatibility
								final String gangwayConnectionId2 = gangwayConnectionId.isEmpty() ? getOrDefault(jsonObject, "has_gangway_connection", true, JsonElement::getAsBoolean) ? "mtr:textures/entity/sp1900" : "" : gangwayConnectionId;
								final String newBaseTrainType2 = baseTrainType.startsWith("base_") ? baseTrainType.replace("base_", "train_") : newBaseTrainType;
								final boolean useLegacy = jsonProperties.has("parts_normal");
								// TODO temporary code end

								final ModelTrainBase model = useLegacy ? new DynamicTrainModelLegacy(jsonModel, jsonProperties, doorAnimationType) : new DynamicTrainModel(jsonModel, jsonProperties, doorAnimationType);
								final String soundBaseId = useBveSound ? bveSoundBaseId : speedSoundBaseId;
								final JonTrainSound.JonTrainSoundConfig soundConfig = useBveSound ? null : new JonTrainSound.JonTrainSoundConfig(doorSoundBaseId, speedSoundCount, doorCloseSoundTime, accelSoundAtCoast, constPlaybackSpeed);
								TrainClientRegistry.register(trainId, newBaseTrainType2.toLowerCase(Locale.ENGLISH), name, description, wikipediaArticle, model, textureId, color, gangwayConnectionId2, trainBarrierId, riderOffset, riderOffset, baseTrainProperties.bogiePosition, baseTrainProperties.isJacobsBogie, soundBaseId, soundConfig);
								customTrains.add(trainId);
							}));
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				});
			} catch (Exception ignored) {
			}

			try {
				loadCustomSigns(jsonConfig.get(CUSTOM_SIGNS_KEY).getAsJsonObject());
			} catch (Exception ignored) {
			}
		});

		RELOAD_LISTENERS.forEach(resourceManagerConsumer -> resourceManagerConsumer.accept(manager));

		System.out.println("Loaded " + customTrains.size() + " custom train(s)");
		customTrains.forEach(System.out::println);
		System.out.println("Loaded " + CUSTOM_SIGNS.size() + " custom sign(s)");
		CUSTOM_SIGNS.keySet().forEach(System.out::println);
	}

	private static void loadCustomSigns(JsonObject customSignsObject) {
		customSignsObject.entrySet().forEach(entry -> {
			try {
				final JsonObject jsonObject = entry.getValue().getAsJsonObject();

				final boolean flipTexture = getOrDefault(jsonObject, CUSTOM_SIGNS_FLIP_TEXTURE, false, JsonElement::getAsBoolean);
				final String customText = getOrDefault(jsonObject, CUSTOM_SIGNS_CUSTOM_TEXT, "", JsonElement::getAsString);
				final boolean flipCustomText = getOrDefault(jsonObject, CUSTOM_SIGNS_FLIP_CUSTOM_TEXT, false, JsonElement::getAsBoolean);
				final boolean small = getOrDefault(jsonObject, CUSTOM_SIGNS_SMALL, false, JsonElement::getAsBoolean);
				final int backgroundColor = getOrDefault(jsonObject, CUSTOM_SIGNS_BACKGROUND_COLOR, 0, jsonElement -> colorStringToInt(jsonElement.getAsString()));

				CUSTOM_SIGNS.put(CUSTOM_SIGN_ID_PREFIX + entry.getKey(), new CustomSign(new ResourceLocation(jsonObject.get(CUSTOM_SIGNS_TEXTURE_ID).getAsString()), flipTexture, customText, flipCustomText, small, backgroundColor));
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	/**
	 * Registers everything an MTR 4 pack declares that MTR 3 has somewhere to put. The conversion itself is in
	 * {@link Mtr4CustomResources}; what happens here is only the reading of the files it asks for and the same
	 * registration the MTR 3 path does, so an MTR 4 train ends up as an ordinary entry in the train registry.
	 */
	private static void loadMtr4Resources(ResourceManager manager, JsonObject jsonConfig, List<String> customTrains) {
		final Mtr4CustomResources.Result result = Mtr4CustomResources.convert(jsonConfig, path -> readResourceOnce(manager, path));

		// Kept so that a train assembled from several of these can reach the carriages it is made of.
		final Map<String, ModelTrainBase> modelsByTrainId = new HashMap<>();
		final Map<String, ResourceLocation> texturesByTrainId = new HashMap<>();
		final Map<String, Integer> doorMaxByTrainId = new HashMap<>();

		result.trains.forEach(train -> {
			final List<DynamicTrainModel> models = new ArrayList<>();
			final List<ResourceLocation> textures = new ArrayList<>();

			train.layers.forEach(layer -> {
				final JsonObject jsonModel = readResourceOnce(manager, layer.modelResource);
				if (jsonModel == null) {
					System.out.println("MTR 4 pack: " + train.id + " names a model the pack does not ship: " + layer.modelResource);
					return;
				}
				Mtr4CustomResources.normalizeBlockbenchModel(jsonModel, layer.modelYOffset);
				models.add(new DynamicTrainModel(jsonModel, layer.properties, train.doorAnimationType));
				textures.add(new ResourceLocation(layer.textureId + ".png"));
			});

			if (models.isEmpty()) {
				return;
			}

			final ModelTrainBase model = models.size() == 1 ? models.get(0) : new LayeredTrainModel(models, textures, train.doorAnimationType);
			final String soundBaseId = train.useBveSound ? train.bveSoundBaseId : train.speedSoundBaseId;
			final JonTrainSound.JonTrainSoundConfig soundConfig = train.useBveSound ? null : new JonTrainSound.JonTrainSoundConfig(train.doorSoundBaseId, train.speedSoundCount, train.doorCloseSoundTime, train.accelSoundAtCoast, train.constPlaybackSpeed);

			TrainClientRegistry.register(train.id, train.baseTrainType, train.name, train.description, train.wikipediaArticle, model, train.layers.get(0).textureId, train.color, train.gangwayConnectionId, train.trainBarrierId, train.riderOffset, train.riderOffset, train.bogiePosition, false, soundBaseId, soundConfig);
			customTrains.add(train.id);

			modelsByTrainId.put(train.id, model);
			texturesByTrainId.put(train.id, new ResourceLocation(train.layers.get(0).textureId + ".png"));
			doorMaxByTrainId.put(train.id, train.layers.get(0).properties.get(IResourcePackCreatorProperties.KEY_PROPERTIES_DOOR_MAX).getAsInt());
		});

		// The carriages stay in the list beside the assembled train. Removing them would be tidier, but a depot
		// that already runs one names it by id, and a train type that stops existing is a train that stops
		// appearing -- so they are added to, never replaced.
		result.assemblies.forEach(assembly -> {
			final ModelTrainBase front = modelsByTrainId.get(assembly.frontTrainId);
			final ModelTrainBase middle = modelsByTrainId.get(assembly.middleTrainId);
			final ModelTrainBase back = modelsByTrainId.get(assembly.backTrainId);
			if (front == null || middle == null || back == null) {
				// One of the carriages had no model this build could draw, and was skipped further up
				return;
			}

			final Mtr4CustomResources.Train template = trainById(result, assembly.frontTrainId);
			if (template == null) {
				return;
			}

			final int doorMax = Math.max(doorMaxByTrainId.getOrDefault(assembly.frontTrainId, 0),
					Math.max(doorMaxByTrainId.getOrDefault(assembly.middleTrainId, 0),
							doorMaxByTrainId.getOrDefault(assembly.backTrainId, 0)));

			final ModelTrainBase model = new AssembledTrainModel(
					front, texturesByTrainId.get(assembly.frontTrainId),
					middle, texturesByTrainId.get(assembly.middleTrainId),
					back, texturesByTrainId.get(assembly.backTrainId),
					doorMax, template.doorAnimationType
			);

			final String soundBaseId = template.useBveSound ? template.bveSoundBaseId : template.speedSoundBaseId;
			final JonTrainSound.JonTrainSoundConfig soundConfig = template.useBveSound ? null : new JonTrainSound.JonTrainSoundConfig(template.doorSoundBaseId, template.speedSoundCount, template.doorCloseSoundTime, template.accelSoundAtCoast, template.constPlaybackSpeed);

			TrainClientRegistry.register(assembly.id, template.baseTrainType, assembly.name, template.description, template.wikipediaArticle, model, template.layers.get(0).textureId, template.color, template.gangwayConnectionId, template.trainBarrierId, template.riderOffset, template.riderOffset, template.bogiePosition, false, soundBaseId, soundConfig);
			customTrains.add(assembly.id);
			System.out.println("MTR 4 pack: assembled " + assembly.name + " from its front cab, trailer and back cab");
		});

		loadCustomSigns(result.customSigns);

		result.notes.forEach(note -> System.out.println("MTR 4 pack: " + note));
	}

	private static Mtr4CustomResources.Train trainById(Mtr4CustomResources.Result result, String id) {
		for (final Mtr4CustomResources.Train train : result.trains) {
			if (train.id.equals(id)) {
				return train;
			}
		}
		return null;
	}

	public static int colorStringToInt(String string) {
		try {
			return Integer.parseInt(string.toUpperCase(Locale.ENGLISH).replaceAll("[^\\dA-F]", ""), 16);
		} catch (Exception ignored) {
			return 0;
		}
	}

	private static void readResource(ResourceManager manager, String path, Consumer<JsonObject> callback) {
		try {
			UtilitiesClient.getResources(manager, new ResourceLocation(path)).forEach(resource -> {
				try (final InputStream stream = Utilities.getInputStream(resource)) {
					callback.accept(new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
				} catch (Exception e) {
					e.printStackTrace();
				}
				try {
					Utilities.closeResource(resource);
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
		} catch (Exception ignored) {
		}
	}

	/** The last pack in the stack wins, which is how a pack that overrides another's model is meant to behave. */
	private static JsonObject readResourceOnce(ResourceManager manager, String path) {
		final JsonObject[] jsonObject = {null};
		readResource(manager, path, json -> jsonObject[0] = json);
		return jsonObject[0];
	}

	private static <T> T getOrDefault(JsonObject jsonObject, String key, T defaultValue, Function<JsonElement, T> function) {
		if (jsonObject.has(key)) {
			return function.apply(jsonObject.get(key));
		} else {
			return defaultValue;
		}
	}

	public static void registerReloadListener(Consumer<ResourceManager> listener) {
		RELOAD_LISTENERS.add(listener);
	}

	public static class CustomSign {

		public final ResourceLocation textureId;
		public final boolean flipTexture;
		public final String customText;
		public final boolean flipCustomText;
		public final boolean small;
		public final int backgroundColor;

		public CustomSign(ResourceLocation textureId, boolean flipTexture, String customText, boolean flipCustomText, boolean small, int backgroundColor) {
			this.textureId = textureId;
			this.flipTexture = flipTexture;
			this.customText = customText;
			this.flipCustomText = flipCustomText;
			this.small = small;
			this.backgroundColor = backgroundColor;
		}

		public boolean hasCustomText() {
			return !customText.isEmpty();
		}
	}
}
