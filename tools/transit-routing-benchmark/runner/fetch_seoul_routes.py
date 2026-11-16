#!/usr/bin/env python3
"""Cache Seoul bus (ws.bus.go.kr) station lists and route paths per route.

The national feed's Seoul buses (``BR_TAGO_SEB<9>``) carry 2025-03 KTDB stop
lists.  The Seoul bus API publishes the current ordered station list and the
route polyline per ``busRouteId``.  For each route id this writes, under
``--out``:

* ``<routeId>.stations.json`` — getStaionByRoute (ordered stations with
  coordinates, direction, turn flag ``transYn``, first/last bus times)
* ``<routeId>.path.json``     — getRoutePath (ordered polyline points)

Route ids come from ``--routes`` (one id per line, optionally ``id<TAB>name``).
Re-runs skip files that already hold a successful answer (headerCd 0) or a
definite "no result" (headerCd 4), so an interrupted run resumes.  Any other
header (quota exceeded, key error) stops the run so the shared key is not
burned.  The key comes from ``SEOUL_BUS_API`` and is never printed.  One run at
a time (flock on the output directory).  Stdlib only, like the rest of the
release pipeline.
"""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

BASE = "http://ws.bus.go.kr/api/rest/busRouteInfo"
CALLS = (("stations", "getStaionByRoute"), ("path", "getRoutePath"))
DONE = {"0", "4"}   # 0 = ok, 4 = no result


def cached(path: Path) -> bool:
    try:
        return str(json.loads(path.read_text())["msgHeader"]["headerCd"]) in DONE
    except Exception:
        return False


def fetch(op: str, route_id: str, key: str) -> dict:
    url = f"{BASE}/{op}?serviceKey={key}&busRouteId={route_id}&resultType=json"
    last = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=20) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as exc:  # network / malformed answer: retry
            last = exc
            time.sleep(1 + attempt)
    raise RuntimeError(f"{op} {route_id}: {type(last).__name__}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--routes", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--sleep", type=float, default=0.15)
    args = ap.parse_args()
    key = os.environ.get("SEOUL_BUS_API", "").strip()
    if not key:
        print("SEOUL_BUS_API is not set", file=sys.stderr)
        return 2
    args.out.mkdir(parents=True, exist_ok=True)
    lock = open(args.out / ".lock", "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("another fetch is running", file=sys.stderr)
        return 3
    ids = [line.split("\t")[0].strip() for line in args.routes.read_text().splitlines() if line.strip()]
    calls = skipped = failed = 0
    for n, route_id in enumerate(ids, 1):
        for suffix, op in CALLS:
            path = args.out / f"{route_id}.{suffix}.json"
            if cached(path):
                skipped += 1
                continue
            try:
                body = fetch(op, route_id, key)
            except RuntimeError as exc:
                print(exc, file=sys.stderr)
                failed += 1
                continue
            calls += 1
            code = str((body.get("msgHeader") or {}).get("headerCd"))
            if code not in DONE:
                msg = (body.get("msgHeader") or {}).get("headerMsg")
                print(f"stop: {op} {route_id} headerCd={code} {msg} (calls={calls})", file=sys.stderr)
                return 4
            tmp = path.with_suffix(".tmp")
            tmp.write_text(json.dumps(body, ensure_ascii=False))
            tmp.replace(path)
            time.sleep(args.sleep)
        if n % 100 == 0:
            print(f"{n}/{len(ids)} calls={calls} skipped={skipped} failed={failed}", flush=True)
    print(f"done routes={len(ids)} calls={calls} skipped={skipped} failed={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
