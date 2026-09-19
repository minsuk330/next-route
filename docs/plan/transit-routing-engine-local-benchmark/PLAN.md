# OTP vs GraphHopper 로컬 대중교통 탐색 비교 — 실행 계획

> 목적: 같은 서울 OSM·GTFS와 같은 검색 조건을 OpenTripPlanner(OTP), GraphHopper에 입력하고,
> 네이버지도 기준 경로와의 차이 및 로컬 운영 비용을 수치로 비교한다.
>
> 이 단계에서는 ODsay를 제거하거나 운영 코드에 새 엔진을 연결하지 않는다. 먼저 재현 가능한 실험으로
> 어느 엔진이 NexRoute의 대중교통 경로 후보 생성기에 적합한지 결정한다.

## 1. 이번 실험에서 답할 질문

1. 두 엔진 중 어느 쪽이 네이버지도와 유사한 버스·지하철 조합을 상위 후보로 반환하는가?
2. 승·하차 정류장, 이용 노선, 환승 횟수, 도보 구간이 얼마나 일치하는가?
3. 같은 데이터에서 탐색 실패율과 응답시간, 메모리 사용량은 어느 쪽이 더 안정적인가?
4. 서울 버스의 배차간격 중심 데이터를 넣었을 때 비현실적인 환승을 얼마나 제안하는가?
5. NexRoute의 실시간 버스·ML 재평가를 붙이기 좋은 식별자와 구간 정보를 어느 쪽이 더 잘 보존하는가?

## 2. 고정 결정

| 항목 | 결정 |
|---|---|
| 실행 방식 | NexRoute API와 분리된 로컬 컨테이너 2개 |
| OTP | `2.9.0` 고정, GraphQL `planConnection` 사용 |
| GraphHopper | `11.0` 고정, `profile=pt` 사용 |
| 호스트 | 현재 로컬 `arm64` Mac |
| 포트 | OTP `18080`, GraphHopper `18989` |
| 거리 좌표계 | WGS84 |
| 정적 데이터 | 두 엔진에 동일한 OSM PBF와 동일한 GTFS ZIP 입력 |
| 1차 비교 | GTFS-Realtime·NexRoute ML 없이 정적 탐색만 비교 |
| 2차 비교 | 정적 비교 통과 후에만 실시간 데이터 적용 가능성 검증 |
| 화면 | 엔진 내장 UI는 기동 확인용. 품질 판정은 공통 DTO와 네이버지도 기준 데이터로 수행 |
| 운영 코드 | 이번 실험에서는 수정하지 않음 |

### 버전 선택 이유

- OTP 2.9.0은 2026년 3월 공개된 현재 안정 버전이며 Java 25 기반이다.
- GraphHopper 11.0도 별도 런타임으로 격리한다.
- 현재 NexRoute는 Java 17 toolchain이므로 두 엔진을 애플리케이션 내부 라이브러리로 넣지 않는다.
- 컨테이너 간 HTTP 호출 구조로 시험하면 향후 버전 변경과 메모리 격리가 쉽다.

참고:

- OTP 릴리스: <https://github.com/opentripplanner/OpenTripPlanner/releases/tag/v2.9.0>
- OTP 컨테이너: <https://docs.opentripplanner.org/en/latest/Container-Image/>
- GraphHopper GTFS: <https://github.com/graphhopper/graphhopper/tree/11.x/reader-gtfs>

## 3. 가장 중요한 전제: 동일 입력

엔진 비교에서 다음 값은 반드시 같아야 한다.

```text
동일 OSM PBF
+ 동일 GTFS ZIP
+ 동일 서비스 날짜와 출발 시각
+ 동일 출발·도착 좌표
+ 동일 도보 속도
+ 동일 최대 도보시간
+ 동일 환승 여유시간
+ 동일 최대 환승 횟수
+ 동일 반환 후보 수
```

입력 데이터가 다르면 엔진 비교가 아니라 데이터 비교가 된다.

### 3.1 OSM

- 대한민국 전체 PBF를 바로 쓰지 않고 서울·수도권 범위로 한 번 잘라 두 엔진에 같은 파일을 전달한다.
- 원본 URL, 다운로드 시각, SHA-256을 `manifest.json`에 기록한다.
- 두 엔진별로 OSM을 따로 가공하지 않는다.
- 첫 실험에서는 elevation을 비활성화한다.

### 3.2 GTFS

필요한 최소 파일:

```text
agency.txt
stops.txt
routes.txt
trips.txt
stop_times.txt
calendar.txt 또는 calendar_dates.txt
shapes.txt
transfers.txt (없으면 누락 사실 기록)
```

