package mtr.data;

import mtr.MTRClient;
import mtr.client.*;
import mtr.render.RenderDrivingOverlay;
import mtr.render.TrainRendererBase;
import mtr.sound.TrainSoundBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class TrainClient extends Train implements IGui {

	public boolean isRemoved = false;
	private boolean justMounted;
	private float oldSpeed;
	private double oldRailProgress;
	private float oldDoorValue;
	private boolean doorOpening;
	private boolean isSitting;
	private boolean previousShifting;

	private int currentStationIndex;
	private Route thisRoute;
	private Route nextRoute;
	private Station thisStation;
	private Station nextStation;
	private Station lastStation;

	private SpeedCallback speedCallback;
	private AnnouncementCallback announcementCallback;
	private AnnouncementCallback lightRailAnnouncementCallback;
	private Depot depot;
	private List<Long> routeIds = new ArrayList<>();

	public final TrainRendererBase trainRenderer;
	public final TrainSoundBase trainSound;
	public final VehicleRidingClient vehicleRidingClient = new VehicleRidingClient(ridingEntities, PACKET_UPDATE_TRAIN_PASSENGER_POSITION);
	public final List<ScrollingText> scrollingTexts = new ArrayList<>();

	// Keep this a Set<Runnable>: mtr-steamloco / MTR-ANTE @Shadow this field, and a @Shadow match is by
	// name AND descriptor, so changing the type to List makes the addon fail to apply and crash the game.
	private final Set<Runnable> trainTranslucentRenders = new HashSet<>();

	// Sway model, in two parts that add together.
	//
	// The lean is the lateral acceleration a car feels through a curve, which is speed times rate of turn.
	// The roll is the rocking a car does on its suspension simply from running, which happens on straight track too
	// and is what makes a moving train look alive rather than glued to the rail. Its phase advances with distance
	// travelled rather than with time, so it slows as the train slows and stops dead when the train does, and each
	// car is offset along that phase so a rake rocks in a travelling wave instead of moving as one rigid block.
	//
	// All of these are taste, not physics; they are named so they can be retuned against a real train.
	private static final float SWAY_STRENGTH = 26;
	private static final float MAX_SWAY = 0.13F;
	private static final float SWAY_RESPONSE = 0.12F;
	private static final float SWAY_PIVOT_HEIGHT = 1;
	private static final float SWAY_RUNNING_AMPLITUDE = 0.024F;
	private static final float SWAY_RUNNING_RATE = 1.6F;
	private static final float SWAY_RUNNING_FULL_SPEED = 0.6F;
	private static final float SWAY_CAR_PHASE_OFFSET = 0.7F;

	private float[] swayLean = new float[0];
	private float[] swayPreviousYaw = new float[0];
	private boolean swayPrimed;
	private float swayPhase;

	private static final float CONNECTION_HEIGHT = 2.25F;
	private static final float CONNECTION_Z_OFFSET = 0.5F;
	private static final float CONNECTION_X_OFFSET = 0.25F;

	public TrainClient(FriendlyByteBuf packet) {
		super(packet);
		final TrainProperties trainProperties = TrainClientRegistry.getTrainProperties(trainId);
		trainRenderer = trainProperties.renderer.createTrainInstance(this);
		trainSound = trainProperties.sound.createTrainInstance(this);
	}

	@Override
	protected void simulateCar(
			Level world, int ridingCar, float ticksElapsed,
			double carX, double carY, double carZ, float carYaw, float carPitch,
			double prevCarX, double prevCarY, double prevCarZ, float prevCarYaw, float prevCarPitch,
			boolean doorLeftOpen, boolean doorRightOpen, double realSpacing
	) {
		final LocalPlayer clientPlayer = Minecraft.getInstance().player;
		if (clientPlayer == null) {
			return;
		}

		final BlockPos soundPos = RailwayData.newBlockPos(carX, carY, carZ);
		trainSound.playAllCars(world, soundPos, ridingCar);
		if (doorLeftOpen || doorRightOpen) {
			trainSound.playAllCarsDoorOpening(world, soundPos, ridingCar);
		}

		final Vec3 offset = vehicleRidingClient.renderPlayerAndGetOffset();
		final double newX = carX - offset.x;
		final double newY = carY - offset.y;
		final double newZ = carZ - offset.z;

		doorOpening = doorValue > oldDoorValue;
		updateSway(ridingCar, carYaw, ticksElapsed);
		trainRenderer.renderCar(ridingCar, newX, newY, newZ, carYaw, carPitch, doorLeftOpen, doorRightOpen);
		if (!Config.hideTranslucentParts()) {
			// renderTranslucent (the only thing that drains this list) is skipped when translucent parts are hidden,
			// so queueing here regardless leaked one lambda per car per frame for the lifetime of the train
			trainTranslucentRenders.add(() -> trainRenderer.renderCar(ridingCar, newX, newY, newZ, carYaw, carPitch, doorLeftOpen, doorRightOpen));
		}

		if (ridingCar > 0) {
			final double newPrevCarX = prevCarX - offset.x;
			final double newPrevCarY = prevCarY - offset.y;
			final double newPrevCarZ = prevCarZ - offset.z;

			final Vec3 prevPos0 = new Vec3(0, 0, spacing / 2D - 1).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
			final Vec3 thisPos0 = new Vec3(0, 0, -(spacing / 2D - 1)).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
			final Vec3 connectPos = prevPos0.add(thisPos0).scale(0.5);
			final float connectYaw = (float) Mth.atan2(thisPos0.x - prevPos0.x, thisPos0.z - prevPos0.z);
			final float connectPitch = realSpacing == 0 ? 0 : (float) asin((thisPos0.y - prevPos0.y) / realSpacing);

			for (int i = 0; i < 2; i++) {
				final double xStart = width / 2D + (i == 0 ? -1 : 0.5) * CONNECTION_X_OFFSET;
				final double zStart = spacing / 2D - (i == 0 ? 1 : 2) * CONNECTION_Z_OFFSET;

				final Vec3 prevPos1 = new Vec3(xStart, SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
				final Vec3 prevPos2 = new Vec3(xStart, CONNECTION_HEIGHT + SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
				final Vec3 prevPos3 = new Vec3(-xStart, CONNECTION_HEIGHT + SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
				final Vec3 prevPos4 = new Vec3(-xStart, SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);

				final Vec3 thisPos1 = new Vec3(-xStart, SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
				final Vec3 thisPos2 = new Vec3(-xStart, CONNECTION_HEIGHT + SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
				final Vec3 thisPos3 = new Vec3(xStart, CONNECTION_HEIGHT + SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
				final Vec3 thisPos4 = new Vec3(xStart, SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);

				if (i == 0) {
					trainRenderer.renderConnection(prevPos1, prevPos2, prevPos3, prevPos4, thisPos1, thisPos2, thisPos3, thisPos4, connectPos.x, connectPos.y, connectPos.z, connectYaw, connectPitch);
				} else {
					trainRenderer.renderBarrier(prevPos1, prevPos2, prevPos3, prevPos4, thisPos1, thisPos2, thisPos3, thisPos4, connectPos.x, connectPos.y, connectPos.z, connectYaw, connectPitch);
				}
			}
		}
	}

	/**
	 * Leans each car by the lateral acceleration it is feeling. Rate of turn comes from this car's own yaw between
	 * ticks rather than from the neighbouring car, so the lead car banks too, and the result is smoothed towards its
	 * target so a sharp point in the path reads as a lean rather than a snap.
	 */
	private void updateSway(int carIndex, float carYaw, float ticksElapsed) {
		if (carIndex < 0) {
			return;
		}
		if (swayLean.length <= carIndex) {
			swayLean = Arrays.copyOf(swayLean, carIndex + 1);
			swayPreviousYaw = Arrays.copyOf(swayPreviousYaw, carIndex + 1);
			swayPrimed = false;
		}

		if (carIndex == 0) {
			// Advance once per pass over the rake, not once per car
			swayPhase += Math.abs(speed) * SWAY_RUNNING_RATE * ticksElapsed;
			swayPhase %= (float) (Math.PI * 2);
		}

		float yawDelta = carYaw - swayPreviousYaw[carIndex];
		swayPreviousYaw[carIndex] = carYaw;
		while (yawDelta > Math.PI) {
			yawDelta -= (float) (Math.PI * 2);
		}
		while (yawDelta < -Math.PI) {
			yawDelta += (float) (Math.PI * 2);
		}

		if (!swayPrimed) {
			// The first tick after a resize has no meaningful previous yaw; leaning on it would throw the car over
			swayPrimed = true;
			return;
		}

		final float target = ticksElapsed <= 0 ? swayLean[carIndex] : Mth.clamp(yawDelta / ticksElapsed * speed * SWAY_STRENGTH, -MAX_SWAY, MAX_SWAY);
		swayLean[carIndex] += (target - swayLean[carIndex]) * Math.min(1, SWAY_RESPONSE * Math.max(ticksElapsed, 1));
	}

	/**
	 * Roll in radians for one car: the curve lean plus the running rock, positive leaning into a right-hand curve.
	 * The running part is scaled by speed so a stationary train sits still.
	 */
	public float getSwayRoll(int carIndex) {
		if (carIndex < 0 || carIndex >= swayLean.length) {
			return 0;
		}
		return swayLean[carIndex] + getSwayRunningRoll(carIndex);
	}

	/**
	 * Just the running rock, without the lean into curves.
	 *
	 * For riding alongside an addon that banks the cars itself. Applying the curve lean on top of one that is
	 * already being applied rolls the train twice, but an addon that only leans into curves leaves a train on
	 * straight track dead still, which is the half worth keeping.
	 */
	public float getSwayRunningRoll(int carIndex) {
		if (carIndex < 0 || carIndex >= swayLean.length) {
			return 0;
		}
		final float runningFraction = Math.min(1, Math.abs(speed) / SWAY_RUNNING_FULL_SPEED);
		return Mth.sin(swayPhase + carIndex * SWAY_CAR_PHASE_OFFSET) * SWAY_RUNNING_AMPLITUDE * runningFraction;
	}

	public static float getSwayPivotHeight() {
		return SWAY_PIVOT_HEIGHT;
	}

	@Override
	protected boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed) {
		final Minecraft client = Minecraft.getInstance();
		final LocalPlayer clientPlayer = client.player;
		if (clientPlayer == null) {
			return false;
		}

		vehicleRidingClient.begin();

		if (ticksElapsed > 0) {
			if (isPlayerRiding(clientPlayer)) {
				final int headIndex = getIndex(0, spacing, false);
				final int stopIndex = path.get(headIndex).stopIndex - 1;

				if (speedCallback != null) {
					speedCallback.speedCallback(speed * 20, stopIndex, routeIds);
				}

				if (announcementCallback != null) {
					final double targetProgress = distances.get(getPreviousStoppingIndex(headIndex)) + (trainCars + 1) * spacing;
					if (oldRailProgress < targetProgress && railProgress >= targetProgress) {
						announcementCallback.announcementCallback(stopIndex, routeIds);
					}
				}

				if (lightRailAnnouncementCallback != null && (justOpening() || justMounted)) {
					lightRailAnnouncementCallback.announcementCallback(stopIndex, routeIds);
				}
			}

			final TrainProperties trainProperties = TrainClientRegistry.getTrainProperties(trainId);
			vehicleRidingClient.movePlayer(uuid -> {
				final CalculateCarCallback calculateCarCallback = (x, y, z, yaw, pitch, realSpacingRender, doorLeftOpenRender, doorRightOpenRender) -> vehicleRidingClient.setOffsets(uuid, x, y, z, yaw, pitch, transportMode.maxLength == 1 ? spacing : realSpacingRender, width, doorLeftOpenRender, doorRightOpenRender, transportMode.hasPitchAscending, transportMode.hasPitchDescending, trainProperties.riderOffset, trainProperties.riderOffsetDismounting, speed > 0, doorValue == 0, () -> {
					final boolean isShifting = clientPlayer.isShiftKeyDown();
					if (Config.shiftToToggleSitting() && !MTRClient.isVivecraft()) {
						if (isShifting && !previousShifting) {
							isSitting = !isSitting;
						}
						clientPlayer.setPose(isSitting && !client.gameRenderer.getMainCamera().isDetached() ? Pose.CROUCHING : Pose.STANDING);
					}
					previousShifting = isShifting;
				});

				final int currentRidingCar = Mth.clamp((int) Math.floor(vehicleRidingClient.getPercentageZ(uuid)), 0, positions.length - 2);
				calculateCar(world, positions, currentRidingCar, 0, (x, y, z, yaw, pitch, realSpacingRender, doorLeftOpenRender, doorRightOpenRender) -> {
					vehicleRidingClient.moveSelf(id, uuid, realSpacingRender, width, yaw, currentRidingCar, trainCars, doorLeftOpenRender, doorRightOpenRender, !trainProperties.hasGangwayConnection, ticksElapsed);

					final int newRidingCar = Mth.clamp((int) Math.floor(vehicleRidingClient.getPercentageZ(uuid)), 0, positions.length - 2);
					if (currentRidingCar == newRidingCar) {
						calculateCarCallback.calculateCarCallback(x, y, z, yaw, pitch, realSpacingRender, doorLeftOpenRender, doorRightOpenRender);
					} else {
						calculateCar(world, positions, newRidingCar, 0, calculateCarCallback);
					}
				});
			});
		}

		vehicleRidingClient.end();
		justMounted = false;

		final Entity camera = client.cameraEntity;
		final Vec3 cameraPos = camera == null ? Vec3.ZERO : camera.position();
		double nearestDistance = Double.POSITIVE_INFINITY;
		int nearestCar = 0;
		for (int i = 0; i < trainCars; i++) {
			final double checkDistance = cameraPos.distanceToSqr(positions[i]);
			if (checkDistance < nearestDistance) {
				nearestCar = i;
				nearestDistance = checkDistance;
			}
		}
		final BlockPos soundPos = RailwayData.newBlockPos(positions[nearestCar].x, positions[nearestCar].y, positions[nearestCar].z);
		trainSound.playNearestCar(world, soundPos, nearestCar);

		return true;
	}

	@Override
	protected boolean canDeploy(Depot depot) {
		return false;
	}

	@Override
	protected boolean isRailBlocked(int checkIndex) {
		return false;
	}

	@Override
	protected boolean skipScanBlocks(Level world, double trainX, double trainY, double trainZ) {
		// scanDoors is up to 450 getBlockState calls per side per car and it runs every frame, not every tick.
		// On the client its only consumer is calculateCar, which ANDs the result with doorValue > 0, and the
		// client's openDoors has no side effects - so with the doors shut the whole scan is thrown away.
		return doorValue <= 0;
	}

	@Override
	protected boolean openDoors(Level world, Block block, BlockPos checkPos, int dwellTicks) {
		return true;
	}

	@Override
	protected float getModelZOffset() {
		return baseTrainType.startsWith("london_underground_199")
				|| trainId.startsWith("london_underground_199")
				|| baseTrainType.equals("mpl_85")
				|| trainId.equals("mpl_85")
				|| baseTrainType.equals("br_423")
				|| trainId.equals("br_423") ?
				reversed ? -0.5F : 0.5F : 0; // TODO integrate this into TrainClientRegistry
	}

	@Override
	protected double asin(double value) {
		return Math.asin(value);
	}

	public void simulateTrain(Level world, float ticksElapsed, SpeedCallback speedCallback, AnnouncementCallback announcementCallback, AnnouncementCallback lightRailAnnouncementCallback) {
		trainTranslucentRenders.clear();
		this.speedCallback = speedCallback;
		this.announcementCallback = announcementCallback;
		this.lightRailAnnouncementCallback = lightRailAnnouncementCallback;
		oldSpeed = speed;
		oldRailProgress = railProgress;
		oldDoorValue = doorValue;

		if (ticksElapsed != 0) {
			final int stopIndex = path.get(getIndex(0, spacing, false)).stopIndex - 1;
			if (!RailwayData.useRoutesAndStationsFromIndex(stopIndex, routeIds, ClientData.DATA_CACHE, (currentStationIndex, thisRoute1, nextRoute1, thisStation1, nextStation1, lastStation1) -> {
				this.currentStationIndex = currentStationIndex;
				thisRoute = thisRoute1;
				nextRoute = nextRoute1;
				thisStation = thisStation1;
				nextStation = nextStation1;
				lastStation = lastStation1;
			})) {
				currentStationIndex = 0;
				thisRoute = null;
				nextRoute = null;
				thisStation = null;
				nextStation = null;
				lastStation = null;
			}
		}

		simulateTrain(world, ticksElapsed, null);

		if (depot == null || routeIds.isEmpty()) {
			final Siding siding = ClientData.DATA_CACHE.sidingIdMap.get(sidingId);
			depot = siding == null ? null : ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id);
			routeIds = depot == null ? new ArrayList<>() : depot.routeIds;
			if (depot != null) {
				depot.lastDeployedMillis = System.currentTimeMillis();
			}
		}

		final LocalPlayer player = Minecraft.getInstance().player;
		if (isManualAllowed && Train.isHoldingKey(player) && isPlayerRiding(player)) {
			RenderDrivingOverlay.setData(manualNotch, this);
		}
	}

	public void renderTranslucent() {
		trainTranslucentRenders.forEach(Runnable::run);
		trainTranslucentRenders.clear();
	}

	public Vec3 getViewOffset() {
		return vehicleRidingClient.getViewOffset();
	}

	public int getCurrentStationIndex() {
		return currentStationIndex;
	}

	public Route getThisRoute() {
		return thisRoute;
	}

	public Route getNextRoute() {
		return nextRoute;
	}

	public Station getThisStation() {
		return thisStation;
	}

	public Station getNextStation() {
		return nextStation;
	}

	public Station getLastStation() {
		return lastStation;
	}

	public void startRidingClient(UUID uuid, float percentageX, float percentageZ) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && player.getUUID().equals(uuid)) {
			justMounted = true;
			isSitting = false;
		}
		vehicleRidingClient.startRiding(uuid, percentageX, percentageZ);
	}

	public void updateRiderPercentages(UUID uuid, float percentageX, float percentageZ) {
		vehicleRidingClient.updateRiderPercentages(uuid, percentageX, percentageZ);
	}

	public void copyFromTrain(Train train) {
		path.clear();
		distances.clear();
		ridingEntities.clear();

		path.addAll(train.path);
		distances.addAll(train.distances);
		ridingEntities.addAll(train.ridingEntities);

		speed = train.speed;
		railProgress = train.railProgress;
		doorTarget = train.doorTarget;
		elapsedDwellTicks = train.elapsedDwellTicks;
		nextStoppingIndex = train.nextStoppingIndex;
		nextPlatformIndex = train.nextPlatformIndex;
		reversed = train.reversed;
		isOnRoute = train.isOnRoute;
		isCurrentlyManual = train.isCurrentlyManual;
		manualNotch = train.manualNotch;
	}

	public final float speedChange() {
		return speed - oldSpeed;
	}

	public boolean justOpening() {
		return oldDoorValue == 0 && doorValue > 0;
	}

	public boolean justClosing(float doorCloseTime) {
		return oldDoorValue >= doorCloseTime && doorValue < doorCloseTime;
	}

	public final boolean isDoorOpening() {
		return doorOpening;
	}

	public final List<Long> getRouteIds() {
		return routeIds;
	}

	private int getPreviousStoppingIndex(int headIndex) {
		for (int i = headIndex; i >= 0; i--) {
			if (path.get(i).dwellTime > 0 && path.get(i).rail.railType == RailType.PLATFORM) {
				return i;
			}
		}
		return 0;
	}

	@FunctionalInterface
	public interface SpeedCallback {
		void speedCallback(float speed, int stopIndex, List<Long> routeIds);
	}

	@FunctionalInterface
	public interface AnnouncementCallback {
		void announcementCallback(int stopIndex, List<Long> routeIds);
	}
}
