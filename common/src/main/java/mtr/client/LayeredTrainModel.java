package mtr.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.data.NameColorDataBase;
import mtr.model.ModelTrainBase;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One train drawn from several models, each with a texture of its own.
 *
 * MTR 3 gives a train a single model and a single texture, which is all its own packs ever needed. An MTR 4
 * vehicle lists its models one at a time and each names its own texture — the body, the labels stuck on it and
 * the cab front are routinely three separate images — so keeping only the first would drop most of the train.
 * Every layer is a whole {@link DynamicTrainModel}, so each brings its own parts, doors and displays, and the
 * renderer's own batching means the extra layers cost one more draw call apiece rather than anything structural.
 *
 * The renderer resolves the first layer's texture before it gets here, complete with the fallback to the base
 * train type's texture when a pack does not ship one; the rest are named directly, so a texture a pack forgot
 * shows up as Minecraft's missing texture rather than borrowing another train's.
 */
public class LayeredTrainModel extends ModelTrainBase {

	private final List<DynamicTrainModel> models;
	private final List<ResourceLocation> textures;

	public LayeredTrainModel(List<DynamicTrainModel> models, List<ResourceLocation> textures, DoorAnimationType doorAnimationType) {
		super(doorAnimationType, false);
		this.models = models;
		this.textures = textures;
	}

	@Override
	public void render(PoseStack matrices, MultiBufferSource vertexConsumers, NameColorDataBase data, ResourceLocation texture, int light, float doorLeftValue, float doorRightValue, boolean opening, int currentCar, int trainCars, boolean head1IsFront, boolean lightsOn, boolean isTranslucent, boolean renderDetails, boolean atPlatform) {
		for (int i = 0; i < models.size(); i++) {
			models.get(i).render(matrices, vertexConsumers, data, i == 0 ? texture : textures.get(i), light, doorLeftValue, doorRightValue, opening, currentCar, trainCars, head1IsFront, lightsOn, isTranslucent, renderDetails, atPlatform);
		}
	}

	/** Each layer draws itself whole, so nothing reaches the per-stage pass here. */
	@Override
	protected void render(PoseStack matrices, VertexConsumer vertices, RenderStage renderStage, int light, float doorLeftX, float doorRightX, float doorLeftZ, float doorRightZ, int currentCar, int trainCars, boolean head1IsFront, boolean renderDetails) {
	}

	@Override
	protected int getDoorMax() {
		return models.isEmpty() ? 0 : models.get(0).getDoorMax();
	}
}
