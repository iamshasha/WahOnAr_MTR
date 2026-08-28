# MTR 3.3.0 — work log and task list

Working copy: `C:\Users\Hry\Downloads\Minecraft-Transit-Railway-3.x.x\Minecraft-Transit-Railway-3.x.x` (not a git repo).
Target: **Fabric 1.19.4 only**.

## Build

```bash
JAVA_HOME=/c/Users/Hry/AppData/Roaming/PrismLauncher/java/java-runtime-gamma ./gradlew :common:compileJava -PbuildVersion=1.19.4 --console=plain
```

- **`-PbuildVersion=1.19.4` is mandatory.** `build.gradle:12` defaults to `1.16.5`, so omitting it silently builds the wrong target against Forge 1.16.5.
- **Use `java-runtime-gamma` (JDK 17), not `delta` (JDK 21).** Delta appears to work only while Gradle reuses its cached compiled build script; edit `build.gradle` and it dies with `Unsupported class file major version 65`.
- **Never `--offline`** — the `unpkg.com` fabric.min.js download is unconditional and fails the build.
- Don't trust `echo $?` after piping gradle into `tail`/`grep`; the pipe eats the exit code. Grep the log for `BUILD SUCCESSFUL`.
- **Fabric 1.19.4 is the only target that ships.** Build `:fabric:build`, not `build` — the latter also compiles
  and remaps Forge, which nothing here runs. It roughly halves the build and drops the Forge-side dependency
  lookups that have nothing to do with what we publish.
- **Every dependency version is pinned in `build.gradle`** and each is overridable with a `-P` flag
  (`fabricLoaderVersion`, `forgeVersion`, `fabricApiVersion`, `modMenuVersion`, `architecturyVersion`,
  `parchmentVersion`). They used to be fetched live, which is how 3.3.09 shipped a client-wide crash. A pinned
  Modrinth version that is no longer published now fails the build instead of silently taking the newest.
- **Run the two shipping checks before publishing.** Both read the finished jar:
  ```bash
  python checks/mixin_shape_check.py fabric/build/libs/fabric-1.19.4-<ver>.jar /c/Users/Hry/AppData/Roaming/PrismLauncher/java/java-runtime-gamma/bin/javap
  ```
- **Launch the instance before publishing.** `prismlauncher.exe --launch "1.19.4 - MTR"`, then wait for
  `Game took N seconds to start` in `instances/1.19.4 - MTR/minecraft/logs/latest.log` and check that
  `crash-reports/` has nothing newer. 3.3.09 went out without one launch.

- **ANTE cancels `Depot.generateMainRoute` outright.** `DepotMixin` injects at HEAD, calls `ci.cancel()`
  unconditionally, and runs `cn.zbx1425.mtrsteamloco.path.DepotPathGen.generateMainRoute` **on a thread it
  creates itself**. Anything added to MTR's version of that method never executes on this server, and a
  `ThreadLocal` set by the caller does not reach that thread either. Depot path generation has to be changed in
  both mods or in neither. `deployTrain` is *not* mixed into, so the dispatch and timetable work there is safe.
- **ANTE replaces `PathFinder.findPath` and `appendPath`** through `PathFinderMixin`, binding on the exact
  six-parameter signature, and runs `BetterPathFinder` instead. Changing that signature breaks the binding;
  changing the method body changes nothing on this server.

### Building ANTE

Run the **full** `build` task, never `:fabric:build`. ANTE's release artefact is produced by
Forgix's `mergeJars`, which fuses the fabric and forge jars into one; a fabric-only build silently
drops `pack.mcmeta`, `mods.toml` and the whole `forge/` tree. The merge alone takes about a quarter
of an hour.

```bash
JAVA_HOME=/c/Users/Hry/AppData/Roaming/PrismLauncher/java/java-runtime-gamma ./gradlew :fabric:build -PbuildVersion=1.19.4 --console=plain
```

Copy the fresh MTR jars into `checkouts/1.19.4/` first (`common-...-transformProductionFabric.jar`
to `mtr-common.jar`, `fabric-1.19.4-<ver>.jar` to `mtr-fabric.jar`). Compare the finished jar's
entry list against the previous release before publishing it — equal counts, nothing missing.

