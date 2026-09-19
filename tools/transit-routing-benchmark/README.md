# OTP · GraphHopper 로컬 대중교통 비교

운영 서버와 분리해 동일한 OSM·GTFS 입력을 OpenTripPlanner와 GraphHopper에 넣어
대중교통 경로 품질을 비교하는 실험 도구다. `data/` 아래의 PBF, GTFS ZIP, 그래프는
용량이 크므로 Git에 올리지 않는다.

## 현재 준비된 입력

- GTFS: KTDB 2024 기준 데이터에서 서울 시내버스와 수도권 도시철도만 추출한 정규화 ZIP
- OSM: `south-korea-260903.osm.pbf`에서 서울·수도권 bbox(`126.5,37.2,127.4,38.0`)를 추출한 PBF
- 두 파일은 `data/input/`에 두며, 전국 원본 PBF는 보존한다.

GTFS에 `shapes.txt`가 없기 때문에 이번 실험은 시간표 기반 경로 탐색과 OSM 도보 연결을
검증한다. 엔진이 생성한 대중교통 geometry 자체의 품질 비교는 별도 shapes 데이터가 필요하다.

## 반복 실행

```bash
cd tools/transit-routing-benchmark

# 원본 GTFS를 정규화할 때
python3 runner/prepare_gtfs.py /path/to/202403_GTFS_DataSet /tmp/gtfs-seoul
python3 runner/validate_gtfs.py /tmp/gtfs-seoul --output results/gtfs-validation.json

# 준비된 ZIP과 PBF로 그래프 생성
docker compose -f compose.routing-benchmark.yml --profile build run --rm otp-build
docker compose -f compose.routing-benchmark.yml --profile build run --rm graphhopper-import

# 서버 기동 및 종료
docker compose -f compose.routing-benchmark.yml up -d otp graphhopper
docker compose -f compose.routing-benchmark.yml down

# 서울 좌표 한 건을 양쪽에 보내고 요약 결과 출력
python3 runner/smoke_query.py
```

## OSM route relation ↔ GTFS shape POC

KTDB GTFS가 보유한 정류장 순서와 OSM 버스 relation의 정류장·도로 geometry가
실제로 매칭되는지 노선 하나를 기준으로 검증할 수 있다. 이 도구는 완성형
GTFS 변환기가 아니라, `shapes.txt` 보강 파이프라인을 만들기 전의 품질 실험용이다.

```bash
cd tools/transit-routing-benchmark

uv run --with osmium python runner/osm_gtfs_shape_poc.py \
  data/input/south-korea-260903.osm.pbf \
  data/input/seoul-ktdb-normalized.gtfs.zip \
  --route-ref 502 \
  --route-master-id 2899066 \
  --output-dir results/osm-shape-poc
```

결과는 Git에 올리지 않는 `results/osm-shape-poc/`에 생성된다.

- `route-502-osm-gtfs-report.json`: relation 연속성, 길이, GTFS 정류장 매칭률
- `route-502-osm.geojson`: QGIS·geojson.io 등에서 직접 확인할 수 있는 선형과 정류장

#### Naver 지도 위에서 확인

QGIS 대신 실제 Naver 배경지도 위에 OSM polyline을 얹어 gap·정류장 위치를 눈으로
확인하려면 `osm_shape_viewer.py`를 띄운다. 프로덕션 서비스와 무관한 일회성 뷰어다.

```bash
cd tools/transit-routing-benchmark

# 키는 검색용 NAVER_CLIENT_ID 가 아니라 NCP Web Dynamic Map "키 ID"(ncpKeyId) 다.
NAVER_MAP_KEY_ID=<web-dynamic-map-key-id> python3 runner/osm_shape_viewer.py
# http://127.0.0.1:8765/ 접속 (다른 노선/포트는 --geojson, --port)
```

NCP 콘솔의 Web Dynamic Map 서비스 URL 허용목록에 `http://127.0.0.1:8765` 을 등록해야
인증 실패가 나지 않는다. geojson·HTML 은 모두 로컬에서만 서빙한다.

### 502번 1차 결과 (2026-09-04 PBF)

KTDB의 502번은 103개 trip이 모두 하나의 132개 정류장 패턴을 사용한다.

| OSM relation | 방향 | OSM 정류장 | 30m 이내 순서 매칭 | way 총길이 | 재정렬 후 30m 초과 gap |
| --- | --- | ---: | ---: | ---: | ---: |
| `7172324` | 월암 → 한국은행 | 61 | 52 (85.2%) | 36.0km | 2개, 최대 199.4m |
| `4712439` | 한국은행 이후 월암 방향 | 9 | 3 (33.3%) | 8.8km | 3개, 최대 3.58km |

`7172324`는 relation 원본 way 순서로는 최대 25.6km의 잘못된 연결이 생겼지만,
연속된 블록을 재정렬하면 최대 gap이 199.4m로 줄었다. 반대 방향
`4712439`는 정류장과 way가 크게 누락돼 현재 상태로는 shape 생성에 쓸 수 없다.

따라서 502번은 OSM geometry가 있다는 사실만으로 자동 적재하지 않고 `REVIEW`로
분류해야 한다. 향후 자동 승인은 최소한 다음 조건을 모두 만족할 때만 허용한다.

- GTFS 정류장 순서가 OSM platform 순서와 단조 일치
- 정류장 매칭률 90% 이상, 평균 오차 20m 이하
- 조립한 geometry의 30m 초과 gap 0개
- 기점·종점 및 방향이 GTFS trip pattern과 일치

기본 smoke 좌표는 청계천(`37.569147,126.978647`)에서 사당 인근(`37.487272,126.983399`)이며,
출발 시각은 고정된 `2026-09-04T17:00:00+09:00`이다. 다른 케이스는 `--origin`,
`--destination`, `--when`으로 지정한다.

## 로컬 포트

| 엔진 | API | 관리/상태 |
| --- | --- | --- |
| OTP 2.9.0 | `http://localhost:18080` | GraphQL `/otp/routers/default/index/graphql` |
| GraphHopper 11.0 | `http://localhost:18989` | `http://localhost:18990/healthcheck` |

OTP는 GraphQL `planConnection`, GraphHopper는 `/route?profile=pt`를 사용한다. 두 엔진의
원본 응답을 공통 DTO로 정규화하는 작업은 smoke 검증 이후의 다음 단계다.
