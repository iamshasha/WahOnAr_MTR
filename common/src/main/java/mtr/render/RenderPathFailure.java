package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.IDrawing;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;

/**
 * Marks the spot where a depot's path generation gave up.
 *
 * The depot screen already names the two stations the path broke between, but naming them does not help you find the
 * gap in a network of any size. This draws a cage around the platform the failing hop starts from, so the place to go
 * looking is visible from a distance, and stays visible after the screen is closed.
 *
 * Deliberately its own class rather than more code inside {@link RenderTrains}: an addon pins one of that class's
 * synthetic lambdas by index, so lambdas added there would shift it. The call site in RenderTrains is a plain
 * statement, which adds none.
 */
public final class RenderPathFailure {

	private static BlockPos target;

	private static final int BEAM_HEIGHT = 32;
	private static final int MAX_MARKER_DISTANCE = 512;
	// The same red the mod already uses to call out a problem
	private static final int COLOR_R = 0xFF;
	private static final int COLOR_G = 0x5E;
	private static final int COLOR_B = 0x4D;

	private RenderPathFailure() {
	}

	public static void setTarget(BlockPos pos) {
		target = pos;
	}

	public static void clearTarget() {
		target = null;
	}

	public static void render(PoseStack matrices, MultiBufferSource vertexConsumers) {
		final BlockPos pos = target;
		if (pos == null || RenderTrains.shouldNotRender(pos, MAX_MARKER_DISTANCE, null)) {
			return;
		}

		final float x = pos.getX();
		final float y = pos.getY();
		final float z = pos.getZ();

		// Four uprights plus a cross at the base, so the marker reads as a column from any angle rather than
		// disappearing when you line up with a single line
		for (int corner = 0; corner < 4; corner++) {
			final float cornerX = x + (corner == 0 || corner == 3 ? 0 : 1);
			final float cornerZ = z + (corner < 2 ? 0 : 1);
			IDrawing.drawLine(matrices, vertexConsumers, cornerX, y, cornerZ, cornerX, y + BEAM_HEIGHT, cornerZ, COLOR_R, COLOR_G, COLOR_B);
		}

		IDrawing.drawLine(matrices, vertexConsumers, x, y, z, x + 1, y, z + 1, COLOR_R, COLOR_G, COLOR_B);
		IDrawing.drawLine(matrices, vertexConsumers, x + 1, y, z, x, y, z + 1, COLOR_R, COLOR_G, COLOR_B);
	}
}
