"""수집 리더 점검(#458). worker 여러 대 칸에서 "리더는 늘 한 대, 두 대가 같이 수집하지 않음, 리더가 죽으면 이어받음"을 센다.

usage: python3 -I leader_check.py <leader.tsv> <events.tsv> <t_from>

- leader.tsv : run_subway_ladder.sh 가 1초마다 적은 줄
               epoch \t worker이름 \t 리더 게이지(0|1|down) \t 수집 성공 누적(subway_realtime_collect_duration count)
               epoch \t redis \t 잠금 값 앞 8자 \t PTTL(ms)
- events.tsv : 하네스 사건(worker-kill9:w2:leader 등)

수집 "사건" = 어떤 worker 의 수집 누적이 직전 표본보다 늘어난 표본(재기동으로 줄면 기준만 다시 잡는다).
두 대가 같이 수집 = 서로 다른 worker 의 수집 사건이 OVERLAP_S(수집 주기 15초의 절반) 안에 붙은 쌍.
인계 시간 = 리더 kill 사건 → 다른 worker 의 첫 수집 사건(다음 수집 성공), 그리고 → 다른 worker 의 리더 게이지 1.
"""
import collections, sys

OVERLAP_S = 7


def main():
    path, events_path, t_from = sys.argv[1], sys.argv[2], float(sys.argv[3])
    gauges = collections.defaultdict(dict)  # t -> name -> gauge
    last = {}
    collects = []  # (t, name)
    redis_holders = set()
    redis_missing = 0
    redis_samples = 0
    for line in open(path, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) < 4:
            continue
        t, name = float(f[0]), f[1]
        if name == "redis":
            redis_samples += 1
            if f[2]:
                redis_holders.add(f[2])
            else:
                redis_missing += 1
            continue
        gauges[t][name] = f[2]
        if f[2] == "down":
            continue
        c = float(f[3] or 0)
        prev = last.get(name)
        if prev is not None and c > prev:
            collects.append((t, name))
        last[name] = c
    events = []
    try:
        for l in open(events_path, encoding="utf-8"):
            if "\t" in l:
                a, b = l.rstrip("\n").split("\t", 1)
                events.append((float(a), b))
    except FileNotFoundError:
        pass

    print("── 수집 리더(#458) ──")
    times = sorted(gauges)
    multi = [t for t in times if sum(1 for v in gauges[t].values() if v == "1") > 1]
    none_ = [t for t in times if sum(1 for v in gauges[t].values() if v == "1") == 0]
    # 리더 없는 가장 긴 연속 구간(표본 간격 ~1초+curl)
    longest, run_start, prev_t = 0.0, None, None
    for t in times:
        if t in none_:
            if run_start is None:
                run_start = t
            longest = max(longest, t - run_start)
        else:
            run_start = None
    print(f"표본 {len(times)}개 · 리더 게이지 1 이 두 대 이상인 표본 {len(multi)}"
          + (f" (처음 +{multi[0] - t_from:.0f}s)" if multi else "")
          + f" · 리더 없는 표본 {len(none_)} (가장 긴 연속 {longest:.0f}s)")
    print(f"Redis 잠금 키: 본 주인(값 앞 8자) {len(redis_holders)}개 {sorted(redis_holders)} · 키 없던 표본 {redis_missing}/{redis_samples}")
    per = collections.Counter(n for _, n in collects)
    print(f"수집 성공 사건 worker 별 {dict(sorted(per.items()))}")
    collects.sort()
    overlaps = []
    for i, (t, n) in enumerate(collects):
        for t2, n2 in collects[i + 1:]:
            if t2 - t > OVERLAP_S:
                break
            if n2 != n:
                overlaps.append((t, n, t2, n2))
    print(f"두 대가 {OVERLAP_S}s 안에 같이 수집한 쌍 {len(overlaps)}"
          + (" 앞 5: " + ", ".join(f"+{a - t_from:.0f}s {b}/{d}" for a, b, c, d in overlaps[:5]) if overlaps else ""))
    gaps = [b - a for (a, _), (b, _) in zip(collects, collects[1:])]
    if gaps:
        print(f"수집 사건 간격 최대 {max(gaps):.0f}s (+{collects[gaps.index(max(gaps))][0] - t_from:.0f}s 부터)")
    for e, name in events:
        if not name.startswith("worker-kill9"):
            continue
        victim = name.split(":")[1].lstrip("w") if ":" in name else "1"
        vname = "worker" if victim == "1" else f"worker{victim}"
        before = [t for t, n in collects if t <= e]
        nxt = next(((t, n) for t, n in collects if t > e and n != vname), None)
        lead = next((t for t in times if t > e and any(v == "1" for k, v in gauges[t].items() if k != vname)), None)
        print(f"[{name} @+{e - t_from:.0f}s] 직전 수집 {e - before[-1]:.0f}s 전" if before else f"[{name}] 직전 수집 없음", end="")
        if name.endswith(":leader"):
            print(f" · 다른 worker 리더 게이지 1 까지 {lead - e:.0f}s" if lead else " · 다른 worker 가 리더가 안 됨", end="")
        else:
            print(" · (리더 아닌 worker — 리더는 그대로)", end="")
        print(f" · 다음 수집 성공({nxt[1]})까지 {nxt[0] - e:.0f}s"
              + (f" · 수집 공백(직전 수집 → 다음 수집) {nxt[0] - before[-1]:.0f}s" if before else "")
              if nxt else " · 창 안에 다음 수집 없음")


if __name__ == "__main__":
    main()