GTFS는 먼저 공식 validator를 통과시킨다. 오류가 있으면 엔진별 import 결과를 비교하기 전에 데이터부터 고친다.

특히 확인할 항목:

- 시험 날짜가 `calendar` 운행 범위 안에 있는가?
- 모든 `trip_id`, `route_id`, `stop_id`, `shape_id` 참조가 유효한가?
- 정류장 순서와 `stop_sequence`가 단조 증가하는가?
- 자정을 넘는 운행이 `24:xx:xx` 형식으로 보존되는가?
- 버스가 정확한 시각표가 아니라 배차간격 기반이면 `frequencies.txt` 또는 생성 규칙이 명시됐는가?
- 급행·지선·회차를 서로 다른 trip으로 표현했는가?
- 두 엔진에 넣는 ZIP의 SHA-256이 같은가?

> **Gate G0:** 검증 가능한 최신 서울 GTFS가 없으면 품질 비교를 시작하지 않는다.
> 이 경우 먼저 `서울시 원천 데이터 → canonical GTFS` 생성 계획을 별도 작성한다.

## 4. 계획 디렉터리

구현 단계에서 아래처럼 운영 코드와 분리한다.

```text
tools/transit-routing-benchmark/
├── compose.routing-benchmark.yml
├── .env.example
├── data/
│   ├── input/                 # gitignore: OSM·GTFS 원본
│   ├── otp/                   # gitignore: OTP graph
│   └── graphhopper/           # gitignore: graph-cache
├── config/
│   ├── otp/
│   │   ├── build-config.json
│   │   └── router-config.json
│   └── graphhopper/
│       └── config-pt.yml
├── cases/
│   ├── smoke-cases.json
│   ├── tuning-cases.json
│   ├── holdout-cases.json
│   └── naver-reference.json
├── runner/
│   ├── query-otp.*
│   ├── query-graphhopper.*
│   ├── normalize.*
│   └── report.*
└── results/                   # gitignore: raw response·CSV·HTML
```

- 대용량 원천 데이터와 engine graph는 Git에 넣지 않는다.
- 설정, 테스트 케이스, 정규화 규칙, 결과 요약만 버전 관리한다.
- 기존 Docker Compose와 섞지 않고 별도 compose 파일을 사용한다.

## 5. 공정한 파라미터 기준

초기 권장값이며 두 엔진에서 의미가 같도록 매핑한 뒤 고정한다.

| 의미 | 초기값 |
|---|---:|
| 후보 수 | 5개 |
| 도보 속도 | 1.3 m/s |
| 승차 여유 | 60초 |
| 하차 여유 | 30초 |
| 환승 최소 여유 | 120초 |
| 최대 도보시간 | 20분 |
| 최대 환승 | 3회 |
| 검색 방향 | 출발시각 기준 |
| 접근성 옵션 | 비활성 |
| 실시간 | 비활성 |

정확히 같은 파라미터가 없는 경우:

1. 가장 가까운 의미의 설정을 사용한다.
2. 차이를 `parameter-mapping.md`에 기록한다.
3. 한 엔진에서만 가능한 고급 최적화는 기본 비교에서 끈다.

## 6. 비교 라운드

### Round A — 기본값 비교

- 동일한 필수 제약만 맞춘다.
- 엔진별 추천 기본값은 유지한다.
- 각 엔진의 out-of-box 품질과 운영성을 확인한다.

### Round B — 제한된 튜닝 비교

- 네이버와 차이가 나는 원인을 분류한 뒤 환승·도보·대기 페널티만 조정한다.
- 엔진별 최대 3회의 설정 변경만 허용한다.
- OSM·GTFS 자체를 엔진별로 다르게 수정하지 않는다.
- tuning case로만 조정하고 holdout case는 마지막까지 보지 않는다.

이렇게 해야 한쪽 엔진만 과도하게 튜닝해 얻은 결과를 일반 성능으로 오인하지 않는다.

## 7. 테스트 케이스

### 7.1 Smoke 20건

엔진 기동과 응답 정규화 확인용이다.

- 버스만 4건
- 지하철만 4건
- 버스→지하철 4건
- 지하철→버스 4건
- 버스→버스 및 환승 2회 이상 4건

### 7.2 본 비교 300건

| 구분 | 포함 조건 |
|---|---|
| 시간 | 평일 출근·퇴근·비혼잡, 주말, 심야 |
| 지역 | 도심, 강남, 한강 횡단, 서울 외곽, 수도권 경계 |
| 수단 | 버스, 지하철, 혼합, 환승 2회 이상 |
| 노선 특성 | 급행, 지선, 회차, 동일 번호 방향 분리 |
| 보행 | 짧은 접근, 긴 접근, 지하철 출입구, 도보 환승 |
| 예외 | 첫차·막차, 배차 긴 노선, 탐색 결과 없음 |

