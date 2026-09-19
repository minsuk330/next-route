#!/usr/bin/env python3
"""Serve the OSM route shapes on a Naver map for visual inspection.

This is a throwaway viewer for the ``osm_gtfs_shape_poc.py`` output: it puts the
OSM polylines straight on top of the Naver Web Dynamic Map so you can eyeball
geometry gaps and stop placement.  It is not part of the production service.

Three routes only:
  GET /              -> the viewer HTML (osm_shape_viewer.html next to this file)
  GET /config        -> {"key": <Naver Web Dynamic Map key id>}
  GET /route.geojson -> the geojson produced by the shape POC

The map key is a Naver Cloud Platform *Web Dynamic Map* key id (``ncpKeyId``),
which is a different credential from the search API's NAVER_CLIENT_ID.  Provide
it via NAVER_MAP_KEY_ID (or pass --key), otherwise the server refuses to start.
"""

from __future__ import annotations

import argparse
import json
import os
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_HTML = HERE / "osm_shape_viewer.html"
DEFAULT_GEOJSON = HERE.parent / "results" / "osm-shape-poc" / "route-502-osm.geojson"


class ViewerHandler(BaseHTTPRequestHandler):
    def __init__(self, *args, key: str, html_path: Path, geojson_path: Path, **kwargs) -> None:
        self.key = key
        self.html_path = html_path
        self.geojson_path = geojson_path
        super().__init__(*args, **kwargs)

    def _send(self, status: int, content_type: str, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 (http.server API)
        path = self.path.split("?", 1)[0]
        if path == "/":
            self._send(200, "text/html; charset=utf-8", self.html_path.read_bytes())
        elif path == "/config":
            self._send(200, "application/json", json.dumps({"key": self.key}).encode("utf-8"))
        elif path == "/route.geojson":
            if not self.geojson_path.exists():
                self._send(404, "text/plain; charset=utf-8", f"missing {self.geojson_path}".encode("utf-8"))
                return
            self._send(200, "application/geo+json", self.geojson_path.read_bytes())
        else:
            self._send(404, "text/plain; charset=utf-8", b"not found")

    def log_message(self, format: str, *args) -> None:  # quieter console
        return


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key", default=os.environ.get("NAVER_MAP_KEY_ID"),
                        help="Naver Web Dynamic Map key id (default: NAVER_MAP_KEY_ID env)")
    parser.add_argument("--geojson", type=Path, default=DEFAULT_GEOJSON,
                        help=f"route geojson to serve (default: {DEFAULT_GEOJSON})")
    parser.add_argument("--html", type=Path, default=DEFAULT_HTML)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    if not args.key:
        parser.error("no map key: set NAVER_MAP_KEY_ID or pass --key "
                     "(Naver Cloud Platform Web Dynamic Map key id)")
    if not args.html.exists():
        parser.error(f"viewer html not found: {args.html}")
    if not args.geojson.exists():
        print(f"warning: geojson not found yet: {args.geojson}")

    handler = partial(ViewerHandler, key=args.key, html_path=args.html, geojson_path=args.geojson)
    server = ThreadingHTTPServer((args.host, args.port), handler)
    print(f"viewer: http://{args.host}:{args.port}/  (geojson={args.geojson})")
    print("register this URL as an allowed Web Dynamic Map service URL in the NCP console")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
