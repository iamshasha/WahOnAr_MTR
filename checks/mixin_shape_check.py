"""Fails if a built jar's mixin annotations were compiled in a shape the shipped loader cannot read.

Why this exists: build.gradle used to ask fabric for the newest loader at build time, so the jar was compiled
against whatever had been published that morning rather than against what the game runs. Loader 0.19.4 widened
@Redirect's `at` from a single annotation to an array, so javac emitted `at = [@At(...)]` where every earlier
build emitted `at = @At(...)` -- and the MixinExtras that ships with loader 0.19.2, which is what clients run,
casts that value straight to AnnotationNode. Every client crashed before the main menu, on source that had not
changed a line.

@Inject genuinely declares `At[] at()`, so the array form is correct there and always has been. Every other
injector declares a single `At at()`, and those are the ones to look at.

The loader version is pinned now, but a pin can be raised by accident and the symptom appears only at runtime,
on someone else's machine, after the file is already published. This reads the shape back out of the finished
jar, which is the artifact that actually ships.

    python checks/mixin_shape_check.py fabric/build/libs/fabric-1.19.4-3.3.10.jar

Optionally pass the javap to use as a second argument; otherwise the one on PATH is used.
"""

import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# javap opens an annotation as `org.spongepowered.asm.mixin.injection.Redirect(`, then lists its members.
ANNOTATION_OPENER = re.compile(r"org\.spongepowered\.asm\.mixin\.injection\.(\w+)\($")
ARRAY_FORM_AT = re.compile(r"^at=\[@")
# The only injector whose `at` is genuinely plural.
PLURAL_AT = {"Inject"}


def array_form_offences(dumped):
    """Names the injectors in one javap dump whose single-valued `at` was compiled as an array."""
    offences = []
    for index, line in enumerate(dumped.splitlines()):
        opener = ANNOTATION_OPENER.search(line.strip())
        if not opener or opener.group(1) in PLURAL_AT:
            continue
        # javap puts each member on its own line, so `at` is the line after the opener when it is present.
        for following in dumped.splitlines()[index + 1:index + 3]:
            if ARRAY_FORM_AT.match(following.strip()):
                offences.append(opener.group(1))
                break
    return offences


def main(argv):
    if not argv:
        print(__doc__)
        return 2

    jar_path = Path(argv[0])
    javap = argv[1] if len(argv) > 1 else "javap"
    if not jar_path.is_file():
        print("no such jar: %s" % jar_path)
        return 2

    with zipfile.ZipFile(jar_path) as jar:
        mixin_entries = [n for n in jar.namelist() if n.startswith("mtr/mixin/") and n.endswith(".class")]
        if not mixin_entries:
            print("FAIL %s contains no mtr/mixin classes, so this check would pass vacuously" % jar_path.name)
            return 1

        offenders = []
        with tempfile.TemporaryDirectory() as work:
            jar.extractall(work, mixin_entries)
            for entry in sorted(mixin_entries):
                class_name = entry[:-len(".class")].replace("/", ".")
                try:
                    dumped = subprocess.run(
                        [javap, "-p", "-v", "-classpath", work, class_name],
                        capture_output=True, text=True, check=True,
                    ).stdout
                except (OSError, subprocess.CalledProcessError) as error:
                    print("FAIL could not read %s: %s" % (class_name, error))
                    return 2
                offences = array_form_offences(dumped)
                if offences:
                    offenders.append((class_name, offences))

    if offenders:
        print("FAIL %s was compiled against a newer Mixin than the game runs." % jar_path.name)
        for class_name, offences in offenders:
            print("  %s: %s compiled with the array form at=[@At(...)]"
                  % (class_name, ", ".join("@" + name for name in offences)))
        print("Rebuild with the pinned loader (build.gradle sets fabric_loader_version) before publishing.")
        return 1

    print("mixin shape ok (%d mixin classes, no single-valued at compiled as an array)" % len(mixin_entries))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
