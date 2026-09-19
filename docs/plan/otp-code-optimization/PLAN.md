# OTP 코드 최적화 — 에이전트 실행 계획

> **2026-09-15 현재 작업: REDUCED_25_R2_C1_COMPLETE_C2_BOOTSTRAP_EXPLORATORY.** 사용자 결정으로 원본 GTFS 운행편 수의 약 25%를 보존한 별도 데이터에서 A/HF 지연 성능 회귀를 탐색한다. R0 feed·graph 생성과 R1 A/HF 4-OD 정합성 비교, R2 c1 pair-01~05 및 c1 bootstrap, c2 전용 A reference와 현재 HF SHA가 같은 c2 5 pair 및 bootstrap까지 완료됐다. c2 bootstrap은 peak RSS median +18.24% 개선 신호를 보였지만 p50 −0.31%, p95 +1.09%로 모두 INCONCLUSIVE이며 host swap 때문에 formal no-swap 판정이 아니다. c4와 전체 R3 비열등성 판정은 아직 실행하지 않았다. 실행 계약은 [LARGE-HEAP-AGENT-PLAN.md](handoffs/LARGE-HEAP-AGENT-PLAN.md) §P4-R을 우선한다. 상세 원장은 [REDUCED-25-RUN-LEDGER.md](handoffs/REDUCED-25-RUN-LEDGER.md)를 따른다.

작성일: 2026-09-08. 현재 상태: **P4_FORMAL_PENDING** — 2026-09-11의
`VERIFIED` 표기는 이전 `-Xmx3500m`/구 workload 검증에 대한 기록이며, 현재
`-Xmx10g` PR 근거 계약으로는 대체되지 않는다. B/C/G가 포함된 조합은 c1 개별
OD 지연 회귀 기준을 반복 위반해 제외했고, 현재 작업트리는 H+F만 포함한다.
H+F의 이전 정합성·c1/c2/c4·soak 결과는 탐색/운영 후보 근거로 보존하지만, 현재
P4의 no-swap A/HF 성능 gate는 아직 통과하지 않았다.

> 최신 기록 (2026-09-15): [handoffs/NEXT-AGENT.md](handoffs/NEXT-AGENT.md),
> [handoffs/ABLATION-P4-FEASIBILITY.md](handoffs/ABLATION-P4-FEASIBILITY.md),
> [handoffs/LARGE-HEAP-AGENT-PLAN.md](handoffs/LARGE-HEAP-AGENT-PLAN.md)를 우선한다.
> A/c1 feasibility를 세 번 실행해 모두 400/400·warmup·수집 gate를 통과했지만
> swap-in이 각각 +7,675/+250/+88 pages라 formal no-swap gate는 미통과다. A/HF
> 네 고정 OD full-detail correctness는 각 1회 mismatch 0으로 scoped 통과했지만
> 성능 증거가 아니다. 아래의 초기 실행 지시와 9월 11일 결과는 누적 이력이다.

## 1. 최종 목적과 완료 계약

**동일한 수도권 데이터·검색 기능·검색 결과를 유지하면서, OTP의 실제 검색 메모리와 응답시간을 모두 감소시킨다.**

- 메모리만 감소하거나 응답시간만 감소하면 전체 완료가 아니다.
- 임시 객체 할당량 감소, 단위 테스트 통과, 빌드 성공만으로 최적화 성공을 선언하지 않는다.
- bbox, GTFS 노선·정류장·서비스 날짜, 검색 시간창, 반환 후보 수, 환승 허용 범위를 줄여 달성하지 않는다.
- JVM heap 상한·GC 종류·캐시 설정 변경을 코드 최적화의 성과로 섞지 않는다.
- 기존 두 코드 변경을 반드시 유지하는 것이 목표가 아니다. 이득 없는 변경은 최종 후보에서 제외한다.
- 목표 달성을 사전에 보장할 수는 없다. 달성하지 못하면 `NOT_MET`으로 보고하고 근거 있는 다음 실험을 제시한다. 목표를 낮추거나 미측정 값을 추정해 완료하지 않는다.

### 최종 합격 기준 — 이번 계획의 초기 기준

아래 5%는 기존 측정에서 얻은 사실이 아니라, 잡음 수준의 개선을 완료로 선언하지 않기 위한 초기 목표값이다. 실행 에이전트가 결과를 본 뒤 임의로 완화할 수 없다.

