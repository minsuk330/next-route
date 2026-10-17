#!/usr/bin/env python3
"""늦은 기록 행 단위 추적(#464 보완). 일시 정지(STOP) 회차에서 깨어난 worker A 의 늦은 기록이
그 행에 무엇을 했는지 행 번호로 따라간다.

usage: relay_fault_trace.py <out 디렉터리> <라벨>

- 끔: A 로그의 "증서 끔 늦은 기록 통과(op)" 줄(행 id·시각). 켬: A 로그의 "늦은 기록 버림(op)" 줄
  (worker 를 RelayService=INFO 로 띄워야 남는다)
- 늦은 실패 기록(transient) 뒤에 같은 행이 또 접수됐으면 = 되돌림이 만든 추가 발송
  (A 의 원래 시도는 실패라 접수가 없다. 그 앞의 접수는 B 의 정상 발송)
- 늦은 발송 기록(sent) 은 A 가 실제로 보낸 것이라 B 의 발송과 겹친 중복은 최소 한 번 보장의 몫이다.
  여기서는 행이 덮어쓰였는지(최종 상태)만 본다
"""
import collections
import datetime as dt
import os
import re
import sys

LINE = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+[+-]\d{2}:\d{2}).*id=(\d+) "
                  r"(증서 끔 늦은 기록 통과|늦은 기록 버림)\((\w+)\)")


def late_writes(path):
    out = []
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8", errors="replace"):
        m = LINE.match(line)
        if m:
            out.append((dt.datetime.fromisoformat(m.group(1)).timestamp(), m.group(2),
                        ("통과" if "통과" in m.group(3) else "버림"), m.group(4)))
    return out


def main():
    out, label = sys.argv[1], sys.argv[2]
    p = lambda name: os.path.join(out, name)
    receipts = collections.defaultdict(list)
    for w in ("A", "B"):
        path = p(f"push-{label}-{w}.tsv")
        if os.path.exists(path):
            for line in open(path, encoding="utf-8"):
                f = line.rstrip("\n").split("\t")
                if len(f) > 1:
                    receipts[f[1]].append((float(f[0]) / 1000, w))
    status = {}
    rows_path = p(f"rows-{label}.tsv")
    if os.path.exists(rows_path):
        for line in open(rows_path, encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            status[f[0]] = f[5]

    print(f"== trace {label}")
    for kind in ("통과", "버림"):
        lw = [(t, i, op) for t, i, k, op in late_writes(p(f"app-{label}-A.log")) if k == kind]
        report(kind, lw, receipts, status)


def report(kind, lw, receipts, status):
    by_op = collections.Counter(op for _, _, op in lw)
    print(f"[A 늦은 기록 {kind}] {len(lw)}건 연산별 {dict(by_op)}")
    trans = [(t, i) for t, i, op in lw if op == "transient"]
    # A 의 시도는 실패라 접수가 없다. 행마다 정상 발송 1번(B 또는 다음 선점자)을 빼고 남는 접수가 추가 발송이다
    ids = sorted({i for _, i in trans})
    dist = collections.Counter(len(receipts.get(i, [])) for i in ids)
    extra_ids = [i for i in ids if len(receipts.get(i, [])) >= 2]
    extra = sum(len(receipts[i]) - 1 for i in extra_ids)
    print(f"  늦은 실패 기록 {len(trans)}건({len(ids)}행) | 행별 접수 횟수 {dict(sorted(dist.items()))} | "
          f"추가 발송 {len(extra_ids)}행 {extra}번 {extra_ids[:10]}")
    final = collections.Counter(status.get(i, "?") for _, i in trans)
    print(f"  늦은 실패 기록 행의 최종 상태 {dict(final)}")


if __name__ == "__main__":
    main()
