# Changelog — MTR 4 line

This is the changelog for the **`MTR-4-DEV`** branch only. It is kept separate from
[CHANGELOG.md](CHANGELOG.md) on purpose: `main` is the MTR 3 line, that is what the Wah On Ar server actually
runs, and an operator reading its changelog should never be told about something they cannot use.

Nothing here ships. The plan this branch exists to carry out is in [MIGRATION.md](MIGRATION.md)
· [遷移計劃](MIGRATION.zh-Hant.md).

## Unreleased

### Legacy rail speeds survive the conversion

The first piece of the forward converter, kept as a patch against
[Transport-Simulation-Core](https://github.com/Minecraft-Transit-Railway/Transport-Simulation-Core) rather than a
fork, so that project's updates keep arriving. It lives in [mtr4/](mtr4/), and `mtr4/apply.sh` clones, patches
and builds it in one command.

MTR 4's converter read a legacy rail's type by name and fell back to WOODEN, 20 km/h, for any name it did not
know — silently, and with the original speed unrecoverable, since the name was the only record of it. That is
every rail absorbed from the High Speed Rails addon and every one of ANTE's ~640 synthetic `P###` types: most of
the high-speed network. It now tries three things in order — the speed the rail saved on itself, which MTR 3.4.0
writes out precisely so it outlives the name and which a builder may also have set by hand; the five absorbed
type names; and the speed spelled out in a `P###` name. One-way rails keep a different limit each way, as they
do in MTR 3.

A world with none of these converts exactly as it did before. Four tests cover it, including the cases that must
*not* be guessed at, and they run as part of the build.

Still planned, roughly in the order it has to happen:

- **The rest of the forward world converter, MTR 3 → MTR 4.** The rail speeds above were the part that loses
  data outright. What remains is running it against a copy of the live world and diffing the result, station by
  station and timetable by timetable.
- **The reverse converter, MTR 4 → MTR 3.** Written second, because the forward pass is what establishes the
  field mapping. Allowed to be lossy for MTR 4 fields with no MTR 3 counterpart; track geometry and timetables
  are not.
- **Compound Creator and Displacement Tool.** The two things MTR 4 genuinely has no equivalent for. Both live in
  the mod layer, neither uses mixins.

Both converters are standalone programs rather than mods: each side stores a world as MessagePack files under
`<world>/mtr/<namespace>/<dimension>/`, and for everything MTR 3 has the folder names are identical. So they need
no Minecraft to run or to test, and can be pointed at a copy of the real world and diffed.