**`mod_version` must be exactly three numbers**, optionally `-<n>`. `ScriptResourceUtil` parses it
in a static initialiser, so a fourth part throws before the class finishes loading, leaves it
permanently broken and takes MTR's resource reload with it — the whole game then reports
`Caught error loading resourcepacks, removing all selected resourcepacks`. That is what 1.2.4.1
did. Run `python checks/version_parse_check.py build/MTR-ANTE-<version>+<mc>.jar` before
publishing; it runs the shipped parser against the built jar's own version.

## Deploying to the downloader

The mod archive is Bunny CDN, **not** the Firebase site. `firebase deploy` publishes the changelog
and manual only. Credentials are in `D:\srnmgmt\scripts\push\.env`; the `BUNNY_STORAGE_*`
environment variables the older `scripts/bunny_storage_api_sync.py` wants are not set anywhere, so
use `scripts/push/` instead. Jars first, map second, so a client reading mid-deploy is never told
about a version whose file has not landed. From `D:\srnmgmt`:

```bash
python scripts/push/push_files.py ".stage-mods/<jar>=archive/mods/<published name>.jar"
python scripts/push/deploy.py --map-only --verify-packages
```

`--verify-packages` re-hashes every mod the live map advertises and prints `ALL MATCH`. Update the
entry in `map/map.json` first: version, fileName, sourceFileName, downloadUrl, sha256, sizeBytes —
the checksum is what the client verifies the download against, so read it from the jar. Never pass
`--cleanup-mods` to the old sync script; it deletes every remote file absent from the map.

### Checks

There is no test source set, so the one runnable check is a plain main compiled against the built
classes. `TrainDeadlock` is deliberately free of Minecraft imports so it loads on its own.

```bash
javac -cp common/build/classes/java/main -d "$TEMP/checks" checks/TrainDeadlockCheck.java
```

Then run `java -ea -cp "<abs path to common/build/classes/java/main>;$TEMP/checks" TrainDeadlockCheck`.
Use absolute Windows paths and `;` — the JVM here does not read git-bash paths. It asserts that of two
trains each waiting on the other exactly one proceeds; it caught train id `0` colliding with a `0`
sentinel for "not blocked", which is why the blocked state is a flag rather than a reserved id.

### Checks

Two runnable checks, both compiled against the built classes. Their classes are deliberately free of Minecraft
imports so they load on their own.

```bash
javac -cp common/build/classes/java/main -d "$TEMP/checks" checks/TrainDeadlockCheck.java checks/TrainCatchUpCheck.java
```

Then `java -ea -cp "<abs path to common/build/classes/java/main>;$TEMP/checks" TrainDeadlockCheck` and the same
for `TrainCatchUpCheck`. Absolute Windows paths and `;` — the JVM here does not read git-bash paths.

`TrainDeadlockCheck` asserts that of two trains each waiting on the other, exactly one proceeds. It caught train
id `0` colliding with a `0` sentinel for "not blocked".

`TrainCatchUpCheck` runs a forward simulation of the accelerate-cruise-brake profile and compares it against the
closed form. It caught a run that is exactly on the limit of possible being reported as impossible, because the
acceleration arrives as a float while the distance accumulates in double and the discriminant lands a hair
negative. Note when writing cases for it that the furthest a train can go in T from rest and still stop is
`aT²/4`, which is a lot less than it looks — my first three test cases were physically impossible.

## 3.3.09 was withdrawn — the build was never reproducible

3.3.09 crashed every client before the main menu. Nothing in MTR's source changed; the compiler's output did.

`build.gradle` fetched the Fabric Loader version live from `meta.fabricmc.net` on every run, so a build took
whatever had been published that morning. Loader **0.19.4** landed between the 3.3.08 and 3.3.09 builds and
widened `@Redirect`'s `at` from a single annotation to an array, so javac emitted `at = [@At(...)]`. The
MixinExtras that ships with loader **0.19.2** — what clients actually run — casts that value straight to
`AnnotationNode`, got an `ArrayList`, and the mixin transform of `class_634` (`ClientPacketListener`) died:

