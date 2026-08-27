# Changelog

The Wah On Ar build of Minecraft Transit Railway. This is a modified fork, not the original — see the
[README](README.md). Upstream stops at 3.2.2-hotfix-1; every version from 3.2.3 onward is this server's own
build, so these numbers will not match anything on the MTR project's site.

Also published, with the Traditional Chinese version, at the server's own site.

## 3.3.13 — 27 August 2026

### Fixes

- **Trains arrived at the origin thirty seconds early.** The mod measures the run between two stops from the
  middle of one to the middle of the next, so that figure already carries half of the stop at the far end. The
  dispatch was adding the whole stop on top, counting it one and a half times, and a minute-long stop therefore
  released the train half a minute too soon.

- **A train that arrived on time could sail straight through its own stop.** A train whose next departure is far
  off returns to the depot rather than holding the platform, and the test for "far off" was counting the stop it
  was scheduled to make as time spent idle. A train arriving two minutes before its departure with a one-minute
  stop is idle for one minute, not two — but it read as idle for two, decided it had a long wait ahead, and ran
  to the depot instead of stopping. The early arrival above was pushing every train further into this.

- **Trains could be held where they stood, for good.** A train decides whether the one ahead is going its way by
  comparing directions, and it reads its own direction from the gap between its two ends. A train standing at the
  very start of its run has both ends in the same place, so no direction could be read from it, so every occupied
  track ahead looked like oncoming traffic — and nothing that looks like oncoming traffic is ever passed. That is
  precisely a train waiting to leave a siding. Every vehicle now states which way it faces rather than leaving
  the others to work it out.

- **Ghost departures on platform displays.** Whether a train shows the next lap's arrivals is the same setting
  that decides whether it runs another lap. The decision was taken at the far end of the loop, so until it got
  there a train kept advertising arrivals for a lap it had already been booked not to run. Those then vanished
  all at once, and every display showing one jumped to a later train. It is decided once now, as the train leaves
  the origin.

- **Arrival times froze instead of slipping.** Arrival is projected from where a train currently is, and a held
  train does not move, so the projection stopped changing and the display promised a train in ten seconds for as
  long as the hold lasted. The time a train has actually lost standing still now counts towards its arrival.

### Additions

- **Platform displays can show when a train is due instead of counting down to it** — 5:49 rather than "4 min".
  A countdown has to revise itself whenever a train runs late, and a revised countdown reads as a fault even when
  it is telling the truth; a due time simply becomes a later due time. In the mod's options, off by default.

- **The ground ahead of a moving train is warmed by time, not by distance.** Ninety-six blocks is a comfortable
  margin at walking pace and about a second and a half at two hundred and forty kilometres an hour, which is
  where it was actually needed. It now looks a set number of seconds ahead at the train's current speed. Server
  owners can change or disable it in `config/mtr-server.json`, which the server writes out for itself.

- **Vehicle sway works alongside MTR-ANTE again.** It used to stand down entirely when ANTE was present, because
  both leaning into curves rolls a train twice. ANTE does not rock a train running on straight track, though, so
  that half is now kept and only the curve lean is left to ANTE.

`Minecraft_Transit_Railway_1.19.4_3.3.13.jar` · 62,794,236 B · `sha256 1280c0971ae6f0d62294e59e2cc72fe3203458402955e0c97428dff319687f73`

## 3.3.12 — 27 August 2026

### Fixes

- **Vehicles stopped vanishing when the world got busy.** The mod lowers how far away vehicles are drawn when
  frames are slow, which is reasonable, but it gave up half the distance on the very first slow frame and then
  won it back one block at a time. A single slow frame is nearly always a chunk being built or a burst of packets
  arriving — exactly what happens while riding fast or crossing a busy station — so a fifth of a second of
  hitching cost hundreds of good frames, and vehicles stayed invisible for as long as the area stayed busy. It
  now takes a sustained run of slow frames to give ground, gives a quarter rather than a half, comes back at the
  rate it left, and never drops below 64 blocks instead of 32.

