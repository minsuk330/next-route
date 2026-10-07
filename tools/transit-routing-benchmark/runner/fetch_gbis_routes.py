#!/usr/bin/env python3
"""Cache Gyeonggi (GBIS v2) route lines and station lists for the feed's routes.

Gyeonggi bus routes in the national feed are ``BR_TAGO_GGB<9>[_U|_D]`` and
``BR_3100_<9>`` (village buses); the 9 digits are the GBIS ``routeId``.  For
each one this writes, under ``--out``:

* ``<routeId>.line.json``     — getBusRouteLineListv2 (one round-trip polyline)
* ``<routeId>.stations.json`` — getBusRouteStationListv2 (ordered stations,
  including pass-through ``…(경유)`` waypoints that apply_gbis_routes.py drops)

Re-runs skip files that already hold a successful answer (resultCode 0) or a
definite "no result" (resultCode 4), so an interrupted run resumes.  The API
key comes from ``GYEONGGI_BUS_API`` (the Encoding key in VPS env/api.env) and is
never printed.  Daily quota is 10,000 calls per API; the full feed needs about
3,100 per API.  Stdlib only, like the rest of the release pipeline.
"""

from __future__ import annotations

import argparse
import csv
import fcntl
import io
import json
import os
import re
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

BASE = "https://apis.data.go.kr/6410000/busrouteservice/v2"
APIS = {"line": "getBusRouteLineListv2", "stations": "getBusRouteStationListv2"}
ROUTE_RE = re.compile(r"^BR_(?:TAGO_GGB|3100_)(\d{9})(?:_[UD])?$")
DONE_CODES = {0, 4}  # 4 = 결과가 존재하지 않습니다 (definite, not worth retrying)


def gyeonggi_route_ids(gtfs: Path) -> list[str]:
    if gtfs.is_dir():
        stream = (gtfs / "routes.txt").open(encoding="utf-8-sig", newline="")
    else:
        stream = io.TextIOWrapper(zipfile.ZipFile(gtfs).open("routes.txt"), encoding="utf-8-sig", newline="")
    with stream:
        ids = {m.group(1) for row in csv.DictReader(stream) if (m := ROUTE_RE.match(row["route_id"]))}
    return sorted(ids)


def result_code(path: Path) -> int | None:
    try:
        return int(json.loads(path.read_text())["response"]["msgHeader"]["resultCode"])
    except (OSError, ValueError, KeyError, TypeError):
        return None


def fetch(api: str, route_id: str, key: str, retries: int = 3) -> bytes:
    url = f"{BASE}/{api}?serviceKey={key}&routeId={route_id}&format=json"
    last: Exception | None = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=10) as resp:
                body = resp.read()
            json.loads(body)  # quota/gateway errors come back as XML or HTML
            return body
        except Exception as exc:  # noqa: BLE001 — report and retry any transport error
            last = exc
            time.sleep(1 + attempt)
    raise RuntimeError(f"{api} {route_id}: {type(last).__name__}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--gtfs", type=Path, required=True, help="GTFS zip or directory (routes.txt is read)")
    parser.add_argument("--out", type=Path, required=True, help="cache directory, e.g. data/input/gbis")
    parser.add_argument("--limit", type=int, default=0, help="only the first N routes (smoke run)")
    parser.add_argument("--sleep", type=float, default=0.05)
    args = parser.parse_args()

    key = os.environ.get("GYEONGGI_BUS_API", "").strip()
    if not key:
        print("GYEONGGI_BUS_API is not set", file=sys.stderr)
        return 2
    args.out.mkdir(parents=True, exist_ok=True)
    lock = (args.out / ".lock").open("w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print(f"another fetch is running on {args.out}", file=sys.stderr)
        return 1

    ids = gyeonggi_route_ids(args.gtfs)
    if args.limit:
        ids = ids[: args.limit]
    report = {"fetchedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "routes": len(ids),
              "ok": {k: 0 for k in APIS}, "noResult": {k: [] for k in APIS}, "error": {}}
    calls = 0
    for i, route_id in enumerate(ids, 1):
        for kind, api in APIS.items():
            path = args.out / f"{route_id}.{kind}.json"
            code = result_code(path) if path.exists() else None
            if code not in DONE_CODES:
                try:
                    path.write_bytes(fetch(api, route_id, key))
                    calls += 1
                    time.sleep(args.sleep)
                except RuntimeError as exc:
                    report["error"][f"{route_id}.{kind}"] = str(exc)
                    continue
                code = result_code(path)
            if code == 0:
                report["ok"][kind] += 1
            elif code == 4:
                report["noResult"][kind].append(route_id)
            else:
                report["error"][f"{route_id}.{kind}"] = f"resultCode={code}"
        if i % 200 == 0:
            print(f"{i}/{len(ids)} routes, {calls} calls", flush=True)
    (args.out / "fetch-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    print(json.dumps({"routes": len(ids), "calls": calls, "ok": report["ok"],
                      "noResult": {k: len(v) for k, v in report["noResult"].items()},
                      "error": len(report["error"])}, ensure_ascii=False))
    return 0 if not report["error"] else 1


if __name__ == "__main__":
    sys.exit(main())