```
java.lang.ClassCastException: class java.util.ArrayList cannot be cast to class org.objectweb.asm.tree.AnnotationNode
	at com.llamalad7.mixinextras.wrapper.factory.FactoryRedirectWrapperMixinTransformer.transform
```

Only `UnknownPacketMixin` was affected, because it is MTR's only `@Redirect`. `@Inject` genuinely declares
`At[] at()`, so the array form there is correct and always has been — that distinction is what the check turns on.

- The loader version is now **pinned to 0.19.2** in `build.gradle`. Build against the loader we ship on: a newer
  loader reads the old shape, but not the reverse. Override with `-PfabricLoaderVersion=...` on purpose only.
- `checks/mixin_shape_check.py <jar>` reads the shape back out of the finished jar and fails on a single-valued
  `at` compiled as an array. Verified to pass 3.3.05/06/08 and fail 3.3.09.
- **Still unpinned and able to do this again**: `forge_version`, `fabric_api_data`, `mod_menu_data`,
  `architectury_data`, `parchment_version` — all fetched live in `build.gradle` lines 17-25.
- **Launch the instance before publishing.** 3.3.09 shipped without one launch, on the reasoning that nothing
  touched mixins. That reasoning was true and still wrong.

## Watch after 3.3.06

- **Memory on a large network with train-length conditions.** `Rail.filterByTrainCars` copies the whole rail map
  when anything on it refuses a length. It is memoised per distinct length per depot, but several depots
  regenerating at once — server start, or a global regenerate — each hold their own copies at the same time. Only
  paid by networks using the feature: with no conditions set anywhere the original map is returned untouched.
- **Depots reporting fewer successful segments than before.** ANTE used to claim every segment succeeded and
  stitch later routes on after a failed one, producing a path with a hole in the middle. It now stops at the first
  failure and reports the real number, so a depot with a long-standing broken segment will start showing it.
  That is the bug surfacing, not a new one.
- **Train-length conditions do not apply to a route whose path was drawn with ANTE's route path creator.** The
  stored path is used as drawn and there is no route-finding left to steer. A warning naming the route is printed
  once per length per generation. Failing generation instead was considered and rejected: it would stop routes
  that worked yesterday from generating on a live server.

## Cadence

Finish about five tasks, then deploy once. A deploy is a rebuild plus a CDN publish, and every
published version is a fresh 59 MB download for every client, so per-task deploys mostly wait.
Keep adding to the unreleased changelog section as each task lands; bump, build and publish when
the batch is done. Deploy sooner only when users are actively broken — rolling map.json back to a
known-good version takes seconds and is the right first move there.

## Hard constraints (do not break)

- **ANTE overwrites `lambda$render$8` in `RenderTrains`.** Its mixin declares a plain method with the exact descriptor `(LocalPlayer, BlockPos, int, Map, Level, boolean, PoseStack, MultiBufferSource, BlockPos, Rail)V`. Adding / removing / reordering **any** lambda in `RenderTrains` shifts javac's class-wide counter and makes ANTE clobber a different lambda. Verify after any edit to that file:
  ```bash
  javap -p common/build/classes/java/main/mtr/render/RenderTrains.class | grep 'lambda\$render\$8'
  ```
- **ANTE cancels `Train.simulateTrain`.** `TrainMixin` injects at `simulateTrain(Level,float,Depot)` HEAD with `cancellable = true` and runs its own full copy. On this server MTR's body never executes. Anything in that method must either be duplicated into ANTE, or expressed through a method ANTE's copy still calls (`isRailBlocked`, `isRepeat`, `getTotalDwellTicks`, `startUp`).
- ANTE `@Shadow`s ~40 fields across `Train`, `TrainClient`, `VehicleRidingClient`, `TrainRendererBase` by name **and** descriptor. `TrainClient.trainTranslucentRenders` must stay `Set<Runnable>`.
- ANTE injects into `JonModelTrainRenderer.renderCar` after the **first** `UtilitiesClient.rotateX` (`ordinal = 0`). Don't add a `rotateX` before it.
- ANTE source: `C:\Users\Hry\Downloads\dl_misc\mtr-ante-alpha`. May be modified; ship a patched build if so.
- Mod languages: only `en_us.json` is bundled (Crowdin is unreachable at build time). Site docs are **English + Traditional Chinese only — no Simplified**.