- **Chunk checks were asking about the wrong chunk west and north of the origin.** The mod worked out which chunk
  a block sits in by dividing, and division rounds towards zero rather than downwards, so a block five blocks west
  of the origin reported the chunk to its east. Everything gated on "is this ground loaded" — door scanning, lift
  movement, rail node checks — has been answering about a different chunk in that whole half of the map. This is
  an old bug and not one of ours.

- **The ground ahead of a moving vehicle is warmed up.** On a long fast run a vehicle outruns everything the
  server has in memory, and each new piece of ground gets fetched from disk while somebody is already standing on
  it. Vehicles carrying passengers now ask for the ground a short way ahead to be kept ready. This cannot make
  your own client receive ground any faster — that is the game's own business — but it stops the server being
  caught out. Deliberately small, and it releases itself.

### Housekeeping

- The repository now says plainly that this is a modified build rather than the original, and links back to the
  upstream project. Continuous integration builds Fabric 1.19.4 only and publishes nothing anywhere.

`Minecraft_Transit_Railway_1.19.4_3.3.12.jar` · 62,790,029 B · `sha256 517dafc44885986aa743b7d7891a6e60c7db2d1e30789d5bf448f5aec7e31441`

## 3.3.11 — 27 August 2026

### Fixes

- **Escalators survive being copied.** An escalator is four blocks that only exist together, and each one deleted
  itself the instant it noticed a missing neighbour. WorldEdit and Effortless write blocks one at a time, so for
  an instant during a paste every block's neighbour was missing and the escalator ate itself as it was being
  written — which is why a copied one came out broken or vanished. Placing one by hand always worked because the
  item places the halves together. A block that finds itself alone now waits a tick and looks again, by which
  time the paste has finished.

- **A held vehicle no longer fills the log.** A vehicle that cannot move says so once, which was meant to make a
  stuck line diagnosable. But a vehicle pressed up against a blocked section is not standing still: it creeps
  forward, is stopped, and creeps again several times a second, and every creep counted as having got going
  again — so the same line went out about twenty times a second per vehicle. It now takes real movement to count,
  and the same complaint is never repeated inside half a minute.

- **That message now says what is wrong.** It reports whether the vehicle in the way is running, parked, has not
  reported its position recently, or is itself waiting on something else. Those are different faults on the
  railway wanting different fixes, and they were indistinguishable from the outside.

- **Credits.** `AimedOrpheus177` is listed as a contributor and the description says the build is modified for
  this server. This was recorded as done some time ago and had in fact never been done.

`Minecraft_Transit_Railway_1.19.4_3.3.11.jar` · 62,788,989 B · `sha256 408f8334b8ae20e87fd4ad290c223044c7df08fefde07067eb4b8e6725ed4d82`

## 3.3.10 — 27 August 2026

### Fixes

- **3.3.09 crashed every client before the main menu, and is withdrawn.** Not one line of the mod's own source
  was to blame. The build asked fabric for the newest loader every time it ran, so a jar was compiled against
  whatever had been published that morning rather than against what the game actually runs. Loader 0.19.4
  landed between the two builds and widened one mixin annotation from a single value to a list, and the mixin
  library that ships with the loader clients run reads that value as a single value — so it failed while
  patching the network handler, and the game died during startup. Every dependency version is pinned now, and
  a released version upstream can no longer change what a build produces.

- **The finished jar is checked before it is published.** A new check reads the annotation shapes back out of
  the built file and refuses anything compiled against a newer mixin library than the game runs. It is a
  backstop rather than the fix — the pin is the fix — but this failure only ever appears at runtime, on
  somebody else's machine, after the file is already downloadable.

Everything in 3.3.09 is in this release: the double booked departures, the depot that came up behind its own
timetable at server start, and the screen door and station sign culling.


`Minecraft_Transit_Railway_1.19.4_3.3.10.jar` · 62,787,991 B · `sha256 6581dd6f88f6618f1c95d3b52bd853fbdd9809ee7021e718736c434f04c6f672`

