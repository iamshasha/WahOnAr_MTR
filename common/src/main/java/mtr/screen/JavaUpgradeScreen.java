package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.JavaVersionNotice;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.Util;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Says once, at launch, that MTR 4 will need Java 21, and then gets out of the way.
 *
 * Shown here rather than in chat because chat is the wrong place for it. A line in chat scrolls away in seconds,
 * arrives when someone is already playing and disinclined to go and restart, and is missed entirely by anyone who
 * has chat hidden. This is something to act on in a launcher, before joining, so it is said before the game is
 * joined at all.
 *
 * It is dismissed with one button and does not remember having been shown. That is deliberate: it costs a click
 * per launch and stops entirely the moment Java is upgraded, which is the point. Nothing in the MTR 3 line needs
 * Java 21, so there is nothing behind this screen to gate.
 */
public class JavaUpgradeScreen extends ScreenMapper implements IGui {

	private final Screen previousScreen;
	private final Button buttonGuide;
	private final Button buttonContinue;
	private final List<FormattedCharSequence> body = new ArrayList<>();

	private static final int BUTTON_WIDTH = 200;
	private static final int MAX_TEXT_WIDTH = 340;
	private static final int LINE_HEIGHT = 12;

	public JavaUpgradeScreen(Screen previousScreen) {
		super(Text.translatable("gui.mtr.java_upgrade_title"));
		this.previousScreen = previousScreen;
		buttonGuide = UtilitiesClient.newButton(Text.translatable("gui.mtr.java_upgrade_guide"), button -> Util.getPlatform().openUri(JavaVersionNotice.GUIDE_URL));
		buttonContinue = UtilitiesClient.newButton(Text.translatable("gui.mtr.java_upgrade_continue"), button -> onClose());
	}

	@Override
	protected void init() {
		super.init();

		body.clear();
		// Wrapped here rather than at construction because the width is not known until the screen has a size
		body.addAll(font.split(Text.translatable("gui.mtr.java_upgrade_needed",
				JavaVersionNotice.currentVersion(), JavaVersionNotice.REQUIRED_FOR_MTR_4), Math.min(width - SQUARE_SIZE * 2, MAX_TEXT_WIDTH)));

		IDrawing.setPositionAndWidth(buttonGuide, (width - BUTTON_WIDTH) / 2, height - SQUARE_SIZE * 4, BUTTON_WIDTH);
		IDrawing.setPositionAndWidth(buttonContinue, (width - BUTTON_WIDTH) / 2, height - SQUARE_SIZE * 2, BUTTON_WIDTH);
		addDrawableChild(buttonGuide);
		addDrawableChild(buttonContinue);
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);

			final int textTop = (height - body.size() * LINE_HEIGHT) / 2;
			drawCenteredString(matrices, font, title, width / 2, textTop - SQUARE_SIZE * 2, ARGB_WHITE);
			for (int i = 0; i < body.size(); i++) {
				drawCenteredString(matrices, font, body.get(i), width / 2, textTop + i * LINE_HEIGHT, ARGB_LIGHT_GRAY);
			}
			drawCenteredString(matrices, font, JavaVersionNotice.GUIDE_URL, width / 2, textTop + (body.size() + 1) * LINE_HEIGHT, ARGB_GRAY);

			super.render(matrices, mouseX, mouseY, delta);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onClose() {
		if (minecraft != null) {
			minecraft.setScreen(previousScreen);
		}
	}
}