## Done (compiles, none runtime-tested)

- [x] **3.3.09 — double booking.** `Depot.lastBookedDeparture` (a scalar) → `DepartureLedger`, which holds the set of consumed departures and the floor, and owns the two timetable finders. `Depot` delegates. Sidings have different leads, so their bookings interleave and one "last booked" lets an earlier slot look free again. That is what put two trains on one departure
- [x] **3.3.09 — server-start settle.** `settleTimetableOnLoad()` moved inside `if (!deployableSidings.isEmpty())`. On the first tick after load the sidings are still generating paths, so settling then burned every departure that passed during the warm-up. Depot now logs the time it resumed from
- [x] **3.3.09 — block-detail culling.** `RenderTrains.shouldNotRenderBlockDetail(pos, facing)` with `BLOCK_CULLING_MARGIN = 2`. `CULLING_MARGIN = 24` is sized for a train whose reference point is at one end of something tens of blocks long; on a one-block door it culls almost nothing. Applied to `RenderPSDAPGDoor` (had **no** culling — five block-state reads and a queue submit per door per frame), `RenderStationNameBase`, and the rail/signal segment loops in `renderRailStandard` / `renderSignalsStandard`. `renderedRailMap` reused instead of allocated per frame
- [x] Version `3.2.3` → **`3.3.10`**, ANTE `1.2.4` → **`1.2.8`** (`gradle.properties`). 3.3.09 was published and withdrawn.
- [x] **Render perf**: `RenderTrains` queue `Set`→`List` (lambdas are fresh instances every frame, so the set never deduped); `shouldNotRender` builds one AABB from coords instead of two
- [x] **Airplane pathfinding fix** (`PathFinder.countUsable`): one-way rails store the blocked direction as a `RailType.NONE` placeholder, so `railMap.size() == 1` never matched a runway end anything could taxi into. `runways` came out empty and no flight leg was ever built
- [x] **Depot max cars**: `Depot.maxTrainCars` (0 = uncapped) + full MessagePack/NBT/packet/`update()`/`setData()` round-trip; `Siding.effectiveTrainCars()` clamps at spawn and in timetable math; slider in `EditDepotScreen` up to `MAX_TRAIN_CARS_LIMIT = 32`
- [x] **Vehicle sway**: `TrainClient.updateSway` leans cars by yaw-rate × speed, smoothed, clamped ~7.5°, pivot 1 block above floor. `Config.useVehicleSway()` + options-screen toggle. **Detects ANTE and stands down** (`MTRClient.isAnte()`) or trains roll twice
- [x] **Strict timetable** 「嚴格跟隨時間表行駛及出車」:
  - `Depot.strictTimetable` checkbox (visible only with Repeat Indefinitely on)
  - short gap → holds at origin by stretching the stop: `TrainServer.getTotalDwellTicks()` returns however long is left until `timetableDepartureMillis`, so the doors stay open and nothing has to brake mid-section. (An earlier `isHeldForTimetable()` routed through `isRailBlocked` was replaced by this and no longer exists.)
  - long gap → `isRepeat()` returns false past `Depot.STABLING_THRESHOLD_MILLIS` (2 min, tunable), so the train leaves the loop, comes off route and stables in the depot on the existing dispatch gate. Decision is **latched** at the loop end so PIDS projection doesn't flicker
  - Works under ANTE with no ANTE patch, because both hooks are methods ANTE's copy still calls