| 구분 | 최종 기준 |
| --- | --- |
| 검색 정합성 | 고정 검증 corpus의 모든 응답에서 아래 의미적 동등성 계약 충족. 새 오류·timeout·결과 누락 0건 |
| 실제 메모리 | 동시성 1·2·4 각각에서 측정 구간의 OTP 프로세스 peak RSS가 원본 대비 최소 5% 감소 |
| 응답시간 | 동시성 1·2·4 각각에서 동일 비율 workload의 p50과 p95가 원본 대비 최소 5% 감소 |
| 개별 경로 회귀 | 특정 OD의 지연 악화를 전체 평균으로 숨기지 않는다. OD별 p50/p95가 반복 측정에서 5% 넘게 악화되면 원인 규명·수정 전 합격 금지 |
| 반복 신뢰성 | 최소 5개 독립 JVM 실행 쌍. 실행 쌍별 개선율의 중앙값이 목표 충족하고, 실행 단위 paired bootstrap 95% 구간 하한이 0% 초과. 부족하면 INCONCLUSIVE |
| 정상 상태 | soak에서 오류 증가·시간 경과에 따른 지속적인 메모리 증가·late GC 지연 악화가 없어야 함 |

개선율은 `(baseline - candidate) / baseline × 100`이다. 요청 하나하나를 독립 실행처럼 취급해 신뢰구간을 부풀리지 않는다. 5개 실행은 최소치일 뿐 정밀도를 보장하지 않는다. 신뢰구간 계산 방식·seed·run별 원본값을 보존한다.

## 2. 작업 위치와 기존 근거

- 구현 대상: `/Users/wa/golbob/개인/OpenTripPlanner`
- 이 계획 위치: Nexroute의 `docs/plan/otp-code-optimization/PLAN.md`
- 기존 기록: OTP의 `test/performance/seoul-metro/README.md`
- 기존 측정기: OTP의 `test/performance/seoul-metro/compare_graphql.py`
- 원본 코드 기준: `02c0b4b537eb07bb0b284726397821682248fe20`
- 관측된 작업 브랜치: `codex/otp-phase0-3` — 실행 시 다시 확인한다.
- 기존 graph: `/private/tmp/otp-seoul-dev-graph/graph.obj`
- 기록된 graph SHA-256: `8b597d8bbc1c8588acf0c829735da7ff22ea9d160bd7cb272e23a11ea3002385`
- 기록된 검색 시각: `2026-09-08T08:00:00+09:00`
- 기록된 JVM: Java 25, `-Xmx3500m`, `-XX:+UseStringDeduplication`

한글 경로는 실제 파일시스템의 Unicode 정규화를 확인한다. 임시 graph가 없어졌다면 동일 입력을 먼저 복원한다. 다른 graph를 이전 결과와 직접 비교하지 않는다. 이 실험 graph와 최신 운영 전환 graph가 같은지는 별도 확인 대상이다. 기존 dev 대비 성능을 검증하는 작업이며 2.9.0 대비 효과와 섞지 않는다.

### 현재 증거 수준

| 동시성 | 원본 p50/p95 ms | 두 변경 적용 p50/p95 ms |
| --- | --- | --- |
| 1 | 566.19 / 574.11 | 784.16 / 969.58 |
| 2 | 779.92 / 931.86 | 771.78 / 1018.53 |
| 4 | 959.98 / 1629.42 | 957.00 / 1337.43 |

위 값은 warmup 5회·측정 5회의 탐색적 결과다. 메모리·GC 개선 증거가 아니며, 단일 검색 악화는 재확인해야 한다. 동시성별 OD 구성이 달랐고, JVM 수명·예열이 완전히 통제되지 않았다. 마지막 응답의 간단한 fingerprint만 비교했다. 수정 산출물도 정규 package 대신 기존 shaded JAR에 class를 주입했다. 따라서 기존 README의 correctness gate 통과는 제한적인 smoke 증거로만 취급한다.

## 3. 에이전트 공통 작업 규칙

