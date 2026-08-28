package mtr.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.data.NameColorDataBase;
import mtr.model.ModelTrainBase;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;

/**
 * One train whose carriages are not all the same: a cab at each end and trailers in between.
 *
 * MTR 4 ships a vehicle per carriage type and lets a driver couple them together in the depot. MTR 3 has no such
 * step — a train is one type from end to end — so a pack converted one vehicle at a time leaves the front cab,
 * the trailer and the back cab sitting in the train list as three separate trains, none of which is the train the
 * pack is actually of. This puts them back together.
 *
 * {@link ModelTrainBase#render} is already told which car it is drawing and how many there are, which is all this
 * needs. The cabs go on the physical ends and everything between is a trailer, so a two-car train is two cabs and
 * has no trailer in it at all.
 *
 * The ends are chosen by car index rather than by which way the train is running. A cab is welded to its carriage;
 * reversing a train does not move it to the other end, it only means the other cab is leading. MTR 3 already
 * mirrors a car's parts when the train runs the other way, and {@code head1IsFront} is passed straight through so
 * that keeps happening.
 */
public class AssembledTrainModel extends ModelTrainBase {

	private final ModelTrainBase front;
	private final ModelTrainBase middle;
	private final ModelTrainBase back;
	private final ResourceLocation frontTexture;
	private final ResourceLocation middleTexture;
	private final ResourceLocation backTexture;
	private final int doorMax;

	public AssembledTrainModel(
			ModelTrainBase front, ResourceLocation frontTexture,
			ModelTrainBase middle, ResourceLocation middleTexture,
			ModelTrainBase back, ResourceLocation backTexture,
			int doorMax, DoorAnimationType doorAnimationType
	) {
		super(doorAnimationType, false);
		this.front = front;
		this.middle = middle;
		this.back = back;
		this.frontTexture = frontTexture;
		this.middleTexture = middleTexture;
		this.backTexture = backTexture;
		this.doorMax = doorMax;
	}

	@Override
	public void render(PoseStack matrices, MultiBufferSource vertexConsumers, NameColorDataBase data, ResourceLocation texture, int light, float doorLeftValue, float doorRightValue, boolean opening, int currentCar, int trainCars, boolean head1IsFront, boolean lightsOn, boolean isTranslucent, boolean renderDetails, boolean atPlatform) {
		final boolean isFirst = currentCar == 0;
		final boolean isLast = currentCar == trainCars - 1;

		// A one-car train is both ends at once. Neither cab is right for that, and there is no whole-train model
		// to fall back on, so the leading cab is drawn: half of it is correct and the alternative is nothing.
		final ModelTrainBase model = isFirst ? front : isLast ? back : middle;
		final ResourceLocation carTexture = isFirst ? frontTexture : isLast ? backTexture : middleTexture;

		model.render(matrices, vertexConsumers, data, carTexture, light, doorLeftValue, doorRightValue, opening, currentCar, trainCars, head1IsFront, lightsOn, isTranslucent, renderDetails, atPlatform);
	}

	/** Each carriage draws itself whole, so nothing reaches the per-stage pass here. */
	@Override
	protected void render(PoseStack matrices, VertexConsumer vertices, RenderStage renderStage, int light, float doorLeftX, float doorRightX, float doorLeftZ, float doorRightZ, int currentCar, int trainCars, boolean head1IsFront, boolean renderDetails) {
	}

	/**
	 * The widest of the three, worked out by the caller and handed in. A train opens every door by the same
	 * amount, and a cab whose doors travel less than a trailer's would otherwise pull the whole train down to
	 * its own. It is given rather than asked for because a carriage's own door travel is not visible from here:
	 * {@link ModelTrainBase#getDoorMax()} is protected, and these three live in another package.
	 */
	@Override
	protected int getDoorMax() {
		return doorMax;
	}
}