## 3.3.09 — 27 August 2026

### Fixes

- **Two trains for one departure.** The depot remembered only the most recent booked departure it had sent a
  train for. Sidings have different lead times, so they read the timetable from different moments and their
  bookings interleave: a long-lead siding books 09:05 while a short-lead one is still working toward 09:00,
  the single "last booked" then reads 09:05, and 09:00 looks free again. The depot now keeps the whole set of
  departures already sent for, which cannot be fooled by the order they arrive in.

- **A depot could come up already behind its own timetable.** At server start the sidings are still generating
  their paths, so for the first seconds nothing can be dispatched at all — but the depot was marking its
  timetable as settled on its very first tick, inside that dead window. Every departure that passed while the
  paths were still building was burned. Passenger information displays kept showing those departures, because a
  display reads the timetable rather than the dispatch gate, which is what made it look like a phantom. The
  timetable now settles when a siding first actually has a train ready, and the depot says in the server log
  which time it resumed from.

- **Platform screen doors cost frames even when they were behind you.** The screen door renderer had no culling
  of any kind: every door block within render distance read five block state properties and queued its geometry
  every frame, including every door on the platforms facing away from the camera. A four platform station is
  several hundred of them. Station name plates were the same. Both are now tested against the view before
  anything else happens.

- **The culling margin was sized for trains, so it barely culled anything small.** A train's reference point sits
  at one end of something tens of blocks long, so its margin has to be generous; applied to a one block door,
  that same margin keeps almost everything alive. Block sized fittings now have their own tight margin, and rail
  and signal segments moved onto it as well — a rail segment is block sized, not train sized.


`Minecraft_Transit_Railway_1.19.4_3.3.09.jar` · 62,787,992 B · `sha256 3c78732fa9000e455d65f4b3a95cd7322d561ed5776f6db9bb8059027959bc3d`

## 3.3.08 — 27 August 2026

### Fixes

- **A vehicle whose pack you do not have cost as much as one you do.** With the pack missing there is no model
  and no texture, so the mod falls back to a minecart drawn with a fully transparent texture — you see nothing,
  which is intended, but the model was still being set up and its geometry submitted for every car of every such
  train, every frame. Where the fallback is invisible, nothing is drawn at all now. A line running vehicles from
  a pack you have not installed stops costing you frames for the privilege of not seeing them.


`Minecraft_Transit_Railway_1.19.4_3.3.08.jar` · 62,785,844 B · `sha256 82d6011ab66662c94d0d72e8693d7620b7d140c66be6ddb9773204c46acfaef6`

## 3.3.07 — 27 August 2026

### Fixes

- **Depots stopped dispatching.** The release moment for a departure is a single instant, and a siding can only
  act on it while a train is actually standing in it. The allowance for acting late was ten seconds — so a train
  that came back a few seconds after its window shut missed that departure, and the one after, and every one
  after that. The allowance now runs until the booked departure itself, capped at halfway to the next one: a
  train present anywhere near its moment goes, and one arriving most of a headway late still waits rather than
  running hopelessly behind.
- **A train could be held so hard it could never start.** The rule that keeps a train a safe distance behind the
  one in front capped its speed at zero when the gap closed — which is not the same as telling it to wait, and a
  train leaving a siding within that distance of its stabled neighbours could not move at all. Deciding that a
  train may not proceed belongs to the signalling, which knows about claims and reversing and yielding; the
  following distance now only decides how fast it may close, never whether it may move.
- **Clear Trains no longer throws away stabled trains.** It used to empty every siding in the depot, so a button
  offered as a way to unstick a stuck route also destroyed every train quietly waiting for its departure. It now
  does what the automatic clear on server start does: recover the trains that are lost out on the route, leave
  the ones sitting where they belong.
- **Trains stranded mid-route by a restart are recovered for every depot**, not only those following a timetable.
  A train reloaded halfway along its path has no idea where it was going whether or not a timetable is involved.

