# 지하철 진행 링 — 정차 구간 포함·TYPICAL 직선·STALE 축소 — PLAN v2 (L)

원 요청: `REQUEST.md`(같은 폴더, 2026-10-10 제품 결정). 기준 커밋 `bb1aba23`, 줄 번호는 그 커밋 기준.
범위: 링 계산·상태·스냅샷·push payload. **고정(binding)·열차 선택 로직은 건드리지 않는다.**
v2 = Opus 리뷰(`REVIEW.md`) 반영: 시간축 정의를 PR-1 계약으로, LATE 보정을 PR-2 로 당김, 정차+운행 **단일 직선**, 구간 내 단계 역행 금지.

## 0. 결정
- 원칙 전환: "모르면 멈춘다" → "느리게라도 움직인다, 추정이면 `etaSource=TYPICAL`". 되감김 금지(#358 `notBehindScreen`)·근거 없는 도착 금지(진입/도착 전 cap 0.90 이하) 유지.
- **실측 정차 시간 수집 제외.** 정차 = GTFS `arrivalGapSeconds − runSeconds`(**같은 패턴**에서, ≤0·없음 → `defaultDwell` 30s).
- **계산기는 순수.** GTFS 조회는 갱신기가 하고 **현재 목표 구간 하나만** 계산해 `SubwayRingContext` 로 넣는다(`SubwayLineOrder.patterns` 비면 `loadLater` 129 — 비었으면 typical 없음 → 기존 동작).
- **시간축(리뷰 R1·R2·Y3)**: 직선 앵커는 recptnDt(1~2분 늦음)가 아니라 **서버가 그 사건을 처음 본 시각(`sourceSeenAt`)** 에 둔다. LIVE(`recptnDt + barvlDt`)는 원래 실제 시간축이라 그대로. 원천 지연 보정은 **앵커를 만들 때 한 번만** — tick 마다 재계산 금지.
- **정차+운행 단일 직선(리뷰 Y2, 요청서 §3-1 의 고정 dwellShare 분할 대신)**: 전역 정차 시작부터 0 → cap 을 `(정차+운행)×factor` 로 한 직선. 출발(PREV_DEPARTED) 관측 시 화면값 → cap 을 운행 예정으로 재앵커. 이유: 15% 꺾임 없음, `estimatedNextStationAt` 의미("다음 역 도착 예정") 유지 → 하차 예정·staleDate·보호 연장이 그대로 맞음(리뷰 R3 소멸), 전환 push 1건 감소. `dwellShare` 설정은 두지 않는다.
- **구간 내 단계 순서(리뷰 R4)**: 정차(DWELL) < 운행(RUN) < 늦음(LATE) < 진입 < 도착. 같은 구간에서 더 앞 단계 관측(예: 위치 DEPARTED 뒤 도착 API 의 늦은 PREV_ARRIVED)은 `prior.refreshed`. 단계는 상태 내부 필드로 저장.
- **같은 관측은 refreshed(리뷰 Y1)**: TYPICAL/MEASURED 경로는 (구간·단계·etaSource·sourceSeenAt)이 같으면 `prior.refreshed` — 예정·지문이 흔들리지 않는다.
- 새 enum 은 새 필드로만. **`SubwayRingEtaSource`·`SubwayRingStage` 도 배포 후 값 추가 금지**(롤백한 옛 코드 Redis 읽기 실패 → 링 리셋). Redis 롤백 안전은 리뷰 G1 이 확인(Boot 기본 매퍼, 레코드 정규 생성자, 기존 IT `guidanceVersionRoundTripsAndOldValuesReadAsZero` 147).
- PR 4개, 각각 fresh main 기준 독립 브랜치, 머지·배포 후 실여정 1회 확인하고 다음으로.

## 1. 공통 계약 (PR-1)
- `enum SubwayRingEtaSource { LIVE, MEASURED, TYPICAL }`, `enum SubwayRingStage { DWELL, RUN, LATE, APPROACH, ARRIVED }` — `application/journey/dto/`.
- `SubwayRingState` 끝에 추가: `etaSource`, `alightingEtaSource`(외부 노출), `stage`, `sourceObservedAt`(그 근거 행의 recptnDt), `sourceSeenAt`(서버가 그 recptnDt 를 처음 본 시각)(내부 전용). 옛 생성자 유지. **`with…()` 전부·`withAnchor`·`refreshed` 가 새 필드 통과**.
- **sourceSeenAt 결정 규칙(갱신기)**: 이번 관측의 근거 시각(recptnDt 또는 위치 로그 receivedAt)이 직전 상태의 `sourceObservedAt` 과 같으면 직전 `sourceSeenAt` 을 이어 쓰고, 다르면 `now`. 수집기·로그 포맷 변경 없음(리뷰 Y6 의 7번째 필드 안보다 단순 — ring tick 5s·수집 15s 라 최초 감지 오차 ≤ 20s).
- `SubwayRingObservation` 끝에 `Instant seenAt` 추가(갱신기가 위 규칙으로 채움).
- `SubwayGuidance` 끝에 `etaSource`·`alightingEtaSource`, `of()` 매핑, `pending()` null. push `props` 동일 두 키. push 지문에 두 키 추가(`progressTarget` 버킷은 넣지 않는다 — ETA 유무로 이미 갈림, 리뷰 Y1).
- 설정(`JourneySubwayRingProperties`+`application.yaml`): `typical-factor: 1.2`, `default-dwell: PT30S`, `stale-after: PT60S`, `stale-source-max-age: PT240S`, `late-grace: PT30S`. Javadoc 에 `freshness-window`(recptnDt 기준 validUntil 용) 와 `stale-after`(서버 시계 기준 STALE) 역할 분리.

## 2. PR 별 작업

### PR-1 `feat/ring-eta-source-contract` — 계약·시간축 필드 (화면 동작 변화 없음)
- [ ] §1 전부. 계산기는 기존 경로에서 etaSource·stage 를 채운다(`reanchored`→LIVE/RUN, `fromMeasured` 예정 경로→MEASURED/RUN, LATE hold→LATE, PREV 정차 hold→DWELL, 진입→APPROACH, 도착→ARRIVED). alightingEtaSource = 다음 역이 하차역이면 etaSource, 아니면 다음 역 예정이 있을 때 TYPICAL(남은 구간은 계획값 — 요청서 §3-4 좁힌 안), 없으면 null.
- [ ] 갱신기 sourceSeenAt 규칙 + Context `typical`(현재 목표 구간의 dwell/run 초, 없으면 null).
- [ ] PLAN 에 명시: 배포 직후 진행 중인 링마다 지문 변화로 ANCHOR push 1건·guidanceVersion 상승 1회(일회성, 허용 — 리뷰 Y4).
- 시험: with… 보존(REQUEST #11), payload 직렬화·기존 키 불변(#12), etaSource 만 바뀌어도 지문 상이(#13), 옛 JSON→null·모르는 필드 JSON 성공(#15), sourceSeenAt 이어쓰기/갱신, 같은 패턴 GTFS 정차·음수→default(리뷰 G3), 패턴 캐시 비면 typical null(빠진 시험 6).
- 배포: api+worker.

### PR-2 `feat/ring-typical-segment` — 전환·첫 관측 TYPICAL + 서버 시계 STALE + LATE 보정
- [ ] `advanced()`(329-334) 를 사건별로 분리(리뷰 Y5): `departed(next)` = 그 역 출발 → RUN 직선(base 0, 예정 = seenAt + run×factor, TYPICAL), `arrivedAtBoarding` 등 기존 호출부 구분. typical 없으면 기존(0 STALE).
- [ ] 위치 폴백 ARRIVED/ENTERING(314-317)·DEPARTED(318)·LEFT_PREVIOUS(320-322): NORMAL, 전환은 위 규칙.
- [ ] 표본 없는 PREV_DEPARTED 첫 관측(199-204): RUN TYPICAL NORMAL, 앵커 seenAt.
- [ ] MEASURED(`fromMeasured`·`SignalReader.measured` 120-131): 시작 = PREV_DEPARTED 를 **처음 본 서버 시각**(sourceSeenAt) → journey 202 의 첫 관측 즉시 LATE 해소.
- [ ] LATE: `now ≥ eta + lateGrace`. 예정은 앵커 시점에 고정(재계산 없음).
- [ ] STALE: `now − sourceSeenAt > staleAfter` **또는** `now − sourceObservedAt > staleSourceMaxAge`(리뷰 R1-c). `freshnessWindow` 는 validUntil 계산에만.
- [ ] validUntil = `max(baseAt+window, eta+window, now+window)` — 생성 시점에 now 보다 뒤(빠진 시험 7).
- [ ] 같은 관측 반복 → refreshed.
- 시험: REQUEST #5·#6·#7·#8, 빠진 시험 1(행 고정·collectedAt 만 증가 → STALE, ETA 안 밀림)·2(같은 관측 10 tick → refreshed, push 0)·5(운행 60s·지연 90s 첫 tick 점프 없음)·7·8(LIVE↔MEASURED 전환 ETA 튐 < 원천 지연), 시나리오 IT "역당 push ≤ 6". "advanced=STALE" 고정 기존 시험은 새 계약으로 수정(노트에 목록).

### PR-3 `feat/ring-dwell-run-line` — 전역 정차를 같은 직선에 포함
- [ ] PREV_ENTERING/PREV_ARRIVED(187-190): `hold` 대신 DWELL 직선 — base = 같은 구간 화면값|0, 예정 = 정차 시작(sourceSeenAt) + (dwell+run)×factor, target cap, TYPICAL, NORMAL, `holdReason=PREV_STATION_STOPPED`+역명 유지. 같은 4/5 반복 → refreshed(정차 시작 재앵커 금지, journey 158 교훈).
- [ ] PREV_DEPARTED(DWELL→RUN): 화면값 → cap, 예정 = MEASURED 또는 seenAt + run×factor. 단계 역행(RUN 뒤 PREV_ARRIVED) → refreshed + 시험(빠진 시험 3).
- [ ] 정차 직선 중에도 `estimatedNextStationAt` = 다음 역 도착 예정이라 `alightingAtOf` 그대로 맞음 — 시험으로 고정(빠진 시험 4).
- [ ] 승차역 구간(seq 0) 불변(REQUEST #9).
- [ ] Javadoc: `SubwayRingCalculator` 22-50("모르면 멈춘다", 리뷰 G2), `SubwayRingQuality` 15-20, `SubwayRingSignalReader` 29, `SubwayRingState` 23·31-34, `SubwayRingHoldReason` 6-8, `SubwayGuidance` 39 — PREV_STATION_STOPPED = "열차가 전역에 서 있음(링은 느리게 진행)".
- 시험: REQUEST #1·#2·#3, 빠진 시험 3·4, 단조 #8.

### PR-4 `feat/ring-alighting-always` — 하차 예정 항상
- [ ] `alightingAtOf`(171-188): 다음 역 예정이 없으면(LATE·진입·도착·freeze) `max(now, 다음 역 추정)` + 남은 구간 전형 합(정차+운행, factor). 다음 역 추정 = LATE/진입 → now. alightingEtaSource = TYPICAL.
- [ ] `extendProtection`(136-149): TYPICAL 값으로도 연장(상한 cap 유지) — Javadoc 명시.
- 시험: REQUEST #10.

## 3. 검증 (매 PR)
- `./gradlew test --tests '*SubwayRing*' --tests '*SubwayGuidance*' --tests '*JourneySubwayGuidance*' --tests '*SubwayRideScenario*'` + 전체 `./gradlew test --continue`(기존 무관 12건: JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1·JourneyNotificationSchemaIT 1 flaky).
- 스크래치 IT: 일회용 postgis(로컬 dev DB schema-only + flyway 이력, init "ready" 2회 뒤 복원) + redis + 더미 env 5개(implementation_note #357 항목).
- 배포 후: ERROR 0, 실여정 1회 링 시계열(quality·p/target·etaSource·stage·holdReason·push 건수) → 노트 `### 검증`.

## 4. 리스크
- TYPICAL 이 실제보다 빠르면 cap 에서 LATE 로 멈춤 — factor 1.2, 실측 후 조정.
- 서버 최초 감지 시각 기준이라 링이 실제보다 최대 ~20s + 원천 지연만큼 늦게 출발 — 진입/도착 코드도 같은 지연을 가지므로 일관. 하차 안내는 기존대로 "다음 역 = 하차역" 국면 전환이라 영향 없음.
- push: 리뷰 추정 역당 5~6건으로 현행과 비슷. refreshed 규칙이 깨지면 30초 버킷마다 증가 → 시나리오 IT 로 상한 고정.

## 5. 앱 인계 (PR-1 머지 시 HANDOFF.md)
- `etaSource` TYPICAL → 도착 시각 숨김(링은 그림). 필드 없으면 기존 동작. `alightingEtaSource` 동일.
- `holdReason=PREV_STATION_STOPPED` 중에도 링이 느리게 움직인다(문구 유지). 회색 끊김 문구는 `quality=STALE` 일 때만.

## 실행 중 정정 (2026-10-10, PR-2·PR-3 구현)
- **정차 = 비례 정차 지점까지의 직선**(v2 의 "정차+운행 한 직선" 수정): 0 → `상한 × 정차/(정차+운행)` 을 정차 × 배수 동안. 정시면 운행 직선과 기울기가 같아 꺾이지 않고(시험 고정), 정차가 길어지면 그 지점에서 멈춘다(사유 유지). 한 직선이면 정차 초과 때 링이 정차 중에 상한까지 차 버린다 — 실녹화(교대 정차 87초 vs GTFS 60초)에서 확인. 정차 직선의 `estimatedNextStationAt` 은 정차 끝이라 하차 예정은 운행 × 배수를 더한다(리뷰 R3 대응)
- **STALE 서버 시계는 도착 관측만**: 위치 관측은 전이 로그라 달리는 동안 갱신이 없다 → 종전(원천 시각 + freshness 120s) 유지
- **Context typical = 순번 → 시간 함수**: 구간 전환 때 다음 구간 값이 필요
- 재생 하네스(PR-0, `SubwayRingReplayIT`)로 PR 마다 실녹화 비교 — OTP 패턴 적재가 시험 환경에서 안 되므로 `REPLAY_TYPICAL` 로 GTFS 구간 시간 주입
