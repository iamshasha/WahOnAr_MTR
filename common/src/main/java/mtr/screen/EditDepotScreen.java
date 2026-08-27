package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.*;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataGuiClient;
import mtr.render.RenderPathFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class EditDepotScreen extends EditNameColorScreenBase<Depot> {

	private final int sliderX;
	private final int sliderWidthWithText;
	private final int rightPanelsX;
	private final boolean showScheduleControls;
	private final boolean showCruisingAltitude;
	private final Map<Long, Siding> sidingsInDepot;

	private final Button buttonUseRealTime;
	private final Button buttonReset;
	private final WidgetShorterSlider[] sliders = new WidgetShorterSlider[Depot.HOURS_IN_DAY];
	private final WidgetBetterTextField textFieldDeparture;
	private final WidgetShorterSlider sliderDepartureHour;
	private final WidgetShorterSlider sliderDepartureMinute;
	private final WidgetShorterSlider sliderDepartureCount;
	private final WidgetShorterSlider sliderDepartureInterval;
	private String composedDeparture = "";
	private final Button buttonAddDeparture;

	private final Button buttonEditInstructions;
	private final Button buttonGenerateRoute;
	private final Button buttonClearTrains;
	private final Button buttonTeleportToFailure;
	private final WidgetBetterCheckbox checkboxRepeatIndefinitely;
	private final WidgetBetterCheckbox checkboxStrictTimetable;
	private final WidgetBetterTextField textFieldCruisingAltitude;
	private final WidgetShorterSlider sliderMaxTrainCars;
	private final WidgetShorterSlider sliderStablingThreshold;
	private final DashboardList departuresList;

	private final Component cruisingAltitudeText = Text.translatable("gui.mtr.cruising_altitude");
	private final Component maxTrainCarsText = Text.translatable("gui.mtr.max_train_cars");
	private final Component stablingThresholdText = Text.translatable("gui.mtr.stabling_threshold");

	private static final int PANELS_START = SQUARE_SIZE * 2 + TEXT_FIELD_PADDING;
	private static final int SLIDER_WIDTH = 64;
	private static final int MAX_TRAINS_PER_HOUR = 5;
	private static final int SECONDS_PER_MC_HOUR = Depot.TICKS_PER_HOUR / 20;

	public EditDepotScreen(Depot depot, TransportMode transportMode, DashboardScreen dashboardScreen) {
		super(depot, dashboardScreen, "gui.mtr.depot_name", "gui.mtr.depot_color");

		sidingsInDepot = ClientData.DATA_CACHE.requestDepotIdToSidings(depot.id);

		font = Minecraft.getInstance().font;
		sliderX = font.width(getTimeString(0)) + TEXT_PADDING * 2;
		sliderWidthWithText = SLIDER_WIDTH + TEXT_PADDING + font.width(getSliderString(0));
		rightPanelsX = sliderX + SLIDER_WIDTH + TEXT_PADDING * 2 + font.width(getSliderString(1));
		showScheduleControls = !transportMode.continuousMovement;
		showCruisingAltitude = transportMode == TransportMode.AIRPLANE;

		buttonUseRealTime = UtilitiesClient.newButton(Text.translatable("gui.mtr.schedule_mode_real_time_off"), button -> {
			depot.useRealTime = !depot.useRealTime;
			toggleRealTime();
			saveData();
		});
		buttonReset = UtilitiesClient.newButton(Text.translatable("gui.mtr.reset_sign"), button -> {
			for (int i = 0; i < Depot.HOURS_IN_DAY; i++) {
				sliders[i].setValue(0);
			}
			data.departures.clear();
			updateList();
			saveData();
		});

		for (int i = 0; i < Depot.HOURS_IN_DAY; i++) {
			final int currentIndex = i;
			sliders[currentIndex] = new WidgetShorterSlider(sliderX, SLIDER_WIDTH, MAX_TRAINS_PER_HOUR * 2, EditDepotScreen::getSliderString, value -> {
				for (int j = 0; j < Depot.HOURS_IN_DAY; j++) {
					if (j != currentIndex) {
						sliders[j].setValue(value);
					}
				}
			});
		}
		departuresList = new DashboardList(null, null, null, null, null, this::onDeleteDeparture, null, () -> "", text -> {
		});

		textFieldDeparture = new WidgetBetterTextField("[^\\d:+* ]", "07:10:00 + 10 * 00:03:00", 25);
		// The departure syntax is "HH:MM:SS + N * HH:MM:SS" and is documented nowhere, so these four sliders write it
		// into the field rather than replacing it: what they compose stays visible and editable, which teaches the syntax.
		sliderDepartureHour = new WidgetShorterSlider(0, 0, 23, value -> String.format("%02d", value), null);
		sliderDepartureMinute = new WidgetShorterSlider(0, 0, 59, value -> String.format("%02d", value), null);
		sliderDepartureCount = new WidgetShorterSlider(0, 0, 59, value -> "x" + (value + 1), null);
		sliderDepartureInterval = new WidgetShorterSlider(0, 0, 119, value -> (value + 1) + "m", null);
		buttonAddDeparture = UtilitiesClient.newButton(Text.literal("+"), button -> {
			checkDeparture(textFieldDeparture.getValue(), true, false);
			saveData();
		});

		buttonEditInstructions = UtilitiesClient.newButton(Text.translatable("gui.mtr.edit_instructions"), button -> {
			if (minecraft != null) {
				saveData();
				final List<NameColorDataBase> routes = new ArrayList<>(ClientData.getFilteredDataSet(transportMode, ClientData.ROUTES));
				Collections.sort(routes);
				UtilitiesClient.setScreen(minecraft, new DashboardListSelectorScreen(this, routes, data.routeIds, false, true));
			}
		});
		buttonGenerateRoute = UtilitiesClient.newButton(Text.translatable("gui.mtr.refresh_path"), button -> {
			saveData();
			depot.clientPathGenerationSuccessfulSegments = -1;
			PacketTrainDataGuiClient.generatePathC2S(depot.id);
		});
		buttonClearTrains = UtilitiesClient.newButton(Text.translatable("gui.mtr.clear_vehicles"), button -> {
			sidingsInDepot.values().forEach(Siding::clearTrains);
			PacketTrainDataGuiClient.clearTrainsC2S(depot.id, sidingsInDepot.values());
		});
		buttonTeleportToFailure = UtilitiesClient.newButton(Text.translatable("gui.mtr.teleport_to_path_failure"), button -> PacketTrainDataGuiClient.teleportToPathFailureC2S(depot.id, depot.clientPathGenerationSuccessfulSegments));
		checkboxRepeatIndefinitely = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.repeat_indefinitely"), button -> {
			saveData();
			depot.clientPathGenerationSuccessfulSegments = -1;
			PacketTrainDataGuiClient.generatePathC2S(depot.id);
		});
		checkboxStrictTimetable = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.strict_timetable"), button -> saveData());
		textFieldCruisingAltitude = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.INTEGER, String.valueOf(Depot.DEFAULT_CRUISING_ALTITUDE), 5);
		sliderMaxTrainCars = new WidgetShorterSlider(0, SLIDER_WIDTH, Depot.MAX_TRAIN_CARS_LIMIT, EditDepotScreen::getMaxTrainCarsString, null);
		sliderStablingThreshold = new WidgetShorterSlider(0, SLIDER_WIDTH, Depot.MAX_STABLING_THRESHOLD_MINUTES - 1, EditDepotScreen::getStablingThresholdString, null);
	}

	@Override
	protected void init() {
		setPositionsAndInit(rightPanelsX, width / 4 * 3, width);

		final int buttonWidth = (width - rightPanelsX) / 2;
		IDrawing.setPositionAndWidth(buttonEditInstructions, rightPanelsX, PANELS_START, buttonWidth * 2);
		IDrawing.setPositionAndWidth(buttonGenerateRoute, rightPanelsX, PANELS_START + SQUARE_SIZE, buttonWidth * (showScheduleControls ? 1 : 2));
		IDrawing.setPositionAndWidth(buttonClearTrains, rightPanelsX + buttonWidth, PANELS_START + SQUARE_SIZE, buttonWidth);
		IDrawing.setPositionAndWidth(buttonTeleportToFailure, rightPanelsX, PANELS_START + SQUARE_SIZE * 2, buttonWidth * 2);
		IDrawing.setPositionAndWidth(checkboxRepeatIndefinitely, rightPanelsX, PANELS_START + SQUARE_SIZE * 2 + (showCruisingAltitude ? SQUARE_SIZE + TEXT_FIELD_PADDING : 0), buttonWidth * 2);
		checkboxRepeatIndefinitely.setChecked(data.repeatInfinitely);
		IDrawing.setPositionAndWidth(checkboxStrictTimetable, rightPanelsX, PANELS_START + SQUARE_SIZE * 3 + (showCruisingAltitude ? SQUARE_SIZE + TEXT_FIELD_PADDING : 0), buttonWidth * 2);
		checkboxStrictTimetable.setChecked(data.strictTimetable);
		sliderStablingThreshold.setValue(data.stablingThresholdMinutes - 1);

		final int cruisingAltitudeTextWidth = font.width(cruisingAltitudeText) + TEXT_PADDING * 2;
		IDrawing.setPositionAndWidth(textFieldCruisingAltitude, rightPanelsX + Math.min(cruisingAltitudeTextWidth, buttonWidth * 2 - SQUARE_SIZE * 3) + TEXT_FIELD_PADDING / 2, PANELS_START + SQUARE_SIZE * 2 + TEXT_FIELD_PADDING / 2, SQUARE_SIZE * 3 - TEXT_FIELD_PADDING);
		textFieldCruisingAltitude.setValue(String.valueOf(data.cruisingAltitude));

		if (showScheduleControls) {
			for (WidgetShorterSlider slider : sliders) {
				addDrawableChild(slider);
			}
		}
		for (int i = 0; i < Depot.HOURS_IN_DAY; i++) {
			sliders[i].setValue(data.getFrequency(i));
		}

		final int leftWidth = rightPanelsX - 1;
		IDrawing.setPositionAndWidth(buttonUseRealTime, 0, 0, leftWidth - SQUARE_SIZE * 3);
		IDrawing.setPositionAndWidth(buttonReset, leftWidth - SQUARE_SIZE * 3, 0, SQUARE_SIZE * 3);

		departuresList.y = SQUARE_SIZE;
		departuresList.height = height - SQUARE_SIZE * 3 - TEXT_FIELD_PADDING;
		departuresList.width = leftWidth;
		departuresList.init(this::addDrawableChild);

		IDrawing.setPositionAndWidth(textFieldDeparture, TEXT_FIELD_PADDING / 2, height - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, leftWidth - TEXT_FIELD_PADDING - SQUARE_SIZE);
		final int builderY = height - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING / 2;
		final int builderWidth = leftWidth / 4;
		IDrawing.setPositionAndWidth(sliderDepartureHour, 0, builderY, builderWidth);
		IDrawing.setPositionAndWidth(sliderDepartureMinute, builderWidth, builderY, builderWidth);
		IDrawing.setPositionAndWidth(sliderDepartureCount, builderWidth * 2, builderY, builderWidth);
		IDrawing.setPositionAndWidth(sliderDepartureInterval, builderWidth * 3, builderY, leftWidth - builderWidth * 3);
		for (final WidgetShorterSlider slider : new WidgetShorterSlider[]{sliderDepartureHour, sliderDepartureMinute, sliderDepartureCount, sliderDepartureInterval}) {
			slider.setHeight(SQUARE_SIZE);
			addDrawableChild(slider);
		}

		addDrawableChild(textFieldDeparture);
		textFieldDeparture.setResponder(text -> buttonAddDeparture.active = checkDeparture(text, false, false));
		IDrawing.setPositionAndWidth(buttonAddDeparture, leftWidth - SQUARE_SIZE, height - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, SQUARE_SIZE);
		addDrawableChild(buttonAddDeparture);
		buttonAddDeparture.active = false;

		addDrawableChild(buttonEditInstructions);
		addDrawableChild(buttonGenerateRoute);
		if (showScheduleControls) {
			addDrawableChild(buttonUseRealTime);
			addDrawableChild(buttonReset);
			addDrawableChild(buttonClearTrains);
			addDrawableChild(buttonTeleportToFailure);
			addDrawableChild(checkboxRepeatIndefinitely);
			addDrawableChild(checkboxStrictTimetable);
			addDrawableChild(sliderStablingThreshold);
		}
		final int maxTrainCarsLabelWidth = font.width(maxTrainCarsText) + TEXT_PADDING * 2;
		// WidgetShorterSlider draws its value just past its own right edge, so leave room for the widest reading
		// or the number lands outside the panel and cannot be seen at all
		final int maxTrainCarsValueWidth = TEXT_PADDING + Math.max(
				font.width(getMaxTrainCarsString(0)),
				font.width(getMaxTrainCarsString(Depot.MAX_TRAIN_CARS_LIMIT))
		);
		IDrawing.setPositionAndWidth(sliderMaxTrainCars, rightPanelsX + maxTrainCarsLabelWidth, 0, Math.max(SLIDER_WIDTH, buttonWidth * 2 - maxTrainCarsLabelWidth - maxTrainCarsValueWidth));
		final int stablingLabelWidth = font.width(stablingThresholdText) + TEXT_PADDING * 2;
		final int stablingValueWidth = TEXT_PADDING + Math.max(
				font.width(getStablingThresholdString(0)),
				font.width(getStablingThresholdString(Depot.MAX_STABLING_THRESHOLD_MINUTES - 1)));
		IDrawing.setPositionAndWidth(sliderStablingThreshold, rightPanelsX + stablingLabelWidth, 0, Math.max(SLIDER_WIDTH, buttonWidth * 2 - stablingLabelWidth - stablingValueWidth));
		sliderMaxTrainCars.setValue(data.maxTrainCars);
		addDrawableChild(sliderMaxTrainCars);

		if (showCruisingAltitude) {
			addDrawableChild(textFieldCruisingAltitude);
		}

		toggleRealTime();
	}

	@Override
	public void tick() {
		super.tick();
		buttonGenerateRoute.active = data.clientPathGenerationSuccessfulSegments >= 0;
		// Only offer it when there is actually a break to jump to
		final boolean pathBroken = data.clientPathGenerationSuccessfulSegments > 0 && !pathFullyGenerated();
		buttonTeleportToFailure.visible = pathBroken;
		// Leave the marker standing after the screen closes; finding the gap is the whole point of it
		if (pathBroken) {
			RenderPathFailure.setTarget(data.getPathFailurePos(ClientData.DATA_CACHE, data.clientPathGenerationSuccessfulSegments));
		} else if (data.clientPathGenerationSuccessfulSegments > 0) {
			RenderPathFailure.clearTarget();
		}
		departuresList.tick();
		textFieldDeparture.tick();
		textFieldCruisingAltitude.tick();

		for (int i = 0; i < Depot.HOURS_IN_DAY; i++) {
			data.setFrequency(sliders[i].getIntValue(), i);
		}

		data.maxTrainCars = sliderMaxTrainCars.getIntValue();

		final int intervalMinutes = sliderDepartureInterval.getIntValue() + 1;
		final int extraDepartures = sliderDepartureCount.getIntValue();
		final String newComposed = extraDepartures == 0
				? String.format("%02d:%02d:00", sliderDepartureHour.getIntValue(), sliderDepartureMinute.getIntValue())
				: String.format("%02d:%02d:00 + %d * %02d:%02d:00", sliderDepartureHour.getIntValue(), sliderDepartureMinute.getIntValue(), extraDepartures, intervalMinutes / 60, intervalMinutes % 60);
		if (!newComposed.equals(composedDeparture)) {
			composedDeparture = newComposed;
			textFieldDeparture.setValue(newComposed);
		}

		if (data.routeIds.isEmpty()) {
			checkboxRepeatIndefinitely.visible = false;
		} else {
			final Route firstRoute = ClientData.DATA_CACHE.routeIdMap.get(data.routeIds.get(0));
			final Route lastRoute = ClientData.DATA_CACHE.routeIdMap.get(data.routeIds.get(data.routeIds.size() - 1));
			checkboxRepeatIndefinitely.visible = firstRoute != null && lastRoute != null && !firstRoute.platformIds.isEmpty() && !lastRoute.platformIds.isEmpty() && firstRoute.getFirstPlatformId() == lastRoute.getLastPlatformId();
		}
		// A route that ends rather than loops still leaves its first platform on the clock, so this is no longer
		// tied to the repeat option
		checkboxStrictTimetable.visible = showScheduleControls;
		sliderStablingThreshold.visible = checkboxStrictTimetable.visible && checkboxStrictTimetable.selected() && checkboxRepeatIndefinitely.visible && checkboxRepeatIndefinitely.selected();
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			vLine(matrices, rightPanelsX - 1, -1, height, ARGB_WHITE_TRANSLUCENT);
			renderTextFields(matrices);

			if (showScheduleControls && data.useRealTime) {
				departuresList.render(matrices, font);
			}

			final int lineHeight = Math.min(SQUARE_SIZE, (height - SQUARE_SIZE * 2) / Depot.HOURS_IN_DAY);
			for (int i = 0; i < Depot.HOURS_IN_DAY; i++) {
				if (showScheduleControls && !data.useRealTime) {
					drawString(matrices, font, getTimeString(i), TEXT_PADDING, SQUARE_SIZE * 2 + lineHeight * i + (int) ((lineHeight - TEXT_HEIGHT) / 2F), ARGB_WHITE);
				}
				UtilitiesClient.setWidgetY(sliders[i], SQUARE_SIZE * 2 + lineHeight * i);
				sliders[i].setHeight(lineHeight);
			}

			super.render(matrices, mouseX, mouseY, delta);

			// The rows above can each be absent, so every following row is placed against a running count rather
			// than a fixed offset
			int row = checkboxRepeatIndefinitely.visible ? 3 : 2;
			final int cruisingOffset = showCruisingAltitude ? SQUARE_SIZE + TEXT_FIELD_PADDING : 0;
			if (checkboxStrictTimetable.visible) {
				UtilitiesClient.setWidgetY(checkboxStrictTimetable, PANELS_START + SQUARE_SIZE * row + cruisingOffset);
				row++;
			}
			if (sliderStablingThreshold.visible) {
				final int stablingY = PANELS_START + SQUARE_SIZE * row + cruisingOffset;
				UtilitiesClient.setWidgetY(sliderStablingThreshold, stablingY);
				sliderStablingThreshold.setHeight(SQUARE_SIZE);
				font.draw(matrices, stablingThresholdText, rightPanelsX + TEXT_PADDING, stablingY + (SQUARE_SIZE - TEXT_HEIGHT) / 2F, ARGB_WHITE);
				row++;
			}
			final int maxTrainCarsY = PANELS_START + SQUARE_SIZE * row + cruisingOffset;
			UtilitiesClient.setWidgetY(sliderMaxTrainCars, maxTrainCarsY);
			sliderMaxTrainCars.setHeight(SQUARE_SIZE);
			font.draw(matrices, maxTrainCarsText, rightPanelsX + TEXT_PADDING, maxTrainCarsY + (SQUARE_SIZE - TEXT_HEIGHT) / 2F, ARGB_WHITE);
			final int yStartRightPane = maxTrainCarsY + SQUARE_SIZE + TEXT_PADDING;
			if (showCruisingAltitude) {
				font.draw(matrices, cruisingAltitudeText, rightPanelsX + TEXT_PADDING, PANELS_START + SQUARE_SIZE * 2 + TEXT_PADDING + TEXT_FIELD_PADDING / 2F, ARGB_WHITE);
			}
			font.draw(matrices, Text.translatable("gui.mtr.sidings_in_depot", sidingsInDepot.size()), rightPanelsX + TEXT_PADDING, yStartRightPane, ARGB_WHITE);

			final Component text;
			data.generateTempDepartures(Minecraft.getInstance().level);
			final int nextDepartureMillis = data.getMillisUntilDeploy(1);
			if (nextDepartureMillis >= 0) {
				final long hour = TimeUnit.MILLISECONDS.toHours(nextDepartureMillis);
				final long minute = TimeUnit.MILLISECONDS.toMinutes(nextDepartureMillis) % 60;
				final long second = TimeUnit.MILLISECONDS.toSeconds(nextDepartureMillis) % 60;
				text = Text.translatable("gui.mtr.next_departure", String.format("%2s:%2s:%2s", hour, minute, second).replace(' ', '0'));
			} else {
				text = Text.translatable("gui.mtr.next_departure_none");
			}
			font.draw(matrices, text, rightPanelsX + TEXT_PADDING, yStartRightPane + SQUARE_SIZE, ARGB_WHITE);

			final String[] stringSplit = getSuccessfulSegmentsText().getString().split("\\|");
			for (int i = 0; i < stringSplit.length; i++) {
				font.draw(matrices, stringSplit[i], rightPanelsX + TEXT_PADDING, yStartRightPane + SQUARE_SIZE * 2 + (TEXT_HEIGHT + TEXT_PADDING) * i, ARGB_WHITE);
			}

			if (showScheduleControls && !data.useRealTime) {
				drawCenteredString(matrices, font, Text.translatable("gui.mtr.game_time"), sliderX / 2, SQUARE_SIZE + TEXT_PADDING, ARGB_LIGHT_GRAY);
				drawCenteredString(matrices, font, Text.translatable("gui.mtr.vehicles_per_hour"), sliderX + sliderWidthWithText / 2, SQUARE_SIZE + TEXT_PADDING, ARGB_LIGHT_GRAY);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		departuresList.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		departuresList.mouseScrolled(mouseX, mouseY, amount);
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	protected void saveData() {
		super.saveData();
		data.repeatInfinitely = checkboxRepeatIndefinitely.visible && checkboxRepeatIndefinitely.selected();
		data.strictTimetable = checkboxStrictTimetable.visible && checkboxStrictTimetable.selected();
		data.stablingThresholdMinutes = sliderStablingThreshold.getIntValue() + 1;
		try {
			data.cruisingAltitude = Integer.parseInt(textFieldCruisingAltitude.getValue());
		} catch (Exception e) {
			e.printStackTrace();
			data.cruisingAltitude = Depot.DEFAULT_CRUISING_ALTITUDE;
		}
		data.setData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_DEPOT, packet));
	}

	private void toggleRealTime() {
		for (final WidgetShorterSlider slider : sliders) {
			slider.visible = !data.useRealTime;
		}
		departuresList.x = data.useRealTime ? 0 : width;
		UtilitiesClient.setWidgetX(textFieldDeparture, data.useRealTime ? TEXT_FIELD_PADDING / 2 : width);
		sliderDepartureHour.visible = data.useRealTime;
		sliderDepartureMinute.visible = data.useRealTime;
		sliderDepartureCount.visible = data.useRealTime;
		sliderDepartureInterval.visible = data.useRealTime;
		buttonAddDeparture.visible = data.useRealTime;
		buttonUseRealTime.setMessage(Text.translatable(data.useRealTime ? "gui.mtr.schedule_mode_real_time_on" : "gui.mtr.schedule_mode_real_time_off"));
		updateList();
	}

	private void onDeleteDeparture(NameColorDataBase data, int index) {
		checkDeparture(data.name, false, true);
		saveData();
	}

	private void updateList() {
		final List<NameColorDataBase> departureData = new ArrayList<>();
		final long offset = System.currentTimeMillis() / Depot.MILLISECONDS_PER_DAY * Depot.MILLISECONDS_PER_DAY;
		data.departures.stream().map(departure -> {
			final Calendar calendar = Calendar.getInstance();
			calendar.setTimeInMillis(departure + offset);
			return calendar;
		}).sorted(Comparator.comparingInt(calendar -> {
			final int hour = calendar.get(Calendar.HOUR_OF_DAY);
			final int minute = calendar.get(Calendar.MINUTE);
			final int second = calendar.get(Calendar.SECOND);
			return hour * 3600 + minute * 60 + second;
		})).forEach(calendar -> {
			final int hour = calendar.get(Calendar.HOUR_OF_DAY);
			final int minute = calendar.get(Calendar.MINUTE);
			final int second = calendar.get(Calendar.SECOND);
			departureData.add(new DataConverter(String.format("%2s:%2s:%2s", hour, minute, second).replace(' ', '0'), 0));
		});
		departuresList.setData(departureData, false, false, false, false, false, true);
		data.generateTempDepartures(Minecraft.getInstance().level);
	}

	private boolean checkDeparture(String text, boolean addToList, boolean removeFromList) {
		try {
			final String[] departureSplit = text.replace(" ", "").split("\\+");
			final String[] timeSplit1 = departureSplit[0].split(":");
			final Calendar calendar = Calendar.getInstance();
			calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(timeSplit1[0]) % 24);
			calendar.set(Calendar.MINUTE, Integer.parseInt(timeSplit1[1]) % 60);
			calendar.set(Calendar.SECOND, Integer.parseInt(timeSplit1[2]) % 60);
			calendar.set(Calendar.MILLISECOND, 0);
			final int departure = (int) (calendar.getTimeInMillis() % Depot.MILLISECONDS_PER_DAY);
			final int multiple;
			final int interval;

			if (departureSplit.length > 1) {
				final String[] intervalSplit = departureSplit[1].split("\\*");
				multiple = Integer.parseInt(intervalSplit[0]) + 1;
				final String[] timeSplit2 = intervalSplit[1].split(":");
				interval = (Integer.parseInt(timeSplit2[0]) * 3600 + Integer.parseInt(timeSplit2[1]) * 60 + Integer.parseInt(timeSplit2[2])) * 1000;
			} else {
				multiple = 1;
				interval = 0;
			}

			if (addToList || removeFromList) {
				for (int i = 0; i < multiple; i++) {
					final int rawDeparture = (departure + i * interval) % Depot.MILLISECONDS_PER_DAY;
					if (addToList) {
						if (!data.departures.contains(rawDeparture)) {
							data.departures.add(rawDeparture);
						}
					} else {
						data.departures.remove((Integer) rawDeparture);
					}
				}
				updateList();
			}

			return true;
		} catch (Exception ignored) {
		}

		return false;
	}

	/** Whether the last generation reached the end of the route, mirroring the check in getSuccessfulSegmentsText. */
	private boolean pathFullyGenerated() {
		int sum = 0;
		for (int i = 0; i < data.routeIds.size(); i++) {
			final Route thisRoute = ClientData.DATA_CACHE.routeIdMap.get(data.routeIds.get(i));
			final Route nextRoute = i < data.routeIds.size() - 1 ? ClientData.DATA_CACHE.routeIdMap.get(data.routeIds.get(i + 1)) : null;
			if (thisRoute != null) {
				sum += thisRoute.platformIds.size();
				if (!thisRoute.platformIds.isEmpty() && nextRoute != null && !nextRoute.platformIds.isEmpty() && thisRoute.getLastPlatformId() == nextRoute.getFirstPlatformId()) {
					sum--;
				}
			}
		}
		return data.clientPathGenerationSuccessfulSegments >= sum + 2;
	}

	private Component getSuccessfulSegmentsText() {
		final int successfulSegments = data.clientPathGenerationSuccessfulSegments;

		if (successfulSegments < 0) {
			return Text.translatable("gui.mtr.generating_path");
		} else if (successfulSegments == 0) {
			return Text.translatable("gui.mtr.path_not_generated");
		} else {
			final List<String> stationNames = new ArrayList<>();
			final List<String> routeNames = new ArrayList<>();
			final String depotName = IGui.textOrUntitled(IGui.formatStationName(data.name));

			if (successfulSegments == 1) {
				RailwayData.useRoutesAndStationsFromIndex(0, data.routeIds, ClientData.DATA_CACHE, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
					stationNames.add(IGui.textOrUntitled(thisStation == null ? "" : IGui.formatStationName(thisStation.name)));
					routeNames.add(IGui.textOrUntitled(thisRoute == null ? "" : IGui.formatStationName(thisRoute.name)));
				});
				stationNames.add("-");
				routeNames.add("-");

				return Text.translatable("gui.mtr.path_not_found_between", routeNames.get(0), depotName, stationNames.get(0));
			} else {
				int sum = 0;
				for (int i = 0; i < data.routeIds.size(); i++) {
					final Route thisRoute = ClientData.DATA_CACHE.routeIdMap.get(data.routeIds.get(i));
					final Route nextRoute = i < data.routeIds.size() - 1 ? ClientData.DATA_CACHE.routeIdMap.get(data.routeIds.get(i + 1)) : null;
					if (thisRoute != null) {
						sum += thisRoute.platformIds.size();
						if (!thisRoute.platformIds.isEmpty() && nextRoute != null && !nextRoute.platformIds.isEmpty() && thisRoute.getLastPlatformId() == nextRoute.getFirstPlatformId()) {
							sum--;
						}
					}
				}

				if (successfulSegments >= sum + 2) {
					return Text.translatable("gui.mtr.path_found");
				} else {
					RailwayData.useRoutesAndStationsFromIndex(successfulSegments - 2, data.routeIds, ClientData.DATA_CACHE, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
						stationNames.add(IGui.textOrUntitled(thisStation == null ? "" : IGui.formatStationName(thisStation.name)));
						if (nextStation == null) {
							RailwayData.useRoutesAndStationsFromIndex(successfulSegments - 1, data.routeIds, ClientData.DATA_CACHE, (currentStationIndex1, thisRoute1, nextRoute1, thisStation1, nextStation1, lastStation1) -> stationNames.add(IGui.textOrUntitled(thisStation1 == null ? "" : IGui.formatStationName(thisStation1.name))));
						} else {
							stationNames.add(IGui.textOrUntitled(IGui.formatStationName(nextStation.name)));
						}
						routeNames.add(IGui.textOrUntitled(IGui.formatStationName(thisRoute.name)));
					});
					stationNames.add("-");
					stationNames.add("-");
					routeNames.add("-");

					if (successfulSegments < sum + 1) {
						return Text.translatable("gui.mtr.path_not_found_between", routeNames.get(0), stationNames.get(0), stationNames.get(1));
					} else {
						return Text.translatable("gui.mtr.path_not_found_between", routeNames.get(0), stationNames.get(0), depotName);
					}
				}
			}
		}
	}

	private static String getStablingThresholdString(int value) {
		return Text.translatable("gui.mtr.stabling_threshold_value", value + 1).getString();
	}

	private static String getMaxTrainCarsString(int value) {
		return value <= 0 ? Text.translatable("gui.mtr.max_train_cars_unlimited").getString() : Text.translatable("gui.mtr.max_train_cars_value", value).getString();
	}

	private static String getSliderString(int value) {
		final String headwayText;
		if (value == 0) {
			headwayText = "";
		} else {
			headwayText = " (" + RailwayData.round((float) Depot.TRAIN_FREQUENCY_MULTIPLIER * SECONDS_PER_MC_HOUR / value, 1) + Text.translatable("gui.mtr.s").getString() + ")";
		}
		return value / (float) Depot.TRAIN_FREQUENCY_MULTIPLIER + Text.translatable("gui.mtr.tph").getString() + headwayText;
	}

	private static String getTimeString(int hour) {
		final String hourString = StringUtils.leftPad(String.valueOf(hour), 2, "0");
		return String.format("%s:00-%s:59", hourString, hourString);
	}
}