- tuning set: 200건
- holdout set: 100건
- 날짜와 시각은 고정값으로 저장한다. 실행 시각의 `now`를 사용하지 않는다.
- 네이버 기준 결과는 자동 scraping하지 않고 수동 기록하거나 허용된 공식 수단만 사용한다.
- 네이버 결과를 기록할 때 `capturedAt`과 검색 출발시각을 함께 저장한다.
- 정적 Round A/B에서는 네이버와 **노선·승하차·환승 구조**를 주 지표로 비교하고,
  실시간 정보가 섞일 수 있는 총소요시간 차이는 참고값으로만 사용한다.
- ETA 정확도는 Phase 6에서 같은 시점의 실시간 조건을 맞춘 뒤 별도로 판정한다.

## 8. 공통 결과 DTO

엔진 원본 응답을 아래 형태로 정규화한다.

```json
{
  "caseId": "weekday-am-001",
  "engine": "OTP",
  "rank": 1,
  "departureAt": "2026-09-08T08:10:00+09:00",
  "arrivalAt": "2026-09-08T08:52:10+09:00",
  "totalTimeSeconds": 2530,
  "walkTimeSeconds": 540,
  "walkDistanceMeters": 680,
  "waitingTimeSeconds": 280,
  "transferCount": 1,
  "legs": [
    {
      "mode": "BUS",
      "routeId": "canonical-route-id",
      "routeName": "502",
      "fromStopId": "canonical-stop-id",
      "fromStopName": "서울역버스환승센터",
      "toStopId": "canonical-stop-id",
      "toStopName": "사당역"
    }
  ]
}
```

### 정규화 규칙

- 엔진 내부 ID가 아니라 GTFS 원본의 canonical ID를 비교한다.
- 노선명은 표시용이고 동일성 판정은 `routeId`로 한다.
- 정류장명만 같은 다른 정류장을 동일 처리하지 않는다.
- 도보 leg는 출발·도착 좌표와 시간·거리로 비교한다.
- 반환되지 않는 값은 0으로 만들지 않고 `null`과 `missingReason`을 남긴다.
- 원본 응답도 별도로 저장해 정규화 버그를 추적할 수 있게 한다.

## 9. 품질 지표

### 9.1 네이버 기준 유사도

| 지표 | 의미 |
|---|---|
| `search_success_rate` | 정상 경로를 하나 이상 반환한 비율 |
| `naver_top1_exact_rate` | 엔진 1위와 네이버 1위의 수단·노선·승하차가 모두 같은 비율 |
| `naver_in_engine_top3_rate` | 네이버 1위가 엔진 상위 3개 안에 포함된 비율 |
| `route_sequence_similarity` | 이용 노선 순서의 유사도 |
| `boarding_stop_match_rate` | 승차 정류장 일치율 |
| `alighting_stop_match_rate` | 하차 정류장 일치율 |
| `transfer_count_delta` | 네이버 대비 환승 횟수 차이 |
| `duration_absolute_error` | 총소요시간 절대 오차 |
| `walk_distance_delta` | 도보거리 차이 |
| `unsafe_transfer_rate` | 환승 여유가 기준보다 짧은 후보 비율 |

> 정적 비교 점수에는 `duration_absolute_error`를 직접 합산하지 않는다. 네이버의 실시간 ETA와
> 정적 GTFS 기반 ETA를 그대로 비교하면 엔진 품질이 아니라 실시간 데이터 유무를 측정하게 된다.

### 9.2 NexRoute 적합성

- 버스·지하철 leg가 canonical route/stop/trip ID를 보존하는가?
- 중간 정류장 순서가 제공되는가?
- 각 leg의 Polyline이 제공되는가?
- 후보별 출발·도착시각이 제공되는가?
- 대체 경로를 최소 3개 안정적으로 반환하는가?
- 이후 NexRoute 실시간 버스·ML 평가에 필요한 필드가 누락되지 않는가?

### 9.3 성능·운영 지표

- graph import 시간과 peak memory
- graph 파일 크기
- 서버 기동시간
- warm query p50/p95/p99
- 10·50 동시 요청 성공률과 p95
- idle/peak CPU와 RSS
- 잘못된 요청 및 데이터 공백 시 오류 계약
- GTFS 갱신 후 graph 재생성 및 교체 시간

