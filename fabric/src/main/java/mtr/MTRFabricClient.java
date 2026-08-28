package mtr;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.CustomResources;
import mtr.client.ICustomResources;
import mtr.render.RenderDrivingOverlay;
import mtr.render.RenderTrains;
import mtr.screen.JavaUpgradeScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;

public class MTRFabricClient implements ClientModInitializer, ICustomResources {

	@Override
	public void onInitializeClient() {
		MTRClient.init();
		MTRClient.initItemModelPredicate();
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			RenderTrains.setFrustum(context.frustum());
			final PoseStack matrices = context.matrixStack();
			matrices.pushPose();
			final Vec3 cameraPos = context.camera().getPosition();
			matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
			RenderTrains.render(null, 0, matrices, context.consumers());
			matrices.popPose();
		});
		WorldRenderEvents.END.register(event -> MTRClient.incrementGameTick());
		HudRenderCallback.EVENT.register((matrices, tickDelta) -> RenderDrivingOverlay.render(matrices));
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new CustomResourcesWrapper());

		if (JavaVersionNotice.upgradeNeeded()) {
			// Waits for the title screen rather than showing it from here: at mod initialization there is no screen to
			// return to, and the game is still assembling itself. Once shown, the check stops running.
			ClientTickEvents.END_CLIENT_TICK.register(new ClientTickEvents.EndTick() {

				private boolean shown;

				@Override
				public void onEndTick(Minecraft minecraft) {
					if (!shown && minecraft.screen instanceof TitleScreen) {
						shown = true;
						minecraft.setScreen(new JavaUpgradeScreen(minecraft.screen));
					}
				}
			});
		}
	}

	private static class CustomResourcesWrapper implements SimpleSynchronousResourceReloadListener {

		@Override
		public ResourceLocation getFabricId() {
			return new ResourceLocation(MTR.MOD_ID, CUSTOM_RESOURCES_ID);
		}

		@Override
		public void onResourceManagerReload(ResourceManager resourceManager) {
			CustomResources.reload(resourceManager);
		}
	}
}
