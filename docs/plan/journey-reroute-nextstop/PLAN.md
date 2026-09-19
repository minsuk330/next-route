# 주행 중 다음-정류장 재탐색 통일 — 구현 PLAN (에이전트용, rev4 = A′ + codex 반영)

티어 L. 설계 = `docs/superpowers/specs/2026-09-11-journey-reroute-nextstop-replan-design.md` + `DESIGN-LOG.md`. 사람용 = artifact `60cc3419`. 각 PR = fresh main 독립 브랜치([[feedback-per-task-pr]]). 노트 = 루트 `implementation_note.md` append-only. **규모 = PR-0 스파이크(문서) + 8 PR(A·B·C·1~5).**

## 결론 먼저
③(`RouteOptionCandidateProvider`) 폐기 + ④(`RemainingPathCandidateProvider`)를 **다음 정류장 창 + 모든 탑승점 실시간 overlay + 정류장별 리스트 + 알림/선택 + 진행중 RIDE 중간 하차**로 일반화(A′). 리뷰가 잡은 두 블로커(정류장별 시각 없음 / transferIndex 주소체계)를 OTP-네이티브 모델 + 원점 주소 일반화로 해결. **초기 스코프 = 버스만**(RIDE_INTERIOR bus-only), 지하철/철도는 보수적 폴백.

## 리뷰가 확정한 사실 (필독)
- 정류장별 데이터는 OTP 원천에 있음: `Leg.stopCalls[].{stopLocation gtfsId/lat/lon, schedule.time 시간표 도착/출발}`. **`legGeometry` 는 이미 받아 `RoutingLeg.geometry` 로 매핑됨**(OtpPlanConnectionMapper:204) — 진짜 결핍은 **stop-call 시각**.
- **~~realTime.arrival/departure 도 OTP 에서 받는다~~ 취소(2026-09-18 실측)**: 배포된 OTP 에 GTFS-RT 가 안 물려 있다(`router-config.json` 부재, build-config 의 feed 는 static GTFS 하나). `realTime`·`estimated`·`realtimeState` 는 **영원히 빈 값**이므로 쿼리에서 제거했다. **OTP = 정적 시간표·경로, 실시간 = 서버 캐시**([[project-bus-realtime-cache]]) 로 역할을 가른다. 둘을 합치는 계산은 서버가 한다.
- **유실 지점 = legacy 경계**: `OtpPlanConnectionMapper` 가 `StopOccurrence` 를 시각·seq null 로 만들고(:166), `LegacyRouteResponseMapper` 가 `PathResult` 변환 시 polyline·시각을 **일부러 버림**(:152). `RemainingRouteSearchService.loadOtp` 가 즉시 이 lossy 변환을 탐(:144). → GraphQL·mapper 만 넓혀선 안 됨, **변환 경계를 손봐야**.
- `reroute_proposal`·`transferIndex`: `V42:18` `transfer_index NOT NULL` + unique `(journey_id, transfer_index) WHERE ACTIVE`(:45). transferIndex = **34파일** + 공개 API `RerouteProposalService.request(Long,Long,int)`(:101), 저장 재검증·supersede 전부 transfer_index 기준(`RerouteProposalTxService`:95).
- `RemainingRouteSearchService.loadOtp` 는 **`clock.instant()` 고정 검색**, 도착시각 전파는 "후속작업"(:139).
- 수락 재작성은 바뀐 현재 leg 에 `ACTIVE`+활성시각만 승계, **progress/예측 안 승계**(`RerouteAcceptTxService`:229,:265). stale 체크는 `fromLegIndex` vs 현재 leg index 뿐(:136).
- `stopSeq` = GTFS stop_sequence ≠ leg-로컬 occurrence(`StopOccurrence` 경고). 공용 rank 함수 없음(현 provider 는 개선폭 내림차순, `RemainingPathCandidateProvider`:279). Redis cooldown 은 키+TTL 뿐(`RedisRerouteCooldownAdapter`:38).

