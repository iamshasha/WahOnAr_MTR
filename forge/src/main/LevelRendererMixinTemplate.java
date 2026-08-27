package mtr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.render.RenderTrains;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Shadow
	@Final
	private RenderBuffers renderBuffers;

	@Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=blockentities", ordinal = 0))
	private void afterEntities(PoseStack matrices, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, @path@.Matrix4f matrix4f, CallbackInfo ci) {
		final Vec3 cameraPos = camera.getPosition();
		final Frustum frustum = new Frustum(matrices.last().pose(), matrix4f);
		frustum.prepare(cameraPos.x, cameraPos.y, cameraPos.z);
		RenderTrains.setFrustum(frustum);
		matrices.pushPose();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		RenderTrains.render(null, 0, matrices, renderBuffers.bufferSource());
		matrices.popPose();
	}
}