### Diagnostics

- **A depot that skips a departure now says so**, naming the time, how far ahead the siding needed to release the
  train and by how much that moment was missed. A train held by another says which one is holding it and why.
  A railway that will not move looked identical whether the depot never dispatched or the train could not start,
  and those have nothing to do with each other.

`Minecraft_Transit_Railway_1.19.4_3.3.07.jar` · 62,785,739 B · `sha256 db64e27ce7aaf3a2afeb43123549d25d97d977de1b24c4669e7863ccede44b73`

## 3.3.06 — 27 August 2026

### Track

- **A rail can refuse a train by length.** Set the shortest and longest train a rail will carry, and anything
  outside that is routed around it rather than stopped at it — a tight loop only short units can take, a siding
  spur too short for a full-length set, a bypass reserved for the long ones. Zero at either end means no limit
  there. Set through MTR-ANTE's rail brush, and carried across when a rail is flipped or copied.
- **A maximum below the minimum is raised to meet it.** A range nothing can satisfy would leave a rail that
  silently refuses every train, which is far harder to find than a setting that gives way, so the brush will not
  let you type one.
- **Length conditions do not reach a hand-drawn path.** A route whose path was drawn with MTR-ANTE's route path
  creator is used exactly as drawn, so there is no route-finding left to steer around a rail that refuses the
  train. The server log names the route when this happens. Everything the game works out for itself, including
  the run in and out of the depot, still honours the conditions.
- **Route generation follows the train.** Because the way through the network now depends on how long the train
  is, a depot builds one route per distinct train length among its sidings. A depot whose sidings all spawn the
  same length still generates exactly one, as does a network with no length conditions set anywhere.

### Timetable

- **Catching up is worked out properly.** The first version aimed at the average speed needed, which a train can
  never hold: it starts from wherever it is and has to be stationary at the platform, and the time spent getting
  up to speed and back down is time spent below the average. It arrived late every time. The peak speed is now
  solved for directly, from the distance, the time left, how fast it is already going and how hard it accelerates,
  with the dwell at the stops in between taken out of the time available.

### Fixes

- **The navigation sidebar would not scroll on a phone.** It was told not to stretch — correct for the desk
  column it also is — and a fixed panel told not to stretch shrinks to fit its contents instead of the screen. So
  it grew past the bottom edge, never overflowed itself, and had nothing to scroll: the last few entries were
  simply unreachable.

`Minecraft_Transit_Railway_1.19.4_3.3.06.jar` · 62,784,730 B · `sha256 570930eee9f83ad4a2c468d4313fa5c88853e2c57edb20443e5efdd2177c2c6c`

## 3.3.05 — 26 August 2026

### Timetable

- **A route that does not repeat can follow a timetable too.** The setting used to appear only on depots set to
  repeat indefinitely. A booked time now means the same thing everywhere: the moment the train leaves the first
  platform. The siding lets go a run and a stop earlier so the train is standing there before it is due away,
  rather than only setting off then — which is what made every departure a dwell late.
- **One train per departure, however many sidings.** Sidings have different run-ins, so each was reading the
  timetable from a different moment and several could see the same slot as still free. A depot with a row of
  sidings would put a row of trains onto the line at one booked time. Departures are now claimed by the departure
  itself, not by when the last train happened to leave.
- **A departure that can no longer be made is skipped.** If no train was free in time, or the run to the platform
  is longer than the time left, the siding waits for the next booked time instead of releasing a train that would
  then sit on the origin for most of a headway.
- **Starting the server mid-day no longer replays the day.** Every departure since the last save used to read as
  due at once, so the depot emptied its sidings in a burst and ran the rest of the day against a clock that no
  longer matched. Everything already past is now treated as gone.
- **Trains left mid-route by a restart are returned to their siding.** Clearing them by hand was already the
  advice; a timetabled depot cannot depend on someone remembering after every restart.
