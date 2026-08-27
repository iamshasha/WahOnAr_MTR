package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.Keys;
import mtr.Patreon;
import mtr.client.ClientData;
import mtr.client.Config;
import mtr.client.IDrawing;
import mtr.client.XaeroWaypoints;
import mtr.data.IGui;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class ConfigScreen extends ScreenMapper implements IGui {

	private boolean useMTRFont;
	private boolean showAnnouncementMessages;
	private boolean useTTSAnnouncements;
	private boolean hideSpecialRailColors;
	private boolean hideTranslucentParts;
	private boolean shiftToToggleSitting;
	private int languageOptions;
	private boolean useDynamicFPS;
	private boolean useVehicleSway;
	private boolean useFrustumCulling;

	/** Nothing to offer when the minimap that would receive the waypoints is not installed, so the row is hidden. */
	private final boolean hasMinimap = XaeroWaypoints.isAvailable();

	private final boolean hasTimeAndWindControls;
	private final boolean useTimeAndWindSync;

	private final WidgetBetterCheckbox checkboxUseTimeAndWindSync;
	private final Button buttonUseMTRFont;
	private final Button buttonShowAnnouncementMessages;
	private final Button buttonUseTTSAnnouncements;
	private final Button buttonHideSpecialRailColors;
	private final Button buttonHideTranslucentParts;
	private final Button buttonShiftToToggleSitting;
	private final Button buttonLanguageOptions;
	private final Button buttonUseDynamicFPS;
	private final Button buttonUseVehicleSway;
	private final Button buttonUseFrustumCulling;
	private final WidgetShorterSlider sliderTrackTextureOffset;
	private final WidgetShorterSlider sliderDynamicTextureResolution;
	private final WidgetShorterSlider sliderTrainRenderDistanceRatio;
	private final Button buttonAddStationsToMinimap;
	private final Button buttonSupportPatreon;

	/**
	 * Where each option's label was drawn this frame, so hovering it can explain what it does.
	 *
	 * Rows are laid out by a running counter rather than held as widgets, so the only place that knows where an
	 * option ended up is the draw itself. Collecting as it draws keeps the two from drifting apart.
	 */
	private final List<TooltipRow> tooltipRows = new ArrayList<>();

	private static final int BUTTON_WIDTH = 60;
	private static final int BUTTON_HEIGHT = TEXT_HEIGHT + TEXT_PADDING;

	public ConfigScreen() {
		this(false, false);
	}

	public ConfigScreen(boolean useTimeAndWindSync) {
		this(true, useTimeAndWindSync);
	}

	private ConfigScreen(boolean hasTimeAndWindControls, boolean useTimeAndWindSync) {
		super(Text.literal(""));

		this.hasTimeAndWindControls = hasTimeAndWindControls && ClientData.hasPermission();
		this.useTimeAndWindSync = useTimeAndWindSync;

		checkboxUseTimeAndWindSync = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.use_time_and_wind_sync"), PacketTrainDataGuiClient::sendUseTimeAndWindSyncC2S);

		buttonUseMTRFont = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			useMTRFont = Config.setUseMTRFont(!useMTRFont);
			setButtonText(button, useMTRFont);
		});
		buttonShowAnnouncementMessages = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			showAnnouncementMessages = Config.setShowAnnouncementMessages(!showAnnouncementMessages);
			setButtonText(button, showAnnouncementMessages);
		});
		buttonUseTTSAnnouncements = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			useTTSAnnouncements = Config.setUseTTSAnnouncements(!useTTSAnnouncements);
			setButtonText(button, useTTSAnnouncements);
		});
		buttonHideSpecialRailColors = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			hideSpecialRailColors = Config.setHideSpecialRailColors(!hideSpecialRailColors);
			setButtonText(button, hideSpecialRailColors);
		});
		buttonHideTranslucentParts = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			hideTranslucentParts = Config.setHideTranslucentParts(!hideTranslucentParts);
			setButtonText(button, hideTranslucentParts);
		});
		buttonShiftToToggleSitting = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			shiftToToggleSitting = Config.setShiftToToggleSitting(!shiftToToggleSitting);
			setButtonText(button, shiftToToggleSitting);
		});
		buttonLanguageOptions = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			languageOptions = Config.setLanguageOptions(languageOptions + 1);
			button.setMessage(Text.translatable("options.mtr.language_options_" + languageOptions));
		});
		buttonUseDynamicFPS = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			useDynamicFPS = Config.setUseDynamicFPS(!useDynamicFPS);
			setButtonText(button, useDynamicFPS);
		});
		buttonUseVehicleSway = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			useVehicleSway = Config.setUseVehicleSway(!useVehicleSway);
			setButtonText(button, useVehicleSway);
		});
		buttonUseFrustumCulling = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			useFrustumCulling = Config.setUseFrustumCulling(!useFrustumCulling);
			setButtonText(button, useFrustumCulling);
		});
		sliderTrackTextureOffset = new WidgetShorterSlider(0, 0, Config.TRACK_OFFSET_COUNT - 1, Object::toString, null);
		sliderDynamicTextureResolution = new WidgetShorterSlider(0, 0, Config.DYNAMIC_RESOLUTION_COUNT - 1, Object::toString, null);
		sliderTrainRenderDistanceRatio = new WidgetShorterSlider(0, 0, Config.TRAIN_RENDER_DISTANCE_RATIO_COUNT - 1, num -> String.format("%d%%", (num + 1) * 100 / Config.TRAIN_RENDER_DISTANCE_RATIO_COUNT), null);
		buttonAddStationsToMinimap = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			final int added = XaeroWaypoints.addAllStations();
			button.setMessage(Text.translatable(added < 0 ? "gui.mtr.minimap_failed" : "gui.mtr.minimap_added", Math.max(0, added)));
		});
		buttonSupportPatreon = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> Util.getPlatform().openUri("https://www.patreon.com/minecraft_transit_railway"));
	}

	@Override
	protected void init() {
		super.init();
		Config.refreshProperties();
		useMTRFont = Config.useMTRFont();
		showAnnouncementMessages = Config.showAnnouncementMessages();
		useTTSAnnouncements = Config.useTTSAnnouncements();
		hideSpecialRailColors = Config.hideSpecialRailColors();
		hideTranslucentParts = Config.hideTranslucentParts();
		shiftToToggleSitting = Config.shiftToToggleSitting();
		languageOptions = Config.languageOptions();
		useDynamicFPS = Config.useDynamicFPS();
		useVehicleSway = Config.useVehicleSway();
		useFrustumCulling = Config.useFrustumCulling();

		final int offsetY;
		if (hasTimeAndWindControls) {
			IDrawing.setPositionAndWidth(checkboxUseTimeAndWindSync, SQUARE_SIZE, SQUARE_SIZE, width);
			checkboxUseTimeAndWindSync.setChecked(useTimeAndWindSync);
			offsetY = SQUARE_SIZE;
		} else {
			offsetY = 0;
		}

		int i = 1;
		IDrawing.setPositionAndWidth(buttonUseMTRFont, width - SQUARE_SIZE - BUTTON_WIDTH, SQUARE_SIZE + offsetY, BUTTON_WIDTH);
		if (!Keys.LIFTS_ONLY) {
			IDrawing.setPositionAndWidth(buttonShowAnnouncementMessages, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonUseTTSAnnouncements, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonHideSpecialRailColors, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonHideTranslucentParts, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonShiftToToggleSitting, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonLanguageOptions, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonUseDynamicFPS, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonUseVehicleSway, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonUseFrustumCulling, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(sliderTrackTextureOffset, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH - TEXT_PADDING - font.width("100%"));
			IDrawing.setPositionAndWidth(sliderDynamicTextureResolution, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH - TEXT_PADDING - font.width("100%"));
			IDrawing.setPositionAndWidth(sliderTrainRenderDistanceRatio, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH - TEXT_PADDING - font.width("100%"));
			if (hasMinimap) {
				IDrawing.setPositionAndWidth(buttonAddStationsToMinimap, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
				buttonAddStationsToMinimap.setMessage(Text.translatable("gui.mtr.minimap_add"));
			}
		}
		IDrawing.setPositionAndWidth(buttonSupportPatreon, width - SQUARE_SIZE - BUTTON_WIDTH, BUTTON_HEIGHT * i + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
		setButtonText(buttonUseMTRFont, useMTRFont);
		setButtonText(buttonShowAnnouncementMessages, showAnnouncementMessages);
		setButtonText(buttonUseTTSAnnouncements, useTTSAnnouncements);
		setButtonText(buttonHideSpecialRailColors, hideSpecialRailColors);
		setButtonText(buttonHideTranslucentParts, hideTranslucentParts);
		setButtonText(buttonShiftToToggleSitting, shiftToToggleSitting);
		buttonLanguageOptions.setMessage(Text.translatable("options.mtr.language_options_" + languageOptions));
		setButtonText(buttonUseDynamicFPS, useDynamicFPS);
		setButtonText(buttonUseVehicleSway, useVehicleSway);
		setButtonText(buttonUseFrustumCulling, useFrustumCulling);
		sliderTrackTextureOffset.setHeight(BUTTON_HEIGHT);
		sliderTrackTextureOffset.setValue(Config.trackTextureOffset());
		sliderDynamicTextureResolution.setHeight(BUTTON_HEIGHT);
		sliderDynamicTextureResolution.setValue(Config.dynamicTextureResolution());
		sliderTrainRenderDistanceRatio.setHeight(BUTTON_HEIGHT);
		sliderTrainRenderDistanceRatio.setValue(Config.trainRenderDistanceRatio());
		buttonSupportPatreon.setMessage(Text.translatable("gui.mtr.support"));

		if (hasTimeAndWindControls) {
			addDrawableChild(checkboxUseTimeAndWindSync);
		}
		addDrawableChild(buttonUseMTRFont);
		if (!Keys.LIFTS_ONLY) {
			addDrawableChild(buttonShowAnnouncementMessages);
			addDrawableChild(buttonUseTTSAnnouncements);
			addDrawableChild(buttonHideSpecialRailColors);
			addDrawableChild(buttonHideTranslucentParts);
			addDrawableChild(buttonShiftToToggleSitting);
			addDrawableChild(buttonLanguageOptions);
			addDrawableChild(buttonUseDynamicFPS);
			addDrawableChild(buttonUseVehicleSway);
			addDrawableChild(buttonUseFrustumCulling);
			addDrawableChild(sliderTrackTextureOffset);
			addDrawableChild(sliderDynamicTextureResolution);
			addDrawableChild(sliderTrainRenderDistanceRatio);
			if (hasMinimap) {
				addDrawableChild(buttonAddStationsToMinimap);
			}
		}
		addDrawableChild(buttonSupportPatreon);
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			drawCenteredString(matrices, font, Text.translatable("gui.mtr.mtr_options"), width / 2, TEXT_PADDING, ARGB_WHITE);

			tooltipRows.clear();
			if (hasTimeAndWindControls) {
				recordTooltipRow("gui.mtr.use_time_and_wind_sync", SQUARE_SIZE + TEXT_PADDING / 2);
			}

			final int yStart1 = SQUARE_SIZE + TEXT_PADDING / 2 + (hasTimeAndWindControls ? SQUARE_SIZE : 0);
			int i = 1;
			drawOption(matrices, "options.mtr.use_mtr_font", yStart1);
			if (!Keys.LIFTS_ONLY) {
				drawOption(matrices, "options.mtr.show_announcement_messages", BUTTON_HEIGHT * (i++) + yStart1);
				drawOption(matrices, "options.mtr.use_tts_announcements", BUTTON_HEIGHT * (i++) + yStart1);
				drawOption(matrices, "options.mtr.hide_special_rail_colors", BUTTON_HEIGHT * (i++) + yStart1);
				drawOption(matrices, "options.mtr.hide_translucent_parts", BUTTON_HEIGHT * (i++) + yStart1);
				final int shiftY = BUTTON_HEIGHT * (i++) + yStart1;
				drawString(matrices, font, Text.translatable("options.mtr.shift_to_toggle_sitting", minecraft == null ? "" : minecraft.options.keyShift.getTranslatedKeyMessage()), SQUARE_SIZE, shiftY, ARGB_WHITE);
				recordTooltipRow("options.mtr.shift_to_toggle_sitting", shiftY);
				drawOption(matrices, "options.mtr.language_options", BUTTON_HEIGHT * (i++) + yStart1);
				drawOption(matrices, "options.mtr.use_dynamic_fps", BUTTON_HEIGHT * (i++) + yStart1);
				drawOption(matrices, "options.mtr.use_vehicle_sway", BUTTON_HEIGHT * (i++) + yStart1);
				drawOption(matrices, "options.mtr.use_frustum_culling", BUTTON_HEIGHT * (i++) + yStart1);
				drawOption(matrices, "options.mtr.track_texture_offset", BUTTON_HEIGHT * (i++) + yStart1);
				drawOption(matrices, "options.mtr.dynamic_texture_resolution", BUTTON_HEIGHT * (i++) + yStart1);
				drawOption(matrices, "options.mtr.vehicle_render_distance_ratio", BUTTON_HEIGHT * (i++) + yStart1);
				if (hasMinimap) {
					drawOption(matrices, "options.mtr.add_stations_to_minimap", BUTTON_HEIGHT * (i++) + yStart1);
				}
			}
			drawString(matrices, font, Text.translatable("options.mtr.support_patreon"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);

			final int yStart2 = BUTTON_HEIGHT * (i + 1) + yStart1;
			String tierTitle = "";
			int y = 0;
			int x = 0;
			int maxWidth = 0;
			for (final Patreon patreon : Config.PATREON_LIST) {
				if (!patreon.tierTitle.equals(tierTitle)) {
					x += maxWidth + TEXT_PADDING;
					y = 0;
					final Component text = Text.literal(patreon.tierTitle);
					maxWidth = font.width(text);
					drawString(matrices, font, text, SQUARE_SIZE - TEXT_PADDING + x, yStart2, patreon.tierColor);
				} else if (y + yStart2 + TEXT_HEIGHT + SQUARE_SIZE > height) {
					x += maxWidth + TEXT_PADDING;
					y = 0;
					maxWidth = 0;
				}

				tierTitle = patreon.tierTitle;
				final Component text = patreon.tierAmount < 1000 ? Text.translatable("options.mtr.anonymous") : Text.literal(patreon.name);
				maxWidth = Math.max(maxWidth, font.width(text));
				drawString(matrices, font, text, SQUARE_SIZE - TEXT_PADDING + x, yStart2 + y + TEXT_HEIGHT + TEXT_PADDING, ARGB_LIGHT_GRAY);
				y += TEXT_HEIGHT + 2;
			}

			super.render(matrices, mouseX, mouseY, delta);
			renderOptionTooltip(matrices, mouseX, mouseY);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onClose() {
		super.onClose();
		Config.setTrackTextureOffset(sliderTrackTextureOffset.getIntValue());
		Config.setDynamicTextureResolution(sliderDynamicTextureResolution.getIntValue());
		Config.setTrainRenderDistanceRatio(sliderTrainRenderDistanceRatio.getIntValue());
		ClientData.DATA_CACHE.sync();
		ClientData.DATA_CACHE.refreshDynamicResources();
		ClientData.SIGNAL_BLOCKS.writeCache();
	}

	private void drawOption(PoseStack matrices, String key, int y) {
		drawString(matrices, font, Text.translatable(key), SQUARE_SIZE, y, ARGB_WHITE);
		recordTooltipRow(key, y);
	}

	private void recordTooltipRow(String key, int y) {
		tooltipRows.add(new TooltipRow(key, y));
	}

	/**
	 * Explains the option the cursor is over, if there is anything to say about it.
	 *
	 * A key with no matching tooltip translation resolves to the key itself, which would put a raw
	 * {@code options.mtr.something.tooltip} on screen. Options that have nothing worth adding simply go
	 * untranslated and are skipped here, so a missing line is silence rather than debug text.
	 */
	private void renderOptionTooltip(PoseStack matrices, int mouseX, int mouseY) {
		if (mouseX < SQUARE_SIZE || mouseX > width - SQUARE_SIZE) {
			return;
		}
		for (final TooltipRow row : tooltipRows) {
			if (mouseY < row.y - TEXT_PADDING / 2 || mouseY > row.y + TEXT_HEIGHT + TEXT_PADDING / 2) {
				continue;
			}
			final String tooltipKey = row.key + ".tooltip";
			final Component tooltip = Text.translatable(tooltipKey);
			if (tooltip.getString().equals(tooltipKey)) {
				return;
			}
			// Split rather than passed whole: a tooltip long enough to be useful would otherwise run off the screen
			renderTooltip(matrices, font.split(tooltip, Math.max(width / 2, 200)), mouseX, mouseY);
			return;
		}
	}

	private static class TooltipRow {

		private final String key;
		private final int y;

		private TooltipRow(String key, int y) {
			this.key = key;
			this.y = y;
		}
	}

	private static void setButtonText(Button button, boolean state) {
		button.setMessage(Text.translatable(state ? "options.mtr.on" : "options.mtr.off"));
	}
}
