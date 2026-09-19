# Phase 1 실행 결과

- 상태: **COMPLETE** (수도권 기본 graph: 정규화 무결성 + build + load + 실제 planConnection query 통과). 대중교통 **형상 품질/네비 완료는 아님** — GTFS shapes 없음, transit leg geometry 는 OTP street 기반. OSM relation 형상은 P3.
- 코드 기준: branch `main`, 미커밋. 변경/신규는 전부 `tools/transit-routing-benchmark/` (untracked) 안.

## 완료한 작업
1. **P1-A 범위필터·정규화**: `runner/prepare_gtfs.py` 재작성.
   - **bbox 공간필터 신설**: trip 의 서비스 정류장이 하나라도 bbox 밖이면 trip **전체 제외**(stop 삭제로 가짜 단축 trip 안 만듦). route_id prefix 판정 폐기(AGENT-GUIDE 준수).
   - **calendar ±2개월 창**: `--calendar-center 20260907 --calendar-margin-days 62` → `20260707..20261108` 로 clamp. KTDB 2017–2030 단일 service 를 좁혀 raptor 패턴×날짜 폭발 방지(사용자 지시).
   - route_type KTDB→표준 변환 유지(0/3/5→3, 1→1, 4/6→2, 2→4, 7 항공 제외). 원천 ID 문자열+namespace 보존. calendar_dates/24h+ 시각/transfers 참조폐쇄 보존.
2. **P1-B build/load**: `config/otp-seoul/` + `compose.seoul-otp.yml` 신설(검증된 otp-national 플래그 이식: maxTransferDuration PT10M, transferRequests WALK, staticParkAndRide false, areaVisibility false, transitServiceStart -P1D, transitServiceEnd P2M). graph build → load → GraphQL query 성공.
3. **fixture 도구**: `runner/otp_fixture_query.py`(OTP 전용, leg mode/route/agency/trip/stop/legGeometry 캡처). 실제 2.9.0 schema 로 검증(gqlErr 0).

## 산출물 (실제 경로)
- 정규화 feed: `tools/transit-routing-benchmark/data/gen/seoul-bbox-202503/` + zip `data/input/seoul-bbox-202503.gtfs.zip`
  - sha256(zip) = `0cb23b0d9d370c6c59a2e7374c1702a58f7306a60c339b0b422f66461f5016e6`
- graph: `tools/transit-routing-benchmark/data/otp-seoul/graph.obj` (227MB)
  - sha256 = `15bfe00d18c699b00174c623eb501738119108e67a455bcc98c0f91b46379509`
- **graphVersion** = `seoul-bbox-202503-cal20260707_20261108-g15bfe00d18c6`
- manifest: `data/otp-seoul/release-manifest.json` (입력 hash, scope, graphVersion, 건수)
- GraphQL fixtures: `tools/transit-routing-benchmark/results/otp-seoul-p1/*.json`

## scope 건수 / 제외 보고
- routes candidate 26,363 → **kept 2,738** (subway 59 / rail 2 / bus 2,676 / ferry 1). 항공 36 제외.
- trips candidate 325,889 → **out-of-bbox 제외 171,002 → kept 154,887**. stops used 43,783 / 원천 215,409(bbox 안 46,038). stop_times 9,584,068. transfers 284.
- 주의: rail(2건)·ferry(1건)은 **bbox 안에 전 정류장이 있어 graph 에 남았다**. 원천 타입 보존 원칙대로 둠 — **검색 노출 제외는 P2 책임**(데이터에서 지우지 않음). KTX/장거리 rail 은 타 정류장이 bbox 밖이라 bbox trip 필터가 자동 제외.
- graph: |V|=808,127 |E|=2,067,264, Stops 43,783, Patterns 3,936, ConstrainedTransfers 284.