## 10. 초기 판정 기준

다음은 실험 전에 고정하는 권장 기준이다. 결과를 본 뒤 유리하게 바꾸지 않는다.

| 항목 | 통과 기준 |
|---|---:|
| holdout 탐색 성공률 | 98% 이상 |
| 네이버 1위가 엔진 상위 3개에 포함 | 85% 이상 |
| 승·하차 정류장 일치 | 90% 이상 |
| 환승 횟수 차이 | 95%가 ±1 이내 |
| 비현실적 짧은 환승 | 1% 미만 |
| warm p95 | 1초 이하 |
| 결과 canonical ID 매핑 | 99% 이상 |

통과한 엔진이 둘이면 아래 가중치로 결정한다.

```text
네이버 경로 유사도       45%
승하차·노선 식별 안정성 20%
탐색 성공률             15%
응답시간·자원           10%
설정·갱신 운영성        10%
```

## 11. 실행 단계

### Phase 0 — 데이터 준비와 검증

1. OSM/GTFS 원본 URL, 날짜, 라이선스, SHA-256 기록.
2. 서울·수도권 OSM 범위를 한 번 생성.
3. GTFS validator 실행 및 오류 목록 저장.
4. 시험 날짜의 운행 trip 수와 노선·정류장 수를 SQL/스크립트로 산출.
5. 동일 파일을 양쪽 엔진 디렉터리에 read-only mount.

완료 조건:

- `manifest.json` 존재
- validator 치명 오류 0건
- 두 엔진 입력 SHA-256 동일
- 시험 날짜에 유효 trip 존재

### Phase 1 — 컨테이너 기동

1. OTP 2.9.0 공식 이미지를 고정 digest로 사용.
2. OTP graph build와 serve를 별도 명령으로 분리.
3. GraphHopper 11.0 tag에서 로컬 arm64 이미지를 빌드.
4. GraphHopper import와 serve를 별도 명령으로 분리.
5. healthcheck와 CPU/memory limit 추가.

예정 명령 인터페이스:

```bash
docker compose -f tools/transit-routing-benchmark/compose.routing-benchmark.yml build
docker compose -f tools/transit-routing-benchmark/compose.routing-benchmark.yml run --rm otp-build
docker compose -f tools/transit-routing-benchmark/compose.routing-benchmark.yml run --rm graphhopper-import
docker compose -f tools/transit-routing-benchmark/compose.routing-benchmark.yml up -d otp graphhopper
```

완료 조건:

- OTP `localhost:18080` health 정상
- GraphHopper `localhost:18989` health 정상
- 같은 날짜·OD 한 건이 두 엔진에서 성공

### Phase 2 — API adapter와 정규화

1. OTP GraphQL `planConnection` query 작성.
2. GraphHopper `/route?profile=pt` query 작성.
3. 공통 DTO 변환기 작성.
4. 원본 응답, 정규화 결과, elapsed time을 함께 저장.
5. schema 변경이나 필드 누락은 즉시 실패하도록 contract test 작성.

> OTP 2.8부터 REST API가 제거됐으므로 과거 `/otp/routers/default/plan` 예제를 사용하지 않는다.

완료 조건:

- 같은 case를 한 명령으로 양쪽에 요청 가능
- 정규화 결과에서 leg/route/stop 식별자 비교 가능
- 원본 응답으로 언제든 역추적 가능

### Phase 3 — Smoke 20건

1. 20건을 순차 실행.
2. 탐색 실패와 정규화 실패를 먼저 해결.
3. 네이버 지도 위에 양쪽 Polyline을 다른 색으로 수동 확인.
4. 도로·건물 관통, 정류장 연결 실패, 시간대 오류를 분류.

완료 조건:

- 테스트 하네스 오류 0건
- 엔진 차이와 데이터 결함을 구분할 수 있음
- 본 비교 전에 수정할 GTFS 문제가 목록화됨

### Phase 4 — Round A 기본값 비교

1. tuning 200건을 각 엔진에 1회 warm-up 후 반복 실행.
2. 품질·성능·자원 지표 산출.
3. 불일치 원인을 `DATA`, `DEFAULT_COST`, `ENGINE_LIMIT`, `NORMALIZER`로 분류.
4. holdout 100건은 실행하지 않는다.

완료 조건:

- 기본값 결과 보고서 생성
- 엔진별 상위 불일치 원인 5개 확정

### Phase 5 — Round B 제한 튜닝

1. 각 엔진 최대 3회 설정 변경.
2. 변경마다 tuning 200건 전체 재실행.
3. 가장 좋은 설정 한 개를 고정.
4. 같은 설정으로 holdout 100건을 한 번만 실행.