- [x] **Pathfinding-failure teleport + marker**: `Depot.getPlatformsInRoute()` extracted so path generation and the failure lookup share one ordering; `Depot.getPathFailurePos()` resolves the start of the hop that failed. New `PACKET_TELEPORT_TO_PATH_FAILURE`, gated on `RailwayData.hasNoPermission`, and the **server resolves the position itself** from depot id + segment count so a client cannot dictate coordinates. "Teleport to break" button appears only when a break exists. `RenderPathFailure` draws a red cage at the spot and it stays up after the screen closes — kept in its own class, and called from `RenderTrains.render` as a plain statement so no lambda is added there
- [x] **Sway v2**: added a running rock on top of the curve lean. Phase advances with *distance travelled*, so it slows with the train and stops dead when it stops; each car is offset along that phase so a rake rocks as a travelling wave instead of one rigid block
- [x] **Per-siding car count**: `Siding.maxTrainCars` (0 = as many as the rail fits) with full serialization; slider in `SidingScreen` whose label shows **cars and the length in blocks**, so a builder can size a platform without doing the sum. `effectiveTrainCars()` applies the siding limit first, then the depot ceiling
- [x] **Credits/description**: `AimedOrpheus177` as `contributors` in `fabric.mod.json` and `credits` in forge `mods.toml`, "Modified for the use of Wah On Ar Server." appended to the description. Edit `resources/fabric/normal/` and `resources/forge/normal/` as well as the `fabric/`/`forge/` copies — the build overwrites the latter from the former.
  **This was ticked for weeks without having been done**; neither name was anywhere in the repo. Verify a claim in this file against the code before trusting it.
- [x] **Realistic Time visual editor**: four sliders (hour / minute / count / interval) compose `HH:MM:SS + N * HH:MM:SS` into the existing departure field. Reuses `checkDeparture`; only writes when a slider moves so typing isn't clobbered


## Auditing this file

Entries here are written when work is done and then not revisited, so they drift as the code moves on and at
least one was simply wrong. Checked on 27 August 2026:

- **Credits/description was ticked and had never been done.** Now done.
- **Double booking** described `consumedDepartures`/`timetableFloor`, which a later refactor replaced with
  `DepartureLedger`. Corrected.
- **Strict timetable** described `isHeldForTimetable()`, which no longer exists; the hold is done by stretching
  the dwell instead. Corrected.
- **Version line** still said 3.3.06. Corrected.

Everything else in Done was checked against the code and is accurate. Do the same before relying on any of it:
`grep` for the symbol an entry names. If it is not there, the entry is wrong, not the code.

## Remaining, in order

- [x] **Signalling hold-back (partial)**: `SignalBlocks` now records each train's tail position (timestamp-expiring, so no extra per-tick plumbing); `TrainServer.hasClearanceBehind()` lets a follower keep closing on a train that is still far down a long section instead of stopping at its mouth. Straight-line distance only ever *understates* along-track separation, so it errs towards holding back; two trains closing head-on both stop, as before. Needed **no ANTE change** — `isRailBlocked` is called by ANTE's replacement copy, and the stop target ("end of my current rail") advances with the train, so a cleared train creeps in a rail at a time. `SAFE_FOLLOWING_DISTANCE = 48` blocks, tunable.
- [x] **i18n — root cause found and fixed.** Not a missing-translations problem: Crowdin exports `zh_TW.json`, jar resource lookup is **case sensitive**, and Minecraft asks for `zh_tw`, so *no* language but English ever loaded (true of upstream MTR too; Adorn/ClothConfig/ANTE all ship lowercase). All 41 files sourced from ANTE's `checkouts/1.19.4/mtr-common.jar` (which has the Crowdin build), renamed lowercase, `en_us.json` preserved so new keys survive, new keys merged into `zh_tw`/`zh_hk`, and `build.gradle` now lowercases whatever Crowdin hands it. The legacy Crowdin export URL is dead regardless — returns `{"success":false,"version":"9"}` even authenticated.
- [x] **Changelog + deploy loop**: 3.3.0 "Unreleased" section in `ante/updates/mtr.{en,zh}.md`, committed, `firebase deploy --only hosting` (functions untouched).
- [x] **Moving-block following.** `Train.getFollowingSpeedLimit()` hook (default no limit) folded into the final
  `getRailSpeed`, overridden in `TrainServer`: scan `FOLLOWING_LOOKAHEAD` path rails ahead for another train's
  claim, take that train's reported tail from `SignalBlocks`, and cap speed at `sqrt(2 * a * (gap - 48))`. A long
  section now carries several trains on a brake curve instead of one behind a stop line. `getRailSpeed` is called
  by ANTE's own movement copy, so no ANTE change was needed.
