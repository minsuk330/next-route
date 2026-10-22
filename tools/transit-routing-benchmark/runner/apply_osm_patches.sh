#!/usr/bin/env bash
# OTP 거리망용 OSM 에 손보정(osmChange)을 덧댄다(#495). 원본 pbf 는 그대로 두고 새 pbf 를 쓴다.
#   runner/apply_osm_patches.sh <in.osm.pbf> <patches.osc> <out.osm.pbf>
# 그래프 빌드: OTP_OSM=<out.osm.pbf> runner/build_otp_graph.sh --profile national ...
# 가드: 패치가 참조하는 node 가 원본에 전부 있어야 한다(없으면 OTP 가 그 way 를 소리 없이 버린다).
set -euo pipefail
[[ $# -eq 3 ]] || { echo "usage: $0 <in.osm.pbf> <patches.osc> <out.osm.pbf>" >&2; exit 2; }
IN="$1"; OSC="$2"; OUT="$3"
[[ "$OUT" != "$IN" ]] || { echo "out must not be in" >&2; exit 2; }
command -v osmium >/dev/null || { echo "osmium not found (brew install osmium-tool)" >&2; exit 2; }
refs=$(sed -n 's/.*<nd ref="\([0-9]*\)".*/n\1/p' "$OSC" | sort -u)
[[ -n "$refs" ]] || { echo "no node refs in $OSC" >&2; exit 2; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
# shellcheck disable=SC2086
osmium getid "$IN" $refs -o "$tmp/refs.opl" -f opl 2>/dev/null || true
for r in $refs; do
  grep -q "^$r " "$tmp/refs.opl" || { echo "node $r of $OSC is not in $IN" >&2; exit 1; }
done
osmium apply-changes "$IN" "$OSC" -o "$OUT" --overwrite
echo "patched $(echo "$refs" | wc -l | tr -d ' ') nodes' ways -> $OUT sha256 $(shasum -a 256 "$OUT" | awk '{print $1}')"
