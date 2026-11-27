#!/usr/bin/env bash
# 운영 DB 스키마를 CI 테스트용 덤프로 뜬다(docs/plan/pipeline-fix-verify).
#
# Mac 에서 실행한다. ssh `vps` 로 운영 postgres 컨테이너에서 pg_dump 를 돌리고 **읽기만** 한다.
# 데이터 행은 담지 않는다: 스키마(--schema-only) + flyway_schema_history 행(--inserts)만.
#
# V1__baseline.sql 은 운영 DB 덤프라 빈 DB 에서 다시 돌릴 수 없다(spatial_ref_sys 충돌).
# 그래서 CI 는 이 덤프를 복원하고 Flyway 가 덤프 뒤의 마이그레이션만 적용한다.
#
# 중단 조건(파일을 바꾸지 않는다):
#   - 데이터가 섞임: COPY 가 있거나, flyway_schema_history 아닌 INSERT 가 있음
#   - 역할·비밀번호 줄: CREATE ROLE / ALTER ROLE / PASSWORD '
#   - history 행을 다 못 읽음, 또는 실패(success=false) 행이 있음
#   - 드리프트: 운영에 적용된 버전이 origin/main 에 파일로 없거나, 최대 버전이 다름
#     (운영에만 있는 마이그레이션이면 CI 의 Flyway validate 가 깨진다)
#
# installed_by 는 운영 DB 사용자 이름이라 'ci' 로 바꿔 쓴다(Flyway validate 는 이 칸을 안 본다).
#
# 사용: ops/ci/refresh-schema.sh [출력 경로, 기본 ops/ci/schema.sql]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$ROOT/ops/ci/schema.sql}"
CONTAINER="${PG_CONTAINER:-nextroute-postgres-1}"
BODY="$(mktemp)"; FILES="$(mktemp)"; NEW="$(mktemp)"
trap 'rm -f "$BODY" "$FILES" "$NEW"' EXIT

dump() {
  # 컨테이너 안의 POSTGRES_USER/POSTGRES_DB 로 실행. 값은 이 쪽으로 나오지 않는다.
  ssh -o ConnectTimeout=10 vps "docker exec $CONTAINER sh -c 'pg_dump -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" $*'"
}

dump --schema-only --no-owner --no-privileges > "$BODY"
dump --data-only --inserts --no-owner --no-privileges -t public.flyway_schema_history >> "$BODY"

# 작업 트리가 아니라 origin/main 의 마이그레이션 목록과 비교한다.
git -C "$ROOT" fetch -q origin main
git -C "$ROOT" ls-tree --name-only origin/main src/main/resources/db/migration/ > "$FILES"

MAX_V="$(python3 -I - "$BODY" "$FILES" "$NEW" <<'PY'
import re, sys
from datetime import datetime, timezone
from pathlib import Path

body = Path(sys.argv[1]).read_text(encoding="utf-8")
problems = []
if re.search(r"^COPY ", body, re.M):
    problems.append("COPY 문이 있음(데이터 섞임)")
HIST = "INSERT INTO public.flyway_schema_history "
other = [l for l in body.splitlines() if l.startswith("INSERT INTO") and not l.startswith(HIST)]
if other:
    problems.append(f"flyway_schema_history 아닌 INSERT {len(other)}줄")
if re.search(r"^\s*(CREATE|ALTER) ROLE\b|PASSWORD '", body, re.M | re.I):
    problems.append("ROLE/PASSWORD 줄이 있음")

# VALUES (rank, 'version'|NULL, 'desc', 'type', 'script', checksum|NULL, 'by', 'at', ms, success);
TXT = r"'(?:[^']|'')*'"
ROW = re.compile(rf"^{re.escape(HIST)}VALUES \((\d+), (NULL|'([^']*)'), {TXT}, '(\w+)', {TXT}, (-?\d+|NULL), ({TXT}), ({TXT}), (\d+), (true|false)\);$")
lines = [l for l in body.splitlines() if l.startswith(HIST)]
applied, rows = set(), 0
def scrub(m):
    return m.group(0).replace(", " + m.group(6) + ", " + m.group(7), ", 'ci', " + m.group(7), 1)
out = []
for l in body.splitlines(keepends=True):
    if not l.startswith(HIST):
        out.append(l); continue
    m = ROW.match(l.rstrip("\n"))
    if not m:
        continue
    rows += 1
    if m.group(9) != "true":
        problems.append(f"실패한 history 행 rank={m.group(1)}")
    if m.group(3) and m.group(4) != "BASELINE":
        applied.add(int(m.group(3).split(".")[0]))
    out.append(scrub(m) + "\n")
if rows != len(lines) or not rows:
    problems.append(f"history 행 {len(lines)}줄 중 {rows}줄만 읽음")

files = {int(m.group(1)) for n in Path(sys.argv[2]).read_text().split() if (m := re.match(r"V(\d+)__", n.rsplit("/", 1)[-1]))}
missing = sorted(applied - files)
if missing:
    problems.append(f"운영에만 있는 마이그레이션 {missing}")
if applied and files and max(applied) != max(files):
    problems.append(f"최대 버전 운영 V{max(applied)} != origin/main V{max(files)}")
if problems:
    sys.exit("중단: " + "; ".join(problems))

top = max(applied)
stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
Path(sys.argv[3]).write_text(
    "-- 운영 DB 스키마 덤프(CI 전용, 데이터 없음). 생성: ops/ci/refresh-schema.sh\n"
    f"-- dumped_at={stamp} flyway_max=V{top}\n" + "".join(out), encoding="utf-8")
print(top)
PY
)"

mv "$NEW" "$OUT"
echo "wrote $OUT (V$MAX_V, $(wc -c < "$OUT" | tr -d ' ') bytes)"
