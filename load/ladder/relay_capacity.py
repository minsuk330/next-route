#!/usr/bin/env python3
"""발송(relay) 용량 실험(#461) 단계별 표 — run_relay_capacity.sh 가 남긴 파일을 읽는다.

usage: relay_capacity.py <out 디렉터리> <라벨>

단계 귀속: 알림 행은 created_at 이 속한 단계로 센다(앞 단계 잔여 대기열이 다음 단계 지연에 섞이지 않게).
처리량(초당 SENT)은 sent_at 이 단계 창 안인 행으로 센다. 대기열 기울기는 queue 표본의 '보낼 때 된 PENDING' 선형 회귀.

한계점(knee) = 처음으로 다음 중 하나가 걸린 단계:
  (a) 따라잡지 못함: 초당 SENT < 들어온 속도 × 0.95 이고 대기열 기울기 > 0
  (b) 잃음 > 0 (만료 CANCELED·DEAD·FAILED)
지연은 relay 몫(sent_at − created_at)만이다. 끝-끝 긴급 SLO 32초(감지 + 보류 포함)를 넘은 단계는 따로 표시한다.
"""
import collections
import statistics
import sys

E2E_URGENT_SLO = 32.0


def pct(xs, p):
    if not xs:
        return float("nan")
    xs = sorted(xs)
    k = (len(xs) - 1) * p / 100
    f = int(k)
    c = min(f + 1, len(xs) - 1)
    return xs[f] + (xs[c] - xs[f]) * (k - f)


def slope(points):
    if len(points) < 2:
        return float("nan")
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    den = sum((x - mx) ** 2 for x in xs)
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den if den else float("nan")


def read_tsv(path):
    try:
        with open(path) as f:
            return [line.rstrip("\n").split("\t") for line in f if line.strip()]
    except FileNotFoundError:
        return []


