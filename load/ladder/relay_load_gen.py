#!/usr/bin/env python3
"""발송(relay) 용량 실험(#461) 적재 생성기 — 알림 행을 정해진 속도로 아웃박스에 직접 넣는다(open-loop).

usage: relay_load_gen.py --steps "10:180,25:180" --out gen.tsv [--warmup 10:60] [--tick-ms 200]
                         [--ttl-mix "180:0.2,600:0.8"] [--pg-container st-pg] [--pg-user myuser] [--pg-db mydatabase]

- 벽시계 일정표를 따른다: 단계 시작부터 경과 시간 × 속도 = 누적 목표 건수. tick 마다 모자란 만큼 넣는다.
  DB 가 느려 늦어지면 다음 tick 에 따라잡는다(처리 완료를 기다렸다 넣지 않는다 — coordinated omission 회피).
- 넣는 시각은 DB clock_timestamp() 로 돌려받아 기록한다(stdin 에 쓴 시각 ≠ 커밋 시각).
- 운영 적재와 같게: checkpoint=TRANSFER_RISK_AT_RISK, priority=0(NotificationPriority.URGENT), created_at=KST 벽시계
  (ClockConfig), scheduled_at=now. 여정은 RelayCapacitySeedIT 표지(LOAD-RELAY)에서 돌려 쓴다.

출력 TSV(열): 단계 번호, 속도(건/초), 예정 epoch, 실제 epoch(DB), 넣은 건수. 단계 시작마다 '#step' 줄을 남긴다.
--stop-on-knee: 단계 끝 대기열이 시작보다 max(20, 들어온 양의 5%) 넘게 늘었으면 '#knee' 줄을 남기고 멈춘다
(분석기의 (a) "SENT < 속도 × 0.95" 와 같은 문턱). 최종 판정은 분석기가 한다.
"""
import argparse
import os
import random
import subprocess
import sys
import time

MARKER = "LOAD-RELAY"


def parse_pairs(text, conv):
    out = []
    for part in text.split(","):
        part = part.strip()
        if part:
            a, b = part.split(":")
            out.append((conv[0](a), conv[1](b)))
    return out