1. 작업 저장소와 상위 경로의 AGENTS.md, git status, 이 계획, 선행 handoff를 읽는다.
2. OTP의 미커밋 Java 구현·테스트와 benchmark 디렉터리를 사용자 작업으로 보존한다. 파일 수는 재개 시 git status로 확인한다. 원본 비교를 위해 현재 checkout을 reset하지 않는다.
3. 원본·후보는 별도 디렉터리 또는 worktree로 분리하고 각각 source diff/hash를 남긴다. 필요한 파일 접근 권한은 정식 절차로 확보한다.
4. Nexroute 운영 코드·원천 GTFS·기존 graph·실행 중인 다른 서비스는 수정/종료하지 않는다. 충돌하는 프로세스가 있으면 사용자에게 조율을 요청한다.
5. 이 문서 작성은 벤치마크 실행·배포·커밋·PR 수행 완료를 의미하지 않는다. 실행 에이전트는 배정된 Phase만 수행한다.
6. 결과·프로파일 대용량 파일은 Git 밖의 실행별 디렉터리에 보존한다. OTP benchmark 디렉터리에는 재현 스크립트와 간결한 보고서만 둔다.
7. Phase별 handoff는 이 계획 옆 `handoffs/PHASE-N.md`에 남긴다. 선행 gate 실패 상태로 최종 합격 Phase를 진행하지 않는다.
8. 장시간 측정은 재개 가능한 run 단위로 나누고 진행 상황을 보고한다. 사전 run 수와 시간 예산을 기록하고, 연속 2개 개선 가설이 기각되면 원인을 요약한 뒤 다음 실험 범위를 사용자와 확인한다.

## 4. 실험 공통 계약

### 입력·환경 고정

- graph/JAR SHA, 원본 commit와 patch SHA, Java 정확한 버전, 컨테이너 image digest, CPU 아키텍처·할당량, Docker 메모리 제한, 모든 JVM 옵션, OTP 설정과 query 본문을 manifest로 보존한다.
- 모든 후보는 동일한 정상 Maven 빌드 절차로 만든다. 의존성 저장소 실패는 BLOCKED이지 build PASS가 아니다. class 주입 JAR은 최종 비교에 사용하지 않는다.
- 동일 호스트에서 OTP 한 대만 측정한다. 측정 중 다른 빌드·프로파일 작업을 동시에 실행하지 않는다. 호스트 메모리 압박·swap·열 상태/CPU 부하를 기록한다.
- 고정 graph, 고정 서비스 시각, 고정 realtime 상태를 사용한다. 외부 realtime 변화나 네트워크 수집이 섞이면 끄거나 동일 입력을 재생하고 이를 양쪽 모두에 적용한다.
- 원본 재실행으로 재현성을 확인한다. 자연적인 tie 순서 차이가 발견되면 후보 실행 전에 허용 범위를 문서화한다. 후보 불일치를 숨기기 위해 사후 허용 기준을 만들지 않는다.

### 검색 corpus와 부하

- 기존 4개 OD 좌표와 시각을 그대로 baseline corpus에 포함한다.
- 먼저 그래프에서 실제 지원하는 도보 단독, 버스, 지하철, 환승, 긴 구간, 경계 부근, 경로 없음 요청을 정합성 corpus로 확정한다. 기존 4개가 서비스 전체를 대표한다고 주장하지 않는다.
- 모든 동시성에서 동일한 OD 목록·동일한 요청 비율을 사용한다. `CASES[:concurrency]`는 제거한다.
- 단발 batch barrier 방식 대신 고정 크기 worker pool과 큐로 in-flight 요청 1·2·4를 유지하는 closed-loop 부하를 사용한다. workload seed와 순서를 보존한다.
- 지연은 실제 HTTP 전송부터 전체 응답 수신까지 측정한다. 큐 대기 시간은 별도 필드로 보존한다. 이 결과를 고정 RPS/open-loop 용량 보장으로 해석하지 않는다.
- 후보 선별: concurrency별 OD당 최소 20개 성공 요청. 최종 검증: concurrency별 OD당 최소 100개 성공 요청 × 최소 5개 독립 JVM 실행 쌍.
- 원본·후보 실행 순서는 AB/BA로 균형화하고 seed를 고정한다. 각 concurrency의 최종 run은 새 JVM에서 시작한다.
- 예열은 최소 100개 요청 후 동일 workload의 3개 연속 window p50 변동 5% 이내를 초기 기준으로 한다. 최대 10분 내 안정화하지 못하면 원인을 기록하고 미확정 처리한다. 예열 결과도 버리지 않고 별도 보존한다.

### 검색 결과 동등성

