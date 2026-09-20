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
- [x] **5 컷오버 1/2**(V51): 충돌 ACTIVE 정리→**여정당 unique** 신규 생성 + 키 이동(supersede=여정 단위, cooldown 키에서 transferIndex 제거, stillCurrent 는 원점의 legIndex 기준·못 푼 여정만 옛 주소 폴백). 옛 인덱스·컬럼은 **그대로 둔다** — 새 인덱스가 더 좁아 공존이 안전하고 롤백하면 옛 코드가 그대로 돈다. @Sql 3곳 갱신.
- [x] **5 컷오버 2/2**(V52): 옛 인덱스 `uq_reroute_proposal_active` + `transfer_index` 컬럼 제거, 엔티티 필드·`RerouteProposalView.transferIndex`·`stillCurrent` 폴백 제거. **저장 시 origin 필수**(못 풀면 `origin-unresolved` 로 거절 — 주소 없는 행은 어디에 대한 제안인지 말할 수 없다). 응답 DTO 는 `transferIndex` → `origin`(**모바일 계약 변경**).
  - **남긴 것 = `RerouteContext.transferIndex` 와 요청 DTO**: 그 값은 저장 키가 아니라 "어느 환승에서 찾아 달라"는 **입력**이고, 지금 ③·④ 공급자와 원점 해석이 전부 그것으로 환승 행을 찾는다. 서버가 스스로 자리를 고르는 것은 PR-2(원점 창)의 일이라, 여기서 걷으면 그 자리를 무엇으로 대신할지 없이 부수게 된다.
  - **③에서 옮겨온 것 — 키 이동 전부**(`stillCurrent`·`supersedeActive`·조회·cooldown 키·트리거·수락). 이유 = **부분 unique 인덱스(자리의 정의)와 supersede(자리 비우기)는 짝**이고 지금 둘 다 `(journey_id, transfer_index)` 로 맞아 있다. 여기만 먼저 바꾸면 ⓐ여정 기준 → 아직 살아야 할 다른 환승 제안까지 죽거나(현 IT `differentTransfersCoexist` 가 고정한 동작), ⓑorigin 기준 → origin 이 null 인 행을 못 밀어내는데 인덱스는 여전히 transfer_index 라 **새 insert 가 unique 위반으로 튕긴다**. ⑤는 인덱스 교체와 코드 교체가 같은 배포라 어긋나는 창이 없다.
  - 🔴 이 오류는 **테스트로 안 잡힌다**: 재탐색은 flag off 라 프로덕션에 티가 안 나고, 이 경로는 Postgres IT 로만 도는데 이 저장소엔 CI 가 없다.
- 검증: origin 왕복(TRANSFER_WAIT 무회귀), 이중읽기 폴백, 공개 API 컨트롤러 테스트, 마이그레이션 IT, 여정당 unique.

### PR-C (기반) — composer 중간 절단 합성
의존: PR-A.
- [x] `JourneyRevisionComposer`: 중간 하차 합성 = `[prefix] + [truncated board→X] + [candidate]`. 가드(:80-86) "다른 노선 오염" 방지 유지(명시 origin 일 때만 절단 허용). 기존 미래-WAIT 경로 불변.
- [x] **절단 입력은 부르는 쪽이 준다**(`RideTruncation{alightStationIndex, rideSeconds}`, 2026-09-19 결정): ①자를 자리 — 스냅샷 정류장 id 는 검색 id(OTP gtfsId)고 origin.stopId 는 서울 id 라, composer 안에서 맞히면 "번역이 반만 되는" 자리를 하나 더 만든다. ②소요 — 진행 중인 구간의 board→X 는 **실시간** 근거인데 composer 재료엔 없다(PR-A `TruncatedRide` 는 *검색 결과* `RoutingLeg` 용이라 진행 중 leg 엔 안 닿는다). 둘 다 PR-2/PR-4 가 푼다.
- [x] 버스만 절단(`trafficType==2`) — 하차 정류장 id 가 버스 칸(`endLocalStationID`)에 들어가므로 지하철에 쓰면 주소 체계가 섞인다.
- 검증: 중간하차 revision·앞 절반 보존·오염 거부 유지. ⑤ 무회귀(RerouteAcceptIT).

