#!/usr/bin/env bash
# Build an OTP graph with pfaedle transit shapes for a given profile.
#   --profile seoul     수도권 bbox feed (P3/P4). Graph built in a container via
#                       compose.seoul-otp.yml. Same behavior as the old
#                       build_seoul_graph.sh (which now just calls this).
#   --profile national  전국(제주 제외) feed (national-otp-shapes). Graph built with
#                       the host JVM: the no-shape national build peaked at 11.6GiB
#                       heap, more than the Docker Desktop VM (7.75GB) can give.
#
# Pipeline:
#   1. GTFS  — reuse the profile's input feed (seoul: or regenerate from KTDB raw
#              via prepare_gtfs.py --from-ktdb).
#   2. pfaedle — map-match trips of --mots against the nationwide OSM, adding
#                shapes.txt. Image is pinned by digest (NOT :latest); ENTRYPOINT is
#                `pfaedle`, so pass ARGS ONLY (a stray `pfaedle` word is read as a
#                second feed → "Multiple feeds only allowed in filter mode").
#   2b-2f — national zip→zip edits, in this order: 2b GBIS (--gbis), 2d bus runtimes
#              (--bus-runtime-coefficients), 2g added trips (--add-trips), 2e subway corrections
#              (--subway-corrections), 2c virtual stations (--station-entrances), 2f calendar end (--calendar-end),
#              2h metro weekday/Saturday/holiday services (--day-types + --holidays, after 2f so the holiday
#              guard sees the final service range).
#              --from-zip <finished feed> skips 1-2c and runs only 2g/2e/2f/2h on it.
#   3. gate  — compute_shape_quality.py prints the per-trip metric distribution and,
#              when --thresholds is given, writes coverage-index.json.
#   4. package — zip the shaped feed and (unless --report-only) rebuild the OTP graph.
#
# --report-only stops after step 3 (first run: get the distribution, then pick
# thresholds with the data — PLAN "임계값은 전량 실행 분포 보고 확정").
set -euo pipefail

# --- pins (change deliberately; recorded in release-manifest.json) ------------
PFAEDLE_DIGEST="sha256:af4fdc647699ea1ffe5150fd1f9d264c465c2876710ea749e8cb284f13abd20d"
PFAEDLE_IMAGE="ghcr.io/ad-freiburg/pfaedle@${PFAEDLE_DIGEST}"
OSM_ROADS="data/input/south-korea-260903.osm.pbf"   # pfaedle input (both profiles)

# --- options ------------------------------------------------------------------
PROFILE=""
REPORT_ONLY=0
FROM_KTDB=""
THRESHOLDS=""
GRAPH_VERSION=""
MOTS="bus"      # pfaedle -m (mode-of-transport to map-match).
MODES="bus"     # compute_shape_quality.py --modes (gate/report scope). Independent
                # of MOTS — e.g. report on bus+subway while only re-matching subway.
INPUT_OVERRIDE=""   # --input: feed dir or zip to match instead of the profile feed.
                    # Chain passes: pfaedle keeps other modes' shapes, so feed the
                    # bus-shaped output (dir) into the subway pass.
PFAEDLE_CONFIG=""   # --pfaedle-config: full pfaedle.cfg replacing the image default
                    # (e.g. config/otp-national/pfaedle-subway-rail.cfg).
SKIP_MATCH=0        # --skip-match: reuse the existing shape-build/gtfs-out (steps 1-2
                    # skipped), e.g. to build the graph from already reviewed passes.
FROM_SHAPED_ZIP=0   # --from-shaped-zip: steps 1-2 skipped, gtfs-out = the profile's current
                    # shaped zip (national: gtfs-out on disk no longer holds stop_times/shapes).
GBIS_DIR=""         # --gbis DIR: step 2b, rewrite Gyeonggi bus trips with GBIS stations and
                    # shapes (apply_gbis_routes.py; cache from fetch_gbis_routes.py).
BUS_RUNTIME_COEF="" # --bus-runtime-coefficients JSON: step 2d, re-time bus stop_times per region
                    # (calibrate_bus_runtimes.py; config/otp-national/bus-runtime-coefficients.json).
BUS_SECTION_TIMES="" # --bus-section-times CSV: with step 2d, stop-pair hourly running times (Seoul TOPIS,
                    # fetch_seoul_section_times.py) used instead of the formula where a pair is known.
STATION_ENTRANCES="" # --station-entrances CSV: step 2c, one virtual station per metro platform = platform + OSM exits
                    # (add_station_entrances.py; CSV = src/main/resources/subway/entrances.csv).
SUBWAY_CORRECTIONS="" # --subway-corrections JSON: step 2e (after 2d, before 2c), hand fixes to the KTDB metro
                    # timetable (apply_subway_corrections.py; config/otp-national/subway-corrections.json).
CALENDAR_END=""     # --calendar-end YYYYMMDD: step 2f, move calendar.txt end_date later (extend_calendar.py). Fails
                    # when build-config.json transitServiceEnd (P2M from today) would cut the graph earlier.
