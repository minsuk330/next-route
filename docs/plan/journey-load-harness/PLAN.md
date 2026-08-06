# ⑧ journey-load-harness — 여정 10,000건에서 평가기가 버티는지 재고 기록한다

- 상위 인덱스: [../journey-navigation/PHASE-4.md](../journey-navigation/PHASE-4.md) ⑧
- 선행: ④(#113) ⑤(#116) ⑥(#117) 머지 완료, ⑦(#118) 오픈 중 — **⑦ 머지 후 착수**
- 티어: **L** (측정 결과에 따라 Redis 도입 여부가 갈리고, 실험 설계 자체가 산출물)
- 브랜치: `feat/journey-load-harness` (fresh `main` 기준)
- 마이그레이션: **측정 전에는 없음.** 게이트를 넘기면 후속 PR에서 논의
- 작성일: 2026-08-06

용어 — "평가기"는 앱 요청 없이 주기적으로 환승 위험을 다시 계산하는 백그라운드 작업
(`JourneyDueEvaluatorScheduler` + `JourneyEvaluationService`). "선점(claim)"은 여정 하나를 한 워커만
계산하도록 `next_evaluation_at`을 미래로 밀어 두는 것.

---

## 0. 이 PR이 답해야 하는 질문

**ACTIVE 여정이 10,000건일 때 평가기가 SLO 안에서 도는가.** 답이 "아니오"면 무엇을 고쳐야 하는가.

REQUIREMENTS의 초기 SLO 중 이 실험이 직접 겨냥하는 값은 하나다.

| 대상 | 목표 | 이 실험에서의 측정 방법 |
|---|---:|---|
| **due evaluator 지연 p95** | **10초 이하** | `next_evaluation_at`이 지난 시점부터 실제 평가가 끝난 시점까지 |

나머지는 그 값을 설명하기 위한 보조 지표다.

- 서로 다른 관심(정류장·노선) 수 대비 외부 provider 호출 수 — ⑤가 만든 성질이 규모에서도 유지되는지
- 중복 선점 건수 — 0이어야 한다
- 평가 적체(backlog): `next_evaluation_at <= now`인 ACTIVE 여정 수의 시계열
- 평가 한 건 소요 p50·p95·p99
- DB 커넥션 풀 사용량, 평가 워커 큐 길이·거부 수

### 결정 게이트 (codex 리뷰 반영 — 복합 조건)

단일 숫자로 판정하지 않는다. **셋을 모두 만족해야 통과**다.

| 조건 | 기준 | 왜 하나로 부족한가 |
|---|---|---|
| 지연 p95 | 10초 이하 | SLO 그 자체 |
| 적체 비발산 | 관측 구간에서 완료 수 ≥ 새로 due 된 수, **그리고** 종료 backlog ≤ 시작 backlog | p95가 좋아도 적체가 계속 쌓이면 곧 무너진다 |
| 동시 선점 겹침 | 0건 | 정상 재평가와 구분해야 한다(아래) |

**"중복 선점 0"의 정의를 바꿨다.** 같은 `journeyId`가 실험 중 여러 번 claim 되는 것은 정상이다 —
평가가 끝나면 다음 평가 시각이 다시 심기기 때문이다. 세야 하는 것은 **동시에 유효한 claim이
겹치는 경우**, 즉 앞선 claim의 lease가 아직 살아 있는데 같은 여정이 다시 claim 된 사건이다.

**판정 구간을 나눈다.** 10,000건이 같은 시각에 due가 되면 그것은 정상 상태가 아니라 cold burst다
(scheduler는 한 tick에 100건을 claim한 뒤 배치 완료를 기다리고 다시 5초 `fixedDelay`를 둔다).
그래서 fixture는 due 시각을 **평가 간격 안에 고르게 흩어 배치**하고, SLO 게이트는 초기 burst가
빠진 뒤의 steady-state 구간에서 판정한다. 초기 burst 회복 시간은 **별도 회복력 지표**로 남긴다.

**먼저 재고 나중에 넣는다**(PHASE-4 §3). 게이트를 넘기면 개선안을 검토하되, **Redis ZSET은 후보에서
뺀다** — 근거는 §4·§9 참고. 목표 안이면 "도입하지 않는다"를 같은 무게로 문서에 남긴다.

---

## 1. 실험 설계

### 1-1. 무엇을 고정하고 무엇을 바꾸나

**고정** — provider 응답 지연(고정 50ms), 여정 구조(환승 1개), 평가 간격 설정 기본값,
DB·Redis는 Testcontainers.

**바꾸는 것(축)** — 셋만 본다. 더 늘리면 결과를 읽을 수 없다.

| 축 | 값 | 왜 이 축인가 |
|---|---|---|
| 여정 수 | 1,000 / 10,000 | 목표 규모와 그 1/10. 선형인지 아닌지가 보인다 |
| 서로 다른 관심 수 | 여정 수의 1% / 100% | ⑤의 dedupe가 실제로 호출을 줄이는지 |
| 워커 풀 크기 | 4 / 16 | ⑦이 만든 provider permit 상한이 실제로 검색 몫을 지키는지 |

**관심 분포에 대한 주의(codex 리뷰 반영).** 저장소에는 실제 사용자 정류장 분포 데이터가 없다.
그래서 1%를 "현실 분포"라고 주장하지 않는다 — **1% 관심에 균등 배치한 보수적 집중 시나리오**로만
읽는다. 실제 출퇴근 편중은 이보다 dedupe에 유리할 가능성이 크므로 이 결과는 하한이다.

관심은 여정 순서에 **결정적으로 교차 배치**한다(`interestIndex = journeyIndex % distinctInterests`).
같은 관심을 연속으로 몰아 두면 캐시 hit가 부풀려져 dedupe 효과를 과대평가한다. ⑤는 tick 안에서
묶는 방식이 아니라 TTL 캐시(버스 15초·ML 30초·지하철 10초)로 줄이므로, **관심 가짓수뿐 아니라
평가 순서와 시간창도 호출 수를 좌우한다.**

### 1-2. 시나리오

| # | 이름 | 구성 | 확인할 것 |
|---|---|---|---|
| S1 | baseline-1k | 여정 1,000 · 관심 1% · 풀 4 | 정상 규모에서 지연 p95, 적체 0 수렴 |
| S2 | **target-10k** | 여정 10,000 · 관심 1% · 풀 4 | **게이트 판정의 주 근거** |
| S3 | fanout-10k | 여정 10,000 · 관심 100% · 풀 4 | dedupe가 없을 때의 provider 호출 상한 |
| S4 | scaled-10k | 여정 10,000 · 관심 1% · 풀 16 | 풀을 키웠을 때 처리량이 늘고, **검색 몫이 지켜지는지** |

S4에는 부하 도중 경로 검색 요청을 별도 스레드에서 흘려 보내 `LIMITED` 비율을 센다. ⑦의 전용
Semaphore가 없던 시절이라면 이 값이 치솟았을 자리다.

### 1-3. 측정 방법

- **평가 지연**(codex 리뷰로 정의 변경): **원래 due 시각 → 평가 결과가 DB에 commit 된 시각.**
  worker 실행 시간만 재는 기존 `JourneyEvaluationMetrics.DURATION`으로는 claim 대기·큐 대기가
  빠져 SLO 정의와 맞지 않는다.
  - 원래 due 시각은 claim 이 덮어쓰므로, **claim SQL이 선점 전 값을 함께 돌려주게 바꾼다**
    (`RETURNING j.id, picked.next_evaluation_at`). 실험 도구가 claim 직전에 따로 SELECT 하는 방식은
    동시 claim 과 경합해 엉뚱한 값을 집는다
  - `SKIPPED`·`LOST_CLAIM`·실패는 **완료로 세지 않는다.** 그 여정의 최초 due 시각을 계속 들고 있다가
    성공한 평가에서 한 번만 지연을 기록한다 — 그래야 재시도 비용이 지연에 포함된다
  - 이 때문에 "프로덕션 코드 무변경" 목표는 포기한다. 변경은 claim 쿼리 1개 + DTO 1개 + 그 값을
    지나 보내는 자리로 한정한다
- **적체**: 1초 간격으로 `SELECT count(*) FROM journey_session WHERE status='ACTIVE' AND
  next_evaluation_at <= now()` — 시계열을 배열로 모아 최대·최종값을 남긴다
- **provider 호출 수**: delegate mock의 호출 카운터(`SearchTimeBusAdapter`·`MlArrivalPredictorAdapter`)
- **중복 선점**: 실험 동안 claim된 journeyId를 전부 모아 중복 여부 확인
- **평가 소요**: `JourneyEvaluationMetrics.DURATION` 타이머의 스냅숏(p50·p95·p99)
- **DB 풀**: HikariCP `hikaricp.connections.active` gauge 최대값
- **워커 큐**: `journeyEvaluatorExecutor`의 queue size 최대값, `queue_full` 실패 카운터

### 1-4. 여정 10,000건을 만드는 법

`StartJourneyUseCase`로 만들면 여정 하나마다 스냅숏 조회·5테이블 쓰기가 일어나 준비만 몇 분이
걸린다. **JDBC batch insert로 직접 넣는다.**

주의할 스키마 제약:

- **`uq_journey_active_per_user`** — 사용자당 ACTIVE 여정은 하나(`V35__create_journey.sql:45`).
  따라서 여정 10,000건은 **사용자 행 10,000개**를 함께 만들어야 한다
- `journey_session.route_snapshot`이 `jsonb not null` — 최소 형태로 채우되, 그 크기가 곧 claim·로드
  비용이므로 **실제 경로와 비슷한 크기**여야 의미가 있다. 실제 검색 응답 1건을 픽스처로 박아 재사용
- 예측이 실제로 일어나려면 `journey_leg.start_stop_id` + `journey_leg_route_option.boarding_seq`가
  채워져 있어야 한다(④a에서 확인). 관심 수 축은 이 `start_stop_id`·`route_id` 조합의 가짓수로 만든다
- `bus_route`·`bus_stop`·`bus_route_stop`에 대응 행이 있어야 `SingleTransferPredictor:69`의
  `existsByRouteIdAndStopIdAndSeq`를 통과한다

### 1-5. 어디서 어떻게 돌리나

**Gradle 통합 시험으로 만든다.** `BusAlertEndToEndLoadIT`가 같은 방식이다(Testcontainers + 규모를
env로 조정). 근거는 셋이다 — 재현 가능하고, provider를 mock으로 고정할 수 있고, 결과를
`TestReporter`로 뽑아 문서에 바로 옮길 수 있다. 별도 실행 도구(main 클래스·VPS 스크립트)는 만들지
않는다.

**단, 실제 스케줄러 경로를 그대로 구동한다**(codex 리뷰). `JourneyDueEvaluatorScheduler.tick()`을
거치지 않고 `claimService`·`evaluationService`를 직접 반복 호출하면 **배치 완료 대기와 5초
`fixedDelay`가 빠져** 지연 값이 실제보다 낙관적으로 나온다. 측정 대상이 바로 그 대기 시간이다.

- **평소 CI·전체 스위트에서는 돌지 않는다.** `@EnabledIfEnvironmentVariable(named = "JOURNEY_LOAD",
  matches = "true")`로 막는다. 10,000건 실험은 수 분이 걸리므로 `./gradlew test`에 들어가면 안 된다
- 규모는 env로 조정: `JOURNEY_LOAD_JOURNEYS`, `JOURNEY_LOAD_INTEREST_RATIO`, `JOURNEY_LOAD_POOL`
- 실제 provider·실제 VPS는 쓰지 않는다. 이 실험이 증명하는 것은 절대 성능치가 아니라
  **구조가 규모에서 무너지지 않는다는 성질**이다(RESULTS.md에 명시)

---

## 2. 변경 파일

| 파일 | 신규/수정 | 내용 |
|---|---|---|
| `src/test/java/watoo/grd/nextroute/application/journey/load/JourneyLoadFixture.java` | 신규 | 사용자·여정·leg·환승·노선정류장 대량 삽입(JDBC batch) |
| `src/test/java/watoo/grd/nextroute/application/journey/load/JourneyLoadProbe.java` | 신규 | 적체·지연·호출 수·풀 사용량 수집기 |
| `src/test/java/watoo/grd/nextroute/application/journey/load/JourneyEvaluatorLoadIT.java` | 신규 | 시나리오 S1~S4 실행, 결과를 표로 출력 |
| `docs/plan/journey-load-harness/RESULTS.md` | 신규 | 측정 결과 보고서(`route-search-resilience/RESULTS.md` 형식) |
| `docs/plan/journey-navigation/PHASE-4.md` | 수정 | ⑧ 절에 결과·게이트 판정 반영 |

**프로덕션 코드는 바꾸지 않는 것을 목표로 한다.** 측정을 위해 꼭 필요한 것이 나오면(예: 적체 gauge)
그때 최소로 추가하고 PLAN에 사유를 남긴다.

### 핵심 시그니처

```java
/** 대량 준비. 반환값은 만들어진 여정 id 목록. */
public final class JourneyLoadFixture {
    public record Spec(int journeys, double interestRatio, Instant dueAt) {}
    public record Prepared(List<Long> journeyIds, List<Long> userIds, int distinctInterests) {}

    public Prepared prepare(Spec spec);   // users → journey_session → leg → option → transfer
    public void cleanUp(Prepared prepared);
}

/** 실험 도중 상태를 훑는다. 1초 간격 샘플링. */
public final class JourneyLoadProbe {
    public record Sample(Instant at, long backlog, int activeDbConnections, int workerQueue) {}
    public record Report(List<Sample> samples, long maxBacklog, long finalBacklog,
                         Duration evaluationP50, Duration evaluationP95, Duration evaluationP99,
                         long providerArrivalCalls, long providerPositionCalls, long mlCalls,
                         long duplicateClaims, long limitedSearchResponses) {}

    public Report runFor(Duration window);
}
```

---

## 3. 작업 순서

- [ ] **0. 착수 조건** — ⑦(#118) 머지 확인. 작업트리에 커밋 안 된 변경이 있으면 먼저
      `git stash push -m`으로 대피(2026-08-05에 `reset --hard`로 남의 문서를 날린 적 있다)
- [ ] **1. 브랜치** — `git fetch origin && git checkout -b feat/journey-load-harness origin/main`
- [ ] **2. 픽스처** — `JourneyLoadFixture`. 1,000건 준비가 30초 안에 끝나는지부터 확인
      → `./gradlew test --tests '*JourneyEvaluatorLoadIT' -i` (S1만)
- [ ] **3. 수집기** — `JourneyLoadProbe`. 적체·지연·호출 수가 실제로 잡히는지 S1으로 검증
- [ ] **4. S1 → S2** — 규모를 올리며 병목을 관찰. 준비 시간이 실험 시간을 넘지 않게 튜닝
- [ ] **5. S3 · S4** — dedupe 효과와 검색 몫 보호 확인
- [ ] **6. RESULTS.md 작성** — 아래 §5 골격대로. **숫자를 먼저 적고 해석을 뒤에 붙인다**
- [ ] **7. 게이트 판정** — 지연 p95가 10초를 넘으면 "Redis ZSET 도입 필요" 결론과 근거를 문서에
      남기고, 넘지 않으면 "도입하지 않는다"를 같은 무게로 남긴다
- [ ] **8. PHASE-4.md ⑧ 절 갱신** + Phase 4 완료 조건 체크
- [ ] **9. 전체 스위트** — `./gradlew test` (부하 IT는 env 없으면 skip 되는지 확인)
- [ ] **10. PR** — 제목 `feat(journey): 여정 10,000건 부하 실험 도구와 측정 결과`

---

## 4. 예상되는 병목 — 실험 전 가설

측정 전에 적어 둔다. **맞히려는 것이 아니라, 결과가 가설과 다를 때 그것을 발견하기 위해서다.**

1. **claim 쿼리보다 dispatch 주기가 먼저 막힌다.** `idx_journey_due`(부분 인덱스) + `SKIP LOCKED`라
   claim SQL 자체는 싸다. 대신 스케줄러는 **한 배치가 다 끝난 뒤에야 다음 `fixedDelay` 주기로 간다**
   — tick 5초 × 배치 100건이면 이론 상한이 분당 1,200건이다. 10,000건을 30초 간격으로 보려면
   분당 20,000건이 필요하다. 그렇다면 Redis ZSET(=claim 조회를 빠르게)은 **엉뚱한 곳을 고치는 것**이
   된다. 이 가설의 확인이 게이트 판정의 핵심이다
2. **`route_snapshot` jsonb 로드 비용이 무시할 수 없다.** `JourneyEvaluationContextLoader`가
   `findById`로 엔티티 전체를 읽으므로 여정마다 경로 JSON이 통째로 올라온다. 10,000건 규모에서
   이것이 드러나면 필요한 컬럼만 읽는 projection이 후속 과제가 된다
3. **DB 커넥션이 워커 수보다 먼저 마른다.** Hikari 기본 최대 10인데 평가 한 건이 읽기·쓰기
   트랜잭션을 각각 열고, 톰캣 요청도 같은 풀을 쓴다
4. **적체는 평가 간격(⑥) 때문에 자기 조절된다.** 값이 안 바뀌는 여정은 backoff로 120~180초까지
   물러나므로, 10,000건이어도 실제 due 발생률은 균등하지 않을 수 있다

---

## 5. RESULTS.md 골격

`route-search-resilience/RESULTS.md`와 같은 순서로 쓴다.

1. **요약 — 목표 대비 결과 표**: due evaluator 지연 p95 목표 10초 vs 측정값, 게이트 판정 한 줄
2. **측정 조건 주의**: 로컬 Mock 환경, provider 고정 50ms, 절대값이 아니라 성질을 증명한다는 문장
3. **테스트 환경 표**: 머신·JVM·컨테이너 버전·시나리오별 설정값
4. **시나리오별 결과 표**: S1~S4 각각 지연 p50/p95/p99, 최대 적체, provider 호출 수, 중복 선점,
   DB 풀 최대, 검색 LIMITED 비율
5. **관찰 1~n**: 숫자에서 읽은 것. 가설(§4)과 맞았는지 틀렸는지 명시
6. **결정**: Redis ZSET 도입 여부와 근거. 도입하지 않기로 했다면 **어떤 조건에서 다시 볼지**를 적는다
7. **다음 단계**: 남은 과제(예: projection, Hikari 상향)

---

## 6. 함정

- **부하 IT가 전체 스위트에 섞이면 안 된다.** env 가드를 먼저 넣고 시작한다
- **준비(fixture) 시간이 측정 시간을 삼킨다.** 배치 insert 크기를 키우고, 준비 시간을 별도로 찍어
  결과에 남긴다
- **Testcontainers 자원 경합** — 2026-08-06 전체 스위트에서 postgres 컨테이너 기동 실패로 무관한
  IT가 한 번 깨졌다. 부하 IT는 단독 실행이 기본이다
- **여정마다 사용자를 만들어야 한다**(unique index). 정리(cleanUp)에서 사용자까지 지워야 다음 실행이
  깨끗하다
- **provider mock 지연을 0으로 두지 마라.** 0이면 permit·큐가 전혀 안 쌓여 ⑦의 격리 효과가
  측정되지 않는다
- **결과 문서는 숫자를 먼저, 해석을 뒤에.** 해석이 먼저 오면 숫자를 그 방향으로 읽게 된다

---

## 7. 완료 판정

- [ ] S1~S4가 재현 가능하게 돌고, env 없이는 skip 된다
- [ ] due evaluator 지연 p95가 측정되어 SLO(10초)와 비교됐다
- [ ] 중복 선점 0건이 확인됐다
- [ ] 서로 다른 관심 수 대비 provider 호출 수가 S2·S3 비교로 드러났다
- [ ] `RESULTS.md`에 before/after 형식 표와 게이트 판정이 남았다
- [ ] `PHASE-4.md` ⑧ 절과 Phase 4 완료 조건이 갱신됐다
- [ ] `./gradlew test` 회귀 0

## 8. 이 PR이 하지 않는 것

- **Redis ZSET due index 구현** — 게이트를 넘겼을 때만 별도 PR. 먼저 재고 나중에 넣는다
- 프로덕션 성능 튜닝(Hikari 상향, projection 도입) — 측정에서 근거가 나오면 후속 과제로 기록만
- 실제 VPS·실제 provider 부하 — 상대 API를 두들기지 않는다
- 알림 발송 경로 부하 — Phase 5 범위

---

## 4-1. 게이트를 넘겼을 때의 개선 우선순위 (codex 리뷰로 확정)

**Redis ZSET due index는 후보에서 뺀다.** claim 조회가 병목이라는 전제가 §4-1 가설과 어긋난다.
대신 이 순서로 본다.

1. **스케줄러 dispatch 방식** — 배치 완료를 기다렸다가 고정 지연을 두는 구조를 계속 흘려 보내는
   방식(drain)으로 바꾸거나, tick 간격·배치 크기를 조정한다. 가장 싸고 효과가 직접적이다
2. **`route_snapshot` projection** — `JourneyEvaluationContextLoader`가 `findById`로 엔티티 전체를
   읽어 경로 JSON까지 올린다. 실제로 쓰는 필드만 읽으면 DB 비용이 준다
3. **provider 동시성 확대** — `provider-permits`를 올린다. **가장 마지막**이다. ⑦이 만든 검색 몫
   보호를 약화시키고 외부 quota 소모를 늘린다

## 9. L 티어로 둔 이유와 codex 논의 결과

diff 모양은 크지 않지만 **무엇을 재는지가 곧 산출물**이라, 설계가 틀리면 숫자가 나와도 쓸모가 없다.
착수 전 codex 와 다섯 항목을 맞췄고(2026-08-06), 결론은 위 본문에 이미 반영했다.

| 논의 항목 | 결론 | 반영 위치 |
|---|---|---|
| 지연 측정 기준점 | 원래 due → 저장 commit. claim SQL 이 선점 전 값을 함께 반환. 실패·SKIPPED 는 완료로 안 셈 | §1-3 |
| 관심 분포 가정 | 1% 를 현실이라 주장하지 않는다. "보수적 집중 시나리오"로만 읽고 관심을 교차 배치 | §1-1 |
| IT vs 별도 도구 | IT 유지. 단 실제 스케줄러 경로를 구동해 배치 대기·고정 지연을 포함 | §1-5 |
| 게이트 기준 | p95 단독 → **p95 + 적체 비발산 + 동시 선점 겹침 0** 복합. burst 는 별도 지표 | §0 |
| 가설이 맞을 때 대안 | Redis ZSET 제외. dispatch → projection → provider 동시성 순 | §4-1 |

남은 판단은 실험 결과가 나온 뒤에 한다.
