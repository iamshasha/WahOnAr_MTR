#!/usr/bin/env bash
# Clones Transport-Simulation-Core, applies our converter changes, and builds it.
#
#   ./mtr4/apply.sh [directory]        # default: ../mtr4-reference/core
#
# The changes are kept as a patch rather than a fork because the upstream project is active and we want its
# updates. A patch that stops applying is a signal to look at what changed there; a fork would just drift.
#
# Nothing here touches the running server. It produces a jar under build/libs and a core to test the world
# converter against.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-$HERE/../../mtr4-reference/core}"
REPO="https://github.com/Minecraft-Transit-Railway/Transport-Simulation-Core.git"

# The commit the patch was written against. Later commits usually still take it; this is what to check first if
# it stops applying.
BASE="22e5ce4"

# Transport-Simulation-Core needs Java 21. MTR 3 builds on 17, so this is not the JDK the rest of the repo uses.
JAVA_HOME="${JAVA_HOME:-C:/Users/Hry/AppData/Roaming/PrismLauncher/java/java-runtime-delta}"
export JAVA_HOME

if [ ! -d "$TARGET/.git" ]; then
	echo "cloning into $TARGET"
	git clone "$REPO" "$TARGET"
fi

cd "$TARGET"
git fetch --all --quiet
echo "base commit $BASE is $(git log --oneline -1 "$BASE" 2>/dev/null || echo 'NOT PRESENT -- upstream may have rewritten history')"

if git log --oneline -1 --grep="Carry a legacy rail's real speed" >/dev/null 2>&1 \
	&& [ -n "$(git log --all --oneline --grep="Carry a legacy rail's real speed")" ]; then
	echo "patch already committed here; skipping apply"
else
	git checkout -B wahonar-converter "$BASE"
	git am "$HERE/core/0001-legacy-rail-speed.patch"
fi

# generateSchemaClasses writes into src/main/java/org/mtr/legacy/generated, which upstream does not commit, so
# the build is the only thing that proves the schema change is real.
./gradlew.bat build --console=plain

echo
grep -n "speed_limit" src/main/java/org/mtr/legacy/generated/data/RailNodeConnectionSchema.java \
	|| { echo "FAIL the generated schema has no speed_limit; the schema change did not take" >&2; exit 1; }
echo "core built with the converter changes"