- 실제 GraphQL schema를 읽어 필드명을 확정한다. HTTP 200 외에도 GraphQL errors, routingErrors, null data, timeout, itinerary 수를 검증한다.
- 모든 요청·모든 itinerary를 저장/비교한다. mode, route ID, trip ID(제공 시), 승하차 stop ID, 출도착 시각, duration, 환승 수, leg 순서, 거리와 geometry를 schema 지원 범위에서 포함한다.
- 표시 이름만 같다고 동일 경로로 보지 않는다. schema에서 지원하지 않는 항목과 대체 검증법을 명시한다.
- 시간/ID/leg 구조는 정확 일치를 원칙으로 한다. 부동소수점 또는 동률 순위 허용은 원본 반복에서 관찰된 근거에 한해 사전 고정한다. geometry 단순화·노선 변경은 허용 오차로 숨기지 않는다.
- 성능 query와 확장 정합성 query는 역할을 구분하고, 각각 원본/후보에서 완전히 같은 query를 쓴다. 무거운 필드를 추가한 측정을 이전 간단 query의 지연과 직접 비교하지 않는다.
- 오류 응답의 짧은 시간을 성공 latency 집계에 섞지 않는다. 오류 원본은 모두 보존하고, 정상 경로 요청의 오류는 최종 gate 실패다. 의도된 경로 없음은 양쪽 의미적 응답 일치로 판정한다.

### 지표와 수집 구간

| 지표 | 정의 / 용도 |
| --- | --- |
| p50/p95, throughput | 전체 및 OD별. 원본 요청 로그에서 재계산 가능한 empirical nearest-rank quantile 사용 |
| peak RSS | Linux 컨테이너 내 OTP PID의 resident 메모리, 측정 시작~마지막 응답까지 100ms 간격 샘플링. 관측 peak임을 표기 |
| idle RSS / delta RSS | 동일 예열 이후 부하 직전 값 및 peak와 차이. delta만 줄어도 전체 메모리 합격이 아님 |
| container memory | cgroup memory.current/peak 등 가용 지표와 cache 구성. 프로세스 RSS와 섞지 않고 별도 보고 |
| heap used / after-GC heap | GC 시점과 연결해 보고. GC가 없으면 after-GC는 N/A. timed run에서 강제 GC 금지 |
| allocation bytes/op | JMH 등 allocation profiler로 대상 메서드 측정. 실제 서버 할당도 가용 계측 방법과 범위를 명시 |
| GC / CPU | timed interval의 횟수, 총/최대 pause, CPU time. lifetime 누적값을 검색 구간 값처럼 사용하지 않음 |

- 기본 지연/RSS run과 JFR·상세 프로파일 run은 분리한다. 각각 원본/후보의 계측 조건을 맞춘다.
- JFR allocation sample은 hotspot 추정용이며 정확한 전체 bytes/op로 둔갑시키지 않는다.
- graph load/build peak와 serve 검색 peak를 별도로 보고한다. 이번 필수 gate는 serve 검색이다.

## 5. Phase 0 — 증거·빌드 고정

**목표:** 원본과 현재 후보를 재현 가능한 상태로 준비한다.

작업:

1. source diff를 보존하고 기존 README·측정기·Java 변경·테스트를 읽는다.
2. graph hash, 설정, 서비스 날짜 지원을 검증하고 manifest/corpus를 확정한다.
3. 동일 도구 체인으로 아래 A/B/C/D의 정상 빌드 방식을 확보한다. 빌드 실패 시 의존성 문제와 성능 문제를 구분한다.
4. 기존 smoke 기록의 한계를 README에 명시한다. 이 계획의 완료 기준과 충돌하는 기존 완료 표현을 정정한다.

산출물: manifest, patch 분리 목록, 재현 빌드 명령, JAR hash, `handoffs/PHASE-0.md`.

완료 gate: 정상 빌드한 원본 A와 현재 후보 D 모두 같은 graph로 기본 검색 성공. 미완료 의존성이 있으면 BLOCKED.

## 6. Phase 1 — 측정기·정합성 gate 보완

**목표:** 틀린 검색 결과나 불공정한 부하로 개선을 주장할 수 없게 만든다.

작업:

1. 공통 계약의 workload, warmup, raw JSONL, 오류 판정, quantile, 전체 응답 diff를 구현한다.
2. raw row에 runId, variant, requestId, case, concurrency, start/end, latency, HTTP/GraphQL 상태, fingerprint를 기록한다.
3. 메모리 sampler와 GC log 수집, run 구간 마커를 구현한다. 누락 지표를 0으로 채우지 않는다.
4. 측정기 자체 테스트: HTTP 200+GraphQL errors, null, timeout, 부분 응답, fingerprint 변화, small sample p95, OD별 동일 요청 수, sampler 누락을 검증한다.
5. A 대 A 재실행으로 기준 노이즈와 원본 정합성을 확인한다. 원본 자체의 불안정성은 먼저 조사한다.