## 확정 결정 (codex 반영)
- **geometry 게이트·투영 폐기**: truncated ride 지도선 = board→X 사이 **정류장 좌표(stopCalls lat/lon) 이어 그림**. 투영·오차·폴백 불필요(OTP transit geometry 는 STREET_FALLBACK 근사라 손해 미미). **시각 분할은 stopCall 시각으로 정확**.
- **PR-A 는 national OTP 마이그레이션(P2-B/P2-G)에 합류**. 옛 `SubPathResult` 확장 금지 → 중립 `StopCall` 모델(정류장별 시간표+실시간+식별 보존) + 버전드 스냅샷 리더. 구현 전 OTP 전환 담당과 조율.
- **stop 식별 = `(trip/pattern, sourceStopSequence, occurrenceIndex)`**. gtfsId 단독 금지(루프/반복정차). **단 이 규칙은 OTP 검색 결과 안에서만 적용한다(2026-09-18 범위 축소)** — 아래 "좌표계 둘" 참고.
- **좌표계가 둘이고 섞으면 안 된다(2026-09-18 실측)**:
  - **OTP 좌표계 `gtfsId + occurrenceIndex`** = 검색 결과 전용(PR-A `StopOccurrence`·`TruncatedRide`).
  - **서울 좌표계 `stopId + routeId + seq`** = 진행 중인 여정·실시간·**원점(`RerouteOrigin`)**. 근거 = 실시간 캐시 키가 `getArrivals(stopId, routeId, seq)` 이고, 여정 leg 는 `routeId`/`startStopId` 를 들며, 정류장 목록은 `busRouteStopRepository.findStopsByRouteId` 가 seq 순으로 준다.
  - 번역은 반만 된다: `OtpSeoulIdentityResolver` 는 stop/route id 를 주지만 **seq 는 못 준다**. `JourneyLeg` 에는 **tripId 자체가 없다**. 루프 재방문 구분은 서울 `seq` 가 한다.
- **ACTIVE 유니크 = 여정당 1개**(origin당 아님). 수락→여정 종료·한 여정 1회 모델과 일치.
- **진행중 leg 절단 시 progress = 현재 위치까지 보존**(물리적으로 같은 주행), 하차 목표만 X 로. grafted 꼬리는 PENDING. PR-0 이 progress 엔진 수용 확인 + stale/재생 progress id 테스트.
- **표준 `rank()` = PR-1 산출물**: 비교기(목적지 ETA → 환승수 → 도보 → 여유) + overlay 전 시간표용 정적 변형. PR-2 소비.
- **근거 기준이 두 곳에서 다르다 — 의도한 비대칭이다(사용자 결정 2026-09-18, 한쪽으로 통일하지 말 것)**:
  - **하차점(원점) = 실시간만.** 내가 탄 버스가 그 정류장에 언제 닿는지는 실시간 캐시가 답해야 하고, 없으면 그 정류장은 후보에서 **제외**한다(시간표 폴백 금지). 이유 = 하차는 **되돌릴 수 없는 물리 행동**이다. 틀리면 못 내리거나 엉뚱한 데서 내리고, 사용자는 서버 제안을 믿고 몸을 움직인다.
  - **다음 차 탑승 = 실시간 → 없으면 ML, 둘 다 없으면 후보 제외**(PR-1 원안 유지). 이유 = 탑승 추정이 틀려도 손실은 **정류장에서 더 기다리는 것**뿐이라 되돌릴 수 있고, 순위를 매기는 용도로는 모델 추정으로 충분하다.
  - 근거가 섞인 후보끼리 비교되는 문제는 PR-1 의 "baseline 도 같은 체인으로 overlay(출처 통일)" 가 이미 막는다. 사용자에게 근거 출처를 표시할지는 별건(UI).

## 작업 순서 (의존성)

### PR-0 (스파이크, 문서) — 절단 표현 + 진행판정
- [ ] OTP stopCalls 시각으로 board→X 소요 산출 + 정류장 좌표로 지도선 슬라이스 가능한지(fixture 1건, 투영 없음).
- [ ] `JourneyProgressService` 가 진행중 RIDE 하차점 교체 + progress 현재까지 보존 revision 에서 안 깨지는지(last_progress_id V38·pairedLegIndex·재생 id).
- [ ] 결과 `implementation_note.md ### 확정`. 산출=문서.

