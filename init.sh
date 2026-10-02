#!/usr/bin/env bash
# Single verification entry point: used by implementer, reviewer and CI.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> Validating feature_list.json"
python3 - <<'PY'
import json, sys
features = json.load(open("feature_list.json"))
ids = [f["id"] for f in features]
assert len(ids) == len(set(ids)), "duplicate feature ids"
valid = {"pending", "in_progress", "done"}
for f in features:
    assert f["status"] in valid, f"{f['id']}: invalid status {f['status']}"
    for dep in f.get("depends_on", []):
        assert dep in ids, f"{f['id']}: unknown dependency {dep}"
in_progress = [f["id"] for f in features if f["status"] == "in_progress"]
assert len(in_progress) <= 1, f"more than one in_progress: {in_progress}"
print(f"OK: {len(features)} features, in_progress={in_progress}")
PY

if [ ! -x ./gradlew ]; then
  echo "BOOTSTRAP: no ./gradlew yet (feature F-001 pending). Skipping build."
  exit 0
fi

# Integration tests need Docker: opt in with INCLUDE_INTEGRATION=true (CI does).
GRADLE_ARGS=()
if [ "${INCLUDE_INTEGRATION:-false}" = "true" ]; then
  GRADLE_ARGS+=("-PincludeIntegration")
  echo "==> Integration tests enabled (INCLUDE_INTEGRATION=true)"
fi

echo "==> Building and verifying (tests + 90% coverage gate)"
./gradlew --no-daemon ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"} clean build jacocoTestReport jacocoTestCoverageVerification
echo "==> init.sh OK"