- **A late train makes the time up on the run.** Behind its booked departure, a train may run past the per-rail
  limits a builder set — by exactly the average it needs to close the gap and no more, easing back as the deficit
  shrinks. The rail type's own maximum still caps it, and so does the train in front.
- **A late train does not stand at the origin.** Past its booked time, it leaves without dwelling at all. Standing
  there is what was costing it.
- **How long a wait is worth going back to the depot for is now a depot setting**, rather than fixed at two
  minutes. A busy metro and a quiet branch want different answers.

### Fixes

- **The compartment you were riding in could vanish.** Nothing the mod draws goes through Minecraft's own culling,
  so 3.2.3 added its own — and it could cull a car whose reference point had passed behind the camera while its
  geometry still filled the screen. Nothing the camera is standing inside is culled now. There is also a switch
  for the whole thing in the mod options: if parts of a train still disappear, turning it off says so, and that is
  worth reporting.
- **Station waypoints all landed at your own height.** They now sit at the average height of the station's own
  platforms, so a waypoint is at track level whether the station is underground, elevated or on the surface.

### Removed

- **Door marking is gone**, one release after it arrived. Right-clicking track with the brush no longer does
  anything.

`Minecraft_Transit_Railway_1.19.4_3.3.05.jar` · 62,781,813 B · `sha256 cc4113733d13c0de93a5ec386d414a74d4e4c6b9e927a7c77b7675e58380769a`

## 3.3.04 — 26 August 2026

### Building

- **Right-click track with the brush to mark where the doors are.** The marks are read off a train that is actually
  standing there rather than predicted, so they cannot be wrong: bring a train in, click the rail beside it, and
  every door gets an upright with a cross bar and every car boundary a plain upright. They stay after the train
  leaves, so the screen doors can be built against them. Click again to clear. Trains whose model does not say
  where its doors are still get their car boundaries marked.
- **Doors can open where nothing is built.** A siding can be set to open its trains' doors at every stop even with
  no platform block or screen door beside the track. Both sides open, because with nothing there the game has
  nothing to tell it which side the passengers are on.

### Interface

- **The options screen explains itself.** Hovering any option now says what it does, including which ones cost
  frames and which are only cosmetic. Nothing is added for an option that has nothing worth saying.
- **Every station onto the minimap at once.** With Xaero's Minimap installed, one button in the mod options adds a
  waypoint for every station on the server, into whichever waypoint set is open. Stations already there by name
  are left alone, and Xaero's own waypoint teleport then works on them.

`Minecraft_Transit_Railway_1.19.4_3.3.04.jar` · 62,782,963 B · `sha256 1baecedd725f3ef7dddf87b131056cc22e436e68779b589643028a863a972e57`

## 3.3.03 — 26 August 2026

### Operations

- **Trains no longer close up on traffic coming the other way.** 3.3.02 let a train enter a section another train
  was already in, as long as that train was far enough ahead — which is right for a train you are following and
  wrong for one coming towards you, because the two then meet in the middle of a section neither can reverse out
  of. A train now compares its own direction of travel with the other's and only closes up on one going the same
  way; opposing traffic keeps the whole-section claim it had before.
- **Two trains blocking each other now sort it out.** Each train publishes what is holding it up, so a pair each
  waiting on track the other is standing on can recognise the standoff from either side. The lower train id
  proceeds — arbitrary, but both compute it identically, so exactly one of them moves and the line returns to
  service instead of standing until someone breaks it up by hand. A ring of three or more is still a layout to
  fix rather than a case to paper over.

`Minecraft_Transit_Railway_1.19.4_3.3.03.jar` · 62,769,609 B · `sha256 6fd7f31e510f03308d338fd12b3ef3e38181183c6f165923e36e0fa51768cb6b`

## 3.3.02 — 26 August 2026

### Translations

- **Every language but English was silently dead.** Crowdin exports its files as `zh_TW.json`, but resource
  lookup inside a jar is case sensitive and Minecraft asks for `zh_tw`, so the mod never found any of them and
  fell back to English throughout. All 41 language files are now lowercase, and the build lowercases whatever
  Crowdin hands it in future. Traditional Chinese and Hong Kong Chinese also carry translations for everything
  added below.

