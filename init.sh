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

# Spec-vs-own traceability (F-027): feature_list.json <-> docs/decisions.md <-> docs/requirements.md.
# Stdlib only, runs on every invocation (about 50 ms). `./init.sh --check-registry` runs only this step and exits
# (handy for negative controls and for quick checks while editing the register).
echo "==> Validating spec traceability and decisions register"
python3 - <<'PY'
import json, re, sys

errors = []
def fail(msg):
    errors.append(msg)

ORIGINS = {"spec", "interpretation", "own"}
DP_RE = re.compile(r"DP-\d{3}")
DP_TYPES = {"Interpretación del enunciado", "Extra propio"}
REQ_STATUSES = ["Cumplido", "Cumplido con interpretación", "Parcial", "Solo diseño (sin despliegue)", "No cumplido"]
REQ_ID_RE = re.compile(r"(CTX|OBJ|NG|RF|RT|ET|EN|SEG|DIA|AWS)-\d+(\.\d+)?")

def read(path):
    try:
        return open(path, encoding="utf-8").read()
    except OSError as e:
        fail(f"{path}: cannot read ({e.strerror})")
        return ""

def cells(line):
    parts = [c.strip() for c in line.strip().split("|")]
    return parts[1:-1] if len(parts) >= 3 else []

def outside_fences(text):
    """Yield lines that are not inside a fenced code block (templates and examples live in fences)."""
    inside = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            inside = not inside
            continue
        if not inside:
            yield line

# --- docs/decisions.md: one "### DP-NNN" heading per decision, each with Tipo and Estado, plus an index table ---
decisions = {}            # id -> {"tipo":..., "estado":...}
dec_lines = list(outside_fences(read("docs/decisions.md")))
current = None
for line in dec_lines:
    h = re.match(r"###\s+([^\s:]+)", line)
    if h:
        token = h.group(1)
        if not DP_RE.fullmatch(token):
            fail(f"docs/decisions.md: malformed decision id in heading '{line.strip()}' (expected DP-NNN, three digits)")
            current = None
            continue
        if token in decisions:
            fail(f"docs/decisions.md: duplicate decision id {token}")
        decisions[token] = {"tipo": None, "estado": None}
        current = token
        continue
    if line.startswith("## "):
        current = None
    if current:
        m = re.match(r"-\s+\*\*(Tipo|Estado):\*\*\s*(.+?)\s*$", line)
        if m:
            decisions[current]["tipo" if m.group(1) == "Tipo" else "estado"] = m.group(2)
for dp, d in decisions.items():
    if d["tipo"] not in DP_TYPES:
        fail(f"docs/decisions.md: {dp} has invalid or missing Tipo '{d['tipo']}' (valid: {sorted(DP_TYPES)})")
    est = d["estado"] or ""
    rep = re.fullmatch(r"Reemplazada por (DP-\d{3})", est)
    if est != "Vigente" and not rep:
        fail(f"docs/decisions.md: {dp} has invalid or missing Estado '{est}' (valid: 'Vigente' or 'Reemplazada por DP-NNN')")
    elif rep and rep.group(1) not in decisions:
        fail(f"docs/decisions.md: {dp} is replaced by {rep.group(1)}, which does not exist")
    elif rep and rep.group(1) == dp:
        fail(f"docs/decisions.md: {dp} cannot replace itself")
index = {}
for line in dec_lines:
    c = cells(line)
    if len(c) == 4 and re.fullmatch(r"\[?(DP-\d{3})\]?(\(.*\))?", c[0]):
        dp = re.match(r"\[?(DP-\d{3})", c[0]).group(1)
        if dp in index:
            fail(f"docs/decisions.md: {dp} appears twice in the index table")
        index[dp] = (c[2], c[3])
if set(index) != set(decisions):
    fail("docs/decisions.md: index table and '### DP-NNN' entries differ: "
         f"only in index {sorted(set(index) - set(decisions))}, only in entries {sorted(set(decisions) - set(index))}")
for dp, (tipo, estado) in index.items():
    d = decisions.get(dp)
    if d and (tipo != d["tipo"] or estado != d["estado"]):
        fail(f"docs/decisions.md: index row of {dp} ('{tipo}' / '{estado}') does not match its entry ('{d['tipo']}' / '{d['estado']}')")

# --- feature_list.json: origin on every feature; non-spec features must list decisions; references must exist ---
features = json.load(open("feature_list.json", encoding="utf-8"))
referenced = set()
for f in features:
    fid = f["id"]
    origin = f.get("origin")
    if origin not in ORIGINS:
        fail(f"{fid}: missing or invalid origin '{origin}' (valid: spec, interpretation, own)")
    decs = f.get("decisions", [])
    if not isinstance(decs, list) or not all(isinstance(x, str) for x in decs):
        fail(f"{fid}: 'decisions' must be a list of DP-NNN ids")
        decs = []
    if origin in ("interpretation", "own") and not decs:
        fail(f"{fid}: origin '{origin}' requires a non-empty 'decisions' list (register the decision in docs/decisions.md)")
    if len(decs) != len(set(decs)):
        fail(f"{fid}: duplicate ids in 'decisions'")
    for dp in decs:
        if not DP_RE.fullmatch(dp):
            fail(f"{fid}: malformed decision id '{dp}' (expected DP-NNN, three digits)")
        elif dp not in decisions:
            fail(f"{fid}: references {dp}, which does not exist in docs/decisions.md")
        referenced.add(dp)
