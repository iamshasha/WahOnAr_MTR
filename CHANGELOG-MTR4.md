# Changelog — MTR 4 line

This is the changelog for the **`MTR-4-DEV`** branch only. It is kept separate from
[CHANGELOG.md](CHANGELOG.md) on purpose: `main` is the MTR 3 line, that is what the Wah On Ar server actually
runs, and an operator reading its changelog should never be told about something they cannot use.

Nothing here ships. The plan this branch exists to carry out is in [MIGRATION.md](MIGRATION.md)
· [遷移計劃](MIGRATION.zh-Hant.md).

## Unreleased

Branch opened. No MTR 4 work has landed yet.

Planned, roughly in the order it has to happen:

- **The forward world converter, MTR 3 → MTR 4.** MTR 4's own `org.mtr.legacy` does most of the mapping already.
  What it gets wrong for this server is its rail-type table — it resolves a saved rail by name against a hardcoded
  list of the sixteen upstream types and falls back to WOODEN, 20 km/h, for anything else, which is every rail
  absorbed from the High Speed Rails addon and every one of ANTE's ~640 synthetic `P###` types. It also never
  reads the per-rail speed that MTR 3.4.0 now stamps onto every rail at risk.
- **The reverse converter, MTR 4 → MTR 3.** Written second, because the forward pass is what establishes the
  field mapping. Allowed to be lossy for MTR 4 fields with no MTR 3 counterpart; track geometry and timetables
  are not.
- **Compound Creator and Displacement Tool.** The two things MTR 4 genuinely has no equivalent for. Both live in
  the mod layer, neither uses mixins.

Both converters are standalone programs rather than mods: each side stores a world as MessagePack files under
`<world>/mtr/<namespace>/<dimension>/`, and for everything MTR 3 has the folder names are identical. So they need
no Minecraft to run or to test, and can be pointed at a copy of the real world and diffed.