### Operations

- **Strictly follow timetable.** A repeating train used to jump back to the start of its loop and go round again
  immediately; the departure list only ever governed the first dispatch out of the depot. A depot set to follow
  its timetable now holds the train at the origin until its booked departure, doors open, the way a terminus
  does. If the next departure is a long way off it stables in the depot instead of standing on the platform.
- **A long section carries several trains, and nobody queues at its mouth.** A train meeting an occupied section
  used to stop at its entrance however far away the train inside actually was, so a section a kilometre long ran
  one train at a time. A following train now reads how far ahead the train in front actually is and takes the
  fastest speed it could still pull up from within that gap. It eases off as it closes rather than stopping at a
  line, and comes to rest a safe margin short only if the train ahead never moves at all.
- **Cars per depot and per siding.** A depot can cap the cars of every train it dispatches; a siding can set its
  own count, and its slider reads out the length in blocks so a platform can be sized without doing the sum.

- **A manual, because the mod explains almost nothing.** [The operating manual](/mtr-manual/) covers getting a
  first train moving end to end, how departures and the strict timetable actually behave, what decides train
  length, per-rail speed limits, and an ordered list of what to check when nothing moves. English and Chinese.

### Track

- **A speed limit you can set on any rail.** Rather than adding more fixed rail types, a rail now carries its own
  limit in km/h, set through MTR-ANTE's rail brush. A train takes the lowest limit it can already see within its
  braking distance, so it slows on approach rather than arriving at the boundary too fast. A limit belongs to the
  track rather than to one direction, so it survives being flipped or copied onto another rail.


### Fixes

- **The car sliders showed no number.** The slider widget draws its reading just past its own right edge, and both
  the depot and the siding slider had been sized to fill the panel, so the number landed outside it.

- **Airplane routes never generated.** One-way rails record their blocked direction as a placeholder, and the
  runway finder counted those, so any runway that could actually be taxied into was invisible to it and no
  flight leg was ever built.

### Building

- **Teleport to where a path broke.** A depot that fails to generate a path now offers a jump to the exact spot,
  and marks it in the world with a red cage that stays up after the screen is closed.
- **Trains ran straight past their own booked departure.** The origin hold asked the depot when the next
  departure was due, but the depot marks a departure used the moment it releases the train from the siding — the
  very departure that train is running to. The hold therefore read the *following* one and the train sat through
  its booked time, leaving a whole headway late. Each train now carries the departure it was dispatched against,
  so the hold and the dispatch agree. A train that reaches the origin already late leaves as soon as its doors
  have worked rather than waiting for the next slot.
- **End of the day returns to the depot.** A train finishing the last run of the day no longer stands on the
  origin platform until the first departure of the next one. Anything more than a couple of minutes and it runs
  through to the depot and stables, and the ordinary dispatch releases it in the morning at its booked departure
  less the run in.

- **A visual editor for realistic-time departures.** The departure syntax was `HH:MM:SS + N * HH:MM:SS` and was
  documented nowhere. Four sliders now compose it into the field, which stays editable, so the syntax is visible
  rather than folklore.

### Vehicles

- **Vehicles lean and rock.** Cars bank into curves by the lateral acceleration they feel, and rock on their
  suspension as they run, phased along the rake so a train moves as a train rather than a rigid bar. Off when
  MTR-ANTE is installed, which does its own. Toggle in the mod options.

`Minecraft_Transit_Railway_1.19.4_3.3.02.jar` · 62,767,869 B · `sha256 83b04aa174ea0827498ed4de1d4ee5291dd3c36a307ff38e7b5785a0a6dec76e`

## 3.2.3

### Performance