### PR-A (기반) — OTP-네이티브 정류장별 모델 (OTP 마이그레이션 합류)
의존: PR-0 + OTP 전환 모델 조율.
- [x] `planConnection.graphql`: `stopCalls` 에 `schedule{time{...on ArrivalDepartureTime{arrival departure}}}`. **realTime 은 안 받는다**(위 취소 항목 — 피드 없음). 같은 이유로 leg 단위 `estimated{time}`·`realtimeState` 도 제거(아무 코드도 안 읽고 있었음).
- [ ] `OtpPlanConnectionMapper`: 정류장별 시각 + `(trip, sourceStopSequence, occurrenceIndex)` 채움(현재 null:166).
- [ ] 중립 `StopCall` 모델 + **legacy 경계 유실 해결**(`LegacyRouteResponseMapper`:152 이 시각/정류장 버리는 것 대체 or 우회 경로). **버전드 스냅샷 리더**(P2-G revision 바인딩).
- [ ] 중간 절단 연산: transit leg 를 X 기준 board→X(시각=stopCall, 지도선=정류장 좌표 슬라이스, 이후 폐기). `JourneyCompiler` 절단 leg 컴파일.
- 검증: 쿼리/mapper 정류장별 시각·seq 파싱(fixture) + 절단 단위(소요·지도선·이후폐기) + 기존 planConnection 소비 무회귀.

### PR-B (기반) — 원점 de-transferIndex (expand→backfill→dual→cutover)
의존: 없음(PR-A 병렬).
- [x] 신규 `RerouteOrigin{legIndex, stopId, routeId, seq, kind{TRANSFER_WAIT, RIDE_INTERIOR}}` — **서울 좌표계**(위 "좌표계 둘" 참고, 원안의 `(trip,seq,occ)+stopGtfsId` 는 취소). 서버 파생(클라 좌표 안 받음). `routeId`/`seq` 가 없으면 실시간 조회 불가 → **그 원점은 후보에서 제외**.
- [x] **1 확장**: `reroute_proposal` 에 nullable origin 컬럼(V50) + 버전 discriminator(별도 컬럼 아니라 `RerouteOriginEnvelope` 봉투 안 — `candidates` 가 이미 쓰는 방식 재사용), `transfer_index NOT NULL`·기존 unique 유지. (JPA primitive int 라 선 nullable 금지.)
- [~] **2 백필**: **안 한다(2026-09-18 결정)**. 막아 줄 사고가 없다 — ③의 폴백(origin 없으면 transfer_index)과 ⑤의 "TTL+sweep 대기 후 옛 컬럼 제거" 두 겹으로 전환 구간이 이미 덮인다. 게다가 `transfer_connection` 에는 leg 번호뿐이라 tripId/정류장을 못 주고, `journey_revision.route_snapshot` JSON 을 과거 포맷까지 파싱해야 해서 비용도 크다(flag off 라 살아있는 행도 없음).
- [x] **3 이중 쓰기/읽기**: TRANSFER_WAIT 는 둘 다 씀(`RerouteOriginResolver`), 읽기 origin 우선·옛행 transfer_index 폴백(모르는 봉투 판은 없는 것으로). **`request(...int)` shim 은 보류** — origin 으로 부르는 진입점이 아직 없어 shim 할 대상이 없다(PR-2/PR-4 와 함께).
- [x] **4 candidate 봉투**: `RemainingPathApply` 에 nullable `origin` 필드 추가. **새 하위타입이 아니라 record 에 칸 추가**(`RouteOptionApply` 선례) — origin 은 "다른 종류의 후보"가 아니라 같은 종류의 추가 정보라 `kind` 축을 늘릴 일이 아니다. 옛 저장 JSON 은 null 로 읽히고 그게 옛 동작이다.
- [ ] **5 컷오버**: 충돌 ACTIVE 정리→**여정당 unique** 신규 생성→origin-only 배포→TTL(5분)+sweep 여유→옛 인덱스/컬럼 제거. **V번호 신규**, VPS 수동배치 + @Sql 3곳.
  - **③에서 옮겨온 것 — 키 이동 전부**(`stillCurrent`·`supersedeActive`·조회·cooldown 키·트리거·수락). 이유 = **부분 unique 인덱스(자리의 정의)와 supersede(자리 비우기)는 짝**이고 지금 둘 다 `(journey_id, transfer_index)` 로 맞아 있다. 여기만 먼저 바꾸면 ⓐ여정 기준 → 아직 살아야 할 다른 환승 제안까지 죽거나(현 IT `differentTransfersCoexist` 가 고정한 동작), ⓑorigin 기준 → origin 이 null 인 행을 못 밀어내는데 인덱스는 여전히 transfer_index 라 **새 insert 가 unique 위반으로 튕긴다**. ⑤는 인덱스 교체와 코드 교체가 같은 배포라 어긋나는 창이 없다.
  - 🔴 이 오류는 **테스트로 안 잡힌다**: 재탐색은 flag off 라 프로덕션에 티가 안 나고, 이 경로는 Postgres IT 로만 도는데 이 저장소엔 CI 가 없다.
