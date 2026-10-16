"""알림 SLO 요약(#446). 지하철 사다리 한 칸이 끝난 뒤 DB 행·대기열 표본·가짜 발송처 기록으로 SLO 네 줄을 낸다.

usage: python3 -I notification_slo.py <notifrows.tsv> <queue.tsv> <pushlog.tsv> <t_from> <t_end> [events.tsv]

- notifrows.tsv : run_subway_ladder.sh 가 지우기 전에 뜬 사다리 여정 알림 행(칸 순서는 아래 COLS)
- queue.tsv     : 5초마다 대기열 표본(epoch, 보낼 때 된 PENDING, PENDING 전체, PROCESSING, 누적 SENT)
- pushlog.tsv   : (쉼표로 여럿 가능 — worker 별 파일, #458) 가짜 발송처가 접수할 때마다 남긴 줄(epoch_ms, 행 번호, 업무 키, 종류, 울림, 여정|버전). JVM 을 죽였다
                  살려도 파일은 이어진다 — 재기동을 넘는 중복을 여기서 센다
- events.tsv    : (선택) 하네스가 일으킨 사건(epoch, 이름) — 폭주 시작·장애 시작/끝·worker 강제 종료/재기동

SLO(이슈 #446):
  제때      처음 본 → FCM 접수 p95, 등급별(긴급·주의·여유). 합치기 창 보류는 따로
  한 번만    업무 키 중복 0, 경로 버전당 울림 2번 이상 0
  빠짐없이   보내야 할 울림이 FAILED·DEAD·만료로 끝난 수 0 (대체·회복 취소는 정상)
  낡지 않게  만료 뒤 발송 0
"""
import collections, math, sys

COLS = ["id", "journey", "revision", "checkpoint", "status", "delivery", "tier", "source", "first_seen",
        "hold_ms", "created", "scheduled", "sent", "expires", "attempts", "reason"]
POLICY = {"TRANSFER_RISK_AT_RISK", "TRANSFER_RISK_MISSED", "REROUTE_SUGGESTED"}
LOST_CANCEL = {"expired", "expired-before-retry"}


def num(v):
    try:
        return float(v) if v not in ("", None) else None
    except ValueError:
        return None


def pct(values, p):
    vs = sorted(v for v in values if v is not None)
    if not vs:
        return None
    k = (len(vs) - 1) * p
    lo, hi = math.floor(k), math.ceil(k)
    return vs[lo] if lo == hi else vs[lo] + (vs[hi] - vs[lo]) * (k - lo)


def fmt(v, unit="s"):
    return "n/a" if v is None else f"{v:.1f}{unit}"


def dist(values):
    return f"p50 {fmt(pct(values, .5))} / p95 {fmt(pct(values, .95))} / p99 {fmt(pct(values, .99))} / max {fmt(pct(values, 1))} (n={len([v for v in values if v is not None])})"


