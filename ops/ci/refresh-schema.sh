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
#   - 드리프트: 운영에 적용된 버전이 main 에 파일로 없거나, 최대 버전이 main 과 다름
#     (운영에만 있는 마이그레이션이면 CI 의 Flyway validate 가 깨진다)
#
# 사용: ops/ci/refresh-schema.sh [출력 경로, 기본 ops/ci/schema.sql]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$ROOT/ops/ci/schema.sql}"
CONTAINER="${PG_CONTAINER:-nextroute-postgres-1}"
TMP="$(mktemp)"
trap 'rm -f "$TMP" "$TMP.body"' EXIT

dump() {
  # 컨테이너 안의 POSTGRES_USER/POSTGRES_DB 로 실행. 값은 이 쪽으로 나오지 않는다.
  ssh -o ConnectTimeout=10 vps "docker exec $CONTAINER sh -c 'pg_dump -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" $*'"
}

dump --schema-only --no-owner --no-privileges > "$TMP.body"
dump --data-only --inserts --no-owner --no-privileges -t public.flyway_schema_history >> "$TMP.body"

python3 -I - "$TMP.body" "$ROOT/src/main/resources/db/migration" <<'PY'
import re, sys
from pathlib import Path

body = Path(sys.argv[1]).read_text(encoding="utf-8")
problems = []
if re.search(r"^COPY ", body, re.M):
    problems.append("COPY 문이 있음(데이터 섞임)")
other = [l for l in body.splitlines() if l.startswith("INSERT INTO") and not l.startswith("INSERT INTO public.flyway_schema_history ")]
if other:
    problems.append(f"flyway_schema_history 아닌 INSERT {len(other)}줄")
if re.search(r"^\s*(CREATE|ALTER) ROLE\b|PASSWORD '", body, re.M | re.I):
    problems.append("ROLE/PASSWORD 줄이 있음")

# INSERT INTO public.flyway_schema_history VALUES (rank, 'version', 'desc', 'type', 'script', checksum, 'by', 'at', ms, success);
applied = set()
for m in re.finditer(r"^INSERT INTO public\.flyway_schema_history VALUES \((\d+), (NULL|'([^']*)'), '[^']*', '(\w+)', .*, (true|false)\);$", body, re.M):
    version, kind, ok = m.group(3), m.group(4), m.group(5)
    if version and ok == "true" and kind != "BASELINE":
        applied.add(int(version.split(".")[0]))
if not applied:
    problems.append("flyway_schema_history 행을 못 읽음")

files = {int(m.group(1)) for p in Path(sys.argv[2]).glob("V*__*.sql") if (m := re.match(r"V(\d+)__", p.name))}
missing = sorted(applied - files)
if missing:
    problems.append(f"운영에만 있는 마이그레이션 {missing}")
if applied and files and max(applied) != max(files):
    problems.append(f"최대 버전 운영 V{max(applied)} != main V{max(files)}")

if problems:
    sys.exit("중단: " + "; ".join(problems))
print(max(applied))
PY
MAX_V="$(python3 -I -c 'import re,sys; print(max(int(m) for m in re.findall(r"^INSERT INTO public\.flyway_schema_history VALUES \(\d+, .(\d+)", open(sys.argv[1]).read(), re.M)))' "$TMP.body")"

{
  echo "-- 운영 DB 스키마 덤프(CI 전용, 데이터 없음). 생성: ops/ci/refresh-schema.sh"
  echo "-- dumped_at=$(date -u +%Y-%m-%dT%H:%M:%SZ) flyway_max=V$MAX_V"
  cat "$TMP.body"
} > "$TMP"
mv "$TMP" "$OUT"
echo "wrote $OUT (V$MAX_V, $(wc -c < "$OUT" | tr -d ' ') bytes)"
