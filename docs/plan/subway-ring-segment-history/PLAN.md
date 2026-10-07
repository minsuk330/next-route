# 지하철 링 — 앞 열차 실측 구간시간으로 ETA (해결안 3) — PLAN

티어 M. 브랜치 `feat/subway-ring-segment-history` (fresh main 87d9bc94). 마이그레이션 없음. 앱 계약 변경 없음(값의 정확도·유효기간만 바뀜).

## 결론
마지막 구간(전역 출발 → 도착)의 ETA 를 도착 API `barvlDt` 대신 **오늘 같은 구간을 지난 최근 열차 3대의 실측 소요시간 중앙값**으로 만든다. 사용자 결정 2026-10-07: "정적 값·중위값으로 채우지 않는다" 원칙을 **"오늘 실제로 지나간 열차의 실측은 쓴다"** 로 바꾼다(시간표·정적 소요시간은 여전히 안 씀).

### 근거 (실측, 2026-10-07 22:38~23:40, 전 노선 63만 행, 구간 3,820개)
| 방식 | 치우침 | 오차 중앙값 | p90 | 30초 넘게 틀림 |
|---|---|---|---|---|
| API 초 (현행) | +54초 | 55초 | 82초 | 80% |
| 최근 앞 열차 3대 중앙값 | 0초 | 15초 | 65초 | 24% |
- 커버리지 82%(앞 열차 기록이 있는 구간). 노선별: 1호선 85→13초, 2호선 48→8초, 4호선 70→15초, 9호선 62→9초, 5·7호선 38→21초, 공항철도·GTX-A 는 약함(57~70초).
- 진단: `barvlDt` 는 상태가 바뀔 때만 정해지는 정적 값이고 전역 출발 뒤에는 50~60초 과대 → 링이 천천히 차다가 도착에서 1.00 으로 튄다. 같은 묶음이 반복되며 `recptnDt` 만 갱신돼 ETA 가 밀리기도 한다(55%).
- 분석 스크립트: 세션 스크래치(`eta_hist2.py`) — 기준 "실제 도착" = 같은 원천에서 그 역 도착(1, 없으면 진입 0)이 처음 보인 시각.

## 결정 (Codex 리뷰 반영)
1. **구간 시작 = 그 역 행이 `arvlCd=3`(전역 출발)으로 처음 보인 `recptnDt`** — 수집 계층에서 열차 단위로 한 번 기록한다(여정별 기록 기각: 늦게 시작한 여정과 ETA 가 갈린다).
2. **구간 소요시간 표본 = 같은 (노선, 방향, 역)의 최근 3대 `도착 처음 보인 시각 − 전역 출발 처음 보인 시각`**. 0 < d ≤ 900초만 받는다.
3. **ETA = 구간 시작 + 표본 중앙값**. 고정값이라 반복 관측에서 밀리지 않는다. 같은 ETA 면 링을 그대로 둔다(push·버전 흔들림 없음).
4. **표본이 없으면(약 18%) API 초를 쓰지 않고** 기존대로 0 앵커 STALE / 진행 중이면 freeze. (API 초는 +54초 과대)
5. **전역 정차·진입(`arvlCd=5·4`) 동안은 진행 고정**: 열차가 아직 출발 전. ETA 없음, 보간 상한 = 현재 값, 품질 NORMAL("확인 중"으로 보이지 않게).
6. **ETA 경과 후 도착이 없으면** 0.90 에서 멈춘다(ETA null, 상한 = 0.90, NORMAL). 도착·진입 사건이 오면 기존대로 0.95/1.00.
7. **유효기간**: 실측 ETA 앵커는 `progressValidUntil = max(base + 120초, ETA + 120초)` — 앱은 이 시각에 Live Activity 를 시간 초과로 본다(`navigation-live-activity.tsx:109-117`). base 가 원천 지연만큼 과거라 지금 규칙(base+120초)이면 ETA 전에 끊긴다.
8. **위치 API 역할은 바꾸지 않는다** — "더 앞선 원천 채택"·출발 시각 보정은 기각(Codex: 비인접 점프 위험, 두 원천 시간축이 달라 실측 표본과 섞으면 치우침). 위치는 지금처럼 도착 행이 없을 때의 구간 전환 폴백.
9. `arvlCd=99`(2역 이상 전)·`0/1/2` 처리는 그대로.