- 검증: origin 왕복(TRANSFER_WAIT 무회귀), 이중읽기 폴백, 공개 API 컨트롤러 테스트, 마이그레이션 IT, 여정당 unique.

### PR-C (기반) — composer 중간 절단 합성
의존: PR-A.
- [ ] `JourneyRevisionComposer`: 중간 하차 합성 = `[prefix] + [truncated board→X(PR-A)] + [candidate]`. 가드(:80-86) "다른 노선 오염" 방지 유지(명시 origin 일 때만 절단 허용). 기존 미래-WAIT 경로 불변.
- 검증: 중간하차 revision·앞 절반 보존·오염 거부 유지. ⑤ 무회귀(RerouteAcceptIT).

### PR-1 — overlay A안 체인 + rank()
의존: PR-A(정류장별 시각).
- [ ] `RemainingPathEtaComposer.compose` 모든 WAIT 순차 체인: running clock 으로 `BoardingTimeEstimator.estimate`(**실시간→ML** — 여기만 ML 폴백을 허용한다, 확정 결정의 "의도한 비대칭" 참고), 둘 다 없음/not boardable → 후보 제외. anchor 갱신. "이후 대기 0"(R1) 폐기. 경로 간 overlay 병렬.
- [ ] 기존 계약 유지: enrich, 첫 대기 앞 도보 앵커, 상대 legIndex, **환승 늘면 버림**.
- [ ] baseline 도 같은 체인 overlay(출처 통일).
- [ ] **표준 `rank()` 신설**(목적지 ETA→환승수→도보→여유) + overlay 전 시간표 정적 변형. `RemainingPathCandidateProvider`:279 정렬 대체.
- [ ] Metrics 사유 `boarding-unresolved` 등재.
- 검증: 2환승 2번째 없음=제외 / 체인 anchor / baseline 동일 / 환승 늘면 버림 / rank 계약 / ④ 무회귀.

### PR-2 — 원점 창 + 정류장별 리스트
의존: **PR-A**, PR-B, PR-1.
- [ ] 신규 `UpcomingStopWindowResolver.resolve(...) → List<OriginStop{legIndex, stop식별, stopGtfsId, busEtaAt, plannedAlight}>`. 창 = 버스 ETA ≥ T_min(90s) ~ 계획 하차. cap K(6), 계획 하차 always. 버스 ETA = 실시간 위치 캐시([[project-bus-realtime-cache]]). **~~없으면 시간표 폴백~~ 취소(사용자 결정 2026-09-18): 실시간이 안 잡히는 정류장은 후보에서 제외한다.** 시간표로 "몇 시에 내릴 수 있다"를 제안하면 지연 중인 실제 차와 어긋난 안내가 되고, 사용자는 그 제안을 믿고 내린다 — 실시간이 뒷받침하는 하차점만 제안한다.
- [ ] **재탐색을 미래 `busEtaAt` 출발로**: `RemainingRouteSearchService` 가 지금 `clock.instant()` 고정(:139) → origin 별 도달시각으로 검색하도록 API/캐시 확장(별도 작업).
- [ ] provider 원점 루프: 각 origin fresh search→prepare→**정류장당 시간표 rank 상위 1개만 overlay**→후보. **계획 하차점 fresh search 안 함, 현재 계획 overlay 만**(기준선).
- [ ] 선별: 정류장 그룹, 절감 = 현재계획ETA − 그 정류장 최선ETA(overlay 후). **0/음수 숨김**. 정렬·동점 = **PR-1 rank() 재사용**. **miss-risk 가드**.
- [ ] 응답 DTO 정류장 축. 설정 `nextStop.leadTimeSec=90`·`maxOrigins=6`·`boardingBufferSec=30`·miss-risk 임계.
- 검증: resolver(창·cap·계획하차·폴백·미래시각검색). provider(원점 다중·정류장당 overlay 1·계획하차 fresh안함·0절감 숨김·miss-risk·rank 재사용). ④ 무회귀.

