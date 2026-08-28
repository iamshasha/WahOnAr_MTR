# MTR 4 work

Everything here is for the migration and none of it affects the MTR 3 mod. This branch exists so that work can
start without the MTR 3 line ever having to carry a half-finished MTR 4 feature.

## What is in here

| | |
| --- | --- |
| `core/0001-legacy-rail-speed.patch` | Our changes to Transport-Simulation-Core, MTR 4's simulation engine. |
| `apply.sh` | Clones that project, applies the patch, and builds it. |

## Why a patch and not a fork

Transport-Simulation-Core is actively developed and MIT licensed. A fork would need merging forever; a patch that
stops applying is a useful signal that something we depend on changed. The patch is four files and 75 lines.

If it does stop applying, the fix is almost always to rebase it onto the new upstream commit and update `BASE` in
`apply.sh`. The changes are small and self-contained enough that this should be minutes, not an afternoon.

## What the patch does

MTR 4's converter reads a legacy rail's type by name, matches it against the sixteen types upstream MTR has
always had, and falls back to **WOODEN, 20 km/h**, for anything else. The fallback is silent and the original
speed is unrecoverable, because the name was the only record of it.

Two populations of rail on this server hit that fallback:

- The five types from the retired High Speed Rails addon, 450 to 800 km/h, absorbed into MTR 3.4.0.
- The roughly 640 types MTR-ANTE creates at runtime, one per km/h, named `P-1` to `P600` and then `P1000` to
  `P10000` in steps of 500.

Between them that is most of the high-speed network. Left alone it all becomes slow wooden track on migration
day.

The patch teaches the converter three things, in order of preference:

1. **The speed the rail saved on itself.** MTR 3.4.0 writes this out precisely so it outlives the type name, and
   a builder may have set it by hand, so it wins over everything else. The legacy schema was reading the field
   and throwing it away; now it keeps it.
2. **The five absorbed types**, by name, with their speeds.
3. **The speed spelled out in a `P###` name**, for ANTE's synthetic types.

`LegacyRailLoader` also now carries a speed per direction rather than a single rail type, so a one-way rail keeps
a different limit each way, the way it does in MTR 3.

A world with none of these is unaffected: every path falls through to the type's own speed, exactly as before.

## Verifying

`apply.sh` builds the project, which runs `LegacyRailSpeedTests` — four cases covering the saved speed winning,
the five absorbed types, the `P###` names, and, most importantly, the things that must *not* be guessed at. It
then checks that the generated schema actually contains the new field, since `generateSchemaClasses` writes code
upstream does not commit and building is the only proof the schema change took.

Before migrating a real world, run [`tools/RailAudit.java`](../tools/RailAudit.java) against it. It lists every
rail type present, says where each one comes from, and counts how many rails carry their own speed. Nothing
should be reported as `UNKNOWN`, and the stamped count for the absorbed types should equal the total.

## Still to do

- **The world converter itself**, MTR 3 to MTR 4, running against a copy of the live world and diffed.
- **The reverse converter**, MTR 4 back to MTR 3. Written second, because the forward pass establishes the field
  mapping. Allowed to be lossy for MTR-4-only data; track geometry and timetables have to survive.
- **Compound Creator and Displacement Tool**, ported to MTR 4 in the ANTE repository's own `MTR-4-DEV` branch.

The reasoning behind all of this, including the backup and revert procedure, is in
[MIGRATION.md](../MIGRATION.md).
