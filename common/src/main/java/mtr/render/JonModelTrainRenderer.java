package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.MTRClient;
import mtr.client.Config;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtr.data.IGui;
import mtr.data.Train;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import mtr.mappings.UtilitiesClient;
import mtr.model.ModelBogie;
import mtr.model.ModelCableCarGrip;
import mtr.model.ModelTrainBase;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public class JonModelTrainRenderer extends TrainRendererBase implements IGui {

	private final TrainClient train;

	public final ModelTrainBase model;
	public final String textureId;
	public final String gangwayConnectionId;
	public final String trainBarrierId;

	private static final EntityModel<Minecart> MODEL_MINECART = UtilitiesClient.getMinecartModel();
	private static final EntityModel<Boat> MODEL_BOAT = UtilitiesClient.getBoatModel();
	private static final Map<Long, FakeBoat> BOATS = new HashMap<>();
	private static final ModelCableCarGrip MODEL_CABLE_CAR_GRIP = new ModelCableCarGrip();
	private static final ModelBogie MODEL_BOGIE = new ModelBogie();
	// Texture ids never change for a renderer instance, but renderConnection/renderBarrier used to rebuild them
	// (String.format plus a ResourceLocation each) for every car of every train, every frame
	private final Map<String, ResourceLocation> connectorTextures = new HashMap<>();
	private final Map<String, ResourceLocation> carTextures = new HashMap<>();
	private int cachedTextureGeneration = -1;

	private JonModelTrainRenderer(ModelTrainBase model, String textureId, String gangwayConnectionId, String trainBarrierId, TrainClient train) {
		this.model = model;
		this.textureId = resolvePath(textureId);
		this.gangwayConnectionId = resolvePath(gangwayConnectionId);
		this.trainBarrierId = resolvePath(trainBarrierId);
		this.train = train;
	}

	public JonModelTrainRenderer(ModelTrainBase model, String textureId, String gangwayConnectionId, String trainBarrierId) {
		this(model, textureId, gangwayConnectionId, trainBarrierId, null);
	}

	@Override
	public TrainRendererBase createTrainInstance(TrainClient train) {
		return new JonModelTrainRenderer(model, textureId, gangwayConnectionId, trainBarrierId, train);
	}

	@Override
	public void renderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen) {
		final float doorLeftValue = doorLeftOpen ? train.getDoorValue() : 0;
		final float doorRightValue = doorRightOpen ? train.getDoorValue() : 0;
		final boolean atPlatform = train.path.get(train.getIndex(0, train.spacing, true)).dwellTime > 0;
		final boolean hasPitch = pitch < 0 ? train.transportMode.hasPitchAscending : train.transportMode.hasPitchDescending;

		final String trainId = train.trainId;
		final TrainProperties trainProperties = TrainClientRegistry.getTrainProperties(trainId);

		if (model == null && isTranslucentBatch) {
			return;
		}

		// A vehicle whose pack the client does not have falls back to a minecart drawn with a fully transparent
		// texture. Nothing appears — but the model is still set up and its geometry submitted, for every car of
		// every such train, every frame. Since there is nothing to see either way, spend nothing on it.
		if (model == null || textureId == null) {
			if (TRANSPARENT_TEXTURE.equals(resolveTexture(textureId, id -> id + ".png"))) {
				return;
			}
		}

		final BlockPos posAverage = applyAverageTransform(train.getViewOffset(), x, y, z);
		if (posAverage == null) {
			return;
		}

		matrices.pushPose();
		matrices.translate(x, y, z);
		UtilitiesClient.rotateY(matrices, (float) Math.PI + yaw);
		UtilitiesClient.rotateX(matrices, (float) Math.PI + (hasPitch ? pitch : 0));

		// Lean into the curve. MTR-ANTE rolls the cars itself at this same point, so stand down when it is loaded
		// rather than leaning twice; the pivot sits a block above the floor so the car rolls about its body, not its feet.
		// ANTE banks the cars into curves itself, so the lean is left to it or the train rolls twice. It does not
		// rock a train running on straight track, though, and that half is worth having either way.
		final float sway = !Config.useVehicleSway() ? 0
				: MTRClient.isAnte() ? train.getSwayRunningRoll(carIndex)
				: train.getSwayRoll(carIndex);
		if (sway != 0) {
			final float swayPivot = TrainClient.getSwayPivotHeight();
			matrices.translate(0, swayPivot, 0);
			UtilitiesClient.rotateZ(matrices, train.isReversed() ? sway : -sway);
			matrices.translate(0, -swayPivot, 0);
		}

		final int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, posAverage), world.getBrightness(LightLayer.SKY, posAverage));

		if (model == null || textureId == null) {
			final boolean isBoat = train.transportMode == TransportMode.BOAT;

			matrices.translate(0, isBoat ? 0.875 : 0.5, 0);
			UtilitiesClient.rotateYDegrees(matrices, 90);

			final EntityModel<? extends Entity> model = isBoat ? MODEL_BOAT : MODEL_MINECART;
			final VertexConsumer vertexConsumer = vertexConsumers.getBuffer(model.renderType(resolveTexture(textureId, textureId -> textureId + ".png")));

			if (isBoat) {
				if (!BOATS.containsKey(train.id)) {
					BOATS.put(train.id, new FakeBoat());
				}
				MODEL_BOAT.setupAnim(BOATS.get(train.id), (train.getSpeed() + Train.ACCELERATION_DEFAULT) * (doorLeftValue == 0 && doorRightValue == 0 ? lastFrameDuration : 0), 0, -0.1F, 0, 0);
			} else {
				model.setupAnim(null, 0, 0, -0.1F, 0, 0);
			}

			model.renderToBuffer(matrices, vertexConsumer, light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
		} else if (!textureId.isEmpty()) {
			final boolean renderDetails = MTRClient.isReplayMod() || posAverage.distSqr(camera.getBlockPosition()) <= RenderTrains.DETAIL_RADIUS_SQUARED;
			model.render(matrices, vertexConsumers, train, resolveTexture(textureId, textureId -> textureId + ".png"), light, doorLeftValue, doorRightValue, train.isDoorOpening(), carIndex, train.trainCars, !train.isReversed(), train.getIsOnRoute(), isTranslucentBatch, renderDetails, atPlatform);

			if (trainProperties.bogiePosition != 0 && !isTranslucentBatch) {
				if (trainProperties.isJacobsBogie) {
					if (carIndex == 0) {
						MODEL_BOGIE.render(matrices, vertexConsumers, light, train.trainCars == 1 ? 0 : -(int) (trainProperties.bogiePosition * 16));
					} else if (carIndex == train.trainCars - 1) {
						MODEL_BOGIE.render(matrices, vertexConsumers, light, (int) (trainProperties.bogiePosition * 16));
					}
				} else {
					MODEL_BOGIE.render(matrices, vertexConsumers, light, (int) (trainProperties.bogiePosition * 16));
					MODEL_BOGIE.render(matrices, vertexConsumers, light, -(int) (trainProperties.bogiePosition * 16));
				}
			}
		}

		if (train.transportMode == TransportMode.CABLE_CAR && !isTranslucentBatch) {
			matrices.translate(0, TransportMode.CABLE_CAR.railOffset + 0.5, 0);
			if (!hasPitch) {
				UtilitiesClient.rotateX(matrices, pitch);
			}
			if (trainId.endsWith("_rht")) {
				UtilitiesClient.rotateYDegrees(matrices, 180);
			}
			MODEL_CABLE_CAR_GRIP.render(matrices, vertexConsumers, light);
		}

		matrices.popPose();
		matrices.popPose();
	}

	@Override
	public void renderConnection(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch) {
		final BlockPos posAverage = applyAverageTransform(train.getViewOffset(), x, y, z);
		if (posAverage == null) {
			return;
		}

		final String trainId = train.trainId;
		final TrainProperties trainProperties = TrainClientRegistry.getTrainProperties(trainId);

		final int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, posAverage), world.getBrightness(LightLayer.SKY, posAverage));

		if (!gangwayConnectionId.isEmpty()) {
			final VertexConsumer vertexConsumerExterior = vertexConsumers.getBuffer(MoreRenderLayers.getExterior(getConnectorTextureString(true, "exterior")));
			drawTexture(matrices, vertexConsumerExterior, thisPos2, prevPos3, prevPos4, thisPos1, light);
			drawTexture(matrices, vertexConsumerExterior, prevPos2, thisPos3, thisPos4, prevPos1, light);
			drawTexture(matrices, vertexConsumerExterior, prevPos3, thisPos2, thisPos3, prevPos2, light);
			drawTexture(matrices, vertexConsumerExterior, prevPos1, thisPos4, thisPos1, prevPos4, light);

			final int lightOnLevel = train.getIsOnRoute() ? RenderTrains.MAX_LIGHT_INTERIOR : light;
			final VertexConsumer vertexConsumerSide = vertexConsumers.getBuffer(MoreRenderLayers.getInterior(getConnectorTextureString(true, "side")));
			drawTexture(matrices, vertexConsumerSide, thisPos3, prevPos2, prevPos1, thisPos4, lightOnLevel);
			drawTexture(matrices, vertexConsumerSide, prevPos3, thisPos2, thisPos1, prevPos4, lightOnLevel);
			drawTexture(matrices, vertexConsumers.getBuffer(MoreRenderLayers.getInterior(getConnectorTextureString(true, "roof"))), prevPos2, thisPos3, thisPos2, prevPos3, lightOnLevel);
			drawTexture(matrices, vertexConsumers.getBuffer(MoreRenderLayers.getInterior(getConnectorTextureString(true, "floor"))), prevPos4, thisPos1, thisPos4, prevPos1, lightOnLevel);
		}

		if (trainProperties.isJacobsBogie) {
			matrices.pushPose();
			matrices.translate(x, y, z);
			UtilitiesClient.rotateY(matrices, (float) Math.PI + yaw);
			UtilitiesClient.rotateX(matrices, (float) Math.PI + ((pitch < 0 ? train.transportMode.hasPitchAscending : train.transportMode.hasPitchDescending) ? pitch : 0));
			MODEL_BOGIE.render(matrices, vertexConsumers, light, 0);
			matrices.popPose();
		}

		matrices.popPose();
	}

	@Override
	public void renderBarrier(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch) {
		if (StringUtils.isEmpty(trainBarrierId)) {
			return;
		}

		final BlockPos posAverage = applyAverageTransform(train.getViewOffset(), x, y, z);
		if (posAverage == null) {
			return;
		}

		final int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, posAverage), world.getBrightness(LightLayer.SKY, posAverage));

		final VertexConsumer vertexConsumerExterior = vertexConsumers.getBuffer(MoreRenderLayers.getExterior(getConnectorTextureString(false, "exterior")));
		drawTexture(matrices, vertexConsumerExterior, thisPos2, prevPos3, prevPos4, thisPos1, light);
		drawTexture(matrices, vertexConsumerExterior, prevPos2, thisPos3, thisPos4, prevPos1, light);
		drawTexture(matrices, vertexConsumerExterior, thisPos3, prevPos2, prevPos1, thisPos4, light);
		drawTexture(matrices, vertexConsumerExterior, prevPos3, thisPos2, thisPos1, prevPos4, light);

		matrices.popPose();
	}

	private void clearTexturesIfStale() {
		if (cachedTextureGeneration != RenderTrains.getTextureGeneration()) {
			cachedTextureGeneration = RenderTrains.getTextureGeneration();
			carTextures.clear();
			connectorTextures.clear();
		}
	}

	public ResourceLocation resolveTexture(String textureId, Function<String, String> formatter) {
		clearTexturesIfStale();
		final ResourceLocation cached = carTextures.get(textureId);
		if (cached != null) {
			return cached;
		}
		final ResourceLocation resolved = resolveTextureUncached(textureId, formatter);
		carTextures.put(textureId, resolved);
		return resolved;
	}

	/** What a missing texture falls back to when there is no base type to borrow one from: literally nothing. */
	private static final ResourceLocation TRANSPARENT_TEXTURE = new ResourceLocation("mtr:textures/block/transparent.png");

	private ResourceLocation resolveTextureUncached(String textureId, Function<String, String> formatter) {
		final String textureString = formatter.apply(textureId);
		final ResourceLocation id = new ResourceLocation(textureString);
		final boolean available;

		if (!RenderTrains.AVAILABLE_TEXTURES.contains(textureString) && !RenderTrains.UNAVAILABLE_TEXTURES.contains(textureString)) {
			available = UtilitiesClient.hasResource(id);
			(available ? RenderTrains.AVAILABLE_TEXTURES : RenderTrains.UNAVAILABLE_TEXTURES).add(textureString);
			if (!available) {
				System.out.println("Texture " + textureString + " not found, using default");
			}
		} else {
			available = RenderTrains.AVAILABLE_TEXTURES.contains(textureString);
		}

		if (available) {
			return id;
		} else {
			final TrainRendererBase baseRenderer = TrainClientRegistry.getTrainProperties(train.baseTrainType).renderer;
			return !(baseRenderer instanceof JonModelTrainRenderer) ? TRANSPARENT_TEXTURE : new ResourceLocation(formatter.apply(((JonModelTrainRenderer) baseRenderer).textureId));
		}
	}

	private ResourceLocation getConnectorTextureString(boolean isConnector, String partName) {
		clearTexturesIfStale();
		final String key = (isConnector ? "c_" : "b_") + partName;
		final ResourceLocation cached = connectorTextures.get(key);
		if (cached != null) {
			return cached;
		}
		final ResourceLocation resolved = resolveTextureUncached(isConnector ? gangwayConnectionId : trainBarrierId, textureId -> String.format("%s_%s_%s.png", textureId, isConnector ? "connector" : "barrier", partName));
		connectorTextures.put(key, resolved);
		return resolved;
	}

	private static void drawTexture(PoseStack matrices, VertexConsumer vertexConsumer, Vec3 pos1, Vec3 pos2, Vec3 pos3, Vec3 pos4, int light) {
		mtr.client.IDrawing.drawTexture(matrices, vertexConsumer, (float) pos1.x, (float) pos1.y, (float) pos1.z, (float) pos2.x, (float) pos2.y, (float) pos2.z, (float) pos3.x, (float) pos3.y, (float) pos3.z, (float) pos4.x, (float) pos4.y, (float) pos4.z, 0, 0, 1, 1, Direction.UP, -1, light);
	}

	private static String resolvePath(String path) {
		return path == null ? null : path.toLowerCase(Locale.ENGLISH).split("\\.png")[0];
	}

	private static class FakeBoat extends Boat {

		private float progress;

		public FakeBoat() {
			super(EntityType.BOAT, null);
		}

		@Override
		public float getRowingTime(int paddle, float newProgress) {
			progress += newProgress;
			return progress;
		}
	}
}