산출물: 수정 측정기/테스트, collector, A/A raw data·요약, `handoffs/PHASE-1.md`.

완료 gate: 정상·실패 결과를 자동 구분하고 모든 요청이 검증되며 지표를 raw data에서 재생성할 수 있음. 짧은 선별 run 통과는 최종 개선 합격이 아님.

## 7. Phase 2 — 기존 두 변경의 기여 분리

| Variant | 내용 |
| --- | --- |
| A | 원본 |
| B | TripPatternForDate.newWithFilteredTripTimes의 리스트 지연 생성만 |
| C | RaptorRoutingRequestTransitDataCreator.createTripPatternsPerStop의 2-pass 정확 크기 배열만 |
| D | B+C |

주요 파일은 OTP `application/src/main/java/org/opentripplanner/routing/algorithm/raptoradapter/transit/` 아래와 대응 test 경로다. 실제 메서드 위치·접근성을 확인하고 불필요한 public API 변경을 하지 않는다.

작업:

1. B 테스트: 변경 없음/전부 제외/첫·중간·끝 일부 제외, 여러 scheduled trip과 frequency 혼합, 원래 순서·중복 보존, predicate 평가 횟수 검증.
2. C 테스트: 빈 정류장, 빈 pattern, 한 정류장에 다수 pattern, 동일 pattern의 반복 stop, 입력 순서·중복 보존 검증.
3. 실제 입력 특성을 참고한 microbenchmark로 no-op 비율·제외 위치, 정류장 수·pattern 분포별 time/op와 allocation bytes/op를 비교한다. 결과 소비와 warmup/fork를 갖춘 기존 프로젝트 방식 우선.
4. A/B/C/D 선별 run으로 단일 검색 악화의 재현 여부를 먼저 확인하고, 시간 증가와 할당 감소의 교환 관계를 보고한다.
5. JFR CPU/allocation과 GC 근거를 연결한다. 2-pass 순회·추가 분기 비용이라는 가설을 프로파일 없이 사실로 단정하지 않는다.

산출물: 경계 테스트, benchmark, variant별 결과·채택 근거, `handoffs/PHASE-2.md`.

완료 gate: 각 변경의 채택/기각/미확정 판단과 증거가 있음. B/C/D 중 어떤 것도 필수 채택 아님. 전체 목표 미달이면 Phase 3으로 진행.

## 8. Phase 3 — 목표 미달분의 코드 최적화

**목표:** 할당량 감소에 그치지 않고 실제 RSS와 응답시간을 함께 줄일 후보를 찾는다.

작업:

1. 메모리를 retained graph/index, 요청별 temporary, native/JVM으로 구분하고 CPU의 실제 상위 비용을 확인한다.
2. Phase 2 변경으로 최종 목표를 충족할 가능성이 충분하면 Phase 4로 이동한다. 불필요한 추가 변경은 만들지 않는다.
3. 부족하면 프로파일이 가리키는 반복 계산·중간 컬렉션·중복 인덱스·불필요한 복사에서 한 가지 가설씩 구현한다.
4. 초기 탐색 후보는 불변 transit 구조의 요청 간 중복 생성 제거, 요청 내부 중복 계산 제거, primitive/compact 표현이다. 실제 hotspot이 아니면 구현하지 않는다.
5. 공유/재사용이 필요하면 날짜·요청 조건·realtime snapshot·동시성·무효화 경계를 증명한다. 무제한 전역 캐시, 큰 ThreadLocal 보유로 속도만 올리거나 사용자 간 상태를 공유하지 않는다.
6. 각 후보마다 예상 메모리 절감량의 상한, CPU 비용, 정합성 위험을 먼저 기록하고 원본/단독 후보 선별을 반복한다. 요청 할당량 변화만으로 GB 단위 RSS 감소를 예측하지 않는다.
7. 기존 후보 대비 좋아도 최종 원본 A 대비 gate를 만족해야 한다. 더 넓은 graph 구조 변경이 필요하면 별도 설계·호환성 검증 범위를 보고하고 승인 없이 확대하지 않는다.