## 재생성 / 기동 / 종료 명령
```bash
cd tools/transit-routing-benchmark
# 1) 정규화 (raw -> 정규화 dir). P3 는 shape 보강 GTFS 로 같은 명령 재실행 후 zip 만 교체.
python3 runner/prepare_gtfs.py "/Users/wa/Downloads/대중교통GTFS(2025년 기준)/202503_GTFS_DataSet" \
    data/gen/seoul-bbox-202503 --bbox 126.5,37.2,127.4,38.0 --calendar-center 20260907 --calendar-margin-days 62
python3 runner/validate_gtfs.py data/gen/seoul-bbox-202503     # error_count 0 확인
(cd data/gen/seoul-bbox-202503 && zip -qX ../../input/seoul-bbox-202503.gtfs.zip agency.txt calendar.txt routes.txt trips.txt stop_times.txt stops.txt transfers.txt)
# 2) build (Docker, heap 11g, ~2.5분)
docker compose -f compose.seoul-otp.yml run --rm otp-build
# 3) serve (heap 3500m, port 18080)
docker compose -f compose.seoul-otp.yml up -d otp
# 4) 종료
docker compose -f compose.seoul-otp.yml down
```

## 검증 (실행 증거)
- validate_gtfs: error_count=0 (참조폐쇄·중복·시간 검사). 25h+ 시각 보존(time_range max 100,874s).
- OTP build exit 0, "Limiting transit service days to time period: [2026-09-06, 2026-11-07]" 로그로 창 클램프 확인. graph.obj 227MB save 완료.
- load: |V|=808,127, Grizzly server running.
- **planConnection smoke** (출발 2026-09-08T08:00+09:00), `when` 창 내:
  | case | OD | 결과 |
  |---|---|---|
  | cheonggye-sadang | 청계천→사당 | 5 itin, 오류0 (버스143→환승→버스643, 48분) |
  | cityhall-gangnam | 서울시청→강남역 | 5 itin, 오류0 |
  | bupyeong-seoulstation | 인천 부평→서울역 | 5 itin, 오류0 (인천 커버리지 확인) |
  | **cityhall-busan (OUT_OF_SCOPE)** | 서울시청→부산역 | **0 itin, routingError `OUTSIDE_BOUNDS`** |
- OUT_OF_SCOPE 가 무경로/timeout 이 아니라 `OUTSIDE_BOUNDS` routingError 로 구분됨 → P2 가 이 코드를 OUT_OF_SCOPE 로 매핑.

## Phase 2 입력 / 재현
- graphVersion / graph.obj / gtfs zip hash 위와 같음. OTP endpoint(local): `http://localhost:18080/otp/routers/default/index/graphql`.
- **planConnection fixture** = `results/otp-seoul-p1/*.json`. 검증된 leg 필드: `mode, route{gtfsId,shortName,longName,mode,agency{gtfsId,name}}, trip{gtfsId}, from/to{name,lat,lon,stop{gtfsId}}, startTime,endTime, legGeometry{length,points}`. 원천 ID 예: route `1:BR_TAGO_SEB100100022`, agency `1:A1`(feed namespace `1:` 접두). shortName=버스번호(143).

## 남은 문제 / P2·P3 로 넘김
1. **GTFS shapes 없음** → transit leg geometry 는 OTP street 기반(정류장 직선 아님이지만 OSM relation 형상도 아님). **P3 가 OSM route relation 으로 형상 생성 후 P1 명령 재실행**해 최종 graph 생성. 현재 fixture 의 legGeometry 를 형상 완료로 쓰지 말 것.
2. rail/ferry graph 잔존 → **P2 가 검색 노출 제외**(mode 판정). 데이터에서 삭제 금지.
3. OUT_OF_SCOPE = OTP `OUTSIDE_BOUNDS` routingError. bbox 안 무경로(정상)와 구분 필요(P2-B).
4. calendar 창이 62일이라 **월 1회 재빌드 운영계약 유효**(capacity-report). 창 넘긴 미래 출발은 무경로 → P2 가 capability 미지원으로 처리.
5. serve 컨테이너는 검증 후 정지함(`docker compose -f compose.seoul-otp.yml down`). P4/P5 는 위 up 명령으로 재기동.

다음: [Phase 2](../PHASE-2.md).
