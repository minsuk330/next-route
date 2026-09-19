# Phase 2 — 공통 라우팅·시간·ID·Journey 계약

먼저 [AGENT-GUIDE.md](AGENT-GUIDE.md)를 읽는다. 선행: P1 handoff의 실제 graph/fixture, P0 policy-inventory.md. 다음: [Phase 3](PHASE-3.md), [Phase 4](PHASE-4.md).
목적: OTP 결과를 최초 검색과 재탐색에서 동일한 규칙으로 처리한다.
수정 범위: src/main route/journey DTO·port·adapter·service·설정과 대응 테스트. P2-A~H 순서로 구현하며 반드시 8개 PR을 만들 필요는 없다. 기존 구현을 재사용하고 클래스명은 권장명으로 취급한다.

`api-contract.md`에 실제 endpoint, 요청/응답 JSON, 오류, 단위/null, 버전/capability, geometry 계약과 fixture 경로를 남긴다. B에서 초안을 만들고 H에서 확정한다.

## P2-A — provider-neutral 모델과 port

변경: application/route의 dto·port/out, `RouteSearchRequest`.
추가 권장: `RoutingPort`, `RoutingQuery`, `RoutingResult`, `RoutingLeg`, `RoutingFailure`.

- [ ] `RoutingPort.search(query, deadline)`로 통일한다. provider 선택은 상위 서비스가 하고 adapter는 선택된 provider만 호출한다.
- [ ] query에는 origin/destination, 확정된 departureAt, modes, 접근성/도보/환승 옵션, schemaVersion, 선택한 provider/graph 문맥을 넣는다.
- [ ] departureAt 생략 시 요청 경계에서 주입 Clock으로 한 번 확정한다. offset 없는 문자열은 입력 오류로 처리한다.
- [ ] 내부 시간은 Instant/초, serviceDate 해석은 feed timezone을 사용한다.
- [ ] mode는 WALK/BUS/SUBWAY/RAIL 및 미지원 수단을 명시한다. high-speed 여부 등 원천 세부 타입도 보존한다.
- [ ] 원천 ID는 문자열로 보존한다. 숫자 변환으로 앞자리 0이나 feed namespace를 잃지 않는다.

### 공통 데이터 계약

| 범위 | 필수 보존 |
|---|---|
| 결과 | provider, schemaVersion, networkDataVersion, graphVersion, 확정 departureAt, warnings |
| 경로 | 정적/보강 도착시각, 이동·대기·도보 초, navigationSupported/reasons, fare availability |
| leg | mode, feedId, agencyId, sourceRouteId, tripId, serviceDate, 정적 승하차시각, rideDurationSeconds |
| 정류장 occurrence | sourceStopId, trip/pattern, 원천 stop_sequence, 구간 내 occurrenceIndex, 좌표 |
| geometry | source, quality, reason, version, 표시/진행 좌표 참조 |
| 보강 | coverage, actualSource, observedAt, calculatedAt, freshness/status |

`realtimeAvailability`는 상위 계획의 coverage enum을 유지한다. 실제 이번 계산이 STATIC/REALTIME/MODEL 중 무엇인지 별도 필드로 구분한다. ML 지원 노선이라도 정적 fallback할 수 있다.

기존 `PathInfo.payment`는 primitive int다. 신규 계약에서 요금 unknown을 0으로 표현하지 않는다. 권장안은 v2 DTO의 nullable amount + availability이고, 구형 앱에는 안전한 legacy 응답 범위만 제공한다.

검증: 모드 미상·ID 충돌·nullable fare·JSON 직렬화, Clock 고정, offset/좌표 입력 검증.

## P2-B — ODsay/OTP adapter와 진입점 연결

변경: `RouteSearchService`, `RemainingRouteSearchService`, 기존 `OdSayApiPort` 호출 경계.
추가 권장: `OdsayRoutingAdapter`, `OtpRoutingAdapter`, provider 선택 서비스, legacy response mapper.