산출물: 가설/프로파일 근거/patch/회귀 테스트/선별 결과, 최종 후보 선정 이유, `handoffs/PHASE-3.md`.

완료 gate: 최종 검증에 올릴 후보와 근거가 있음. 유효 후보가 없으면 NOT_MET, 필요한 다음 실험을 제시. 임의로 범위를 축소해 합격시키지 않음.

## 9. Phase 4 — 최종 동시 개선 검증

입력: Phase 0 manifest, Phase 1 검증기, Phase 2/3에서 선정한 후보, 원본 A.

작업:

1. 추가 최적화 없이 최종 후보를 고정하고 A 대 후보를 공통 계약의 표본수/독립 JVM/순서 교차로 측정한다.
2. concurrency 1·2·4와 OD별로 메모리·지연·오류·정합성·run별 개선율·불확실성을 모두 보고한다.
3. 별도 계측 run으로 GC/할당/CPU 변화가 지연·RSS 변화와 부합하는지 확인한다.
4. 원본과 후보에 동일 조건의 최소 30분 concurrency 4 soak를 각각 수행한다. 5분 window의 RSS/heap/GC/p95/오류 추세를 비교한다. 이 soak는 장기 누수 부재의 증명은 아니다.
5. 모든 필수 gate를 통과하면 VERIFIED. 하나라도 미달이면 NOT_MET, 측정 불안정/부족이면 INCONCLUSIVE, 환경 실패면 BLOCKED로 보고한다.
6. 최종 보고에 실패한 가설도 남긴다. 현재 원본 대비 결과만 주장하고 전국·다른 데이터·OTP 다른 버전으로 일반화하지 않는다.

산출물: 재현 명령, 최종 비교표, raw/profile 위치와 hash, 테스트 결과, 남은 한계, `handoffs/PHASE-4.md`.

전체 완료 gate: **§1의 정합성 + peak RSS 감소 + p50/p95 감소 + 반복 검증 + soak 조건이 전부 충족된 VERIFIED만 허용.** 조건 미충족 시 빌드/구현이 끝났어도 전체 작업 완료가 아니다.

### 2026-09-11 최신 실행 판정

최종 후보 H는 `TripPatternForDates`의 요청별 stop-major `arrivalTimes`/
`departureTimes` 대형 배열을 제거하고, 전역 trip index별 immutable `TripTimes[]`와
`offsetsByTrip`만 보유한다. 현재 작업트리에는 B/C/G를 제외하고 H+F만 반영했다.

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| H+F 후보 c1 paired gate | p50 `+7.79%` `[+1.26%, +16.54%]`, p95 `+20.54%` `[+6.32%, +25.75%]`, RSS `+41.27%` `[+36.87%, +47.40%]` | `/private/tmp/otp-a-h-c1-10pairs.json` |
| H+F 후보 c2/c4 paired gate | p50/p95/RSS 모두 목표 충족 | `/private/tmp/otp-a-h-c2-5pairs.json`, `/private/tmp/otp-a-h-c4-5pairs.json` |
| H+F shaded JAR correctness | 14건: 성공 8, 의도된 routing error 6, validation error 0, fingerprint mismatch 0 | `/private/tmp/otp-hf-current-corpus`, 원본 `/private/tmp/otp-a-a-corpus-new` |
| 이전 B/C/F/G/H smoke(탈락 조합) | c1/c2/c4 각각 1,600/1,600 성공했으나 c1 정식 paired에서 개별 OD 지연 회귀 확인 | `/private/tmp/otp-h-final-c1-smoke`, `c2-smoke`, `c4-smoke` |
| H+F 후보 장시간 안정성 | c4 16,000/16,000 성공, 31.5분, p50/p95 `499.042/685.075ms`, peak RSS `1,982,930,944B`, 최대 sampler gap `0.291s` | `/private/tmp/otp-h-c4-soak-30m` |
| 현재 H+F 빌드·회귀 | Maven shaded package 성공, 타깃 테스트 18개·영향 모듈 전체 8,225개 통과(실패/오류 0, skip 20), 측정기 테스트 33개·checkstyle 통과 | `otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar`; Maven `BUILD SUCCESS` |
| H+F 최종 후보 correctness 재확인 | 14건: 성공 8, 의도된 routing error 6, validation error 0, 원본 raw와 mismatch 0 | `/private/tmp/otp-hf-final-corpus-2`; baseline `/private/tmp/otp-a-a-corpus-new/requests.jsonl` |