- [x] **Timetable Case 3 / departure overshoot.** The origin hold was asking `Depot.getMillisUntilDeploy`, which
  consumes slots through `lastDeployedMillis` — and the dispatch that released the train had already consumed the
  very departure the train was running to, so the hold aimed at the *next* one and the train sat through its
  booked time (the reported 17:16 / 17:22 / 17:28-never-fired). Each `TrainServer` now carries
  `timetableDepartureMillis`, stamped by `Depot.deployTrain` via the new `Depot.findDeparture(reference, atOrAfter)`
  and stepped on each time the train pulls away from the origin. Late trains leave after one door cycle
  (`LATE_DWELL_TICKS`) rather than waiting a headway. Stabling now reads the same target, so the end-of-day run
  returns to the depot rather than standing on the platform overnight.
- [x] **Deadlock yield.** Two parts. First, `TrainServer.isSameDirection` compares this train's front-minus-back
  against the other train's, so the 3.3.02 closing-up rule applies only to a train being followed — opposing
  traffic keeps the whole-section claim, which is what stops two trains meeting in the middle of a section
  neither can reverse out of. That was a hole 3.3.02 opened. Second, each train publishes its blocker through
  `SignalBlocks.setTrainBlockedBy` / `isTrainBlockedBy`, cleared at the top of its own tick, and
  `TrainDeadlock.proceeds` breaks a two-train standoff by letting the lower id through. Rings of three or more
  are left alone deliberately. Checked by `checks/TrainDeadlockCheck.java` — see below.
  stalls: both see the other inside the margin and both stop. Needs a deterministic yield — lowest train id backs
  off — built on the `SignalBlocks` tail registry.
- [ ] **Persist `timetableDepartureMillis`.** Not serialised, so a server restart makes a train at the origin
- [x] **Published 3.3.02 + ANTE 1.2.4.1** to the Bunny archive; live map verified `ALL MATCH` across 69 packages. The MTR entry had been published with an empty `sha256`, which is what the client checks a download against; it now carries the real one.
  adopt the next departure due instead of the one it was dispatched against. Harmless drift, one restart only.
- [ ] **~~Signalling / hold-back~~ + 站前折返 verification.** 站前折返 needs no new track feature — `RailType.TURN_BACK` works and `PathFinder.addPathPart` honours it. Confirm the hold-back change actually clears the reversing stall in-game.
- [x] **Customisable rail speed limit.** `Rail.speedLimitKmh` (0 = rail type default), fully serialized. `Train.getRailSpeed` returns the tightest limit **within braking distance ahead**, so the ordinary "over the limit → decelerate" branch brakes on approach; contained in one method so ANTE's replacement loop honours it. ANTE's `BrushEditRailScreen` gained an int field 0–1000; its existing `PacketUpdateRail` round-trips a whole `Rail` so the value carries free. `RailMixin.getTransposition`/`partialCopyFrom` also carry it so a limit survives flip/copy. Lang keys in all 10 ANTE languages. **ANTE rebuilt and verified.** Original `checkouts/` jars backed up to `checkouts/1.19.4/orig-backup/`.
- [x] **Timetable reworked to the operator spec.** The scheduled time is the **origin platform departure**, not the dwell: due → depart even if the dwell is short; early → keep waiting past the dwell. Implemented in `TrainServer.getTotalDwellTicks()` (a method ANTE's copy reads), replacing the earlier `isRailBlocked` hold. Also fixed `openDoors()`, which read the **raw** platform dwell, so the extended hold would have shut the doors after the nominal dwell and sat there closed — it now reads `getTotalDwellTicks()`.
- [x] **Timetable Case 1 — dispatch the siding early.** `Siding.getDispatchLeadMillis()` + per-siding timing check in `Depot.deployTrain`. `lastDeployedMillis` is stamped as `now + lead` (the nominal slot), not the early moment, so `generateTempDepartures` does not drift earlier every dispatch.
- [x] **Door position preview tool.** Brush + right-click a `BlockNode` sends `PACKET_PREVIEW_DOORS` to that one
  client, which marks the doors of the nearest stopped train through `RenderDoorPreview`. Read off a real train
  rather than predicted, because where a train *would* stop is not derivable from a platform block alone and a
  wrong mark is worse than none. Door offsets come from `ModelTrainBase.getPreviewDoorPositions()` (null-returning
  by default, so addon renderers need no change); car boundaries are marked regardless. Call site in
  `RenderTrains` is a plain statement — no new lambda.
- [x] **Doors without platform/PSD.** `Siding.doorsWithoutPlatform`, pushed onto each `Train` every tick rather
  than serialised on the train: ANTE injects into three `Train` constructors and into `toMessagePack` /
  `messagePackLength` by exact descriptor, so both are the wrong place to add a field. **ANTE needed the same
  edit**: its `TrainMixin.onScanDoors` cancels MTR's `scanDoors` at HEAD and reimplements it, so an MTR-side
  change alone does nothing under ANTE.
- [x] **Xaero station waypoints.** `mtr.client.XaeroWaypoints`, all reflection, no compile-time dependency,
  hidden when Xaero is absent. Xaero ships no API package; its supported `xaero_waypoint_add:` chat line opens a
  confirm screen per waypoint, so bulk had to go through the live `MinimapSession`. Teleport needs nothing from
  us — Xaero's own waypoint teleport works once the waypoints exist.
- [ ] **Trains live on Xaero's minimap.** Possible but not free: `MinimapElementOverMapRendererHandler.add` is
  public, so a custom `MinimapElementRenderer` can be registered — but that is an abstract class with generics,
  so it must be *compiled* against Xaero rather than reached by reflection. That means a `compileOnly` file
  dependency on Xaero's jar and a class loaded only when it is present. MTR trains are not entities, so Xaero's
  radar cannot see them by itself. Its own project, not a small addition.
- [x] **Tooltips for config options.** `ConfigScreen` collects each label's y as it draws (rows are laid out by a
  running counter, so the draw is the only thing that knows where a row landed) and renders
  `<key>.tooltip` on hover. A key with no translation renders nothing rather than raw debug text.