ADD_TRIPS=()        # --add-trips JSON (repeatable): step 2g (after 2d, before 2e), metro trips KTDB lacks, from the Seoul
                    # timetable API (add_missing_trips.py; config/otp-national/added-trips/*.json, #385 7호선 하행).
DAY_TYPES=""        # --day-types DIR: step 2h (last), routes with a data file move to M_WD and get Saturday/holiday
                    # trips (apply_day_types.py; config/otp-national/day-types/*.json, #557). Needs --holidays.
HOLIDAYS=""         # --holidays JSON: public holidays for step 2h (config/otp-national/kr-holidays.json).
SEOUL_BUS_DIR=""    # --seoul-bus DIR: step 2i (after 2h), rewrite Seoul bus trips with the official ws.bus.go.kr
                    # stations and shapes (apply_gbis_routes.py --provider seoul; cache from fetch_seoul_routes.py).
SEOUL_NEW_ROUTES="" # --seoul-new-routes TSV: step 2j, add Seoul routes the feed lacks (add_seoul_routes.py;
                    # route_id, name, type, headway min, first HHMM, last HHMM). Needs --seoul-bus.
STATION_PATHWAYS=0  # --station-pathways: step 2k (last feed edit), exit ↔ platform and transfer pathways
                    # (add_station_pathways.py). Needs the OTP build that re-links entrances after island
                    # pruning (otp-patches/): pass OTP_JAR and OTP_JAR_SHA256 for it.
FROM_ZIP=""         # --from-zip PATH: steps 1-2c skipped, start from a finished feed (e.g. the deployed zip) and
                    # run only 2e/2f on it. The output zip is PATH + suffixes, so PATH is never overwritten.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --subway-corrections) SUBWAY_CORRECTIONS="$2"; shift 2 ;;
    --calendar-end) CALENDAR_END="$2"; shift 2 ;;
    --add-trips) ADD_TRIPS+=("$2"); shift 2 ;;
    --day-types) DAY_TYPES="$2"; shift 2 ;;
    --holidays) HOLIDAYS="$2"; shift 2 ;;
    --from-zip) FROM_ZIP="$2"; shift 2 ;;
    --seoul-bus) SEOUL_BUS_DIR="$2"; shift 2 ;;
    --station-pathways) STATION_PATHWAYS=1; shift ;;
    --seoul-new-routes) SEOUL_NEW_ROUTES="$2"; shift 2 ;;
    --input) INPUT_OVERRIDE="$2"; shift 2 ;;
    --pfaedle-config) PFAEDLE_CONFIG="$2"; shift 2 ;;
    --skip-match) SKIP_MATCH=1; shift ;;
    --from-shaped-zip) FROM_SHAPED_ZIP=1; shift ;;
    --gbis) GBIS_DIR="$2"; shift 2 ;;
    --station-entrances) STATION_ENTRANCES="$2"; shift 2 ;;
    --bus-runtime-coefficients) BUS_RUNTIME_COEF="$2"; shift 2 ;;
    --bus-section-times) BUS_SECTION_TIMES="$2"; shift 2 ;;
    --report-only) REPORT_ONLY=1; shift ;;
    --from-ktdb) FROM_KTDB="$2"; shift 2 ;;
    --thresholds) THRESHOLDS="$2"; shift 2 ;;
    --graph-version) GRAPH_VERSION="$2"; shift 2 ;;
    --mots) MOTS="$2"; shift 2 ;;
    --modes) MODES="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # tools/transit-routing-benchmark
cd "$HERE"

# --- profile ------------------------------------------------------------------
case "$PROFILE" in
  seoul)
    # graph.obj 는 OTP serialization id 로 잠긴다. 배포 대상 OTP 와 다른 빌드로 만들면 로드 거부.
    # 배포용은 항상 VPS 에 떠 있는 이미지를 넘길 것: OTP_IMAGE=nextroute-otp:<tag> ...
    export OTP_IMAGE="${OTP_IMAGE:-opentripplanner/opentripplanner:2.9.0}"
    CAL_CENTER="20260907"
    CAL_MARGIN="62"
    BBOX="126.5,37.2,127.4,38.0"
    INPUT_ZIP="data/input/seoul-bbox-202503.gtfs.zip"
    SHAPED_ZIP="data/input/seoul-bbox-202503.gtfs.zip"   # replaced in-place on full build
    DEPLOY_DIR="data/otp-seoul"
    ;;
  national)
    [[ -z "$FROM_KTDB" ]] || { echo "--from-ktdb is seoul-only (national feed is fixed input, PLAN Phase 1)" >&2; exit 2; }
    INPUT_ZIP="data/input/national-nojeju-202503.gtfs.zip"
    SHAPED_ZIP="data/input/national-nojeju-202503-shaped.gtfs.zip"   # input stays untouched
    DEPLOY_DIR="data/otp-national"
    # Must be the jar baked into the deployed image (label site.refit.otp.jar-sha256).
    OTP_JAR="${OTP_JAR:-data/input/otp-h-only-9dbfb229.jar}"
    OTP_JAR_SHA256="${OTP_JAR_SHA256:-56aebe272e0f204f4af05ce945fc23514dbb354a750ea42fbd46945f7e1378f7}"
    OTP_SERIALIZATION_ID="278"
    OTP_JAVA="${OTP_JAVA:-/opt/homebrew/opt/openjdk@25/bin/java}"   # image runtime = Temurin 25
    OTP_BUILD_XMX="${OTP_BUILD_XMX:-12g}"
    # Street graph for OTP: Geofabrik 260929, Jeju cut off (south of 33.7N) with
    #   osmium extract -b 124.0,33.7,132.0,39.0 --strategy complete_ways
    # 260903 (the pfaedle input) is no longer on Geofabrik; shapes already live in GTFS,
    # so the street OSM date does not have to match the matching OSM.
    OTP_OSM="${OTP_OSM:-data/input/south-korea-260929-nojeju.osm.pbf}"
    ;;
  *) echo "--profile seoul|national is required" >&2; exit 2 ;;