- [ ] 기존 공통 port가 있으면 재사용하고 없으면 ODsay/OTP를 같은 경계에 연결한다. 기존 서울 응답 회귀를 확인한다.
- [ ] 최초/남은 검색 OD와 반환 경로를 scope로 검증한다. OUT_OF_SCOPE를 무경로/timeout과 구분한다. 일반·고속철도 원천 타입은 보존하되 이번 검색 노출에서 제외한다.
- [ ] OTP 2.9.0 실행 이미지의 GraphQL schema와 `planConnection` fixture를 확인해 query 파일을 버전 관리한다. mode만 조회하는 현재 smoke를 운영 query로 복사하지 않는다.
- [ ] 정류장 sequence, trip, 시간, geometry 및 routing errors를 fixture로 고정한다. 실제 schema에 없는 필드는 임의로 추정하지 않는다.
- [ ] HTTP 오류뿐 아니라 HTTP 200 + GraphQL errors/부분 응답도 분류한다.
- [ ] 정상 무경로와 timeout/엔진 장애/입력 오류를 구분해 기존 ErrorResponse·도메인 예외에 연결한다.
- [ ] 도보는 OTP, 대중교통 형상은 P3 OSM relation 계약을 연결한다. ODsay geometry를 OTP의 정상 의존으로 남기지 않는다. P3 전에는 결측 품질 상태를 반환한다.
- [ ] ODsay -98 도보 fallback은 ODsay adapter/정책 내부에서 기존 동작을 유지한다.
- [ ] 남은 검색은 동일 port를 사용하되 검색 이력 기록을 호출하지 않는다. 예상 환승 지점 도착시각을 전달한다.
- [ ] ODsay가 지원하지 않는 미래 출발시각 검색은 capability 미지원으로 처리한다. 현재 결과에 미래 시각만 붙이지 않는다.

초기 응답 권장: legacy endpoint를 유지하고 v2/capability 경로를 명시한다. 인증 사용자의 provider 강제 지정은 운영 설정으로만 허용한다. 일반 요청 파라미터로 미승인 OTP 범위를 열 수 없게 한다.

검증: 정적 bus/subway/rail/walk, partial GraphQL error, no route, timeout, ODsay -98, 최초/남은 검색 port 호출.

## P2-C — 시간 의존 캐시와 graph pinning

변경: `RemainingRouteCachePort`, `RedisRemainingRouteCacheAdapter`, 검색 adapter 캐시, SingleFlight.
추가 권장: 공통 `RoutingCacheKey`, versioned cache envelope.

- [ ] 키 = provider + 실제 graphVersion + schema/options/capability + origin/destination + 초 단위 departureAt.
- [ ] sub-second 입력은 요청 시작 때 정규화한다. 탑승 가능 시간을 앞당기지 않도록 초 경계 올림을 권장하며 계산과 키 모두 동일 값을 사용한다.
- [ ] 경유점/접근성/교통수단 등 결과에 영향을 주는 옵션은 정렬된 직렬화로 hash한다.
- [ ] 캐시와 SingleFlight가 같은 키 생성기를 사용한다. 사용자 ID는 공통 정적 경로 키에 넣지 않는다.
- [ ] lookup 전에 대상 graph 인스턴스를 고정하고 그 인스턴스로 query한다. 실제 응답 버전이 다르면 다른 버전 키에 잘못 저장하지 않고 불일치로 재시도/실패 처리한다.
- [ ] ODsay는 graphVersion이 없음을 명시하는 provider별 sentinel을 사용하며 OTP 버전과 혼용하지 않는다.
- [ ] envelope에 유효 서비스 날짜/출발편/요청시각을 보존한다. TTL이 남아도 현재 userReadyAt으로 탈 수 없는 편은 반환하지 않는다.
- [ ] Redis 장애는 기존 fail-open을 유지하되 OTP 호출 동시성/timeout 한도는 유지한다.
- [ ] 기존 cache namespace와 분리해 구형 JSON을 새 DTO로 읽지 않는다.

현재처럼 departureAt을 생략하는 실시간 요청은 초마다 키가 달라 캐시 적중률이 낮을 수 있다. 1차에서는 정확성을 우선하며 시각 버킷 공유는 실측 후 별도 최적화한다.

검증: 출발시각·옵션·graph별 분리, 같은 키 동시 요청 공유, 자정/막차, Redis 장애, blue/green 교체 중 조회/저장 경합.

## P2-D — ID 매핑과 기존 실시간 coverage

변경: `TransferStopResolver` 연계, 서울 실시간/ML 호출 전 조건.
추가 권장: `TransitIdentityMappingPort`, 버전별 mapping adapter/build job.