- [ ] **i18n.** Only `en_us.json` is bundled and Crowdin is unreachable at build time, so new strings ship English-only. Add `zh_tw.json` by hand for at least the new keys.
- [ ] **ANTE brush feature** — merge into MTR or leave in ANTE; my call, not yet investigated.
- [x] **Rail-level car-count conditions.** `Rail.minCarsAccepted` / `maxCarsAccepted` (0 = no limit that end),
  `Rail.allowsTrainCars(int)` and `Rail.filterByTrainCars(rails, cars)`; `messagePackLength` 19 → 21. The rule is
  applied by **filtering the rail map** handed to path generation, not by teaching the search to skip rails —
  see the constraint below for why that is the only thing that works. `Depot.generateMainRoute` builds one
  filtered map and one main path per distinct `effectiveTrainCars()` among its sidings; a depot whose sidings
  all match generates exactly one, and a network with no conditions set gets the original map back untouched.
  Filtering the map also covers the first and last hop into a saved rail base, which are fetched from the map
  rather than walked to and so could never be caught by a search-side skip.

  An earlier attempt passed the length through a `ThreadLocal` on `PathFinder`. It was chosen to protect ANTE's
  mixin binding and it did — while leaving the feature silently inert, which is worse than a mixin that fails
  loudly. Do not reach for ambient state here again.
  `Rail.allowsTrainCars(int)`; `messagePackLength` 19 → 21. `PathFinder` skips a rail that refuses the length, so
  the search routes around it. The length is passed through a `ThreadLocal` on `PathFinder` rather than as a
  parameter, because **ANTE's `PathFinderMixin` binds to `findPath`'s exact six-parameter signature** and would
  bind to nothing if it changed — and because each depot generates on its own thread. `Depot.generateMainRoute`
  now builds one main path per distinct car count among its sidings, so a depot that mixes lengths pays for more
  than one and a depot that does not still generates exactly one. **ANTE's `BetterPathFinder` replaces `findPath`
  outright, so it needs the same rule or the feature is a silent no-op on this server.**
