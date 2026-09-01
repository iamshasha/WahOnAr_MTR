#!/usr/bin/env bash
# Compiles and runs every check against a built jar.
#
#   ./checks/run.sh build/release/MTR-fabric-1.19.4-3.4.1.jar
#
# Some checks only need the jar. Others name Minecraft types -- Rail reaches Mth, which drags in Util, which
# drags in Mojang's logging, and so on -- so those need Minecraft and the libraries it loads with. Both come from
# the PrismLauncher instance the builds are tested in rather than from a separate download, so there is nothing
# to keep in sync. The classpath is written to a file because 366 jars is past what Windows will accept on a
# command line.
set -euo pipefail

JAR="${1:-}"
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
	echo "usage: $0 <path to a built MTR jar>" >&2
	exit 2
fi

JAVA_BIN="${JAVA_BIN:-C:/Users/Hry/AppData/Local/Programs/PrismLauncher/java/java-runtime-gamma/bin}"
MINECRAFT_JAR="${MINECRAFT_JAR:-C:/Users/Hry/.gradle/caches/fabric-loom/1.19.4/minecraft-merged-intermediary.jar}"
LIBRARIES="${LIBRARIES:-C:/Users/Hry/AppData/Roaming/PrismLauncher/libraries}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python - "$JAR" "$MINECRAFT_JAR" "$LIBRARIES" "$WORK" <<'PY'
import os, sys, subprocess
jar, minecraft, libraries, work = sys.argv[1:5]
jars = [os.path.join(root, name)
        for root, _, files in os.walk(libraries) for name in files
        if name.endswith(".jar") and not any(skip in name for skip in ("sources", "javadoc", "natives"))]
classpath = ";".join([os.path.abspath(jar), minecraft, os.path.join(work, "classes")] + jars)
with open(os.path.join(work, "cp.txt"), "w") as handle:
    handle.write('-cp\n"%s"\n' % classpath.replace("\\", "/"))
print("%d libraries on the classpath" % len(jars))
PY

CP_ARGS="@$(cygpath -w "$WORK/cp.txt")"
mkdir -p "$WORK/classes"

echo "--- mixin shape"
python checks/mixin_shape_check.py "$JAR" "$JAVA_BIN/javap.exe"

echo "--- language files"
python checks/lang_json_check.py "$JAR"

echo "--- MTR-ANTE binding anchor"
LAMBDA_COUNT=$("$JAVA_BIN/javap.exe" -p -classpath "$JAR" mtr.render.RenderTrains 2>/dev/null | grep -c 'lambda\$render\$8' || true)
if [ "$LAMBDA_COUNT" != "1" ]; then
	echo "FAIL RenderTrains has $LAMBDA_COUNT lambda\$render\$8, expected exactly 1 -- MTR-ANTE binds to it by index" >&2
	exit 1
fi
echo "lambda\$render\$8: 1"

RAILTYPE_STATICS=$("$JAVA_BIN/javap.exe" -classpath "$JAR" mtr.data.RailType | grep -cE 'readSaved|saveSpeedLimit' || true)
if [ "$RAILTYPE_STATICS" != "0" ]; then
	echo "FAIL RailType carries migration helpers again. The retired High Speed Rails addon ships its own copy of" >&2
	echo "     that class and Fabric prefers it, so anything added here is missing on a client that still has the" >&2
	echo "     addon installed -- and Rail calls these on every read. They belong in RailTypeMigration." >&2
	exit 1
fi
echo "RailType statics: 0"

echo "--- compiling checks"
"$JAVA_BIN/javac.exe" "$CP_ARGS" -d "$WORK/classes" checks/*.java

for CHECK in DepartureLedgerCheck TrainDeadlockCheck TrainCatchUpCheck RailTypeMigrationCheck RailStampCheck Mtr4PackCheck Mtr4AssemblyCheck DepartureClaimCheck DepartureStabilityCheck; do
	MSYS_NO_PATHCONV=1 "$JAVA_BIN/java.exe" "$CP_ARGS" "$CHECK"
done

echo "all checks passed"