def read_rows(path):
    rows = []
    for line in open(path, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < len(COLS):
            continue
        r = dict(zip(COLS, parts))
        for k in ("first_seen", "hold_ms", "created", "scheduled", "sent", "expires", "attempts"):
            r[k] = num(r[k])
        rows.append(r)
    return rows


def alerting(r):
    return r["checkpoint"] in POLICY and r["delivery"] in ("ALERTING", "none", "")


def main():
    rows_path, queue_path, push_path, t_from, t_end = sys.argv[1:6]
    t_from, t_end = float(t_from), float(t_end)
    events = []
    if len(sys.argv) > 6:
        try:
            events = [(float(a), b) for a, b in (l.rstrip("\n").split("\t", 1) for l in open(sys.argv[6]) if "\t" in l)]
        except FileNotFoundError:
            events = []
    rows = [r for r in read_rows(rows_path) if r["created"] is not None and r["created"] >= t_from]

    print("── 알림 SLO(#446) ──")
    print(f"창 {t_end - t_from:.0f}s · 적재 {len(rows)}행")
    by = collections.Counter((r["checkpoint"], r["delivery"] or "none", r["status"]) for r in rows)
    for k, v in sorted(by.items()):
        print(f"  {k[0]:<28} {k[1]:<9} {k[2]:<10} {v}")
    if events:
        print("사건: " + " · ".join(f"{name}@+{e - t_from:.0f}s" for e, name in events))

    # 제때 — 처음 본 → 접수, 울림(정책 대상)만, 등급별
    sent = [r for r in rows if r["status"] == "SENT" and r["sent"] is not None]
    print("[제때] 처음 본 → FCM 접수 (울림 위험 알림, 보류 포함)")
    for source in ("feed", "evaluation"):
        for tier in ("URGENT", "CAUTION", "RELAXED"):
            vals = [r["sent"] - r["first_seen"] for r in sent
                    if alerting(r) and r["tier"] == tier and r["source"] == source and r["first_seen"] is not None]
            if vals:
                print(f"  {source:<10} {tier:<8} {dist(vals)}")
    all_e2e = [r["sent"] - r["first_seen"] for r in sent if alerting(r) and r["first_seen"] is not None]
    print(f"  전체 울림      {dist(all_e2e)}")
    quiet = [r["sent"] - r["first_seen"] for r in sent if r["delivery"] == "QUIET" and r["first_seen"] is not None]
    if quiet:
        print(f"  조용한 갱신    {dist(quiet)}")
    unmeasured = sum(1 for r in sent if r["first_seen"] is None)
    print(f"  처음 본 시각 없음(접근·재탐색 등) {unmeasured}행")
    holds = [r["hold_ms"] / 1000 for r in sent if alerting(r) and r["hold_ms"] is not None]
    print(f"  보류(합치기 창, 의도된 지연) {dist(holds)}")
    excl = [r["sent"] - r["first_seen"] - r["hold_ms"] / 1000 for r in sent
            if alerting(r) and r["first_seen"] is not None and r["hold_ms"] is not None]
    print(f"  보류 뺀 처음 본 → 접수 {dist(excl)}")
    relay = [r["sent"] - (r["created"] + r["hold_ms"] / 1000) for r in sent
             if alerting(r) and r["hold_ms"] is not None]
    print(f"  relay 몫(적재+보류 → 접수, 재시도 포함) {dist(relay)}")
    detect = [r["created"] - r["first_seen"] for r in sent if alerting(r) and r["first_seen"] is not None]
    print(f"  감지 몫(처음 본 → 적재) {dist(detect)}")
    attempts = collections.Counter(int(r["attempts"] or 0) for r in sent if alerting(r))
    print(f"  접수까지 시도 횟수 {dict(sorted(attempts.items()))}")

    # 한 번만
    print("[한 번만]")
    revs = collections.Counter((r["journey"], r["revision"]) for r in sent if alerting(r))
    over = sum(1 for v in revs.values() if v > 1)
    print(f"  DB: 경로 버전당 울림 SENT >1 = {over} (울림 SENT {sum(revs.values())}, 버전 {len(revs)})")
    # 접수 기록은 worker 마다 따로 쓴다(#458) — 쉼표로 묶어 받아 합친다. 같은 행 번호가 두 파일에 나오면 인스턴스 사이 중복.
    push, files = [], collections.defaultdict(set)
    per_file = {}
    for fp in [x for x in push_path.split(",") if x]:
        try:
            lines = [l.rstrip("\n").split("\t") for l in open(fp, encoding="utf-8") if l.strip()]
        except FileNotFoundError:
            lines = []
        per_file[fp.rsplit("-", 1)[-1].replace(".tsv", "")] = len(lines)
        for p in lines:
            if len(p) >= 6:
                files[p[1]].add(fp)
        push += lines
    ids = collections.Counter(p[1] for p in push if len(p) >= 6)
    biz = collections.Counter(p[2] for p in push if len(p) >= 6)
    alert_rev = collections.Counter(p[5] for p in push
                                    if len(p) >= 6 and p[3] in POLICY and p[4] in ("ALERTING", "none"))
    print(f"  가짜 발송처 접수 {len(push)}줄(재기동 넘어 누적) · 같은 행 번호 2번+ {sum(1 for v in ids.values() if v > 1)}"
          f" · 같은 업무 키 2번+ {sum(1 for v in biz.values() if v > 1)}"
          f" · 경로 버전당 울림 2번+ {sum(1 for v in alert_rev.values() if v > 1)}")
    if len(per_file) > 1:
        cross = sum(1 for v in files.values() if len(v) > 1)
        print(f"  인스턴스별 접수 {per_file} · 두 인스턴스 이상이 같은 행을 보냄 {cross}")
    dup_ids = [k for k, v in ids.items() if v > 1][:10]
    if dup_ids:
        print(f"  중복 행 번호(앞 10) {dup_ids}")

    # 빠짐없이
    print("[빠짐없이] 울림인데 FAILED·DEAD·만료로 끝난 행 (대체·회복·여정 종료 취소는 정상)")
    lost = collections.Counter()
    for r in rows:
        if not alerting(r):
            continue
        if r["status"] in ("FAILED", "DEAD"):
            lost[(r["status"], (r["reason"] or "")[:40])] += 1
        elif r["status"] == "CANCELED" and (r["reason"] or "") in LOST_CANCEL:
            lost[("CANCELED", r["reason"])] += 1
    print(f"  잃음 {sum(lost.values())} {dict(lost)}")
    canceled = collections.Counter((r["reason"] or "") for r in rows if alerting(r) and r["status"] == "CANCELED")
    print(f"  정상 취소(울림) {dict(canceled)}")
    pending = collections.Counter(r["status"] for r in rows if r["status"] in ("PENDING", "PROCESSING"))
    print(f"  끝날 때 아직 안 끝난 행 {dict(pending)}")

    # 낡지 않게
    after = [r for r in sent if r["expires"] is not None and r["sent"] > r["expires"]]
    print(f"[낡지 않게] 만료 뒤 발송 {len(after)}" + (f" (최대 초과 {max(r['sent'] - r['expires'] for r in after):.1f}s)" if after else ""))

    # 대기열·처리량
    print("[대기열·relay 처리량]")
    try:
        q = [[float(x) for x in l.split("\t")[:5]] for l in open(queue_path) if l.strip() and l[0].isdigit()]
    except FileNotFoundError:
        q = []
    if q:
        peak = max(q, key=lambda x: x[1])
        print(f"  보낼 때 된 PENDING 최대 {peak[1]:.0f} (+{peak[0] - t_from:.0f}s) · PENDING 최대 {max(x[2] for x in q):.0f}"
              f" · PROCESSING 최대 {max(x[3] for x in q):.0f}")
        series = " ".join(f"+{x[0] - t_from:.0f}:{x[1]:.0f}/{x[3]:.0f}" for x in q[::6])
        print(f"  30초 간격(보낼 때 된 PENDING/PROCESSING): {series}")
        for e, name in events:
            after_q = [x for x in q if x[0] >= e]
            start = next((x for x in after_q if x[1] > 0 or x[3] > 0), None)
            if start is None:
                continue
            drained = next((x for x in after_q if x[0] > start[0] and x[1] == 0 and x[3] == 0), None)
            print(f"  {name} 뒤 대기열이 비기까지 " + (f"{drained[0] - e:.0f}s" if drained else "창 안에 안 비었다"))
    buckets = collections.Counter(int(r["sent"] // 10) for r in sent)
    if buckets:
        print(f"  접수 10초당 최대 {max(buckets.values())} · 평균(접수 있는 칸) {sum(buckets.values()) / len(buckets):.1f}")


if __name__ == "__main__":
    main()