class Psql:
    """psql 프로세스 하나를 열어 두고 한 문장씩 보내 한 줄씩 받는다."""

    def __init__(self, container, user, db):
        self.p = subprocess.Popen(
            ["docker", "exec", "-i", container, "psql", "-U", user, "-d", db,
             "-At", "-q", "-F", "\t", "-v", "ON_ERROR_STOP=1"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)

    def query(self, sql):
        self.p.stdin.write(sql.strip().rstrip(";") + ";\n")
        self.p.stdin.flush()
        line = self.p.stdout.readline()
        if not line:
            raise SystemExit("psql 종료됨 — 문장 실패(ON_ERROR_STOP)")
        return line.rstrip("\n")

    def close(self):
        self.p.stdin.close()
        self.p.wait(timeout=10)


INSERT = """
with picked as (
    select u.ord, u.sid, t.ttl, gen_random_uuid()::text as key
    from unnest('{{{sids}}}'::bigint[]) with ordinality u(sid, ord)
    join unnest('{{{ttls}}}'::int[]) with ordinality t(ttl, ord) using (ord)
), ins as (
    insert into journey_notification
        (created_at, updated_at, journey_id, user_id, route_revision, command_version, evaluation_version,
         scope_type, scope_id, checkpoint, channel, logical_checkpoint_key, delivery_key, status,
         scheduled_at, expires_at, attempt_count, requeue_count, priority, payload)
    select clock_timestamp() at time zone 'Asia/Seoul', clock_timestamp() at time zone 'Asia/Seoul',
           s.id, s.user_id, s.route_revision, s.command_version, s.evaluation_version,
           'TRANSFER', 0, 'TRANSFER_RISK_AT_RISK', 'PUSH', p.key, p.key || ':PUSH', 'PENDING',
           clock_timestamp(), clock_timestamp() + p.ttl * interval '1 second', 0, 0, 0,
           jsonb_build_object('firstSeenAt',
               to_char(clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'))
    from picked p
    join journey_session s on s.id = p.sid
    returning 1
)
select count(*), extract(epoch from clock_timestamp()) from ins
"""


def backlog(db):
    """보낼 때가 됐는데 아직 안 잡힌 PENDING 수(이 실험 여정만)."""
    return int(db.query(f"select count(*) from journey_notification n join journey_session s on s.id = n.journey_id "
                        f"where s.destination_summary->>'ladder' = '{MARKER}' and n.status = 'PENDING' "
                        f"and n.scheduled_at <= now()"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", required=True, help='"건/초:초,..."')
    ap.add_argument("--warmup", default="", help='"건/초:초" (기록은 단계 0)')
    ap.add_argument("--tick-ms", type=int, default=200)
    ap.add_argument("--ttl-mix", default="180:0.2,600:0.8", help='"초:비율,..."')
    ap.add_argument("--pg-container", default="st-pg")
    ap.add_argument("--pg-user", default="myuser")
    ap.add_argument("--pg-db", default="mydatabase")
    ap.add_argument("--stop-file", default="", help="이 파일이 생기면 멈춘다")
    ap.add_argument("--stop-on-knee", action="store_true",
                    help="단계 끝에 대기열이 들어온 양의 5%% 넘게(최소 20건) 늘었으면 다음 단계로 가지 않는다")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    steps = parse_pairs(args.steps, (float, float))
    if args.warmup:
        steps = parse_pairs(args.warmup, (float, float)) + steps
        first_step = 0
    else:
        first_step = 1
    ttls = parse_pairs(args.ttl_mix, (int, float))
    ttl_values = [t for t, _ in ttls]
    ttl_weights = [w for _, w in ttls]

    db = Psql(args.pg_container, args.pg_user, args.pg_db)
    sessions = db.query(f"select string_agg(id::text, ',' order by id) from journey_session "
                        f"where destination_summary->>'ladder' = '{MARKER}' and status = 'ACTIVE'")
    sids = [int(x) for x in sessions.split(",") if x]
    if not sids:
        raise SystemExit("심은 여정 없음 — RelayCapacitySeedIT 먼저")
    cursor = 0
    tick = args.tick_ms / 1000.0
    with open(args.out, "w") as out:
        for idx, (rate, seconds) in enumerate(steps):
            step_no = first_step + idx
            start = time.time()
            out.write(f"#step\t{step_no}\t{rate:g}\t{start:.3f}\t{seconds:g}\n")
            out.flush()
            backlog_start = backlog(db)
            done = 0
            k = 0
            while True:
                k += 1
                due = start + k * tick
                if due > start + seconds:
                    break
                now = time.time()
                if due > now:
                    time.sleep(due - now)
                if args.stop_file and os.path.exists(args.stop_file):
                    db.close()
                    return
                target = int(rate * min(time.time(), start + seconds) - rate * start)
                n = target - done
                if n <= 0:
                    continue
                batch = [sids[(cursor + i) % len(sids)] for i in range(n)]
                cursor = (cursor + n) % len(sids)
                ttl = random.choices(ttl_values, ttl_weights, k=n)
                row = db.query(INSERT.format(sids=",".join(map(str, batch)), ttls=",".join(map(str, ttl))))
                cnt, at = row.split("\t")
                done += int(cnt)
                out.write(f"{step_no}\t{rate:g}\t{due:.3f}\t{float(at):.3f}\t{cnt}\n")
            out.flush()
            backlog_end = backlog(db)
            grew = backlog_end - backlog_start
            behind = step_no > 0 and grew > max(20, 0.05 * rate * seconds)
            print(f"step {step_no} rate={rate:g}/s seconds={seconds:g} inserted={done} "
                  f"backlog {backlog_start}->{backlog_end}{' KNEE' if behind else ''}", file=sys.stderr)
            if behind and args.stop_on_knee:
                out.write(f"#knee\t{step_no}\t{rate:g}\t{time.time():.3f}\t{grew}\n")
                break
    db.close()


if __name__ == "__main__":
    main()