### PR-1 — overlay A안 체인 + rank()
의존: PR-A(정류장별 시각).
- [x] 신규 `BoardingChainEtaCalculator`: 모든 WAIT 순차 체인(running clock → `BoardingTimeEstimator.estimate`, **실시간→ML** 은 예측기 안에 이미 있음), 못 풀면 후보 제외. anchor 갱신. "이후 대기 0"(R1) 폐기. **체인을 composer 안에 두지 않고 별도 컴포넌트로 뺀 이유 = baseline(저장 엔티티)과 후보(검색 초안)가 같은 계산을 타야 하기 때문**(중립 `Step` 만 받는다).
- [x] `RemainingPathEtaComposer.compose` 가 체인 사용. 기존 계약 유지: enrich, 첫 대기 앞 도보 앵커(체인의 첫 구간이라 자동), 상대 legIndex, **환승 늘면 버림**.
- [x] baseline 도 같은 체인 overlay(출처 통일) — 대기 leg 마다 선택 노선을 읽어 질의를 만든다.
- [x] 경로 간 overlay 병렬: 신규 `rerouteOverlayExecutor`(bulkhead, size 4/queue 8). 풀 포화 시 호출 스레드에서 실행(후보를 버리지 않는다). 예산 초과는 `budget-exhausted` 로 **그 후보만** 접고 이미 끝난 것은 쓴다.
- [x] **표준 `RerouteRanking.rank()` 신설**(목적지 ETA→환승수→도보→여유) + overlay 전 시간표 정적 변형(`Scheduled`). `RemainingPathCandidateProvider` 의 gain 내림차순 정렬 대체(기준선 고정이면 결과 동일, 동점만 달라짐).
- [x] Metrics 사유 `boarding-unresolved` 등재(첫 대기 실패 `no-prediction` 과 구분).
- [x] **신뢰도 = 체인에서 가장 나쁜 고리**(`hasFurtherTransfer` 기반 낮춤 폐기 — 이제 모든 대기를 예측하므로 "뒤에 대기가 남아 있다"가 근거가 아니다).
- 검증: 2환승 2번째 없음=제외 / 체인 anchor / baseline 동일 / 환승 늘면 버림 / rank 계약 / ④ 무회귀.

### PR-2 — 원점 창 + 정류장별 리스트
의존: **PR-A**, PR-B, PR-1.
- [x] 신규 `UpcomingStopWindowResolver.resolveRideInterior(legs, waitLegIndex, now) → List<RerouteOriginStop>`. 창 = 버스 ETA ≥ T_min(90s) ~ 계획 하차 **직전**. cap K(6) + 조회 상한(10). 버스 ETA = 도착 API/캐시(`SearchTimeBusQueryPort`) 의 **내 차** 도착. **~~없으면 시간표 폴백~~ 취소(사용자 결정 2026-09-18): 실시간이 안 잡히는 정류장은 후보에서 제외한다.**
  - **정정(구현, 2026-09-19)**: "계획 하차 always" 는 resolver 가 아니라 **공급자**가 든다 — 그 자리는 이미 환승 대기 원점으로 있어서 두 곳에서 만들면 같은 정류장이 두 번 나온다. resolver 는 **중간 정류장만** 돌려준다.
  - **정정(구현)**: 내 위치 = 앱이 보낸 `remainingStops`(관측 180초 이내), 내 차 = 도착 정보의 `sectionOrder ∈ {currentSeq, currentSeq+1}`. **내 뒤의 차는 안 받는다**(늦은 도착 → "아직 여유 있다" → 하차 지나침). 위치를 모르면 창을 안 연다.
- [x] **재탐색을 미래 `busEtaAt` 출발로**: `RemainingRouteSearchService.search(..., departAt)` 추가(지난 시각은 현재로 되돌림). **ODSAY 경로는 출발 시각 칸이 없어 옛 동작**(OTP 만 실효).
- [x] provider 원점 루프: 각 origin fresh search→prepare→**정류장당 시간표 rank 상위 1개만 overlay**→후보. 원점끼리는 **병렬**(`rerouteOverlayExecutor` 재사용).
  - **정정(구현)**: "계획 하차점 fresh search 안 함"은 **기준선**을 다시 검색하지 않는다는 뜻으로 읽었다(기준선 = 저장된 계획 overlay). 계획 하차 정류장 **자체는 원점으로 남긴다** — 빼면 ④(환승 정류장 발 재검색)와 ③(같은 정류장 다른 노선)이 둘 다 사라지는데, ③은 이 작업이 흡수하기로 한 기능이다.