- [ ] key는 feed/agency/source route + trip pattern + stop occurrence다. 같은 정류장 재방문은 별도 occurrence로 저장한다.
- [ ] mapping은 canonical route/stop/pattern, provider route/stop/direction/seq, 근거·검증상태·dataVersion을 보존한다.
- [ ] source stop_sequence, provider seq, 기존 sectionId를 서로 다른 필드로 둔다.
- [ ] 번호·이름만 같은 경우 자동 승인하지 않는다. 지역/기관/승하차/순서/방향 검증이 모두 필요하다.
- [ ] OTP 결과는 기존 서울 버스 번호 fallback 경로로 진입하지 않는다.
- [ ] 검증된 mapping에만 `busLocalBlID` 등 호환 필드를 넣는다.
- [ ] ML 지원은 매핑뿐 아니라 모델 지원 노선·feature 확보 여부까지 판정한다.
- [ ] P0 목록에서 기존 지하철 도착/열차 매칭의 ID·snapshot 계약도 확인해 보존한다. 신규 GTFS 매핑이 불가하면 영향과 사유를 명시하며 기존 지원을 조용히 삭제하지 않는다.
- [ ] 수도권 내 미매핑은 `STATIC_SCHEDULE` + 사유로 남긴다. 수도권 밖 데이터·요청은 `OUT_OF_SCOPE`로 거부하고, 수도권 내 미매핑 자체로 경로 검색을 실패시키지는 않는다.

저장 권장: 버전별 immutable mapping artifact를 기반으로 읽기 모델을 적재한다. DB 저장을 선택하면 (version, feed, agency, route, pattern, occurrence, provider) 복합 유일성 및 lookup index를 신규 migration에 둔다. 활성 snapshot이 참조하는 버전은 덮어쓰거나 삭제하지 않는다.

검증: 서울/타지역 동명 502, 회차/급행 pattern 차이, 같은 stop 재방문, 모호한 매핑, 모델 미지원, 이전 버전 조회.

## P2-E — 하나의 시간축으로 ETA 합성

변경: `TransferArrivalEnricher` 조회/예측 재사용 경계, 표시 총시간/정렬.
추가 권장: `RouteTimelineComposer`, `ConnectionFeasibilityEvaluator`.

1. 확정 departureAt부터 첫 정류장 userReadyAt을 계산한다.
2. 선택편의 출발이 userReadyAt + boardingBuffer 이상인지 확인한다.
3. 정적 WAIT와 RIDE를 분리한다. waitSeconds = boardAt - userReadyAt이며 buffer는 이 대기에 포함되어 중복 가산하지 않는다.
4. 검증된 기존 실시간 지원 leg만 실시간 도착/ML 결과로 대체한다. 원천 도착값을 출발시각으로 사용하는 근사 정책과 신뢰도를 명시한다.
5. 하차시각과 환승 도보를 누적해 다음 편을 재평가한다.
6. 다음 편을 놓치면 변경된 시각/위치로 OTP 남은 검색을 수행한다. 비용 목적 상한은 해제하고 전체 deadline·동시성·반복 방지 상한은 자원 보호 설정으로 분리한다. 동일 입력의 재귀 반복을 막고 선택한 값/근거를 기록한다.
7. 이후에도 불가능하면 해당 후보를 추천에서 제외하고 이유를 남긴다.

초기 buffer는 기존 프로젝트의 확정된 여유시간 설정을 우선 재사용한다. 값이 없다면 명시적 설정으로 추가하고 fixture에 고정한다. 운영 숫자는 PR에서 근거와 함께 확정한다.

- [ ] 정적 총시간에 실시간 대기를 다시 전부 더하지 않는다. 최종 도착시각 - 요청 출발시각으로 effective total을 계산하고 분 표시는 마지막에 반올림한다.
- [ ] OTP가 준 최초 itinerary start 이전의 초기 대기도 포함한다.
- [ ] 선택 버스의 승차시간/정차 패턴이 동일하다고 입증된 경우에만 정적 ride duration을 재사용한다.
- [ ] 현재 실시간 값의 적용 horizon/freshness를 설정으로 두고 미래 출발 요청에는 범위 밖 값을 적용하지 않는다.
- [ ] upstream 장애와 운행 취소/탈 수 있는 편 없음은 구분한다. 취소로 확인된 편을 정적 fallback으로 다시 추천하지 않는다.
- [ ] 기준 경로와 대안을 같은 calculatedAt 문맥으로 평가하고 보강 total로 비교/정렬한다.
- [ ] 보강 전 staticArrivalAt과 보강 후 realtime/effective 시각을 모두 보존한다.

필수 시험: 10:05→10:12 역 도착 변경으로 10:10 편 제외, TMAP 도보 증가, 버스→지하철→버스, 0초 대기, 초기 대기, 자정 25시, stale fallback, buffer 중복 없음, 재검색 상한. 테스트 Clock을 고정한다.

## P2-F — 동일 leg 대안과 남은 경로 검색