- [x] **Manual shipped** at `/mtr-manual/` — 10 pages, EN + zh, worked examples, ANTE chrome/tokens/transitions reused and sharing its `star-prefs` key. **Trap:** the `<div class="shell">` wrapper between `</header>` and `<nav>` is the sidebar grid and page padding; lifting only the header drops it and the whole layout flattens. Verify visually with a screenshot, not by extracting text — text extraction passes on a page with no CSS at all.
- [ ] **Build + deploy** the Fabric 1.19.4 jar.
- [x] **MTR 4 pack support.** `mtr.client.Mtr4CustomResources` reads the MTR 4 `mtr_custom_resources.json` and
  restates it in MTR 3's terms; `CustomResources.reload` recognises the format by shape and registers the result
  through the same `DynamicTrainModel` + `TrainClientRegistry` the MTR 3 path uses. Checked by
  `checks/Mtr4PackCheck.java`. The mapping is the inverse of MTR 4's own `CustomResourcesConverter`
  (`fabric/src/main/java/org/mtr/legacy/resource/` in the MTR 4 tree), which is the authority wherever the two
  formats disagree — including that MTR 4's `length` counts the coupling gap and MTR 3's does not.

  **The multi-layer blocker was the real work.** `mtr.client.LayeredTrainModel` holds N `DynamicTrainModel`s and
  hands each its own texture, which needed `ModelTrainBase.render` to stop being `final` — descriptor unchanged,
  so nothing ANTE binds to moved. Model entries are gathered by the `(model file, texture)` pair they share, not
  merged: MTR 4 lists one entry per properties file and the same `.bbmodel` appears several times over, so the
  Seoul pack's 4–6 entries collapse to 2–3 layers with no geometry surgery at all.

  Two other traps. A display part draws only text in MTR 4 and both text and geometry in MTR 3; it is given a
  `stage` of `TEXT_ONLY`, which no `RenderStage` is named, so the geometry pass skips it and the text pass — which
  never looks at the stage — still draws. And MTR 4 packs name gangway faces `<id>_side.png` where MTR 3 wants
  `<id>_connector_side.png`, so `JonModelTrainRenderer.getConnectorTextureString` now falls back to the shorter
  name, MTR 3's name still being asked for first.

  Not supportable, all named in the log rather than faked: custom rails, eyecandy objects, lift skins, `.obj` and
  `.mqo` models, floor/doorway/seat markers, `AT_DEPOT` and Christmas-light conditions, departure-number and
  route-colour displays, the display options beyond scroll/upper-case/single-line, bogie models, coupling padding,
  a gangway on one end only, and gangway/barrier dimensions. A per-carriage MTR 4 vehicle also cannot become part
  of a mixed consist: MTR 3 picks one train type for the whole train, so each carriage type lands in the list on
  its own.

## MTR 4 findings (pack: `D:\Agents\seoul_metro_4000_4_mtr4.zip`)

Format maps mechanically: `.bbmodel` is the same Blockbench format `DynamicTrainModel` already parses; `vehicles[]` array vs MTR3's `custom_trains{}` map; `names[]` → one part each; `positionDefinitions[]` resolved via `assets/mtr/definitions/*.json` → `positions` (drop y, MTR3 reads x/z); `renderStage`→`stage`; `doorXMultiplier`→`door_x_multiplier`; `length`/`width` → `base_train_type`.

**Blocker (solved in 3.5.0, see above):** every vehicle uses multiple model layers with multiple textures — trailer 4 layers / 2 textures, cabs 5–6 layers / 3 textures. MTR3's `TrainProperties` holds one model and one `texture_id`. Keeping only the first layer loses all labels and the cab fronts. Real fix is to let MTR3 hold a *list* of model layers; `scheduleRender` already batches by texture, so N layers is N calls with different `ResourceLocation`s.

## Site / deploy notes

- `D:\proj\STAR_TRANSIT_MAP` — Firebase project `star-nation`. Repo was corrupt (missing commit + zero-byte object); grafted and rebuilt, `git fsck` now clean, 20 commits, HEAD was `d239219`. Pre-graft history is gone; backup of old `.git` was in the session scratchpad only.
- **`/mtr/**` is NOT a docs path** — it rewrites to the `mtr` Cloud Function proxying departures from `66.206.27.114:50601`, and `src/data.js:18` points every HTTPS visitor at it. Doc goes to **`/mtr-manual/`**; do not shadow `/mtr/`.
- `D:\srnmgmt` — separate GitHub repo (`iamshasha/srnmgmt`), holds source docs (`ante/`, `locales/`). No Firebase config of its own.