- [x] 선별: 절감 = 현재계획ETA − 그 정류장 최선ETA(overlay 후). **0/음수 숨김**(임계 300s). 정렬·동점 = **PR-1 rank() 재사용**. **miss-risk 가드**(첫 승차 여유 < 30s 제외).
- [x] 응답 DTO 정류장 축 = 후보마다 `display.alight{stopId,name,alightAt,planned}`(정류장당 후보가 하나라 **중첩 안 함**). 설정 `journey.reroute.next-stop.*`(enabled·lead-time-sec=90·max-origins=6·max-stop-lookups=10·arrival-budget-ms=1500·boarding-buffer-sec=30·miss-risk-min-slack-sec=30·progress-max-age-sec=180).
- 검증: resolver(창·cap·계획하차·폴백·미래시각검색). provider(원점 다중·정류장당 overlay 1·계획하차 fresh안함·0절감 숨김·miss-risk·rank 재사용). ④ 무회귀.

### PR-3 — 알림 계약
의존: PR-2, PR-B.
- [x] 트리거: 환승 위험 → 신규 `RerouteAlertPublisher` 가 받아 **개선폭 ≥ 300s(`alert.minimum-gain-seconds`) & 최선 하차점 ≥ T_min** 일 때만 발행(능동, pull 유지). 스위치 `journey.reroute.alert.enabled`(기본 꺼짐) — 꺼져 있으면 방아쇠는 예전처럼 세기만 한다(shadow).
  - **순서가 계약**: 스위치 → 여정 상태(종료·무응답 상한) → cooldown(5분, `Scope.ALERT`) → **후보 생성** → 문턱 → 리드타임 → 채널. cooldown 을 후보 생성 **앞**에 둔 이유 = 어차피 안 보낼 알림에 OTP·실시간·ML 을 쓰지 않는다. 채널이 0이면 **저장도 안 한다**(아무도 못 볼 제안이 여정당 하나뿐인 자리를 5분 막는다).
- [x] 스냅샷 = `reroute_proposal` 재사용(origin, TTL **5분** = `alert.snapshot-ttl`, 사용자 요청 제안의 2분과 별도). push 한 줄 신호 = 신규 체크포인트 `REROUTE_SUGGESTED`(scope=JOURNEY, scopeId=**proposalId**). pull = 조회 시 `validate`(하차점이 리드타임 미만이면 후보 제거, 전멸이면 제안을 `all-passed` 로 접음).
  - **정정**: "전멸 시 1회 재계산"은 **안 넣었다**. 조회 경로에 재검색을 넣으면 화면 열기가 OTP 호출이 되고, 그 자리는 transferIndex 를 되찾아야 해서 주소를 역추적하는 코드가 하나 더 생긴다. 다시 찾는 것은 다음 방아쇠나 사용자의 "다시 찾아줘"가 한다(미결로 기록).
- [x] **신규 엔드포인트**: `POST .../{proposalId}/reject`(본문 `keepAlerts`, 없으면 false)·`POST .../{proposalId}/dismiss`. **멱등**(ACTIVE 일 때만 전이하는 한 문장, 재전송은 200+`closed:false`). 응답 3갈래: 수락→`reroute_terminated=true`(`RerouteAcceptTxService`). 거절+미체크→`reroute_terminated=true`. 거절+체크→`reroute_alert_opt_in=true`. 무시→`reroute_ignored_count++`.
- [x] **무응답 durable 집계**: `journey_session.reroute_ignored_count`(상한 `alert.max-ignored=2`). 세는 자리 셋 = ①명시적 무시 ②lazy 만료(`resolveStale` 앞에서 `countUnansweredDue`) ③**배치 sweep**(본류 — 앱을 안 열면 lazy 가 한 번도 안 온다). sweep 은 `lockDueBatch`(FOR UPDATE SKIP LOCKED)→`expireByIds` 두 문장으로 갈라 여정별 개수를 정확히 센다. **사용자 요청 제안은 세지 않는다**(`reroute_proposal.alerted` 가 가른다).
- [x] 마이그레이션 **V53**(`journey_session` 3칸) + **V54**(`reroute_proposal.alerted` + status CHECK 에 REJECTED·DISMISSED). **표마다 파일을 갈랐다** — 한 파일로 두면 journey 표만 만드는 @Sql 체인이 통째로 막힌다. VPS 수동배치 + @Sql **12곳**(journey 체인 9 + reroute 체인 3).
- [x] 계약 수정 기록: "알림은 서버 먼저, 변경은 사용자만"(`RerouteTriggerEvaluator` javadoc + 노트).
- 검증: 트리거·validate·응답 3갈래·멱등·무응답 상한/cooldown·마이그레이션 IT.

