# Changelog — MTR 4 line

This is the changelog for the **`MTR-4-DEV`** branch only. It is kept separate from the MTR 3 changelog on
`main` on purpose: `main` is what the Wah On Ar server actually runs, and an operator reading its changelog
should never be told about something they cannot use.

Nothing here ships yet. The plan this branch exists to carry out is in [MIGRATION.md](MIGRATION.md)
· [遷移計劃](MIGRATION.zh-Hant.md).

## Unreleased

### The branch is MTR 4 now

It used to be MTR 3's tree with a folder of notes about MTR 4 in it, which was the wrong shape for doing the
work. It is now upstream MTR 4 itself, forked from the `4.0.6` branch.

`4.0.6` rather than `master` for one reason: it is the newest upstream branch whose build still lists
**1.19.4**, and after it upstream drops that version. The Wah On Ar server is 1.19.4, so from here on keeping
1.19.4 building is this fork's work, not upstream's. That also settles a question that was open: MTR 4 does
build for 1.19.4, verified here, not assumed.

The old MTR 3-based branch is kept as the tag `mtr3-era-mtr4-dev`; nothing was lost.

### Migration happens when the world loads, not from a script

Upstream already works this way — `LegacyRailLoader.load` runs inside `Simulator`'s constructor, before anything
else is read, so a world saved by MTR 3 converts itself on the first start. Our changes go in the same place
rather than beside it. There is no separate step for an operator to remember, and nothing to run on a server
panel that cannot execute shell scripts.

### Legacy rail speeds survive that conversion

The engine resolves a saved rail's type by name against the sixteen types upstream MTR has always had, and falls
back to **WOODEN, 20 km/h**, for anything else. The fallback is silent and the speed is unrecoverable afterwards,
because the name was the only record of it.

Two populations of rail on this server hit it: the five types absorbed from the retired High Speed Rails addon,
450 to 800 km/h, and the roughly 640 MTR-ANTE creates at runtime, one per km/h, named `P-1` to `P600` and then
`P1000` to `P10000` in steps of 500. Between them, most of the high-speed network.

The fix tries three things in order — the speed the rail saved on itself, which MTR 3.4.0 writes out precisely so
it outlives the name and which a builder may also have set by hand; the five absorbed type names; and the speed
spelled out in a `P###` name. One-way rails keep a different limit each way, as they do in MTR 3. A world with
none of these converts exactly as it did before.

It is held as [`wahonar/0001-legacy-rail-speed.patch`](wahonar/0001-legacy-rail-speed.patch) against
[Transport-Simulation-Core](https://github.com/Minecraft-Transit-Railway/Transport-Simulation-Core), the engine
MTR 4 vendors as `libs/Transport-Simulation-Core-0.0.1.jar`. Four tests cover it, including the cases that must
*not* be guessed at, and they run as part of that project's build.

**Not yet applied to the vendored jar.** The engine and the mod disagree about Java: upstream builds the engine
down to Java 8 because MTR 4 still supports Minecraft 1.16.5, while current engine source needs Java 21, and the
mod rejects a newer class file outright. Resolving that is the next decision, and it is recorded in MIGRATION.md
rather than guessed at here.

### Still to do

- **Apply the engine patch** once the Java question above is settled, and convert a copy of the live world.
- **The reverse converter, MTR 4 → MTR 3.** Written second, because the forward pass establishes the field
  mapping. Allowed to be lossy for MTR 4 fields with no MTR 3 counterpart; track geometry and timetables are not.
- **Compound Creator and Displacement Tool**, the two things MTR 4 has no equivalent for. Both live in the mod
  layer and neither uses mixins, so there is somewhere for them to attach.
- **MTR 4 train pack support in MTR 3**, so packs authored for MTR 4 work on the server before it moves.
