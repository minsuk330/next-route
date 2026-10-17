#!/usr/bin/env python3
"""장애 주입 회차 표(#464). run_relay_fault.sh 가 남긴 파일을 읽는다.

usage: relay_fault.py <out 디렉터리> <라벨>

- 잃음: 적재한 행 중 SENT 가 아닌 것(만료 취소·DEAD·FAILED). 이 하네스의 여정은 모두 유효하므로 SENT 가 아니면 잃은 것이다
- 접수 횟수: 두 worker 의 가짜 발송처 접수 기록(push-*-A/B.tsv)을 합쳐 행 번호별로 센다. 2번 = 최소 한 번 보장의 재발송,
  3번 이상 = 추가 발송(늦은 기록이 상태를 되돌려 한 번 더 나감)
- 감지: 장애 시각 → 처음 "heartbeat 가 끊긴 행 N건 회수" 로그(B, 또는 재기동한 A)까지
- 재접수: 장애 시각에 A 가 들고 있던 행(회수된 행 = requeue_count > 0)의 마지막 접수 시각 − 장애 시각
- 증서: stale-write(켬에서 버린 늦은 기록), loadtest.unfenced-late-write(끔에서 검사 없이 넘어간 늦은 기록)
- 대가: pg_stat_statements 에서 heartbeat UPDATE 와 회수 쿼리가 DB 실행 시간에서 차지한 몫
"""
import collections
import datetime as dt
import os
import re
import sys


def read_tsv(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f if line.strip()]


def pct(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    k = (len(xs) - 1) * p / 100
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def prom_values(path):
    """끝 스냅숏에서 이름{태그} → 값."""
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        name, _, val = line.rpartition(" ")
        try:
            out[name] = float(val)
        except ValueError:
            pass
    return out


def summed(values, prefix):
    return sum(v for k, v in values.items() if k.startswith(prefix))


def by_op(values, prefix):
    out = {}
    for k, v in values.items():
        if k.startswith(prefix) and v:
            m = re.search(r'op="([^"]+)"', k)
            out[m.group(1) if m else "?"] = int(v)
    return out


LOG_TS = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+[+-]\d{2}:\d{2})")


def reclaim_log_times(path):
    """'heartbeat 가 끊긴 행 N건 회수' 줄의 (epoch, N)."""
    out = []
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8", errors="replace"):
        if "heartbeat 가 끊긴 행" not in line:
            continue
        m = LOG_TS.match(line)
        n = re.search(r"끊긴 행 (\d+)건", line)
        if m and n:
            out.append((dt.datetime.fromisoformat(m.group(1)).timestamp(), int(n.group(1))))
    return out


def main():
    out, label = sys.argv[1], sys.argv[2]
    p = lambda name: os.path.join(out, name)
    rows = read_tsv(p(f"rows-{label}.tsv"))
    events = read_tsv(p(f"events-{label}.tsv"))
    faults = [(float(e[0]), e[1], e[2], e[3]) for e in events if e[1] in ("kill", "stop")]

    status = collections.Counter(r[5] for r in rows)
    lost = [r for r in rows if r[5] != "SENT"]
    lost_reasons = collections.Counter(r[8] or r[5] for r in lost)
    sent_after_expiry = sum(1 for r in rows if r[3] and float(r[3]) > float(r[4]))
    requeued = [r for r in rows if int(r[7]) > 0]

    receipts = collections.defaultdict(list)
    for w in ("A", "B"):
        for rec in read_tsv(p(f"push-{label}-{w}.tsv")):
            receipts[rec[1]].append(float(rec[0]) / 1000)
    per_id = collections.Counter(len(v) for v in receipts.values())
    over2 = sorted(int(k) for k, v in receipts.items() if len(v) >= 3)

    print(f"== {label}")
    print(f"적재 {len(rows)} | 상태 {dict(status)} | 잃음 {len(lost)} {dict(lost_reasons)} | 만료 뒤 발송 {sent_after_expiry}")
    print(f"접수 횟수별 행 수 {dict(sorted(per_id.items()))} | 3번 이상(추가 발송) {len(over2)} {over2[:20]}")
    print(f"회수된 행(requeue>0) {len(requeued)}")

    recl = sorted(reclaim_log_times(p(f"app-{label}-A.log")) + reclaim_log_times(p(f"app-{label}-B.log")))
    for t, kind, inflight, processing in faults:
        nxt = [x for x in recl if x[0] >= t]
        first = nxt[0] if nxt else None
        # 이 장애 뒤 다음 장애 전까지 회수된 행의 재접수
        later = [f[0] for f in faults if f[0] > t]
        end = later[0] if later else float("inf")
        re_ids = [r[0] for r in requeued]
        re_last = [max(receipts[i]) - t for i in re_ids if receipts.get(i) and t <= max(receipts[i]) < end]
        rq = [float(r[9]) - t for r in requeued if len(r) > 9 and r[9] and t <= float(r[9]) < end]
        line = (f"장애 {kind} @{int(t)} | A 들고 있던 행 {inflight} (전체 PROCESSING {processing}) | "
                f"첫 회수 로그 {'%.1f초 뒤 %d건' % (first[0] - t, first[1]) if first else '없음'}")
        if rq:
            line += f" | 회수 시각 최소 {min(rq):.1f}초 / 최대 {max(rq):.1f}초 ({len(rq)}건, 마지막 회수 기준)"
        if re_last:
            line += f" | 재접수 p50 {pct(re_last, 50):.1f}초 / 최대 {max(re_last):.1f}초 ({len(re_last)}건)"
        print(line)

    for w in ("A", "B"):
        v = prom_values(p(f"prom-end-{label}-{w}.txt"))
        hb_n = summed(v, "journey_notification_heartbeat_duration_seconds_count")
        hb_s = summed(v, "journey_notification_heartbeat_duration_seconds_sum")
        hb_max = summed(v, "journey_notification_heartbeat_duration_seconds_max")
        print(f"worker {w}: 회수 {summed(v, 'journey_notification_reclaimed_total'):.0f} | "
              f"stale-write {summed(v, 'journey_notification_stale_write_total'):.0f} | "
              f"증서 끔 늦은 기록 {summed(v, 'journey_notification_loadtest_unfenced_late_write_total'):.0f} | "
              f"heartbeat {hb_n:.0f}회 평균 {1000 * hb_s / hb_n if hb_n else 0:.1f}ms 최대 {1000 * hb_max:.1f}ms"
              f" (A 는 재기동 뒤 값)")
        print(f"   stale-write 연산별 {by_op(v, 'journey_notification_stale_write_total')} | "
              f"증서 끔 늦은 기록 연산별 {by_op(v, 'journey_notification_loadtest_unfenced_late_write_total')}")

    pg = read_tsv(p(f"pgss-{label}.tsv"))
    total = sum(float(r[1]) for r in pg) or 1
    hb = sum(float(r[1]) for r in pg if "unnest" in r[3] and "processing_started_at" in r[3])
    rc = sum(float(r[1]) for r in pg if "requeue_count" in r[3] and "PENDING" in r[3])
    print(f"DB 실행 시간(상위 30문) {total:.0f}ms | heartbeat UPDATE {hb:.0f}ms ({100 * hb / total:.2f}%) | "
          f"회수 {rc:.0f}ms ({100 * rc / total:.2f}%)")


if __name__ == "__main__":
    main()