### PR-4 — apply: 진행중 RIDE 절단 + 새 꼬리
의존: PR-A, PR-C, PR-B, PR-2, PR-3, PR-0.
- [x] `RerouteAcceptTxService` + PR-C 합성으로 (origin X, 경로 Y): 현재 RIDE board→X 절단 + **progress 현재까지 보존**(초기화 아님, `JourneyLeg.copyToRevisionTruncated` = `copyToRevision` 확장) + X 이후 Y 교체, 새 revision.
  - **정정(구현)**: 자를 자리는 **서울 노선 순번의 차이**(`origin.seq - boardSeq`)로 찾고, 스냅샷 정류장 목록과 어긋났는지는 **이름 대조**(`RideTruncation.expectedStopName`)가 잡는다 — id 는 두 세계가 다른 체계라 맞댈 수 없다.
  - **정정(구현)**: `plannedDurationSec` 은 재확인이 잰 **초 단위 실측**이다(스냅샷은 분 단위). `remainingDistanceMeters` 는 비운다(알 수 없는 값을 지어내지 않는다).
- [x] 반영 직전 재확인(tx): X 리드 ≥ `next-stop.accept-min-lead-sec`(30, 제안용 90 과 별도 키) / **X 가 아직 안 지난 정차인지**(앱 `remainingStops` + 노선 순번, 실시간 하차 시각) / Y 첫 탑승 boardable / 세션 행잠금(기존) + 실패=롤백.
  - **정정(구현)**: 하차는 **fail closed**(실시간 없으면 거절), 승차는 **fail open**(추정 못 해도 통과). PLAN 의 비대칭(2026-09-18)을 수락 경로에도 그대로 적용.
- [x] 탭~커밋 사이 X 지나감 = 전용 거절 코드 `REROUTE_ALIGHT_UNAVAILABLE`(409) + 목록 새로고침. **확인 화면은 클라 UX** 라 서버 산출물이 아니다(모바일 HANDOFF).
- 검증: 절단+이식(하차점 교체·X 이후 교체·과거 불변·progress 보존), 재확인(미지난 정차·리드·boardable) 실패=롤백, 동시성, stale/재생 progress id. RerouteAcceptIT 확장.

### PR-5 — ③ 제거
의존: PR-2~4.
- [x] `RouteOptionCandidateProvider`+`RouteOptionEtaCalculator`+출처 4종(Stored/Union/OtpPattern/포트)+테스트 6개 제거, 설정 `route-option.*`(클래스·yaml·validate) 제거, `RouteOptionApply` 하위타입과 수락·합성의 ROUTE_OPTION 분기(`keepsStructure`·`copyAll`·`chooseRouteOption`) 제거.
  - **정정(구현)**: `RerouteProposalKind.ROUTE_OPTION` 은 **남긴다**. 이 enum 은 DB 컬럼을 *읽는* 어휘이고 V42 check 는 아직 그 값을 받는다 — 지우면 그 값으로 저장된 옛 행을 읽다 터진다. 만드는 쪽을 없애면 어휘는 자연히 죽는다(옛 행의 후보는 "모르는 봉투"로 거절).
  - **정정(구현)**: **사다리 자체는 남긴다**(칸이 하나여도 `priority` 는 요금 순서라 값싼 칸이 생기면 앞자리가 필요하다). "사다리 제외"는 ③을 사다리에서 뺀다는 뜻으로 읽었다.
  - **정정(구현)**: PR4c rideSec 포트(`RouteRideDurationPort`)와 `StopPatternPort` 는 PR-5 에서 일단 남겼다가 **후속에서 둘 다 삭제**(-1,082줄). "남기는" 근거였던 national-otp 후속(baseline OTP id 되찾기)이 실은 **③ shadow OFF 용**이라 ③과 함께 죽었다 — `stopPatterns` 는 지도 마이그레이션 플랜에 한 번도 안 나온다(실측).
- [x] 검증: 컴파일 + 사다리(④만, 17/17) + 넓은 회귀(1305/1 기존무관, 478/0). **덤으로 main 에서 이미 깨져 있던 `WithdrawalDeletionBoundaryIT` 7건 수리**(PR-3 의 엔티티 신규 칸 ↔ ddl-auto 가 기본값 없는 not null 을 내는 문제).

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