def main():
    out, label = sys.argv[1], sys.argv[2]
    p = lambda name: f"{out}/{name}-{label}.tsv"

    steps, gen = [], collections.defaultdict(list)
    for r in read_tsv(p("gen")):
        if r[0] == "#step":
            steps.append({"no": int(r[1]), "rate": float(r[2]), "start": float(r[3]), "secs": float(r[4])})
        elif r[0] == "#knee":
            print(f"생성기가 단계 {r[1]}({r[2]}건/초) 끝에서 멈춤 — 대기열 +{r[4]}")
        else:
            gen[int(r[0])].append((float(r[2]), float(r[3]), int(r[4])))

    rows = []
    for r in read_tsv(p("rows")):
        rows.append({"created": float(r[1]), "sent": float(r[3]) if r[3] else None, "status": r[5],
                     "attempts": int(r[6]), "reason": r[7] if len(r) > 7 else ""})
    queue = [(int(r[0]), int(r[1]), int(r[2])) for r in read_tsv(p("queue")) if len(r) >= 3 and r[0].isdigit()]
    cpu = collections.defaultdict(list)
    for r in read_tsv(p("cpu")):
        if len(r) >= 3 and r[2]:
            cpu[r[1]].append((int(r[0]), float(r[2])))
    prom = collections.defaultdict(list)  # 이름(라벨 포함) → [(t, 값)]
    for r in read_tsv(p("prom")):
        if len(r) >= 2:
            name, _, val = r[1].rpartition(" ")
            try:
                prom[name].append((int(r[0]), float(val)))
            except ValueError:
                pass
    pgss = collections.defaultdict(list)
    for r in read_tsv(p("pgss")):
        if len(r) >= 5 and r[0].lstrip("-").isdigit():
            pgss[int(r[0])].append((int(r[1]), float(r[2]), float(r[3]), r[4]))

    def prom_delta(prefix, t0, t1):
        """창 [t0, t1] 안 카운터 증가량(같은 접두사의 시계열 합)."""
        total = 0.0
        for name, series in prom.items():
            if not name.startswith(prefix):
                continue
            inside = [v for t, v in series if t0 <= t <= t1]
            if len(inside) >= 2:
                total += inside[-1] - inside[0]
        return total

    def prom_max(prefix, t0, t1):
        vals = [v for name, s in prom.items() if name.startswith(prefix) for t, v in s if t0 <= t <= t1]
        return max(vals) if vals else float("nan")

    hdr = ("단계\t속도\t적재/s\t일정오차p95ms\tSENT/s\t기울기/s\tPROC평균\tlag p50\tp95\tp99\t잃음\t기타취소\t재시도>0"
           "\t중복id\t회수\thikari대기평균ms\thikari pending최대\thb평균ms\thb최대ms\tGC ms\t스레드최대"
           "\tJVM cpu\tPG cpu\tDB ms/s\tclaim몫%\t판정")
    print(hdr)
    knee = None
    for st in steps:
        t0, t1 = st["start"], st["start"] + st["secs"]
        g = gen[st["no"]]
        inserted = sum(n for _, _, n in g)
        sched_err = [(a - d) * 1000 for d, a, _ in g]
        mine = [r for r in rows if t0 <= r["created"] < t1]
        lags = [r["sent"] - r["created"] for r in mine if r["sent"] is not None]
        lost = sum(1 for r in mine if r["status"] in ("DEAD", "FAILED")
                   or (r["status"] == "CANCELED" and r["reason"].startswith("expired")))
        other_cancel = sum(1 for r in mine if r["status"] == "CANCELED" and not r["reason"].startswith("expired"))
        retried = sum(1 for r in mine if r["attempts"] > 1)
        sent_rate = sum(1 for r in rows if r["sent"] is not None and t0 <= r["sent"] < t1) / st["secs"]
        q = [(t, due) for t, due, _ in queue if t0 <= t <= t1]
        sl = slope(q)
        proc = [pr for t, _, pr in queue if t0 <= t <= t1]
        offered = inserted / st["secs"]
        acq_n = prom_delta("hikaricp_connections_acquire_seconds_count", t0, t1)
        acq_s = prom_delta("hikaricp_connections_acquire_seconds_sum", t0, t1)
        hb_n = prom_delta("journey_notification_heartbeat_duration_seconds_count", t0, t1)
        hb_s = prom_delta("journey_notification_heartbeat_duration_seconds_sum", t0, t1)
        db = pgss.get(st["no"], [])
        db_ms = sum(x[1] for x in db)
        claim_ms = sum(x[1] for x in db if "skip locked" in x[3].lower())
        cpu_of = lambda k: statistics.fmean([v for t, v in cpu[k] if t0 <= t <= t1] or [float("nan")])
        behind = sent_rate < offered * 0.95 and sl > 0
        verdict = []
        if st["no"] > 0 and behind:
            verdict.append("(a)못따라감")
        if st["no"] > 0 and lost > 0:
            verdict.append("(b)잃음")
        if pct(lags, 95) > E2E_URGENT_SLO:
            verdict.append("lag>32s")
        if knee is None and st["no"] > 0 and (behind or lost > 0):
            knee = st
            verdict.append("◀한계점")
        print("\t".join(str(x) for x in [
            st["no"], f'{st["rate"]:g}', f"{offered:.1f}", f"{pct(sched_err, 95):.0f}", f"{sent_rate:.1f}",
            f"{sl:.2f}", f"{statistics.fmean(proc) if proc else float('nan'):.1f}",
            f"{pct(lags, 50):.2f}", f"{pct(lags, 95):.2f}", f"{pct(lags, 99):.2f}", lost, other_cancel, retried,
            f'{prom_delta("loadtest_push_duplicate_id_total", t0, t1):.0f}',
            f'{prom_delta("journey_notification_reclaimed_total", t0, t1):.0f}',
            f"{(acq_s / acq_n * 1000) if acq_n else float('nan'):.2f}",
            f'{prom_max("hikaricp_connections_pending", t0, t1):.0f}',
            f"{(hb_s / hb_n * 1000) if hb_n else float('nan'):.1f}",
            f'{prom_max("journey_notification_heartbeat_duration_seconds_max", t0, t1) * 1000:.1f}',
            f'{prom_delta("jvm_gc_pause_seconds_sum", t0, t1) * 1000:.0f}',
            f'{prom_max("jvm_threads_live_threads", t0, t1):.0f}',
            f'{cpu_of("jvm"):.2f}', f'{cpu_of("pg"):.2f}',
            f"{db_ms / st['secs']:.1f}", f"{(100 * claim_ms / db_ms) if db_ms else float('nan'):.1f}",
            " ".join(verdict)]))
    total = len(rows)
    sent = sum(1 for r in rows if r["status"] == "SENT")
    left = sum(1 for r in rows if r["status"] in ("PENDING", "PROCESSING"))
    print(f"\n행 {total} · SENT {sent} · 남음(PENDING/PROCESSING) {left} · 상태별 "
          + ", ".join(f"{k}={v}" for k, v in sorted(collections.Counter(r['status'] for r in rows).items())))
    print("한계점:", f'단계 {knee["no"]} ({knee["rate"]:g}건/초)' if knee else "없음(마지막 단계까지 따라잡음)")


if __name__ == "__main__":
    main()
