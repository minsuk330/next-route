# 버스 알림 Outbox 점진 적용

## 뭘 하나
버스 알림 발송을 측정된 문제 단위로 단계 적용: (A) atomic claim → (B) outbox 테이블+send-after-commit
→ (C) relay 병렬화 → (D) 스케줄러 pool 분리 → (E) 버스 API 캐시 재사용. 각 단계 독립 PR.

## 왜
현재 claim 이 read-then-write(비원자) → 동시성 시 중복 발송(IT로 재현). 발송이 구독 상태에 결합(dual-write).
단일 worker 직렬 발송 병목(load IT). 발송 채널 추가 예정이라 outbox 별도 테이블로 분리.

## 핵심 결정
- atomic claim 을 outbox 에서 분리 = step A 독립 PR (concurrency IT red→green, 최소 변경). 나머지는 후속
- 별도 outbox 테이블 채택 — 근거는 다중 채널 확장. dedup_key=`BUS_ARRIVAL:{alertId}:{eventType}:{channel}`(채널별 row)
- Step B: polling 은 enqueue 만, Toss 호출 0 → polling 이 외부 지연서 분리. 발송은 relay. 병렬화는 C로 분리
- Step C: claim 은 이미 원자(SKIP LOCKED)라 병렬화 대상은 느린 Toss I/O 만. relay 가 claim 한 배치를 bounded 워커 풀(outboxRelayWorkers)로 팬아웃. workers≤1 이면 인라인(B 동치). throughput IT: 순차 1239ms→병렬 146ms(8.49x), Toss 24회 중복 0
- Step D: 알림 5개 @Scheduled 잡을 전용 풀(noti-sched-, pool 2)로 격리 — @Scheduled(scheduler="notificationTaskScheduler"). 공용 풀(size3) 포화가 도착 알림 밀지 않게. 함정: Boot 자동설정이 @ConditionalOnMissingBean(TaskScheduler)라 공용 풀도 @Primary 로 명시 재정의. isolation Test 로 포화 공용 뒤 알림 즉시 실행 증명
- Step E: 발송조건 판단(processGroup)의 도착조회를 실시간 관측 캐시 우선(miss 시 provider+prime)으로 — collector/검색과 캐시 공유해 busApiCalls 절감. cacheReadEnabled flag off 기본. BusRealtimeCacheRead/WritePort 직접 주입(검색 quota/circuit-breaker 결합 회피, BusApiPort 경로 유지). unit test: N사이클 같은 그룹 → busApiCalls 1회(hit 재사용) vs off=N회
- enqueue 는 단일 SQL(CTE claim + INSERT ON CONFLICT), 선확인/예외흡수 금지 (codex)
- 각 단계가 측정 근거를 가짐: A=IT, C=load IT 처리량, E=busApiCalls. 부하테스트는 병목(C/D)용, outbox 정당화 아님

## 건드는 곳
Step A: BusArrivalAlertRepository(claimForProcessing) / BusAlertStateService(claim) / BusAlertAtomicClaimIT(신규).
후속: bus_alert_notification 엔티티·repo, enqueue/relay 서비스, RelayScheduler, 스케줄러 config, collector 캐시 재사용.

## 리스크
- Step A: claim 시맨틱 불변이라 회귀 낮음. 마이그레이션 없음
- Step B flag 재-on 시 이중 발송 → route PR#59 의 절차 준수(잔존 outbox 정리)
- 부하/병목 서사와 정합성 서사 혼동 금지(도구·정당화 분리)

## 검증법
`./gradlew test` (Testcontainers). Step A = BusAlertAtomicClaimIT 로 동시 N worker → 단일 발송 단정.
기존 무관 실패(PR#58 AdminProperties WebMvcTest, Gong) 제외.
