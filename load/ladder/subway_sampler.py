"""지하철 사다리(#427) 표본기: 1초마다 "방금 평가를 마친 여정"을 DB 에서 떠 적고, 10초마다 주기 되돌림을 한다.

- 평가 기록: journey_session.last_evaluated_at(= 평가를 마치고 판정을 저장한 시각, 앱 시계 — finishEvaluation)이
  최근 3초 안인 사다리 여정을 1초마다 읽는다. 창(3초)이 주기(1초)보다 길어 빠지는 평가가 없고, 같은 평가는
  (여정, last_evaluated_at)으로 중복 제거한다. 한 여정이 1초 안에 두 번 평가를 마치면 앞의 것은 덮여 안 보인다
  (재평가 최소 간격이 5초라 드묾). 정확도 = last_evaluated_at 자체(ms) — 표본 주기는 "놓침"에만 영향.
- 판정 직전 상태: 같은 줄에 그 여정의 환승 준비 시각·상태(transfer_connection)를 함께 적는다 → 집계가 평가 시점의
  등급(긴급·주의·여유)을 다시 계산한다.
- 주기 되돌림(SubwayLadderFixture.RECYCLE_SQL): 준비 시각을 지난 여정의 타는 단계를 다시 시작시켜 등급 비율을 유지.

usage: python3 -I subway_sampler.py out.tsv stop_file seed.tsv   (stop_file 이 생기면 끝낸다)
표본 쿼리는 심은 여정 id 범위(seed.tsv 의 최소~최대)로만 거른다 — 첫 2,000 회차에서 역 id LIKE 조건을 붙인 쿼리가
DB 시간의 34%(1회 68ms)를 먹어 하네스가 측정 대상을 흔들었다.
env: PG_CONTAINER=st-pg PG_USER=myuser PG_DB=mydatabase
"""
import os
import subprocess
import sys
import time

out_path, stop_file, seed_path = sys.argv[1], sys.argv[2], sys.argv[3]
ids = [int(line.split()[0]) for line in open(seed_path) if line.strip()]
lo, hi = min(ids), max(ids)
container = os.environ.get("PG_CONTAINER", "st-pg")
user = os.environ.get("PG_USER", "myuser")
db = os.environ.get("PG_DB", "mydatabase")

SAMPLE = """
/* ladder-sampler */ select extract(epoch from clock_timestamp())::numeric(16,3), s.id,
       extract(epoch from s.last_evaluated_at)::numeric(16,3), s.evaluation_version,
       coalesce(extract(epoch from t.user_ready_at)::bigint::text, ''), coalesce(t.status, '')
  from journey_session s
  join transfer_connection t on t.journey_id = s.id and t.route_revision = s.route_revision and t.transfer_index = 0
 where s.id between {lo} and {hi} and s.status = 'ACTIVE' and s.last_evaluated_at > now() - interval '3 seconds';
"""
RECYCLE = """
/* ladder-recycle */ with u as (update journey_leg r set activated_at = now()
  from journey_session s
 where s.id = r.journey_id and s.destination_summary->>'ladder' = 'LOAD-ST'
   and r.leg_index = 1 and r.type = 'RIDE_SUBWAY' and r.state = 'ACTIVE'
   and r.activated_at + make_interval(secs => r.planned_duration_sec + 210) <= now()
 returning 1) select count(*) from u;
"""
END = "__SAMPLE_END__"

psql = subprocess.Popen(["docker", "exec", "-i", container, "psql", "-U", user, "-d", db, "-At", "-F", "\t", "-q",
                         "-v", "ON_ERROR_STOP=0"],
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)


def run(sql):
    psql.stdin.write(sql.replace("%%", "%") + f"\n\\echo {END}\n")
    psql.stdin.flush()
    rows = []
    while True:
        line = psql.stdout.readline()
        if not line:
            raise SystemExit("psql 종료")
        line = line.rstrip("\n")
        if line == END:
            return rows
        if line:
            rows.append(line)


seen = set()
recycled = 0
tick = 0
with open(out_path, "w") as out:
    out.write("sampled_at\tjourney_id\tevaluated_at\tevaluation_version\tready_at\tstatus\n")
    while not os.path.exists(stop_file):
        started = time.time()
        for line in run(SAMPLE.format(lo=lo, hi=hi)):
            parts = line.split("\t")
            if len(parts) != 6:
                continue
            key = (parts[1], parts[2])
            if key in seen:
                continue
            seen.add(key)
            out.write(line + "\n")
        if tick % 10 == 0:
            for line in run(RECYCLE):
                if line.strip().isdigit():
                    recycled += int(line)
        out.flush()
        tick += 1
        time.sleep(max(0.0, 1.0 - (time.time() - started)))
psql.stdin.close()
psql.wait(timeout=10)
print(f"samples={len(seen)} recycled={recycled}")