## 변경 파일
### 기록 (worker, 수집기)
- 신규 `application/subway/port/out/SubwaySegmentTimePort.java`
  ```java
  void recordDeparted(String lineId, String direction, String stationId, String trainNo, Instant at); // 처음 본 것만, 15분 넘은 옛 값은 덮음
  void recordArrived(String lineId, String direction, String stationId, String trainNo, Instant at);  // 출발 기록 있으면 표본 추가 후 출발 기록 삭제
  Optional<Instant> departedAt(String lineId, String direction, String stationId, String trainNo);
  List<Long> recentSeconds(String lineId, String direction, String stationId);                    // 최신순 ≤3
  ```
- 신규 `infrastructure/adapter/out/cache/subway/RedisSubwaySegmentTimeAdapter.java` — 키 `subway:seg:dep:{line}:{dir}:{station}`(hash train→epochSec, TTL 30분) / `subway:seg:dur:{line}:{dir}:{station}`(list, LPUSH+LTRIM 0 2, TTL 3시간). 출발·도착은 각각 Lua 한 문장(원자). 실패는 로그만(수집을 막지 않음).
- 신규 `application/subway/service/SubwaySegmentTimeRecorder.java` — 수집 행 목록을 받아 `arvlCd=3` → `recordDeparted`, `1`·`0` → `recordArrived`(시각 = `recptnDt`). 플래그 `realtime.subway.segment-time.enabled`(기본 true).
- `application/subway/service/RealtimeSubwayCollector.java` — 스냅샷 저장 뒤 `recorder.record(trains)`(예외 삼킴).

### 링 (worker)
- `application/journey/dto/SubwayRingObservation.java` — `Instant segmentStartAt` 추가(실측 ETA 일 때만). 기존 팩토리 유지.
- `application/journey/service/SubwayRingSignalReader.java` — 도착 행이 `arvlCd=3` 이면 `departedAt`(없으면 그 행 `recptnDt`) + `recentSeconds` 중앙값으로 ETA, `segmentStartAt` 채움. 표본 없으면 ETA null. `4·5` 는 ETA null. 그 외 코드는 기존.
- `application/journey/service/SubwayRingCalculator.java` `fromPreviousStation`:
  - `4·5` → `hold`(같은 구간이면 현재 진행 유지, 아니면 0; ETA null; 상한 = 진행; NORMAL)
  - `3` + 실측 ETA → 같은 구간·같은 ETA 면 직전 링 유지(`updatedAt`·`observedAt` 만 갱신) / 지금 ≥ ETA 면 `hold(0.90)` / 아니면 앵커(진행 = 같은 구간 직전 값 또는 0, base = `segmentStartAt`, ETA, NORMAL, 유효기간 규칙 7)
  - `3` + ETA 없음 → 기존(첫 관측 0 앵커 STALE, 같은 구간 freeze)
- `application/subway/dto/SubwayRealtimeTrain.java` 등 기존 DTO 변경 없음.

## 순서
- [x] 1. 포트·Redis 어댑터 + IT(Testcontainers Redis, `RedisSubwayRingAdapterIT` 패턴): 첫 출발만 기록·15분 넘은 옛 값 덮음·도착 시 표본 추가·3개 유지·범위 밖 버림·출발 없으면 무시
- [x] 2. 기록기 + 수집기 배선 + 단위 시험
- [x] 3. 신호 읽기 실측 ETA + 단위 시험(표본 있음/없음, 출발 기록 없으면 행 시각)
- [x] 4. 계산기 hold·고정 ETA·경과 + 시험(같은 ETA 반복 시 링 불변, 정차 중 0 유지·NORMAL, ETA 경과 0.90 hold, 유효기간 = ETA+120초)
- [x] 5. `SubwayRingIT` — 표본이 Redis 에 있을 때 첫 링의 ETA = 출발 + 중앙값
- [x] 6. 전체 시험, 노트, PR

## 검증
- 전체 `./gradlew test --continue`(일회용 DB·Redis·더미 env). 기존 무관: JourneyEvalReadPathIT·JourneyEvaluationIT·JourneyTransferAssessmentIT·RerouteRemainingPathOtpIT(+간헐 JourneyNotificationSchemaIT)
- 배포 후: Redis `subway:seg:dur:*` 키 생성 확인, 실여정 push outbox 에서 전역 출발 구간 ETA 고정·도착 시 1.00 튐 감소

## 배포
마이그레이션 없음. worker 만(수집기·링). 표본은 배포 후 앞 열차가 지나가며 쌓인다 — 처음 수 분은 STALE 폴백이 많다.
