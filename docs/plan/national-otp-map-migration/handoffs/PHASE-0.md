# Phase 0 실행 결과

- 상태: **COMPLETE** (조사·manifest·정책 목록. 제품 코드 변경 없음 — P0 범위대로)
- 코드 기준: branch `main`, 미커밋. 산출물은 `docs/plan/national-otp-map-migration/` 아래 문서/JSON 뿐.
- 저장소에 P0 무관한 기존 미커밋 변경 존재(보존함): `application.yaml`, `TransferArrivalProperties.java`, `BusArrivalQueryService.java`, `RouteSearchCacheIT.java`, `.gitignore`, `env/*.example` 삭제. **건드리지 않음.**

## 완료한 작업
1. 입력 실측: 202503 raw GTFS 경로/파일/calendar/route_type 분포, 정규화 artifact·OSM PBF hash 확정 → `data-scope.json`.
2. GTFS dialect=KTDB 확인(agency A1). route_type 국제표준과 다름 → 변환표 검증(0→3,1→1,2→4,3→3,4→2,5→3,6→2,7제외).
3. 비용절감용 제한 목록 → `policy-inventory.md`. 실시간/ML 보강 경로도 같은 문서에 P2-D 보존표로 기록.
4. 재사용 증거와 P1 갭 식별(아래).
5. smoke OD 선정(아래).

## 산출물 (실제 경로)
- `docs/plan/national-otp-map-migration/data-scope.json` — bbox, sourcePaths, sourceHashes(sha256), calendar 범위, route_type 매핑, allowedModes, excludedTripPolicy.
- `docs/plan/national-otp-map-migration/policy-inventory.md` — 제한 11행(REMOVE/REVIEW/KEEP) + 실시간/ML 보존표.
- `handoffs/PHASE-0.md` (이 파일).

### sourceHashes (sha256)
- seoul-ktdb-normalized.gtfs.zip = `b8e38845…f82962`
- south-korea-260903.osm.pbf = `c14e2135…d6bdce`
- south-korea-seoul-metro.osm.pbf = `0be8f32b…0bd2170`
(raw stop_times.txt 1.4GB 는 크기만 기록. 재현은 raw→prepare_gtfs.py)

## 재사용 증거 (반복 금지)
- 용량/serve/동시성 측정 = `capacity-report.md`. 전국 통짜는 4G 동시4건 불가, 기존 수도권 graph(217M, |V|=720k)는 동시4건 정상. **build-config/otp-config 는 `tools/transit-routing-benchmark/config/otp-national/` 보존값 사용**(transitServiceEnd=P2M 이 heap 진범 해결).
- 정규화 도구 = `tools/transit-routing-benchmark/runner/prepare_gtfs.py` (스트리밍, KTDB route_type 변환, `--route-prefix`/`--keep-ktdb-type` scope). 재사용.
- OSM shape POC = `runner/osm_gtfs_shape_poc.py`, 결과 `results/osm-shape-poc/` (502 양방향 일부 불완전 → 자동승인 금지, P3 참고).

## 다음 Phase(P1) 입력과 재현
- raw GTFS: `/Users/wa/Downloads/대중교통GTFS(2025년 기준)/202503_GTFS_DataSet`
- bbox PBF: `tools/transit-routing-benchmark/data/input/south-korea-260903.osm.pbf`
- 정규화: `python3 tools/transit-routing-benchmark/runner/prepare_gtfs.py <raw> <out>` — 단, **P1-A 미구현 갭 있음**(아래).
- OTP 이미지: `opentripplanner/opentripplanner:2.9.0`.

## 남은 문제 / P1 갭 (차단 아님, 명세)
1. **bbox 공간필터 부재 (P1-A 핵심 작업).** `prepare_gtfs.py` 의 scope 는 route_id prefix + ktdb_type 뿐. intent/PLAN 이 요구하는 "trip 서비스 정류장 중 bbox 밖이 있으면 trip 전체 제외"는 **미구현**. P1 이 stops.txt 좌표로 bbox 판정 → trip 참조폐쇄 유지하며 제외하는 로직을 추가해야 한다. `--route-prefix` 만으로 수도권 판정 금지(AGENT-GUIDE).
2. **제품 지역필터 없음.** 수도권 범위는 제품 코드가 아니라 OTP graph 데이터 커버리지로 강제된다. P2 는 OUT_OF_SCOPE 를 graph 무경로와 구분하는 판정을 신설해야 한다(현재 없음).
3. calendar 단일·전요일·2030까지. 요일/휴일 구분 없음. calendar 를 늘려 최신성 해결 금지. shapes.txt 없음 → 대중교통 형상은 P3 OSM relation 으로만.
4. route_option(③) 제한은 ODsay 유료가 아니라 실시간·ML/UX 근거 → P2 가 비용 라벨 떼되 상한 유지 판단 필요(policy #4).

## smoke OD (P1/P2 fixture 후보, 정답 만들지 않음)
- in-scope A: 청계천 → 사당 (버스+지하철 4호선). capacity-report smoke 재사용. 실제 운행 검증은 P1 query 로.
- in-scope B: 서울시청 → 강남역 (지하철 중심).
- in-scope C: 인천 부평 → 서울역 (경계 근처, 인천 커버리지 확인).
- **out-of-scope(음성 케이스)**: 서울시청 → 부산역. bbox 밖 → P2 에서 OUT_OF_SCOPE(무경로/timeout 아님)로 거부되어야 한다.
좌표/출발시각은 P1 이 확정하고 실제 검색 결과를 기록. 미확인 운행을 정답으로 굳히지 않는다.

다음: [Phase 1](../PHASE-1.md).
