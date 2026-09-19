#!/usr/bin/env python3
"""Run one identical static transit query against OTP and GraphHopper.

This intentionally keeps the output small: it is a smoke check, not the
normalization/reporting stage described in the benchmark plan.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def http_json(url: str, payload: dict | None = None) -> dict:
    if payload is None:
        request = Request(url, headers={"Accept": "application/json"})
    else:
        request = Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Accept": "application/json", "Content-Type": "application/json"},
            method="POST",
        )
    with urlopen(request, timeout=60) as response:
        return json.load(response)


def otp_query(base_url: str, origin: tuple[float, float], destination: tuple[float, float], when: str) -> dict:
    query = """
    query ($origin: PlanLabeledLocationInput!, $destination: PlanLabeledLocationInput!, $when: OffsetDateTime!) {
      planConnection(
        origin: $origin
        destination: $destination
        dateTime: { earliestDeparture: $when }
        first: 5
      ) {
        edges { node {
          start end duration waitingTime walkDistance walkTime numberOfTransfers
          legs { mode }
        } }
        routingErrors { code description }
      }
    }
    """
    body = {
        "query": query,
        "variables": {
            "origin": {"location": {"coordinate": {"latitude": origin[0], "longitude": origin[1]}}},
            "destination": {
                "location": {"coordinate": {"latitude": destination[0], "longitude": destination[1]}}
            },
            "when": when,
        },
    }
    return http_json(base_url.rstrip("/") + "/otp/routers/default/index/graphql", body)


def graphhopper_query(
    base_url: str, origin: tuple[float, float], destination: tuple[float, float], when: str
) -> dict:
    parsed = datetime.fromisoformat(when)
    utc_when = parsed.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    params = [
        ("point", f"{origin[0]},{origin[1]}"),
        ("point", f"{destination[0]},{destination[1]}"),
        ("profile", "pt"),
        ("pt.earliest_departure_time", utc_when),
        ("pt.limit_street_time", "PT30M"),
        ("points_encoded", "false"),
    ]
    return http_json(base_url.rstrip("/") + "/route?" + urlencode(params))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--origin", nargs=2, type=float, default=(37.569147, 126.978647), metavar=("LAT", "LON"))
    parser.add_argument("--destination", nargs=2, type=float, default=(37.487272, 126.983399), metavar=("LAT", "LON"))
    parser.add_argument("--when", default="2026-09-04T17:00:00+09:00", help="ISO-8601 local departure time")
    parser.add_argument("--otp", default="http://localhost:18080")
    parser.add_argument("--graphhopper", default="http://localhost:18989")
    args = parser.parse_args()

    origin = tuple(args.origin)
    destination = tuple(args.destination)
    otp = otp_query(args.otp, origin, destination, args.when)
    gh = graphhopper_query(args.graphhopper, origin, destination, args.when)

    otp_plan = otp.get("data", {}).get("planConnection", {})
    otp_results = [
        {
            "start": item.get("node", {}).get("start"),
            "end": item.get("node", {}).get("end"),
            "durationSeconds": item.get("node", {}).get("duration"),
            "waitingSeconds": item.get("node", {}).get("waitingTime"),
            "walkMeters": item.get("node", {}).get("walkDistance"),
            "transfers": item.get("node", {}).get("numberOfTransfers"),
            "modes": [leg.get("mode") for leg in item.get("node", {}).get("legs", [])],
        }
        for item in otp_plan.get("edges", [])
    ]
    gh_results = [
        {
            "departure": path.get("legs", [{}])[0].get("departure_time"),
            "arrival": path.get("legs", [{}])[-1].get("arrival_time"),
            "durationSeconds": round(path.get("time", 0) / 1000, 3),
            "distanceMeters": path.get("distance"),
            "transfers": path.get("transfers"),
            "legTypes": [leg.get("type") for leg in path.get("legs", [])],
            "routeIds": [leg.get("route_id") for leg in path.get("legs", []) if leg.get("route_id")],
        }
        for path in gh.get("paths", [])
    ]
    print(json.dumps({"request": vars(args), "otp": {"results": otp_results, "errors": otp_plan.get("routingErrors", [])}, "graphhopper": {"results": gh_results}}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
