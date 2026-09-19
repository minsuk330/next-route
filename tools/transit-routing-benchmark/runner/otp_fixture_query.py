#!/usr/bin/env python3
"""Query a running OTP 2.9.0 (planConnection) and capture a rich fixture.

Unlike smoke_query.py this hits OTP only and records the fields P2/P3 will need:
per-leg mode, route/agency/trip ids, from/to stop + stop_sequence, scheduled
times, and encoded legGeometry. Use it to freeze GraphQL fixtures against the
actual running schema (never invent fields the schema does not return).

Usage:
  python3 otp_fixture_query.py --otp http://localhost:18080 \
      --origin 37.5693 126.9789 --destination 37.4765 126.9816 \
      --when 2026-09-08T08:00:00+09:00 --label cheonggye-sadang
"""

from __future__ import annotations

import argparse
import json
import sys
from urllib.request import Request, urlopen

QUERY = """
query ($origin: PlanLabeledLocationInput!, $destination: PlanLabeledLocationInput!, $when: OffsetDateTime!) {
  planConnection(
    origin: $origin
    destination: $destination
    dateTime: { earliestDeparture: $when }
    first: 5
  ) {
    edges { node {
      start end duration waitingTime walkDistance walkTime numberOfTransfers
      legs {
        mode duration distance startTime endTime
        from { name lat lon stop { gtfsId } }
        to { name lat lon stop { gtfsId } }
        route { gtfsId shortName longName mode agency { gtfsId name } }
        trip { gtfsId }
        legGeometry { length points }
      }
    } }
    routingErrors { code description }
  }
}
"""


def run(base_url: str, origin, destination, when: str) -> dict:
    body = {
        "query": QUERY,
        "variables": {
            "origin": {"location": {"coordinate": {"latitude": origin[0], "longitude": origin[1]}}},
            "destination": {"location": {"coordinate": {"latitude": destination[0], "longitude": destination[1]}}},
            "when": when,
        },
    }
    req = Request(
        base_url.rstrip("/") + "/otp/routers/default/index/graphql",
        data=json.dumps(body).encode("utf-8"),
        headers={"Accept": "application/json", "Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(req, timeout=90) as resp:
        return json.load(resp)


def summarize(raw: dict) -> dict:
    plan = raw.get("data", {}).get("planConnection", {}) or {}
    itineraries = []
    for edge in plan.get("edges", []) or []:
        node = edge.get("node", {})
        legs = []
        for leg in node.get("legs", []) or []:
            legs.append({
                "mode": leg.get("mode"),
                "durationSeconds": leg.get("duration"),
                "distanceMeters": leg.get("distance"),
                "from": (leg.get("from") or {}).get("name"),
                "fromStop": ((leg.get("from") or {}).get("stop") or {}).get("gtfsId"),
                "to": (leg.get("to") or {}).get("name"),
                "toStop": ((leg.get("to") or {}).get("stop") or {}).get("gtfsId"),
                "route": (leg.get("route") or {}).get("gtfsId"),
                "routeShortName": (leg.get("route") or {}).get("shortName"),
                "agency": ((leg.get("route") or {}).get("agency") or {}).get("gtfsId"),
                "trip": (leg.get("trip") or {}).get("gtfsId"),
                "geometryPoints": (leg.get("legGeometry") or {}).get("length"),
            })
        itineraries.append({
            "start": node.get("start"), "end": node.get("end"),
            "durationSeconds": node.get("duration"),
            "waitingSeconds": node.get("waitingTime"),
            "walkMeters": node.get("walkDistance"),
            "transfers": node.get("numberOfTransfers"),
            "legs": legs,
        })
    return {
        "itineraryCount": len(itineraries),
        "itineraries": itineraries,
        "routingErrors": plan.get("routingErrors", []),
        "graphqlErrors": raw.get("errors", []),
    }


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--otp", default="http://localhost:18080")
    p.add_argument("--origin", nargs=2, type=float, required=True, metavar=("LAT", "LON"))
    p.add_argument("--destination", nargs=2, type=float, required=True, metavar=("LAT", "LON"))
    p.add_argument("--when", required=True, help="ISO-8601 local departure, e.g. 2026-09-08T08:00:00+09:00")
    p.add_argument("--label", default="case")
    p.add_argument("--raw", action="store_true", help="print raw GraphQL response too")
    args = p.parse_args()

    raw = run(args.otp, tuple(args.origin), tuple(args.destination), args.when)
    out = {"label": args.label, "request": {"origin": args.origin, "destination": args.destination, "when": args.when}}
    out.update(summarize(raw))
    if args.raw:
        out["rawResponse"] = raw
    json.dump(out, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    main()