- [ ] OTP 후보/검증된 pattern에서 같은 승차·하차·방향이고 하차 stop에 실제 정차하는 대안만 routeOptions로 생성한다.
- [ ] 각 option에 자체 route/trip/occurrence/정적 승차시간/geometry/version을 보존한다.
- [ ] 각 대안의 출발편과 이후 연결은 P2-E로 재평가한다. 기존 laneIndex만으로 trip identity를 대신하지 않는다.
- [ ] 먼저 동일 leg의 대안을 평가하고 유효 제안이 없으면 공통 port로 남은 경로를 검색한다.
- [ ] policy-inventory의 비용 절감용 후보 축소·재탐색 억제·기능 비활성화를 해제/복원한다. 개선폭·알림 cooldown·수락 동시성은 목적을 확인해 자원/중복 방지/사용자 경험 정책으로 구분한다. 변경 값·코드 위치·시험 결과를 목록에 갱신한다.
- [ ] 후보 탐색 결과는 저장 직전에 ACTIVE/current revision을 다시 검증한다.

검증: 먼저 오는 느린 버스, 다른 승차시간, 하차역 통과, 이후 환승 실패, 대안 없음, 검색 중 여정 종료/재탐색.

## P2-G — Journey compile와 snapshot 영속화

변경: `JourneyCompiler`, 시작/수락 서비스·TxService, `JourneyRevisionComposer`, snapshot/geometry reader.

- [ ] mode switch를 명시하고 지원되지 않는 rail/unknown을 bus로 변환하지 않는다.
- [ ] navigationSupported는 mode + ID mapping + geometry OK + 진행 엔진 계약 충족을 모두 평가한다.
- [ ] 서버가 저장된 경로를 기준으로 시작/수락 가능 여부를 판정한다. 클라이언트의 true 값을 신뢰하지 않는다.
- [ ] OTP 정적 WAIT/RIDE 시간을 분리해 저장하고 ETA composer가 다시 대기를 더하지 않게 한다.
- [ ] provider/schema/network/graph/version, 선택편/stop occurrence, routeOptions, 표시·진행 geometry를 revision에 결합한다.
- [ ] 기존 snapshot은 legacy reader, 신규 snapshot은 schemaVersion reader로 읽는다. ODsay metadata를 임의의 OTP 값으로 채우지 않는다.
- [ ] 필요한 column/JSON payload/제약은 신규 migration으로 추가한다. 기존 V35 및 배포 완료 migration은 수정하지 않는다.

트랜잭션 경계: 외부 routing/geometry 준비는 DB 잠금 밖에서 수행 → 시작/수락 Tx에서 세션/revision/제안 유효성과 capability 재확인 → revision·legs·connections·geometry 참조·알림 변경을 원자 반영한다. 외부 artifact를 쓰면 사전에 immutable 저장하고 Tx는 검증된 참조를 저장한다.

새 graph를 올려도 활성 여정은 원래 snapshot을 사용한다. 재탐색 결과의 새 버전은 수락 시에만 새 revision으로 들어간다. 진행 순번은 revision 간 복사하지 않는다. 기존 binding/알림 수명주기 정리도 같은 수락 흐름을 따른다.

검증: KTX 시작/수락 차단, forged capability, 중복 수락/동시 progress, 구형 snapshot, graph 교체·ODsay rollback 후 활성 OTP 여정 조회/진행/종료.

## P2-H — flag·오류·관측성 계약 마무리

- [ ] 초기 기본값 ODSAY, OTP 선택은 내부 시험/승인 범위만 허용한다.
- [ ] 검색 provider와 navigationSupported 정책을 분리한다.
- [ ] route stage/provider/outcome/source/mapping failure/time infeasible/재검색 횟수를 계측한다.
- [ ] graphVersion/routeId/tripId 같은 고카디널리티 값은 일반 metric label에 넣지 않고 구조화 로그/trace로 연결한다.
- [ ] snapshot schema, API capability, cache namespace 배포 순서를 적는다: reader/guard 선배포 → writer/새 응답 → 앱 → flag.
- [ ] 전체 요청 예산 안에 OTP·보강·재검색이 포함되는지 확인한다. 각 단계에 독립 3초를 허용하지 않는다.

완료: A~G 대상 회귀, 전환 전 기존 경로 호환, OTP 내부 검색/재탐색, 미지원 기능/범위 응답 확인. P3 미완료 형상은 네비 승인에서 제외한다.

`handoffs/PHASE-2.md`에 A~H 상태, api-contract.md, JSON fixture, migration/설정/배포 순서, 대상 테스트 결과와 정책 변경을 기록한다. 실제 배포는 P6에서 다룬다.
