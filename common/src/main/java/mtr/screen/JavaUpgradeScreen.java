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
 * From 3.5.1 there is no way past it. The second button quits the game rather than dismissing the screen, escape
 * does nothing, and closing it puts it straight back. That is the deadline arriving: a screen that can be clicked
 * away is a screen that gets clicked away, and the version after this one does not show a screen at all -- it
 * refuses to load. Meeting the crash without having first met this would be the unkind order to do it in.
 *
 * It stops entirely the moment Java is upgraded, which is the point.
 */
public class JavaUpgradeScreen extends ScreenMapper implements IGui {

	private final Button buttonGuide;
	private final Button buttonQuit;
	private final List<FormattedCharSequence> body = new ArrayList<>();

	private static final int BUTTON_WIDTH = 200;
	private static final int MAX_TEXT_WIDTH = 340;
	private static final int LINE_HEIGHT = 12;

	public JavaUpgradeScreen() {
		super(Text.translatable("gui.mtr.java_upgrade_title"));
		buttonGuide = UtilitiesClient.newButton(Text.translatable("gui.mtr.java_upgrade_guide"), button -> Util.getPlatform().openUri(JavaVersionNotice.GUIDE_URL));
		// Quits rather than dismisses. The screen has nothing behind it that can be reached on this Java version,
		// so offering a way back to the title screen would only be offering a way to pretend this did not happen.
		buttonQuit = UtilitiesClient.newButton(Text.translatable("gui.mtr.java_upgrade_quit"), button -> {
			if (minecraft != null) {
				minecraft.stop();
			}
		});
	}

	@Override
	protected void init() {
		super.init();

		body.clear();
		// Wrapped here rather than at construction because the width is not known until the screen has a size
		body.addAll(font.split(Text.translatable("gui.mtr.java_upgrade_needed",
				JavaVersionNotice.currentVersion(), JavaVersionNotice.REQUIRED_FOR_MTR_4), Math.min(width - SQUARE_SIZE * 2, MAX_TEXT_WIDTH)));

		IDrawing.setPositionAndWidth(buttonGuide, (width - BUTTON_WIDTH) / 2, height - SQUARE_SIZE * 4, BUTTON_WIDTH);
		IDrawing.setPositionAndWidth(buttonQuit, (width - BUTTON_WIDTH) / 2, height - SQUARE_SIZE * 2, BUTTON_WIDTH);
		addDrawableChild(buttonGuide);
		addDrawableChild(buttonQuit);
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
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void onClose() {
		// Anything that closes this screen -- a keybind, another mod, the game itself -- gets it back. The only
		// ways out are the launcher and the quit button.
		if (minecraft != null) {
			minecraft.setScreen(this);
		}
	}
}