paired gate와 30분 soak의 H+F 실행본은 `/private/tmp/otp-trip-times-candidate.jar`이며,
현재 작업트리의 H+F 주 코드와 모든 class entry가 동일하다(차이는
`otp-project-info.properties` 메타데이터뿐). B/C/G는 아래 c1 조기 탈락 후 실제
작업트리에서 제거했다.

현재 B/C/F/H c1 정식 3쌍(`/private/tmp/otp-bcfh-a-c1-pair1..3` ↔
`/private/tmp/otp-bcfh-h-c1-pair1..3`)은 모두 400/400 성공·RSS `OBSERVED`였다.
쌍별 전체 개선률(p50/p95/RSS)은 `(+6.96%, -4.40%, +44.35%)`,
`(+8.36%, +6.45%, +40.63%)`, `(+7.24%, -0.10%, +40.69%)`이며, 3쌍 중앙은
`(+7.24%, -0.10%, +40.69%)`이다. `cityhall-gangnam`은 p50
`(-7.76%, -5.71%, -6.39%)`, p95 `(-6.77%, +8.49%, -5.28%)`로 p50이
세 번 연속 5%를 넘게 악화됐다. 이는 §1의 개별 경로 회귀 기준 위반이므로
B/C/F/H 조합의 c1 gate를 `NOT_MET`으로 조기 판정한다. 모든 결과는 고정 수도권
graph, 고정 시각·corpus·Java 25·`-Xmx3500m` 조건에만 한정한다.

이전 `-Xmx3500m` 실험에서 최종 운영 후보로 H+F를 선택한 사실은 유지한다. 그러나
현재 PR 근거를 위한 `-Xmx10g` P4는 별도 계약이다. A feasibility 세 run이 모두
host swap-in으로 부적격이었고, 정식 A/HF c1·c2·c4 5 AB/BA pair(총 30 run)와
run-pair bootstrap은 시작하지 않았다. 따라서 계획의 현재 전체 상태는
`P4_FORMAL_PENDING`이며 `VERIFIED`로 올리지 않는다.

현재 P4에서 확정된 범위:

- 4-OD A reference/A-A 반복 fingerprint는 OD당 100건 mismatch 0이다.
- A/HF full-detail correctness는 네 고정 OD 각 1회, 4/4 성공, mismatch 0이다.
- 세 feasibility의 지연·RSS·GC 수치는 swap gate 실패로 성능 근거에 사용하지 않는다.
- formal 완료에는 swap-in/out delta 0인 controlled host의 feasibility 통과 후
  A/HF c1·c2·c4 각 5 AB/BA pair와 10,000회 run-pair bootstrap이 필요하다.

상세 실행 계약·무효 run 판정·Linux `/proc/vmstat` 기록 방법은
[handoffs/LARGE-HEAP-AGENT-PLAN.md](handoffs/LARGE-HEAP-AGENT-PLAN.md) §P4를 따른다.

현재 작업트리 H+F 산출물 SHA-256:
`otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` =
`54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f`.

## 10. Handoff 양식과 다음 에이전트 지시문

```markdown
# Phase N handoff
- Phase 상태: COMPLETE / PARTIAL / BLOCKED
- 전체 목표 상태: NOT_EVALUATED / NOT_MET / INCONCLUSIVE / VERIFIED
- 작업 위치·branch·base commit·patch/JAR/graph hash:
- 읽은 선행 산출물:
- 수행 변경과 미수행 항목:
- 실제 실행 명령·환경·runId·시간 구간:
- 테스트 및 정합성 결과:
- 메모리·지연·GC·allocation 결과와 raw data 경로:
- 기존 기준과 비교, 가설 채택/기각 근거:
- 다음 Phase 입력·재현 절차·차단 요인:
```

에이전트에게 전달할 요청 예시:

> docs/plan/otp-code-optimization/PLAN.md를 전부 읽고 Phase N을 수행하라. 실제 구현 저장소는 /Users/wa/golbob/개인/OpenTripPlanner다. 선행 handoff와 현재 작업 지침·git 상태를 확인하고 기존 변경을 보존하라. 배정 Phase 밖의 배포·커밋·PR은 하지 마라. 검색 범위·결과·설정 축소 없이 메모리와 응답시간의 동시 감소가 최종 목표다. 측정/검증되지 않은 개선을 완료로 표현하지 말고, Phase별 산출물과 handoff를 남겨라.
