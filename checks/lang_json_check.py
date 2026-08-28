"""Fails if any language file in a built jar is not valid JSON.

Why this exists: 3.4.3 nearly shipped with a broken `en_us.json`. A new string contained a raw double quote,
which ends the JSON string early, and the whole English file then failed to parse -- not one entry, all 836 of
them, and every screen in the game falls back to showing raw translation keys. Nothing in the build says so. The
file is copied into the jar whatever it contains, the mod loads, and the damage only appears on someone's screen.

Checking the source tree would not have caught it either, because a lang file is edited in several places and
only the jar shows what was actually shipped. So this reads the jar.

    python checks/lang_json_check.py <path to a built jar>
"""

import json
import sys
import zipfile

LANG_PREFIX = "assets/mtr/lang/"


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2

    jar = argv[1]
    checked = 0
    broken = []

    with zipfile.ZipFile(jar) as archive:
        names = [n for n in archive.namelist() if n.startswith(LANG_PREFIX) and n.endswith(".json")]
        if not names:
            print("FAIL no language files found under %s -- has the layout changed?" % LANG_PREFIX)
            return 1

        for name in sorted(names):
            raw = archive.read(name).decode("utf-8-sig")
            checked += 1
            try:
                parsed = json.loads(raw)
            except ValueError as error:
                broken.append("%s: %s" % (name, error))
                continue
            if not isinstance(parsed, dict):
                broken.append("%s: parsed as %s, expected an object" % (name, type(parsed).__name__))

    if broken:
        print("FAIL %d of %d language files would ship unreadable:" % (len(broken), checked))
        for line in broken:
            print("  " + line)
        return 1

    print("lang json ok (%d files)" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
