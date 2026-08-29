package mtr;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.CustomResources;
import mtr.client.ICustomResources;
import mtr.render.RenderDrivingOverlay;
import mtr.render.RenderTrains;
import mtr.screen.JavaUpgradeScreen;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
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
			if (JavaVersionNotice.REFUSES_TO_LOAD) {
				// No screen at this point and nowhere to put one, so the crash report is the message
				throw new IllegalStateException(JavaVersionNotice.refusalMessage());
			}

			// Waits for the title screen rather than showing it from here: at mod initialization there is no screen to
			// return to, and the game is still assembling itself. It is then put back whenever anything else takes the
			// screen, because from 3.5.1 there is no way past it -- the check stays registered for the whole session
			// rather than firing once.
			ClientTickEvents.END_CLIENT_TICK.register(new ClientTickEvents.EndTick() {

				private boolean announced;

				@Override
				public void onEndTick(Minecraft minecraft) {
					if (minecraft.screen instanceof TitleScreen) {
						if (!announced) {
							announced = true;
							// Said once, so a support report can be checked against a log rather than against a
							// memory of whether a screen appeared
							System.out.println("MTR: " + JavaVersionNotice.refusalMessage());
						}
						minecraft.setScreen(new JavaUpgradeScreen());
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