for dp, d in decisions.items():
    if dp not in referenced and not (d["estado"] or "").startswith("Reemplazada"):
        fail(f"docs/decisions.md: {dp} is not referenced by any feature in feature_list.json (orphan decision)")

# --- docs/requirements.md: one row per statement item; valid status; interpretation needs an existing DP ---
req_lines = list(outside_fences(read("docs/requirements.md")))
rows, counts = {}, {s: 0 for s in REQ_STATUSES}
summary = {}
for line in req_lines:
    c = cells(line)
    if not c:
        continue
    first = re.sub(r"[`*]", "", c[0])
    if REQ_ID_RE.fullmatch(first):
        if len(c) != 6:
            fail(f"docs/requirements.md: row {first} has {len(c)} columns, expected 6 (ID | Requisito | Estado | DP | Dónde | Evidencia)")
            continue
        if first in rows:
            fail(f"docs/requirements.md: duplicate row id {first}")
        rows[first] = c
        status = c[2]
        if status not in REQ_STATUSES:
            fail(f"docs/requirements.md: row {first} has invalid status '{status}' (valid: {REQ_STATUSES})")
        else:
            counts[status] += 1
        dps = DP_RE.findall(c[3])
        if status == "Cumplido con interpretación" and not dps:
            fail(f"docs/requirements.md: row {first} is 'Cumplido con interpretación' but links no DP-NNN")
        for dp in dps:
            if dp not in decisions:
                fail(f"docs/requirements.md: row {first} references {dp}, which does not exist in docs/decisions.md")
        if not c[4] or not c[5]:
            fail(f"docs/requirements.md: row {first} must say where it is implemented and give evidence")
    elif len(c) == 2 and (c[0] in REQ_STATUSES or c[0] == "Total") and c[1].isdigit():
        summary[c[0]] = int(c[1])
if not rows:
    fail("docs/requirements.md: no matrix rows found")
else:
    expected = dict(counts)
    expected["Total"] = len(rows)
    for key, n in expected.items():
        if summary.get(key) != n:
            fail(f"docs/requirements.md: coverage summary says {key}={summary.get(key)} but the matrix has {n}")

if errors:
    print("FAIL: spec traceability / decisions register", file=sys.stderr)
    for e in errors:
        print("  - " + e, file=sys.stderr)
    sys.exit(1)
print(f"OK: {len(features)} features with origin, {len(decisions)} decisions, {len(rows)} requirement rows")
PY

if [ "${1:-}" = "--check-registry" ]; then
  exit 0
fi

# CI runner pinning (F-029, DP-036): no workflow may use a floating runner label (`ubuntu-latest`, `macos-latest`...).
# Stdlib only (no YAML parser available), so it reads each `runs-on` line and, for list or mapping forms, the more
# indented lines under it. A `${{ ... }}` expression cannot be resolved statically, so it is reported as a failure.
# `./init.sh --check-runners` runs only the fast checks up to this one and exits.
echo "==> Validating CI runner images are pinned (no -latest labels)"
python3 - <<'PY'
import glob, re, sys

errors, checked = [], 0
for path in sorted(glob.glob(".github/workflows/*.yml") + glob.glob(".github/workflows/*.yaml")):
    lines = open(path, encoding="utf-8").read().splitlines()
    for i, raw in enumerate(lines):
        m = re.match(r"^(\s*)(?:-\s+)?runs-on\s*:(.*)$", raw)
        if not m:
            continue
        indent, rest = len(m.group(1)), re.sub(r"\s+#.*$", "", m.group(2)).strip()
        where = f"{path}:{i + 1}"
        tokens = [rest] if rest else []
        if not rest:                       # block list or mapping (group/labels): take the more indented lines
            for nxt in lines[i + 1:]:
                if not nxt.strip() or nxt.lstrip().startswith("#"):
                    continue
                if len(nxt) - len(nxt.lstrip()) <= indent:
                    break
                tokens.append(re.sub(r"\s+#.*$", "", nxt).strip())
        text = " ".join(tokens)
        checked += 1
        if not text:
            errors.append(f"{where}: runs-on has no value; cannot verify the runner image")
            continue
        if "${{" in text:
            errors.append(f"{where}: runs-on uses an expression ({text}); cannot verify it is pinned, use a literal label")
            continue
        for label in re.split(r"[\s,\[\]{}]+", text):
            label = label.strip("'\"")
            label = re.sub(r"^(-|group:|labels:)$", "", label)
            if re.search(r"-latest($|-)", label):
                errors.append(f"{where}: floating runner label '{label}'; pin an explicit image such as ubuntu-24.04 (see docs/verification.md, DP-036)")
if errors:
    print("FAIL: unpinned CI runner image", file=sys.stderr)
    for e in errors:
        print("  - " + e, file=sys.stderr)
    sys.exit(1)
print(f"OK: {checked} runs-on entries, none uses a floating label")
PY

if [ "${1:-}" = "--check-runners" ]; then
  exit 0
fi

# Release gate scripts (F-032, DP-040): offline test with a fake `gh` (about 3 s).
echo "==> Testing the release gate scripts"
scripts/test-release-scripts.sh

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