완료 조건:

- holdout 기준 품질·성능 표 생성
- 결과를 보고 바꾼 사후 임계값 없음

### Phase 6 — 실시간 적용성 검증

정적 비교를 통과한 엔진에만 수행한다.

1. 현재 서울시 버스·지하철 실시간 데이터를 GTFS-RT로 변환 가능한지 확인.
2. `trip_id` 매칭률 측정.
3. 지연·취소·추가 운행 반영 시 후보 순위 변화 확인.
4. NexRoute ML 예측과 engine realtime이 이중 반영되지 않도록 책임 분리.

권장 책임:

```text
라우팅 엔진 realtime : 운행 취소·명확한 timetable 지연
NexRoute ML           : 버스 환승 정류장 도착예측 및 후보 재평가
```

완료 조건:

- 실시간 원천과 canonical trip ID 매칭률 보고
- 지연을 두 번 더하지 않는 계산 계약 확정

### Phase 7 — 최종 결정

결과는 다음 세 가지 중 하나로 끝낸다.

1. **OTP 채택**: 대중교통 품질과 운영성이 기준 충족.
2. **GraphHopper 채택**: 품질 차이가 작고 운영 단순성이 우세.
3. **오픈소스 보류**: 둘 다 네이버 유사도 기준 미달. 카카오/ODsay 유지 후 GTFS 품질부터 개선.

## 12. 위험과 중단 조건

| 위험 | 대응 |
|---|---|
| 서울 GTFS가 오래됨 | 엔진 평가 중단, GTFS 생성·갱신 파이프라인을 먼저 설계 |
| 버스 timetable이 실제 배차를 표현하지 못함 | frequency 기반과 NexRoute 실시간 보정 방식을 별도 시험 |
| 두 엔진 ID가 달라 비교 불가 | GTFS canonical ID 보존 여부부터 adapter contract로 고정 |
| Apple Silicon 이미지 미지원 | amd64 에뮬레이션보다 해당 tag를 로컬 빌드하는 것을 우선 |
| graph build가 로컬 메모리를 과도하게 사용 | 서울 범위로 축소하고 import/serve 메모리를 분리 측정 |
| 네이버 기준 데이터 자동 수집 문제 | 자동 scraping 금지, 수동 golden set 또는 허용된 공식 수단만 사용 |
| 기본값 비교가 불공정 | hard constraint만 동일화하고 튜닝 비교를 별도 라운드로 분리 |

즉시 중단 조건:

- 두 엔진에 동일 GTFS를 넣을 수 없음
- 시험 날짜에 유효한 trip이 없음
- canonical route/stop ID의 95% 미만만 보존됨
- import 과정에서 노선·trip의 5% 이상이 조용히 누락됨

## 13. 이번 계획의 비목표

- ODsay adapter 제거
- 운영 `/api/route/search` 변경
- 모바일 네이버지도 구현 변경
- 카카오/네이버 API와 자동 대량 비교
- 서울시 원천 데이터의 완전한 GTFS 변환기 구현
- 최종 엔진을 미리 결정하는 것
- 부하 테스트 전에 성능 최적화를 시작하는 것

## 14. 구현 착수 순서

- [ ] G0: 사용할 서울 GTFS의 출처·최신성·파일 구성을 확정
- [ ] 데이터 manifest 및 validator 실행 스크립트
- [ ] 별도 Docker Compose와 볼륨 구조
- [ ] OTP 2.9.0 graph build/serve
- [ ] GraphHopper 11.0 import/serve
- [ ] API query adapter 2개
- [ ] 공통 DTO normalizer 및 contract test
- [ ] smoke case 20건
- [ ] 네이버 reference와 tuning/holdout case 작성
- [ ] Round A 기본값 비교
- [ ] Round B 제한 튜닝 및 holdout 평가
- [ ] 승리 엔진만 실시간 적용성 검증
- [ ] 채택/보류 ADR 작성

## 15. 첫 번째 다음 행동

구현에 바로 들어가기 전에 아래 한 가지만 먼저 확인한다.

> **현재 확보한 서울·수도권 데이터로 시험 날짜가 유효한 GTFS ZIP을 만들 수 있는가?**

가능하면 Phase 0부터 진행한다. 불가능하면 OTP와 GraphHopper를 먼저 띄우는 대신,
현재 PostgreSQL 정적 데이터를 GTFS로 내보내는 별도 계획을 세운다. GTFS가 부정확한 상태에서는
어느 엔진을 선택해도 네이버지도와의 경로 차이를 신뢰할 수 없다.
