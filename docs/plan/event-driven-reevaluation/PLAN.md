# 이벤트 기반 재평가 (#415-3) — PLAN (L 티어)

> 에이전트 실행용. 컨텍스트 없이 이 파일만 읽고 구현할 수 있게 쓴다. 사람 검토용 요약 = `SUMMARY.md`.
> 상위 이슈: #415 (환승 위험 평가 파이프라인: 목표 부하 기반 병목 지도와 이벤트 기반 재평가). 선행: #416(단계 계측)·#417(외부 호출 지표)·#410(급함 우선 선점, V66).

## 0. 목적과 원칙

- **문제**: 평가기는 여정마다 정해 둔 간격(10~180초)으로만 다시 계산한다. 감지 지연의 95% 이상이 "다음 평가까지 대기"다(#409 `JourneyDetectionLatencyIT`: 간격 약 34초 → p95 30.9초, 약 124초 → p95 115초, 최악 약 3분).
- **SLO**(서버 구간 p95, **우리가 데이터를 처음 본 시각 → 판정 저장·알림 적재**): 긴급(환승 준비 5분 이내) 10초 / 주의(5~15분) 30초 / 여유(15분+) 2분. 공급자가 데이터를 만든 시각과 우리가 본 시각의 차이(관측 지연)는 따로 잰다.
- **목표 부하**: DAU 1만 → 피크 동시 여정 약 2,000건. 사다리 2k/6k/20k.
- **다중 인스턴스 전제**(사용자 확장 계획): worker·api 여러 대. 나눠 할 일과 한 대만 할 일을 가른다(§2.7).
- **포폴 원칙**: 흔한 기술이라도 문제·제약에 맞으면 쓴다. 서술 = 문제 → 제약 → 선택 → 측정.

## 1. 조사로 확정된 사실 (코드 좌표는 2026-10-14 main cd478f9b 기준)

| 사실 | 좌표 |
|---|---|
| 선점: CTE `FOR UPDATE SKIP LOCKED`, `next_evaluation_at = :leaseUntil`, 선점 전 시각 `due_at` 반환, 앱 시계 | `domain/journey/repository/JourneySessionRepository.java:209-226` (`claimDueBatch`) |
| 완료: `WHERE next_evaluation_at = :claimedLeaseUntil` CAS, 실패 시 LOST_CLAIM | 같은 파일 `:252-266` (`finishEvaluation`), `application/journey/service/JourneyEvaluationScheduleWriter.java:60-81` |
| 알림 적재는 CAS 성공 뒤에만, 트랜잭션 안 이벤트로 relay 깨움 | `JourneyEvaluationScheduleWriter.java:81` |
| 앞당기기 선례(재탐색 보드): `LEAST(existing, now+minGap)`, 선점 중이면 dirty 표시, 별도 claimed_until 칼럼 | `JourneySessionRepository.java:280-366` (`finishRerouteRefresh` :325, `requestRerouteRefresh` :361), `application/journey/service/RerouteRefreshSignal` |
| 단계 전이·시작·재탐색 수락이 `scheduleEvaluationAt(now)` 로 선점 CAS 를 깸(의도된 무효화) | `domain/journey/entity/JourneySession.java:197`, `JourneyCommandService.applyTransition:143`, `JourneyStartTxService:108`, `RerouteAcceptTxService:338` |
| 간격 정책: 위험·5분 이내 10초 / 15분 이내 30초 / 그 외 120초, 무변화 ×2 백오프, [10,180] 클램프, 지터 id mod 7 | `application/journey/service/JourneyEvaluationIntervalPolicy.java`, `JourneyEvaluatorProperties` (yaml `journey.navigation.evaluator.*` :463-493) |
| 변화 판정 `hasChanged` = status·currentLegArrivalAt·userReadyAt·nextBusArrivalAt·transferSlackSeconds·targetSource | `JourneyEvaluationService.java:259-266`, `TransferRiskAssessment.sameAs:51-61` |
| 버스 평가 경로: `SingleTransferPredictor` → `SearchTimeBusQueryPort`(`@Primary CachedSearchTimeBusAdapter`, Redis `bus:search:arr:{stopId}:{routeId}:{seq}` TTL 15초) → 미스 시 정류장·노선별 API. 실시간은 준비 시각 20분 이내만, 그 밖은 ML(위치 스냅숏 함수) | `application/route/service/SingleTransferPredictor.java:54,102,173,202`, `infrastructure/adapter/out/api/bus/CachedSearchTimeBusAdapter.java:70-115`, `infrastructure/adapter/out/cache/bus/RedisBusRealtimeCacheAdapter.java:37-38,87-124` |
| 노선 단위 조회 + 정류장별 캐시 채우기 이미 있음(플래그 기본 off) | `application/bus/service/BusArrivalService.java:~100-140` (`getArrInfoByRouteAll`, `primeRealtimeCache`) |
| 버스 한도: 검색 도착 5,000/일, 수집 도착 60,000/일, 위치 50,000/일 | yaml `bus.quota.search.*`(:143-147), `collector.bus-arrival.daily-budget` |
| 지하철 수집기: worker, 15초, 전 노선 스냅숏 1회 호출 → `realtime:subway:snapshot` | `application/subway/service/RealtimeSubwayCollector.java:66,105` |
| 평가기는 스냅숏 대신 역별 API(`subway:arr:station:{name}`, 10초) 사용 | `SubwayRealtimeArrivalReader.arrivalsByStation:104`, `infrastructure/adapter/out/api/subway/CachedSubwayApiAdapter.java:83-97`, `infrastructure/adapter/out/cache/subway/RedisSubwayStationArrivalCacheAdapter.java:27` |
| 관심 키: 버스 = (WAIT_BUS 구간 `journey_leg.start_stop_id`, `journey_leg_route_option.route_id`, `.boarding_seq` via `selected_route_option_id`), 지하철 = (`journey_leg.route_id`, `start_stop_id`/`start_name`, `route_option.way_code`→방향 `SubwayDirectionNormalizer`). 대상 = `transfer_connection.to_leg_index` 구간 + 현재 WAIT_BUS | V35~V37 마이그레이션, `BusTransferTargetArrivalPredictor:70-76` |
| 정류장·노선 ID 인덱스 없음, join 은 `journey_id` 경유(`uq_journey_leg_index`) | V35 |
| pub/sub·분산 잠금 유틸 없음. in-JVM 이벤트만(relay waker) | `JourneyRelayWaker`, `JourneyRelayWakeListener` |

## 2. 설계

### 2.1 앞당기기 의미 — 처리 중이면 표시만, 아니면 앞당김 (Opus 검토 확정 SQL)
- 단계 전이처럼 `next_evaluation_at = now` 로 덮으면 진행 중 선점이 깨진다. 바쁜 키에서 이벤트가 평가 시간보다 자주 오면 매번 결과가 버려지는 **livelock** → 급한 여정의 "나빠짐" 알림이 굶는다. 그래서 재탐색 보드와 같은 "처리 중이면 표시만" 모양을 쓴다.
- 🔴 **새 칼럼 3개는 엔티티에 매핑하지 않는다(SQL 전용)** + `JourneySession` 에 `@DynamicUpdate`. 이유: 엔티티가 저장 시 모든 칼럼을 다시 써서(`@DynamicUpdate`·`@Version` 없음, V61 기록된 함정) `RerouteProposalTxService.close/closeBoardPush`(:111, :152, 잠금 없는 findById)가 옛 dirty·claimed·next 를 덮어 표시 유실·이중 선점이 난다. 재탐색 보드 칼럼도 SQL 전용이 선례.
- 선점(#410 정렬 유지) — `evaluation_claimed_until = lease`, **`evaluation_dirty = false`**(워커가 죽은 뒤 재선점이 낡은 표시를 물려받지 않게):
  ```sql
  WITH picked AS (SELECT s.id, s.next_evaluation_at AS due_at FROM journey_session s
    WHERE s.status='ACTIVE' AND s.deleted_at IS NULL
      AND s.next_evaluation_at IS NOT NULL AND s.next_evaluation_at <= :now
    ORDER BY <#410 정렬>, s.next_evaluation_at
    LIMIT :batchSize FOR UPDATE OF s SKIP LOCKED)
  UPDATE journey_session j SET next_evaluation_at = :leaseUntil,
         evaluation_claimed_until = :leaseUntil, evaluation_dirty = false
  FROM picked WHERE j.id = picked.id RETURNING j.id, picked.due_at;
  ```
- 완료(CAS) — dirty 면 **`LEAST(:nextAt, :now + dirtyGap)`**(정책이 더 이른 시각을 정했으면 그걸 존중), dirtyGap 은 이번 평가가 정한 등급에서:
  ```sql
  UPDATE journey_session SET
    next_evaluation_at = CASE WHEN evaluation_dirty
       THEN LEAST(CAST(:nextAt AS timestamptz), CAST(:now AS timestamptz) + make_interval(secs => :dirtyGapSeconds))
       ELSE CAST(:nextAt AS timestamptz) END,
    evaluation_dirty = false, evaluation_claimed_until = NULL, last_evaluated_at = :now,
    evaluation_urgent = :urgent,
    evaluation_version = evaluation_version + CASE WHEN :bump THEN 1 ELSE 0 END
  WHERE id=:id AND status='ACTIVE' AND deleted_at IS NULL AND next_evaluation_at = :claimedLeaseUntil;
  ```
- 트리거 — NULL 안전(`COALESCE`), **id 순서 잠금 CTE 로 교착 방지**, 등급별 1회 호출:
  ```sql
  WITH t AS (SELECT id FROM journey_session WHERE id = ANY(:ids) AND status='ACTIVE'
               AND deleted_at IS NULL AND kind='NAVIGATION' ORDER BY id FOR UPDATE)
  UPDATE journey_session j SET
    evaluation_dirty = j.evaluation_dirty OR COALESCE(j.evaluation_claimed_until > :now, false),
    next_evaluation_at = CASE WHEN COALESCE(j.evaluation_claimed_until > :now, false)
        THEN j.next_evaluation_at
        ELSE LEAST(j.next_evaluation_at, GREATEST(:now, j.last_evaluated_at + make_interval(secs => :minGap))) END
  FROM t WHERE j.id = t.id;
  ```
  - 경합 확인: 선점이 FOR UPDATE 중이면 트리거가 기다렸다 새 행을 다시 읽고 "처리 중"→dirty 만. lease 만료 뒤엔 `LEAST(지난 lease, …) = 지난 lease` 라 CAS 보존. `claimExpiredBatch` 는 EXPIRED 로 바꿔 ACTIVE 조건에 걸러짐.
- 단계 전이·시작·재탐색 수락은 지금처럼 CAS 를 깬다(경로가 바뀌어 옛 결과 무효). claimed·dirty 는 다음 선점이 덮으므로 따로 안 비움.
- minGap(등급): 긴급 **5초**(평가기 최소 간격 10초보다 짧되 3초는 포화 때 수요 3배라 기각) / 주의 10초 / 여유 30초.
- **포화 시 부하 차단**: 선점 밀림(N초 넘게 지난 만기 행 수)이 기준 이상이면 주의·여유 트리거는 건너뜀(`reeval.shed`). #410 실측 처리 상한 약 4여정/초.
- **트리거는 `evaluation_urgent` 를 건드리지 않는다**: #410 의 단일 근거(`Decision.urgent()`)를 유지, 완료가 어차피 덮어씀. 등급은 minGap 에만.
- 한 주기 트리거 여정 상한 500(등급순), 넘친 키는 지문을 갱신하지 않아 다음 주기에 다시 나옴(§2.4).

### 2.2 시계 — 앱 시계 하나를 넘긴다 (Opus 판정, DB now() 기각)
- 선점·완료·트리거 SQL 모두 앱 `:now` 하나를 받는다(재탐색 보드 `requestRerouteRefresh` 선례). DB `now()` 로 바꾸면 `Clock.fixed` 로 시간을 조종하는 IT(`AbstractJourneyNotificationIT:94`·`RerouteProposal*IT`·`JourneyNotification*IT`·`JourneyTelemetryIT`)가 깨지거나 가짜 포트가 곳곳에 필요하다.
- 인스턴스 간 시계 차이는 60초 lease 의 마지막 몇 초에만 영향이고 결과도 무해(트리거의 LEAST 가 만료 lease 를 보존). 대신 **`clock.skew` gauge**(앱 시각 − `SELECT now()`, 분 1회), 2초 넘으면 경보 — 다중 인스턴스 NTP 전제를 감시로 바꾼다.

### 2.3 관심 키 색인 `InterestIndex`
- 15~30초마다 SQL 1회로 재구성(활성 NAVIGATION 세션에서 출발 → `journey_leg`(현재 revision, 남은 구간) → `journey_leg_route_option`(선택) → `transfer_connection`(준비 시각)). 메모리 구조: 키 → {journeyId}, journeyId → 가장 가까운 `user_ready_at`. 등급은 매 주기 메모리에서 계산.
- 여정 시작은 api 인스턴스에서 일어나 in-JVM 이벤트가 리더에 안 닿음 → 주기 재구성에만 의존(시작 시 이미 `next_evaluation_at = now`).
- V67 부분 인덱스 `journey_session(id) WHERE status='ACTIVE' AND deleted_at IS NULL AND kind='NAVIGATION'`. 20k 세션 EXPLAIN·시간 측정.

### 2.4 변화 감지 `ChangeDetector`
- 판정이 쓰는 값만 지문. **지하철**: (노선, 역, 방향)별 앞 두 열차의 `(trainNo, arvlCd, stationsAway)` 순서쌍, 시각 단위 없음(`barvlDt` 는 0=미산출이 잦아 #155 시각 단위 비교는 잡음). **버스(PR-B)**: 첫·둘째 차량 ID, 상태 메시지, 도착 예정 시각(30/60초 단위) + 히스테리시스(두 번 연속 또는 2칸 이상).
- 저장소 = **리더 메모리**(Opus 판정): Redis 에 두면 리더가 둘로 갈린 순간 두 스냅숏이 번갈아 써서 "변화"가 깜빡인다. 리더 교체 비용은 한 주기(15초) 기준선 재구성뿐이고 안전망이 메움. 다중 인스턴스에서도 지문을 쓰는 건 리더 하나라 메모리로 충분.
- 스냅숏의 **모든 키**를 지문(관심 키만이 아님) — 새로 구독한 키도 기준선이 있다.
- 순서: 비교 → 트리거 → **트리거가 성공한 키만 지문 갱신**. DB 오류·상한 초과로 못 보낸 키는 옛 기준선을 유지해 다음 주기에 다시 이벤트(유실 방지).
- 기록 없는 키 = 기준선만, 이벤트 없음.
- 지표: 이벤트 ÷ 비교 비율 + **정밀도**(트리거된 평가가 `changed=true` 를 낸 비율, #416 `journey.evaluation.judgment`) — 진짜 민감도 신호.

### 2.5 지하철 (추가 API 호출 0) — PR-A2
- `RealtimeSubwayCollector` 가 스냅숏 저장 직후(`:105`) `SubwayChangeFeed.onSnapshot(trains)` → 키별 지문 비교 → 바뀐 키 구독 여정.
- 🔴 역별 캐시는 덮어쓰지 않고 **지운다(DEL) → 그다음 트리거**. 스냅숏(`ALL`, `SeoulSubwayApiAdapter:123`)과 역별(`0/1000/{name}`, :139)은 다른 엔드포인트라 덮으면 평가 입력이 나빠질 수 있다. 지우면 트리거된 평가가 single-flight 로 새로 받는다(`CachedSubwayApiAdapter:83-97`). 덮어쓰기(write-through)는 `subway_arrival_raw` 픽스처로 두 응답 동일성이 증명된 뒤 별건.
- 한계: 스냅숏 15초 주기라 지하철 긴급 10초는 이벤트 경로만으로 불가 → 지표로 보고.

### 2.6 버스 노선 감시자 — PR-B (잠든 채 배포, 켜기는 한도 상향 후)
- 노선 단위 `getArrInfoByRouteAll` 1회로 그 노선의 모든 정류장 → 호출 수가 여정 수가 아니라 서로 다른 노선 수에 비례.
- 주기 = 예산 배분기(주기 하한 10초 = 공급자 갱신 실측): 남은 일일 예산을 (가장 급한 구독자 등급, 구독 수) 순으로 노선에 배분, 여유부터 안전망으로 밀림. 공급자 갱신 시각(`mkTm`)보다 빨리 안 부름.
- 응답을 정류장·순번별 캐시로 채움(`primeRealtimeCache` 재사용) + 공급자·수집 시각 기록. 평가기는 등급 주기보다 낡은 값은 버림.
- 한도 소진: 감시 중단 + `reeval.watcher.degraded` gauge, 평가기는 자기 간격 유지.
- 지방·경기 버스(`RegionalArrivalLookup`) v1 제외 + `interest.keys{watched=false}` 지표.

### 2.7 다중 인스턴스 역할
- **평가기**: 모든 인스턴스가 `SKIP LOCKED` 로 나눠 처리. 트리거 = DB 행 갱신이라 어느 인스턴스든 집어 감 → 발행 통로에 MQ 불필요(DB 가 공유 대기열). `ReevaluationTrigger` 포트는 남김(DB 쓰기가 병목이면 교체 지점).
- **감시자**(지하철 지문·버스 감시·관심 색인): 한 인스턴스만. Redis 리더 잠금 `LeaderLease`: `SET key token NX PX ttl`(TTL 약 10초), 갱신·해제는 Lua 토큰 비교. **전용 갱신 스레드가 TTL/3 마다 갱신**(15초 수집 주기에 묶으면 TTL 이 스냅숏 TTL 25초에 근접해 인계 공백). 로컬 마감 = 획득 시작 + TTL − 여유(`System.nanoTime()`), 부작용 직전(캐시 DEL·트리거)에 확인해 지났으면 건너뜀. GC 정지가 TTL 을 넘겨 잠깐 리더가 둘이어도 효과는 멱등(트리거=LEAST/dirty, 여분 ALL 호출 15초당 1회).
- 🔴 지하철 수집기 본체도 리더 잠금 아래로 — 단 **별도 플래그 `realtime.subway.leader-lease.enabled`(기본 false)**: 운영은 worker 1대라 그림자 모드가 "누가 수집하나"를 몰래 바꾸면 안 된다. Redis 장애 = 잠금 없음 = 수집 중단(스냅숏도 Redis 라 어차피 못 씀, 수용). 다른 cron(버스 cron 수집·링 갱신·일 배치)은 별건 이슈.
- 규모 다음 단계(구현 안 함): 감시 한 바퀴가 주기를 넘으면 노선 해시 기반 소유권 분할.

### 2.8 안전망
- 감시 안 되는 키(지방 버스·한도 소진)는 여유 최대 간격 90~100초(현재 180초는 여유 2분 SLO 위반). 감시되는 키는 측정 뒤 늘릴지 결정(PR-C).

## 3. PR 분할

### PR-0 비용 모델 ✅ 완료 (2026-10-14) — `COST-MODEL.md`
- 결과: 목표 부하 2,000에서 어떤 방식이든 하루 약 100만~200만 회 → **공급자 한도 상향 필요**(운영 실사용 8.3만/일). 같은 감지 주기 기준으로 노선 감시 = 키 폴링의 0.6배(2k)~0.16배(20k), 상한 약 520만/일(약 600노선 × 10초).
- 공급자 갱신 주기 중앙값 9초(p90 20초), 관측 지연 중앙값 8~10초 → 감시 주기 하한 10초, 버스 긴급 SLO 는 "우리가 처음 본 시각"부터.
- 지문 단순 비교는 중앙값 10초마다 변함 → 히스테리시스 필수.
- PR-B: 구현하되 잠든 채 배포, 예산 배분기로 한도 안에서 운용, 한도 상향 시 설정만 변경.

### PR-A1 앞당기기 핵심 (잠든 채 배포) — 브랜치 `feat/reeval-trigger-core` (#410 머지된 main 기준)
1. [ ] `V67__journey_reevaluation_trigger.sql`
   ```sql
   ALTER TABLE journey_session
     ADD COLUMN evaluation_claimed_until timestamptz,
     ADD COLUMN evaluation_dirty boolean NOT NULL DEFAULT false,
     ADD COLUMN last_evaluated_at timestamptz;
   CREATE INDEX idx_journey_session_active_nav ON journey_session (id)
     WHERE status = 'ACTIVE' AND deleted_at IS NULL AND kind = 'NAVIGATION';
   ```
   엔티티에 **매핑하지 않음**. `JourneySession` 에 `@DynamicUpdate`. `@Sql` 로 스키마를 만드는 IT 13개에 V67 추가.
2. [ ] `JourneySessionRepository`: `claimDueBatch`·`finishEvaluation`(시그니처 `:now`·`:dirtyGapSeconds` 추가)·신규 `requestReevaluation(ids, now, minGapSeconds)` — §2.1 SQL 그대로.
3. [ ] `JourneyEvaluationScheduleWriter`·`JourneyEvaluationService`: 이번 평가의 등급에서 dirtyGap 계산해 전달.
4. [ ] `application/journey/port/out/ReevaluationTrigger` (`TriggerResult request(Map<Tier, Set<Long>> journeys)` — 보낸 키 판단용 결과 포함), 구현 `JdbcReevaluationTrigger`: 등급별 minGap(5/10/30), 상한 500(등급순), 포화 차단(밀림 기준), 그림자 모드(카운트만).
5. [ ] `Tier`(URGENT/CAUTION/RELAXED, 경계 = 기존 near 300초·far 900초 설정).
6. [ ] 지표 `JourneyReevalMetrics`: `reeval.pulled{tier}`, `reeval.dirty_marked`, `reeval.overflow`, `reeval.shed`, `clock.skew`.
7. [ ] 설정 `journey.navigation.reeval.{enabled=false, shadow=true, min-gap-seconds.*, max-journeys-per-cycle=500, shed-backlog-*}`.
8. [ ] 노트 `### 배포`: V67 Flyway 자동, 플래그 전부 off → 동작 변화 없음.

### PR-A2 지하철 변화 → 트리거 — 브랜치 `feat/reeval-subway-feed` (A1 머지 후)
1. [ ] `application/journey/reeval/InterestIndex`(20초 재구성, §2.3 SQL은 리포지토리 `findInterestRows()`), `InterestKey`(sealed `BusKey`·`SubwayKey`).
2. [ ] `ChangeDetector` + `InMemoryChangeDetector`(§2.4).
3. [ ] `common/lock/LeaderLease` + `RedisLeaderLease`(Lua, 전용 갱신 스레드, 로컬 마감), 수집기 본체 잠금은 `realtime.subway.leader-lease.enabled`.
4. [ ] `SubwayChangeFeed`: 지문 비교 → 바뀐 역 캐시 DEL → 트리거 → 성공 키만 지문 갱신. `RealtimeSubwayCollector:105` 뒤 호출(실패는 수집 성공에 무영향).
5. [ ] 지표: `reeval.events{source}`, `reeval.compared{source}`, `interest.keys{source,watched}`, `interest.rebuild.duration`, `reeval.leader{held}`, 정밀도(트리거된 평가의 changed 비율).
6. [ ] 설정 `journey.navigation.reeval.subway-trigger=false`, `interest-rebuild-seconds=20`.

### PR-B 버스 노선 감시자 — 브랜치 `feat/reeval-bus-route-watcher` (PR-A2 뒤, 잠든 채 배포 — 켜기는 한도 상향 후)
- [ ] `application/journey/reeval/BusRouteWatcher`(리더 전용, 1초 tick): `InterestIndex` 의 버스 키를 노선별로 묶음 → `RouteBudgetAllocator` 가 이번 tick 호출할 노선 결정 → `BusApiPort.getArrInfoByRouteAll` → 정류장·순번별 캐시 채우기(+`mkTm`·수집 시각 값 객체) → 지문 비교 → 트리거.
- [ ] 한도 버킷 `bus.quota.watcher.arrival`(검색 몫과 합이 공급자 한도 이하), 소진 시 degraded.
- [ ] 평가기 신선도: `CachedSearchTimeBusAdapter` 가 캐시 값의 수집 시각이 등급 주기보다 오래면 무시하고 직접 조회.
- [ ] 안전망: 감시 안 되는 키 여유 최대 간격 90초(`JourneyEvaluationIntervalPolicy` 에 `watched` 입력).

### PR-C 측정·조정 — 브랜치 `test/reeval-detection-latency` (#409 머지 후)
- [ ] `JourneyDetectionLatencyIT` 에 이벤트 시나리오: 지하철 스냅숏 변화 / 버스 캐시 변화 / 처리 중 dirty. 등급별 p95 를 플래그 off·on 으로 2k/6k/20k 비교, 관측 지연 따로.
- [ ] 결과로 안전망 간격·minGap 확정, 포폴 수치는 구조 off/on 전후만 사용.

## 4. 계약 테스트

PR-A1 (Testcontainers Postgres IT + 단위):
1. 처리 안 된 행: 트리거가 `max(now, last + minGap)` 로 당기고, 이미 더 이른 값은 안 건드림.
2. `evaluation_claimed_until` NULL 행에 트리거 → NOT NULL 위반 없음.
3. 처리 중 행: dirty 만, 증서 불변 → 완료 CAS 성공 → 다음 = `LEAST(nextAt, now + dirtyGap)`, dirty·claimed 해제, `last_evaluated_at` 기록.
4. 선점이 낡은 dirty 를 지운다.
5. **livelock**: 평가 시간(지연 주입)보다 잦은 트리거 속에서도 매 평가 저장(LOST_CLAIM 0).
6. 래치: 선점 트랜잭션 도중 트리거 → 끝나면 dirty.
7. 두 트리거 묶음을 반대 순서로 동시 실행 → 교착 없음.
8. `RerouteProposalTxService.close` 동시 실행이 next·dirty 를 되돌리지 않음(`@DynamicUpdate`).
9. 삭제·비활성·TRACK·끝난 여정 불변.
10. 트리거가 `evaluation_urgent` 를 바꾸지 않음.
11. 그림자 모드: DB 무쓰기.
12. 포화 차단: 밀림 기준 이상이면 주의·여유 미적용, 긴급은 적용.
13. 단계 전이는 지금처럼 진행 중 CAS 를 깬다(회귀).

PR-A2:
14. 관심 키 SQL: 남은 환승 탈 구간 + 현재 WAIT_BUS 만, 지난 환승 제외, 지하철 방향 정규화.
15. 지하철 지문: 같은 스냅숏 무이벤트 / 열차 교체·arvlCd·stationsAway 변화 이벤트 / 처음 보는 키 무이벤트.
16. 순서: 캐시 DEL 이 트리거보다 먼저, 트리거된 평가는 새로 받은 값을 읽음.
17. 유실 방지: 상한 초과·DB 실패한 키는 다음 주기에 다시 이벤트.
18. 리더 잠금: 동시 두 인스턴스 중 하나만 / 소유자 아닌 쪽 갱신·해제 불가 / 만료 인수 / 로컬 마감 지나면 트리거 안 씀 / 잠금 플래그 off 면 수집기 그대로 동작.
19. 그림자 모드: DB 무쓰기·캐시 무삭제.
20. 20k 세션 관심 SQL EXPLAIN + 시간 기록.

PR-B: route-all 분해 = 정류장별 파싱(순환 노선 포함) / 배분기 예산 초과 없음·긴급 우선 / `mkTm` 그대로면 무호출·무이벤트 / 히스테리시스 / 리더 아닐 때 무호출 / 한도 소진 degraded·평가기 정상 / 낡은 캐시 무시.

## 5. 검증 명령
- 단위·IT: `./gradlew test --tests '*Reeval*' --tests '*InterestIndex*' --tests '*ChangeDetector*' --tests '*LeaderLease*' --tests '*JourneyDueEvaluator*' --tests '*RerouteProposal*'`
- 전체: st-pg(:55432)·st-redis(:56379) + `env/{db,api,auth,oauth,toss}.env` export 후 `./gradlew cleanTest test --continue` → 기존 무관 12건(JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1·JourneyNotificationSchemaIT 간헐 1) 외 0.
- 운영 켜기 전: 그림자 모드 하루 → `reeval.events/compared` 비율, `interest.keys`, `interest.rebuild.duration` 확인.

## 6. 결정 기록 (Opus 검토 2026-10-14 반영)
- 시계: 앱 `:now` 하나 + `clock.skew` 감시(DB now() 기각 — Clock.fixed IT 다수).
- 지문: 리더 메모리, 지하철은 시각 단위 없이 `(trainNo, arvlCd, stationsAway)`.
- `evaluation_urgent`: 트리거 불간섭.
- 관심 색인 재구성 사이 공백: 끝난 여정은 ACTIVE 조건으로 걸러지고, 전이·수락은 이미 next=now → 무해(테스트 9).
- 새 칼럼 엔티티 미매핑 + `@DynamicUpdate`, 선점 시 dirty 해제, 트리거 id 순서 잠금, 완료는 LEAST(nextAt, now+gap).
- 역별 캐시 덮어쓰기 대신 DEL.
- 긴급 minGap 5초 + 포화 차단.
- PR-A 를 A1(핵심, 잠든 채)·A2(지하철)로 분할.