### PR-3 — 알림 계약
의존: PR-2, PR-B.
- [ ] 트리거: 환승 위험 + 개선폭 ≥ 300s & 최선 하차점 ≥ T_min → 발행(능동, pull 유지).
- [ ] 스냅샷 = `reroute_proposal` 재사용(PR-B origin, TTL 5분). push 한 줄 신호. pull = 항목별 `validate`(지나감 제거, 전멸 시 1회 재계산).
- [ ] **신규 엔드포인트**: reject(+opt-in 플래그)·dismiss/ignore. **멱등 규칙**. 응답 3갈래: 수락→`reroute_terminated=true`. 거절+미체크→`reroute_terminated=true`. 거절+체크→`reroute_alert_opt_in=true`. 무시→여정당 카운트.
- [ ] **무응답 durable 집계**: Redis TTL(:38) 로 안 됨 → 여정당 "무응답 2회 + 새/악화 에피소드" 상태를 durable(DB/proposal 이력)로. cooldown 5분.
- [ ] 마이그레이션 V__: `journey` 에 `reroute_terminated`·`reroute_alert_opt_in`(default false) [+ 무응답 카운트 컬럼 필요 시]. VPS 수동배치 + @Sql 3곳.
- [ ] 계약 수정 기록: "알림은 서버 먼저, 변경은 사용자만".
- 검증: 트리거·validate·응답 3갈래·멱등·무응답 상한/cooldown·마이그레이션 IT.

### PR-4 — apply: 진행중 RIDE 절단 + 새 꼬리
의존: PR-A, PR-C, PR-B, PR-2, PR-3, PR-0.
- [ ] `RerouteAcceptTxService` + PR-C 합성으로 (origin X, 경로 Y): 현재 RIDE board→X 절단 + **progress 현재까지 보존**(초기화 아님, `copyToRevision` 확장) + X 이후 Y 교체, 새 revision.
- [ ] 반영 직전 재확인(tx): X 리드 ≥ 30s / **X 가 현재 활성 trip 의 아직 안 지난 정차인지**(기존 :136 은 fromLegIndex 비교뿐) / Y 첫 탑승 boardable / 행잠금 + 조건부 update(revision 불변) / 실패=롤백.
- [ ] UX: 확인 화면(서버 요약). 탭~커밋 사이 X 지나감 = 거절 코드 + 목록 새로고침.
- 검증: 절단+이식(하차점 교체·X 이후 교체·과거 불변·progress 보존), 재확인(미지난 정차·리드·boardable) 실패=롤백, 동시성, stale/재생 progress id. RerouteAcceptIT 확장.

### PR-5 — ③ 제거
의존: PR-2~4.
- [ ] `RouteOptionCandidateProvider`+테스트 제거, 사다리 제외. `RouteOptionEtaCalculator` 이후대기0·`RerouteProposalKind.ROUTE_OPTION` 정리. PR4c(#194 파킹) rideSec 포트 재사용 안 함 확정.
- 검증: 컴파일 + 사다리(④만) + 넓은 회귀.

## 마이그레이션 (확정)
- PR-B: `reroute_proposal` origin 컬럼 + 여정당 unique (V번호 1, expand→cutover).
- PR-3: `journey` 컬럼(reroute_terminated·alert_opt_in[+무응답 카운트]) (V번호 1).
- 둘 다 V18+ untracked → VPS 수동배치 + @Sql 3곳.

## 리스크 (구현 중 확정)
- PR-A 를 OTP 전환 모델과 조율 못 하면 병렬 중복 — 구현 전 필수.
- PR-B 34파일 원자경로 동시 이동 — WAIT 소비처 무회귀 광범위.
- overlay A 제외율 폭주(다중환승·광역) — 지표 관측.
- 전환 전 시작 여정은 정류장별 데이터 없어 중간하차 불가 → 원점 leg-경계 폴백.
- 진행중 leg progress 보존이 절단 geometry 와 정합하는지(PR-0 확인).

## 관련
[[project-journey-navigation]] [[project-journey-reroute-remaining-search]] [[project-journey-reroute-accept]] [[project-national-otp-migration]] [[project-otp-shadow-deploy]] [[feedback-server-static-search-front-realtime]] [[project-bus-realtime-cache]]. 폐기=③(PR4c #194 파킹).
