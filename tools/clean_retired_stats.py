"""Removes statistics for items a retired mod used to provide, so the server stops warning about them.

A player's statistics file records how many times they used each item, by id. When the mod that provided an item
is gone, the id no longer resolves, and the server logs a line about it for every such key at every login:

    Invalid statistic in ./world/stats/<uuid>.json: Don't know what highrails:rail_connector_500 is

The warnings are harmless -- Minecraft ignores the key and carries on -- so this exists only to quieten the log.
Nothing about the railway, the world, or anyone's inventory is involved.

    python tools/clean_retired_stats.py <path to world/stats>            # say what would change
    python tools/clean_retired_stats.py <path to world/stats> --write    # change it

Stop the server first. A running server rewrites these files when players disconnect and would undo the edit, or
lose whatever else it was holding. Every file that changes is backed up alongside itself as <name>.json.bak.
"""

import io
import json
import os
import sys

# Mod ids whose items no longer exist. High Speed Rails was pulled from Modrinth and GitHub, and its five rail
# types were absorbed into MTR 3.4.0, so nothing it provided is missing -- only these statistics keys remain.
RETIRED_NAMESPACES = ("highrails:",)


def clean(stats_dir, write):
    if not os.path.isdir(stats_dir):
        print("not a directory: %s" % stats_dir)
        return 2

    files_changed = 0
    keys_removed = 0

    for name in sorted(os.listdir(stats_dir)):
        if not name.endswith(".json"):
            continue
        path = os.path.join(stats_dir, name)

        try:
            with io.open(path, encoding="utf-8") as handle:
                data = json.load(handle)
        except ValueError as error:
            print("  skipped %s: not readable as JSON (%s)" % (name, error))
            continue

        removed_here = 0
        for category, entries in list(data.get("stats", {}).items()):
            if not isinstance(entries, dict):
                continue
            for key in list(entries):
                if key.startswith(RETIRED_NAMESPACES):
                    del entries[key]
                    removed_here += 1
            if not entries:
                del data["stats"][category]

        if removed_here:
            files_changed += 1
            keys_removed += removed_here
            print("  %s: %d" % (name, removed_here))
            if write:
                # Kept next to the original rather than in a temp folder, so restoring it is obvious
                os.replace(path, path + ".bak")
                with io.open(path, "w", encoding="utf-8") as handle:
                    handle.write(json.dumps(data, separators=(",", ":")))

    if files_changed == 0:
        print("nothing to remove")
    elif write:
        print("removed %d statistics across %d files; originals kept as .json.bak" % (keys_removed, files_changed))
    else:
        print("would remove %d statistics across %d files; pass --write to do it" % (keys_removed, files_changed))
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(clean(sys.argv[1], "--write" in sys.argv[2:]))
