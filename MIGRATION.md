# Moving to MTR 4

This is the plan for taking the Wah On Ar server from this fork of MTR 3 to MTR 4. It is written to be read
before anything is decided, and it is deliberate about the parts that can go wrong.

Nothing here has happened yet. The server runs MTR 3, and will keep running it until every question below has an
answer somebody is happy with.

[繁體中文版本](MIGRATION.zh-Hant.md)

## Why move at all

Not because MTR 4 is newer. Three specific reasons.

**The addons have already moved.** JCM's split is clean and public: v1.2.2 is the last release for MTR 3, and
every v2.x release is MTR 4 only. The scripting engine — the part that makes custom PIDS behaviour possible —
exists only in v2. MSD, London Underground, TransitManager and Interference are in the same position. Staying on
MTR 3 does not mean waiting for those to catch up. It means they never do, because the versions we run are the
last ones that will ever exist for us.

**Most of what this fork adds, MTR 4 already has.** That was the surprise when we actually read the source rather
than guessing. The simulation lives in
[Transport-Simulation-Core](https://github.com/Minecraft-Transit-Railway/Transport-Simulation-Core), a separate
MIT-licensed project, and it ships:

| What this fork carries | MTR 4 equivalent |
| --- | --- |
| Strict timetable (wall-clock departures) | `Depot.setUseRealTime` / `getRealTimeDepartures`, editable in the depot screen |
| Stretched dwell to hold an early train | `Siding.getEarlyVehicleIncreaseDwellTime` |
| Catch-up for a late train | `Siding.getDelayedVehicleSpeedIncreasePercentage` and `…ReduceDwellTimePercentage` |
| Departure ledger, to stop two trains taking one departure | Departures are allocated to sidings up front, so it cannot happen |
| — | OneBusAway feeds and the online system map, both already built |

The last row is the point. MTR 4 does not claim a departure at dispatch time the way we do; it hands each
departure to exactly one siding when the timetable is generated, having first checked that a vehicle will be back
in time to run it. Every double-booking, phantom and duplicate-arrival bug this fork has fixed this month is a
bug that design does not have. Migrating is mostly deletion.

**The fork is a maintenance liability.** Every fix we make is one more thing to re-derive later, and every one is
carried by us alone.

## What we are doing first, so MTR 3 stays stable

The work happening now is on MTR 3 and is meant to be useful whether or not the migration ever happens.

- **Timetable logic is being moved out of Minecraft-coupled classes.** `DepartureLedger` is the pattern: plain
  arithmetic over plain types, with checks that run without launching the game. The arrival projection is next.
  This is worth doing on its own — that code has caused four bugs and has no coverage — and it happens to be
  exactly what makes it portable.
- **No MTR 4 code goes near the shipping branch.** The MTR 4 work on ANTE (the Compound Creator and Displacement
  Tool ports) lives on its own branch with its own changelog, so the MTR 3 line stays releasable at all times.
- **Every release is launch-tested before it is published**, and the published jar is byte-compared against the
  one that was tested.

## The one that will bite: rail types

MTR 4 converts an MTR 3 world on first load. Its converter carries a hardcoded copy of MTR 3's rail types
(`org.mtr.legacy.data.DataFixer.RailType`) and resolves a saved rail by name:

```java
return EnumHelper.valueOf(DataFixer.RailType.WOODEN, rail_type);
```

**Any rail type it does not recognise becomes WOODEN — 20 km/h.** That list has the sixteen upstream types and
nothing else. It does not have:

- the five absorbed from the High Speed Rails addon (450, 500, 600, 700, 800 km/h), and
- ANTE's ~640 synthetic `P###` types, one per km/h, which is how ANTE currently gives a rail an arbitrary speed.

So on the day we migrate, without preparation, every high-speed rail on the server silently becomes slow wooden
track. Not broken, not missing — just wrong, everywhere, with the original speed gone because the type name was
the only place it was recorded.

Two things address this, and the first is already done:

1. **Every affected rail now records its own speed** in the rail's `speedLimitKmh` field as well as in its type
   name. This costs nothing today and means the number survives even if the name does not.
2. **The converter is patchable.** `Transport-Simulation-Core` is MIT and MTR 4 consumes it as a plain jar in
   `libs/` via `flatDir`, so building our own and dropping it in is straightforward. Teaching its rail-type table
   about our five types, and making it read the saved per-rail speed, is a small change to source we are allowed
   to change.

Neither is a reason to migrate carelessly. It is a reason to migrate with a converter we have tested against a
copy of the real world first.

## The converters are ours to write, and they are ordinary programs

This looked like the hard part until we checked how each side actually stores a world. Both keep MessagePack
files under `<world>/mtr/<namespace>/<dimension>/`, split into folders — and for everything MTR 3 has, the folder
names are the same:

```
MTR 3   stations platforms sidings routes depots lifts rails
MTR 4   stations platforms sidings routes depots lifts rails  + homes landmarks
```

So a converter in either direction is a standalone MessagePack-to-MessagePack program. Not a mod, not a mixin, no
Minecraft on the classpath, no game to launch in order to test it. It runs against a copy of the real world and
its output can be diffed. That is the difference between a migration we can rehearse and one we have to trust.

**Forward, MTR 3 to MTR 4.** MTR 4's own `org.mtr.legacy` already does most of the mapping and is a worked
example of the field correspondence. What it gets wrong for us is the rail-type table above, and that it never
reads the per-rail speed we now stamp. Both are small changes to MIT source we are allowed to change and that
MTR 4 consumes as a plain jar from `libs/`.

**Backward, MTR 4 to MTR 3.** Worth writing, and worth writing *second*: the forward pass is what establishes
the field mapping the reverse one needs, so doing it the other way round means guessing twice. It can be
deliberately lossy — MTR 4 fields with no MTR 3 counterpart (deviation tracking, homes, landmarks) are simply
dropped. What must survive intact is track geometry and timetables, and those correspond one to one.

## If it goes wrong

**Rolling the mod back is easy.** Every version ever published is still on the archive CDN, and `map.json` pins
each one by version and SHA-256. Moving the entry back re-serves the old jar to every client on their next launch.
Nothing has to be rebuilt.

**Rolling the world back needs the reverse converter**, which is why it is on the list rather than treated as
impossible. MTR 4 rewrites MTR 3's data into its own format on first load and cannot put it back on its own.

Even with that converter, the first line of defence stays a **world backup taken immediately before the first
MTR 4 launch, and verified by loading it**. A backup nobody has opened is a guess, not a backup. The converter
buys back the playing time between that backup and the moment somebody notices a problem; the backup covers
everything else, and costs nothing. The sequence we intend:

1. Take the server down and copy the world.
2. Load the copy on a spare MTR 3 server and confirm the railway actually runs — trains dispatch, timetables hold.
3. Only then let MTR 4 touch the real world.

If something is wrong after the migration, we restore that copy and go back to the MTR 3 mod set from the CDN,
or run the reverse converter over the MTR 4 world if too much has happened since to throw away.

Better than either: **do not need them.** Running MTR 4 on a copy of the world, in parallel, for as long as it
takes to be confident turns the migration into a cutover rather than an experiment, and turns "revert" into
"carry on playing the MTR 3 server that never stopped". That path loses nothing at all, and it is the one to
take unless there is a reason not to.

## If an addon or train pack does not support MTR 4

**Train packs are the good case.** MTR 4 ships `org.mtr.legacy.resource` specifically to read MTR 3 vehicle,
rail, sign and object resources, so existing packs are expected to load. Expected is not verified — every pack
the server uses gets loaded on a test instance before the real migration, and any that fail get listed by name
rather than discovered by a player.

**Addons split into three cases:**

- *Already on MTR 4* — JCM, MSD, London Underground, TransitManager, Interference. These get newer versions than
  we can run today, which is a reason for the move rather than a risk of it.
- *Absorbed or superseded* — most of what ANTE adds is in MTR 4 already. The known gaps are the **Compound
  Creator** and the **Displacement Tool**, which MTR 4 has no equivalent for; MTR 4's bridge and tunnel creators
  cannot pick blocks per slice and it has no undo. Those two are being scoped as a standalone port. The route
  creator is not: it has zero recorded usage on this server.
- *Dead* — the High Speed Rails addon, which was removed from Modrinth and GitHub and is unmaintained. Its rail
  types have been absorbed into this fork so that removing the mod loses nothing, and there is nothing left to
  port.

Anything that turns out to be in none of those three is a blocker, and finding one is a reason to stop and
re-plan rather than to push on. The test instance exists to find them before the server does.

## Where this stands

Undecided, on purpose. The engineering case is now clear enough to act on; whether the server takes the downtime
and the risk is not an engineering decision.