- **Nothing MTR drew was ever frustum culled.** Trains are not entities — they live in a list the
  mod walks itself — and the block entity renderers do not draw at all; they push closures into a
  queue that one entity renderer drains later. Neither route passes through the culling vanilla
  applies to ordinary entities and block entities, so every train, rail, sign and platform door in
  range was submitted whether or not it was behind you. The one shared visibility gate every one of
  those paths already called now also tests the camera frustum, fed per frame by both loaders. The
  test carries a 24-block margin — a full car plus its gangway — so nothing pops in at the edge of
  the screen.
- **Everything the block entities queued was drawn several times over.** The queue was swapped
  once per *game tick*, but block entities fill it once per *frame*. Between ticks the submissions
  piled up instead of replacing each other, and the whole pile was drawn on each of the following
  frames: three copies of every sign, door and platform at 60 FPS, six at 120. The cost grew with
  your framerate, which is the opposite of what a framerate is for. Swapped per frame now, so each
  queued draw happens once.
- **The door scan ran every frame instead of every tick, and its answer was usually discarded.**
  Placing a car asks whether a platform is beside it, once per side, and that scan reads up to
  ~450 block states for a 24-block car. It runs from the per-frame render path, not the tick — and
  on the client the result is immediately ANDed with "are the doors even open", so for a moving
  train the entire scan was thrown away. Skipped outright now while the doors are shut. The server
  still scans every tick, because there it drives the platform doors and its result is real.
- **The station lookup cached nothing.** `getStation` checked a `blockPosToStation` map that was
  cleared on sync and never written to, so the fallback ran every single time: a linear scan of
  every station in the world, allocating a stream to do it. Every PIDS, railway sign and station
  name block asks this once per frame. It now stores what it finds, misses included.
- **Reading a sign's definition threw and caught an exception.** `getSign` resolved built-in ids
  through `valueOf`, let it throw for anything custom, and caught that to fall through to the
  custom table — building a fresh object either way. Filling in a sign block calls it about a
  hundred times per frame, because each sign re-scans its neighbours twice to measure available
  width. Memoised, with the exception path gone.
- **Texture paths were rebuilt from format strings every frame.** Platform screen doors formatted
  four paths and wrapped each in a new `ResourceLocation` per door per frame; the gangway and
  barrier renderers did twelve per car per frame; the dynamic sign cache built its lookup keys with
  `String.format`. All precomputed or cached. The rail, signal, clock and semaphore textures are
  constants now rather than allocations, the render-layer enum is no longer cloned inside a nested
  loop, and a lift no longer rebuilds its entire model on every frame it is visible.
- **The CJK test walked a 700-entry table per character.** Deciding whether a string needs the CJK
  font called `Character.UnicodeBlock.of` — a binary search — for every character, through a fresh
  stream, on every line of text every frame. Latin text now rejects below U+1100 without the
  search. The rewrite was checked against the original over every code point in Unicode.

### Fixed

- **Hiding translucent parts leaked.** The list of translucent draws is only emptied by the pass
  that renders them, and that pass is skipped entirely when *hide translucent parts* is on — but
  the draws were still being added. One closure per car per frame accumulated for as long as the
  train existed. Nothing is queued in that mode now.

### Notes

Verified against MTR-ANTE: all 352 fields and methods it shadows in MTR still resolve. One of them
constrains this build — `TrainClient.trainTranslucentRenders` has to stay a `Set`, because a shadow
matches on name *and* type, and narrowing it to a `List` stops ANTE from loading. It is marked in
the source.

No frame-rate figures are quoted here because none were measured. What is claimed above is
structural: work that was being done more than once per frame, or thrown away after being done.

Building the mod from source also needed three unrelated repairs, all of them upstream rot rather
than anything in the mod: a font library URL that now 404s, a Crowdin export that returns an error
page instead of an archive, and an open version range that had drifted onto a dependency the
build's remapper cannot read.

`Minecraft_Transit_Railway_1.19.4_3.2.3.jar` · 62,445,769 B · `sha256 c05dccca83fd9276a6eb821e2387dab21c0f85ce0742dc0ad810b5f2e0d61c98`