esac
SOURCE_SHAPED_ZIP="$SHAPED_ZIP"
if [[ -n "$FROM_ZIP" ]]; then
  [[ "$PROFILE" == "national" ]] || { echo "--from-zip is national-only" >&2; exit 2; }
  # The rebuild-from-deployed-feed path: everything before 2e is already in the zip.
  [[ -z "$GBIS_DIR$BUS_RUNTIME_COEF$BUS_SECTION_TIMES$STATION_ENTRANCES$INPUT_OVERRIDE$PFAEDLE_CONFIG" ]] \
    || { echo "--from-zip skips steps 1-2c; drop --gbis/--bus-runtime-*/--station-entrances/--input/--pfaedle-config" >&2; exit 2; }
  [[ "$SKIP_MATCH$FROM_SHAPED_ZIP" == "00" ]] || { echo "--from-zip excludes --skip-match/--from-shaped-zip" >&2; exit 2; }
  [[ -n "$SUBWAY_CORRECTIONS$CALENDAR_END$DAY_TYPES$SEOUL_BUS_DIR" || ${#ADD_TRIPS[@]} -gt 0 || "$STATION_PATHWAYS" == "1" ]] \
    || { echo "--from-zip needs --add-trips, --subway-corrections, --calendar-end, --day-types --seoul-bus and/or --station-pathways (else it would rebuild the same feed)" >&2; exit 2; }
  [[ "$FROM_ZIP" != /* && "$FROM_ZIP" == *.gtfs.zip ]] \
    || { echo "--from-zip must be a *.gtfs.zip path relative to tools/transit-routing-benchmark" >&2; exit 2; }
  test -s "$FROM_ZIP" || { echo "--from-zip not found: $FROM_ZIP" >&2; exit 2; }
  # Output = input name + suffixes below. Unlike --skip-match (which packages into the pfaedle zip), this
  # can never land on the from-zip, the pfaedle zip or an earlier rollback zip.
  SHAPED_ZIP="$FROM_ZIP"
fi
if [[ -n "$GBIS_DIR" ]]; then
  [[ "$PROFILE" == "national" ]] || { echo "--gbis is national-only" >&2; exit 2; }
  test -d "$GBIS_DIR" || { echo "--gbis dir not found: $GBIS_DIR" >&2; exit 2; }
  # Never overwrite the pfaedle-only zip: it is the rollback source.
  SHAPED_ZIP="${SHAPED_ZIP%.gtfs.zip}-gbis.gtfs.zip"
fi
if [[ -n "$BUS_RUNTIME_COEF" ]]; then
  [[ "$PROFILE" == "national" ]] || { echo "--bus-runtime-coefficients is national-only" >&2; exit 2; }
  test -s "$BUS_RUNTIME_COEF" || { echo "--bus-runtime-coefficients json not found: $BUS_RUNTIME_COEF" >&2; exit 2; }
  SHAPED_ZIP="${SHAPED_ZIP%.gtfs.zip}-busrt.gtfs.zip"
fi
if [[ -n "$BUS_SECTION_TIMES" ]]; then
  [[ -n "$BUS_RUNTIME_COEF" ]] || { echo "--bus-section-times needs --bus-runtime-coefficients" >&2; exit 2; }
  test -s "$BUS_SECTION_TIMES" || { echo "--bus-section-times csv not found: $BUS_SECTION_TIMES" >&2; exit 2; }
fi
if [[ -n "$STATION_ENTRANCES" ]]; then
  [[ "$PROFILE" == "national" ]] || { echo "--station-entrances is national-only" >&2; exit 2; }
  test -s "$STATION_ENTRANCES" || { echo "--station-entrances csv not found: $STATION_ENTRANCES" >&2; exit 2; }
  # Same rule as --gbis: keep the previous zip as the rollback source.
  SHAPED_ZIP="${SHAPED_ZIP%.gtfs.zip}-entrances.gtfs.zip"
fi
if [[ ${#ADD_TRIPS[@]} -gt 0 ]]; then
  [[ "$PROFILE" == "national" ]] || { echo "--add-trips is national-only" >&2; exit 2; }
  for f in "${ADD_TRIPS[@]}"; do test -s "$f" || { echo "--add-trips json not found: $f" >&2; exit 2; }; done
  SHAPED_ZIP="${SHAPED_ZIP%.gtfs.zip}-addtrips.gtfs.zip"
fi
if [[ -n "$SUBWAY_CORRECTIONS" ]]; then
  [[ "$PROFILE" == "national" ]] || { echo "--subway-corrections is national-only" >&2; exit 2; }
  test -s "$SUBWAY_CORRECTIONS" || { echo "--subway-corrections json not found: $SUBWAY_CORRECTIONS" >&2; exit 2; }
  SHAPED_ZIP="${SHAPED_ZIP%.gtfs.zip}-subwayfix.gtfs.zip"
fi
if [[ -n "$CALENDAR_END" ]]; then
  [[ "$PROFILE" == "national" ]] || { echo "--calendar-end is national-only (seoul: --from-ktdb window)" >&2; exit 2; }
  [[ "$CALENDAR_END" =~ ^[0-9]{8}$ ]] || { echo "--calendar-end must be YYYYMMDD" >&2; exit 2; }
  SHAPED_ZIP="${SHAPED_ZIP%.gtfs.zip}-calend${CALENDAR_END}.gtfs.zip"
fi
if [[ -n "$DAY_TYPES$HOLIDAYS" ]]; then
  [[ "$PROFILE" == "national" ]] || { echo "--day-types is national-only" >&2; exit 2; }
  [[ -n "$DAY_TYPES" && -n "$HOLIDAYS" ]] || { echo "--day-types and --holidays go together" >&2; exit 2; }
  compgen -G "$DAY_TYPES/*.json" > /dev/null || { echo "--day-types has no *.json: $DAY_TYPES" >&2; exit 2; }
  test -s "$HOLIDAYS" || { echo "--holidays json not found: $HOLIDAYS" >&2; exit 2; }
  SHAPED_ZIP="${SHAPED_ZIP%.gtfs.zip}-daytypes.gtfs.zip"
fi
if [[ -n "$SEOUL_BUS_DIR$SEOUL_NEW_ROUTES" ]]; then
  [[ "$PROFILE" == "national" ]] || { echo "--seoul-bus is national-only" >&2; exit 2; }
  [[ -n "$SEOUL_BUS_DIR" ]] || { echo "--seoul-new-routes needs --seoul-bus (same cache)" >&2; exit 2; }
  compgen -G "$SEOUL_BUS_DIR/*.stations.json" > /dev/null || { echo "--seoul-bus has no *.stations.json: $SEOUL_BUS_DIR" >&2; exit 2; }
  [[ -z "$SEOUL_NEW_ROUTES" ]] || test -s "$SEOUL_NEW_ROUTES" || { echo "--seoul-new-routes tsv not found: $SEOUL_NEW_ROUTES" >&2; exit 2; }
  SHAPED_ZIP="${SHAPED_ZIP%.gtfs.zip}-seoulbus.gtfs.zip"
fi
if [[ "$STATION_PATHWAYS" == "1" ]]; then
  [[ "$PROFILE" == "national" ]] || { echo "--station-pathways is national-only" >&2; exit 2; }
  # The stock OTP build leaves entrances on pruned street islands unreachable (구로, 상록수 …).
  [[ "$(basename "$OTP_JAR")" == *entrance-relink* ]] \
    || { echo "--station-pathways needs the entrance-relink OTP build: set OTP_JAR and OTP_JAR_SHA256 (see otp-patches/)" >&2; exit 2; }
  SHAPED_ZIP="${SHAPED_ZIP%.gtfs.zip}-pathways.gtfs.zip"
fi
if [[ -n "$FROM_ZIP" ]]; then
  [[ "$SHAPED_ZIP" != "$FROM_ZIP" && "$SHAPED_ZIP" != "$SOURCE_SHAPED_ZIP" ]] \
    || { echo "refusing to package into $SHAPED_ZIP" >&2; exit 2; }
fi
BUILD="$DEPLOY_DIR/shape-build"
GTFS_IN="$BUILD/gtfs-in"
GTFS_OUT="$BUILD/gtfs-out"

# Fail before the long steps, not after them.
if [[ "$REPORT_ONLY" == "0" ]]; then
  [[ -n "$GRAPH_VERSION" ]] || { echo "full build requires --graph-version" >&2; exit 2; }
fi
if [[ "$PROFILE" == "national" && "$REPORT_ONLY" == "0" ]]; then
  test -s "$OTP_JAR" || { echo "OTP jar not found: $OTP_JAR" >&2; exit 2; }
  actual_sha="$(shasum -a 256 "$OTP_JAR" | awk '{print $1}')"
  [[ "$actual_sha" == "$OTP_JAR_SHA256" ]] || { echo "OTP jar sha mismatch: $actual_sha != $OTP_JAR_SHA256" >&2; exit 2; }
  ser_id="$(unzip -p "$OTP_JAR" otp-project-info.properties | sed -n 's/^otp.serialization.version.id=//p')"
  [[ "$ser_id" == "$OTP_SERIALIZATION_ID" ]] || { echo "OTP serialization id $ser_id != $OTP_SERIALIZATION_ID" >&2; exit 2; }
  "$OTP_JAVA" -version >/dev/null 2>&1 || { echo "java not runnable: $OTP_JAVA" >&2; exit 2; }
  # OTP silently builds a street-less graph when the OSM source is missing (2026-09-30).
  [[ -f "$OTP_OSM" && $(stat -L -f %z "$OTP_OSM") -gt 100000000 ]] || { echo "OTP OSM missing or too small: $OTP_OSM" >&2; exit 2; }
fi
if [[ "$SKIP_MATCH" == "0" && "$FROM_SHAPED_ZIP" == "0" && -z "$FROM_ZIP" ]]; then
  [[ -f "$OSM_ROADS" && $(stat -L -f %z "$OSM_ROADS") -gt 50000000 ]] || { echo "pfaedle OSM missing or too small: $OSM_ROADS" >&2; exit 2; }
fi

# zip→zip steps 2e/2f chain through PENDING_ZIP instead of unzipping/rezipping gtfs-out between them;
# unstage() puts the feed back into gtfs-out before anything that reads the directory.
PENDING_ZIP=""
stage_in() {   # $1 = where to zip gtfs-out when no zip holds the current feed; prints the step's input zip
  if [[ -n "$PENDING_ZIP" ]]; then echo "$PENDING_ZIP"; return; fi
  rm -f "$1"; ( cd "$GTFS_OUT" && zip -q -r -X "$HERE/$1" . ); echo "$1"
}
stage_done() { # $1 = step input, $2 = step output
  [[ "$1" == "$FROM_ZIP" ]] || rm -f "$1"
  PENDING_ZIP="$2"
}
unstage() {
  [[ -n "$PENDING_ZIP" ]] || return 0
  rm -rf "$GTFS_OUT"; mkdir -p "$GTFS_OUT"
  unzip -o -q "$PENDING_ZIP" -d "$GTFS_OUT"
  [[ "$PENDING_ZIP" == "$FROM_ZIP" ]] || rm -f "$PENDING_ZIP"
  PENDING_ZIP=""
}

echo "== profile: $PROFILE =="
if [[ -n "$FROM_ZIP" ]]; then
  echo "== steps 1-2c skipped: --from-zip $FROM_ZIP (sha256 $(shasum -a 256 "$FROM_ZIP" | awk '{print $1}')) =="
  mkdir -p "$BUILD"
  PENDING_ZIP="$FROM_ZIP"
elif [[ "$FROM_SHAPED_ZIP" == "1" ]]; then
  test -s "$SOURCE_SHAPED_ZIP" || { echo "--from-shaped-zip: no $SOURCE_SHAPED_ZIP" >&2; exit 2; }
  echo "== steps 1-2 skipped: gtfs-out = $SOURCE_SHAPED_ZIP (sha256 $(shasum -a 256 "$SOURCE_SHAPED_ZIP" | awk '{print $1}')) =="
  rm -rf "$GTFS_OUT"; mkdir -p "$GTFS_OUT"
  unzip -o -q "$SOURCE_SHAPED_ZIP" -d "$GTFS_OUT"
elif [[ "$SKIP_MATCH" == "1" ]]; then
  test -s "$GTFS_OUT/shapes.txt" || { echo "--skip-match: no $GTFS_OUT/shapes.txt" >&2; exit 2; }
  echo "== steps 1-2 skipped: reusing $GTFS_OUT (shapes.txt lines: $(wc -l < "$GTFS_OUT/shapes.txt")) =="
else
echo "== step 1: GTFS =="
if [[ -n "$FROM_KTDB" ]]; then
  echo "regenerating normalized feed from KTDB raw: $FROM_KTDB"
  rm -rf "$BUILD/gtfs-normalized"
  python3 runner/prepare_gtfs.py "$FROM_KTDB" "$BUILD/gtfs-normalized" \
    --bbox "$BBOX" --calendar-center "$CAL_CENTER" --calendar-margin-days "$CAL_MARGIN"
  rm -rf "$GTFS_IN"; cp -r "$BUILD/gtfs-normalized" "$GTFS_IN"
elif [[ -n "$INPUT_OVERRIDE" && -d "$INPUT_OVERRIDE" ]]; then
  [[ "$(cd "$INPUT_OVERRIDE" && pwd)" != "$(mkdir -p "$GTFS_IN" "$GTFS_OUT" && cd "$GTFS_IN" && pwd)" ]] || { echo "--input must not be $GTFS_IN" >&2; exit 2; }
  [[ "$(cd "$INPUT_OVERRIDE" && pwd)" != "$(cd "$GTFS_OUT" && pwd)" ]] || { echo "--input must not be $GTFS_OUT (step 2 wipes it); move it first" >&2; exit 2; }
  echo "using feed dir: $INPUT_OVERRIDE"
  rm -rf "$GTFS_IN"; cp -R "$INPUT_OVERRIDE" "$GTFS_IN"
else
  INPUT_ZIP="${INPUT_OVERRIDE:-$INPUT_ZIP}"
  echo "reusing feed: $INPUT_ZIP"
  rm -rf "$GTFS_IN"; mkdir -p "$GTFS_IN"
  unzip -o -q "$INPUT_ZIP" -d "$GTFS_IN"
fi

echo "== step 2: pfaedle ($MOTS) =="
rm -rf "$GTFS_OUT"; mkdir -p "$GTFS_OUT"
PFAEDLE_ARGS=(-m "$MOTS" -x /data/roads.osm.pbf -i /data/gtfs-in -o /data/gtfs-out)
DOCKER_CFG=()
if [[ -n "$PFAEDLE_CONFIG" ]]; then
  test -s "$PFAEDLE_CONFIG" || { echo "pfaedle config not found: $PFAEDLE_CONFIG" >&2; exit 2; }
  # Mount over the image default so the stock config is not also read.
  DOCKER_CFG=(-v "$(cd "$(dirname "$PFAEDLE_CONFIG")" && pwd)/$(basename "$PFAEDLE_CONFIG"):/usr/local/etc/pfaedle/pfaedle.cfg:ro")
  echo "pfaedle config: $PFAEDLE_CONFIG (sha256 $(shasum -a 256 "$PFAEDLE_CONFIG" | awk '{print $1}'))"
fi
docker run --rm \
  -v "$HERE/$BUILD:/data" \
  -v "$HERE/$OSM_ROADS:/data/roads.osm.pbf:ro" \
  ${DOCKER_CFG[@]+"${DOCKER_CFG[@]}"} \
  -w /data \
  "$PFAEDLE_IMAGE" \
  "${PFAEDLE_ARGS[@]}"
test -s "$GTFS_OUT/shapes.txt" || { echo "pfaedle produced no shapes.txt" >&2; exit 1; }
echo "shapes.txt lines: $(wc -l < "$GTFS_OUT/shapes.txt")"
fi

if [[ -n "$GBIS_DIR" ]]; then
  echo "== step 2b: GBIS Gyeonggi stations + shapes =="
  rm -f "$BUILD/pre-gbis.gtfs.zip"
  ( cd "$GTFS_OUT" && zip -q -r -X "$HERE/$BUILD/pre-gbis.gtfs.zip" . )
  python3 runner/apply_gbis_routes.py "$BUILD/pre-gbis.gtfs.zip" "$BUILD/gbis.gtfs.zip" \
    --gbis "$GBIS_DIR" --report "$DEPLOY_DIR/gbis-apply-report.json"
  rm -rf "$GTFS_OUT"; mkdir -p "$GTFS_OUT"
  unzip -o -q "$BUILD/gbis.gtfs.zip" -d "$GTFS_OUT"
  rm -f "$BUILD/pre-gbis.gtfs.zip" "$BUILD/gbis.gtfs.zip"
fi

if [[ -n "$BUS_RUNTIME_COEF" ]]; then
  echo "== step 2d: bus runtimes (coefficients sha256 $(shasum -a 256 "$BUS_RUNTIME_COEF" | awk '{print $1}')${BUS_SECTION_TIMES:+, section times sha256 $(shasum -a 256 "$BUS_SECTION_TIMES" | awk '{print $1}')}) =="
  SECTION_ARGS=()
  [[ -z "$BUS_SECTION_TIMES" ]] || SECTION_ARGS=(--section-times "$BUS_SECTION_TIMES")
  rm -f "$BUILD/pre-busrt.gtfs.zip"
  ( cd "$GTFS_OUT" && zip -q -r -X "$HERE/$BUILD/pre-busrt.gtfs.zip" . )
  python3 runner/calibrate_bus_runtimes.py --in "$BUILD/pre-busrt.gtfs.zip" \
    --coefficients "$BUS_RUNTIME_COEF" ${SECTION_ARGS[@]+"${SECTION_ARGS[@]}"} --out "$BUILD/busrt.gtfs.zip" \
    --report "$DEPLOY_DIR/bus-runtime-report.json"
  rm -rf "$GTFS_OUT"; mkdir -p "$GTFS_OUT"
  unzip -o -q "$BUILD/busrt.gtfs.zip" -d "$GTFS_OUT"
  rm -f "$BUILD/pre-busrt.gtfs.zip" "$BUILD/busrt.gtfs.zip"
fi

if [[ ${#ADD_TRIPS[@]} -gt 0 ]]; then
  echo "== step 2g: added trips (${ADD_TRIPS[*]}) =="
  ADD_ARGS=()
  for f in "${ADD_TRIPS[@]}"; do
    echo "  $f sha256 $(shasum -a 256 "$f" | awk '{print $1}')"
    ADD_ARGS+=(--trips "$f")
  done
  step_in="$(stage_in "$BUILD/pre-addtrips.gtfs.zip")"
  python3 runner/add_missing_trips.py --in "$step_in" "${ADD_ARGS[@]}" \
    --out "$BUILD/addtrips.gtfs.zip" --report "$DEPLOY_DIR/added-trips-report.json"
  stage_done "$step_in" "$BUILD/addtrips.gtfs.zip"
fi

if [[ -n "$SUBWAY_CORRECTIONS" ]]; then
  echo "== step 2e: subway corrections (sha256 $(shasum -a 256 "$SUBWAY_CORRECTIONS" | awk '{print $1}')) =="
  step_in="$(stage_in "$BUILD/pre-subwayfix.gtfs.zip")"
  python3 runner/apply_subway_corrections.py --in "$step_in" --corrections "$SUBWAY_CORRECTIONS" \
    --out "$BUILD/subwayfix.gtfs.zip" --report "$DEPLOY_DIR/subway-corrections-report.json"
  stage_done "$step_in" "$BUILD/subwayfix.gtfs.zip"
fi

if [[ -n "$STATION_ENTRANCES" ]]; then
  unstage
  echo "== step 2c: virtual stations (platform + exits) =="
  rm -f "$BUILD/pre-entrances.gtfs.zip"
  ( cd "$GTFS_OUT" && zip -q -r -X "$HERE/$BUILD/pre-entrances.gtfs.zip" . )
  python3 runner/add_station_entrances.py --in "$BUILD/pre-entrances.gtfs.zip" \
    --entrances "$STATION_ENTRANCES" --out "$BUILD/entrances.gtfs.zip" \
    --report "$DEPLOY_DIR/station-entrances-report.json"
  rm -rf "$GTFS_OUT"; mkdir -p "$GTFS_OUT"
  unzip -o -q "$BUILD/entrances.gtfs.zip" -d "$GTFS_OUT"
  rm -f "$BUILD/pre-entrances.gtfs.zip" "$BUILD/entrances.gtfs.zip"
fi

if [[ -n "$CALENDAR_END" ]]; then
  echo "== step 2f: calendar end_date -> $CALENDAR_END (checked against config/otp-national/build-config.json) =="
  step_in="$(stage_in "$BUILD/pre-calendar.gtfs.zip")"
  python3 runner/extend_calendar.py --in "$step_in" --out "$BUILD/calendar.gtfs.zip" --end "$CALENDAR_END" \
    --otp-build-config config/otp-national/build-config.json --report "$DEPLOY_DIR/calendar-report.json"
  stage_done "$step_in" "$BUILD/calendar.gtfs.zip"
fi

if [[ -n "$DAY_TYPES" ]]; then
  echo "== step 2h: metro day types ($DAY_TYPES, holidays sha256 $(shasum -a 256 "$HOLIDAYS" | awk '{print $1}')) =="
  for f in "$DAY_TYPES"/*.json; do echo "  $f sha256 $(shasum -a 256 "$f" | awk '{print $1}')"; done
  step_in="$(stage_in "$BUILD/pre-daytypes.gtfs.zip")"
  python3 runner/apply_day_types.py --in "$step_in" --day-types "$DAY_TYPES" --holidays "$HOLIDAYS" \
    --out "$BUILD/daytypes.gtfs.zip" --report "$DEPLOY_DIR/day-types-report.json"
  stage_done "$step_in" "$BUILD/daytypes.gtfs.zip"
fi

if [[ -n "$SEOUL_BUS_DIR" ]]; then
  echo "== step 2i: Seoul bus official stations + shapes ($SEOUL_BUS_DIR, $(ls "$SEOUL_BUS_DIR" | grep -c '\.stations\.json$') routes cached) =="
  step_in="$(stage_in "$BUILD/pre-seoulbus.gtfs.zip")"
  python3 runner/apply_gbis_routes.py "$step_in" "$BUILD/seoulbus.gtfs.zip" \
    --provider seoul --source "$SEOUL_BUS_DIR" --report "$DEPLOY_DIR/seoul-bus-apply-report.json"
  stage_done "$step_in" "$BUILD/seoulbus.gtfs.zip"
fi

if [[ -n "$SEOUL_NEW_ROUTES" ]]; then
  echo "== step 2j: Seoul routes the feed lacks ($SEOUL_NEW_ROUTES sha256 $(shasum -a 256 "$SEOUL_NEW_ROUTES" | awk '{print $1}')) =="
  step_in="$(stage_in "$BUILD/pre-seoulnew.gtfs.zip")"
  python3 runner/add_seoul_routes.py "$step_in" "$BUILD/seoulnew.gtfs.zip" \
    --source "$SEOUL_BUS_DIR" --routes "$SEOUL_NEW_ROUTES" --report "$DEPLOY_DIR/seoul-new-routes-report.json"
  stage_done "$step_in" "$BUILD/seoulnew.gtfs.zip"
fi

if [[ "$STATION_PATHWAYS" == "1" ]]; then
  echo "== step 2k: station pathways (exit ↔ platform, transfer platforms) =="
  step_in="$(stage_in "$BUILD/pre-pathways.gtfs.zip")"
  python3 runner/add_station_pathways.py --in "$step_in" --out "$BUILD/pathways.gtfs.zip" \
    --stop-entrances config/otp-national/station-stop-entrances.tsv \
    --report "$DEPLOY_DIR/station-pathways-report.json"
  stage_done "$step_in" "$BUILD/pathways.gtfs.zip"
fi
unstage

echo "== step 3: gate (modes=$MODES) =="
GATE_ARGS=("$GTFS_OUT" --modes "$MODES" --metrics-out "$BUILD/coverage-metrics.csv")
if [[ -n "$THRESHOLDS" ]]; then
  [[ -n "$GRAPH_VERSION" ]] || { echo "--thresholds requires --graph-version" >&2; exit 2; }
  GATE_ARGS+=(--thresholds "$THRESHOLDS" --index-out "$DEPLOY_DIR/coverage-index.json" \
              --graph-version "$GRAPH_VERSION")
fi
python3 runner/compute_shape_quality.py "${GATE_ARGS[@]}"

if [[ "$REPORT_ONLY" == "1" ]]; then
  echo "== report-only: stop after gate =="
  exit 0
fi

echo "== step 4: package + graph build =="
if [[ "$PROFILE" == "national" ]]; then
  # route_color lives in the graph (#247); idempotent, rewrites routes.txt in place.
  python3 runner/apply_route_colors.py "$GTFS_OUT" \
    --colors config/otp-national/route-colors.json \
    --seoul-bus-types config/otp-national/seoul-bus-route-types.json
fi
if [[ "$PROFILE" == "seoul" ]]; then
  ( cd "$GTFS_OUT" && zip -q -r -X "$HERE/$SHAPED_ZIP" . )   # unchanged P3/P4 behavior
else
  rm -f "$HERE/$SHAPED_ZIP.tmp"
  ( cd "$GTFS_OUT" && zip -q -r -X "$HERE/$SHAPED_ZIP.tmp" . )
  mv "$HERE/$SHAPED_ZIP.tmp" "$HERE/$SHAPED_ZIP"
fi
echo "shaped feed → $SHAPED_ZIP"

if [[ "$PROFILE" == "seoul" ]]; then
  docker compose -f compose.seoul-otp.yml --profile build run --rm otp-build
else
  # Host-JVM build in a scratch dir holding only this build's inputs, so OTP
  # cannot pick up a stray feed/PBF from elsewhere.
  GRAPH_BUILD="$DEPLOY_DIR/graph-build"
  rm -rf "$GRAPH_BUILD"; mkdir -p "$GRAPH_BUILD"
  ln -s "$HERE/$OTP_OSM" "$GRAPH_BUILD/national.osm.pbf"
  ln -s "$HERE/$SHAPED_ZIP" "$GRAPH_BUILD/national.gtfs.zip"
  cp config/otp-national/otp-config.json "$GRAPH_BUILD/otp-config.json"
  # build-config.json sources point at the container path; rewrite to this dir.
  sed "s#file:///var/opentripplanner/#file://$HERE/$GRAPH_BUILD/#g" \
    config/otp-national/build-config.json > "$GRAPH_BUILD/build-config.json"
  "$OTP_JAVA" "-Xmx$OTP_BUILD_XMX" -XX:+UseStringDeduplication \
    -jar "$OTP_JAR" --build --save "$GRAPH_BUILD"
  mv "$GRAPH_BUILD/graph.obj" "$DEPLOY_DIR/graph.obj"
fi
test -s "$DEPLOY_DIR/graph.obj" || { echo "graph build produced no graph.obj" >&2; exit 1; }
echo "graph.obj rebuilt → $DEPLOY_DIR/graph.obj. Update release-manifest.json (digest/sha/graphVersion) before deploy."

echo "== step 5: release bundle (원자성) =="
echo "$DEPLOY_DIR/release-manifest.json 의 graph.sha256/gtfsShapedSha256/coverageIndexSha256/graphVersion/"
echo "otp jar·image 식별값을 이번 산출물로 갱신했는지 확인 후:"
echo "  python3 runner/verify_release_bundle.py --manifest $DEPLOY_DIR/release-manifest.json --write   # bundleHash 기록"
echo "  python3 runner/verify_release_bundle.py --manifest $DEPLOY_DIR/release-manifest.json           # 배포 전 재검증(부분 스왑 차단)"
