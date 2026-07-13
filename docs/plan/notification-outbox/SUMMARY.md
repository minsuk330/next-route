# 알림 발송 정합성 Outbox 보강

## 뭘 하나
경로 알림 v2 outbox를 완성형으로(SKIP LOCKED 배치 claim + DEAD 상태 + admin requeue),
버스 알림은 발송을 별도 outbox 테이블로 전면 분리(flag 뒤, 기본 off).
crash window별 장애주입 IT + 전달 보장 문서(at-least-once 선언)까지가 산출물.
+독립 PR-5(S): 알림 전용 TaskScheduler 격리 — 풀 3개/스케줄러 15개 경합으로 알림 밀림 방지.

## 왜
발송 = DB 변경 + 토스 호출의 dual-write. 버스 claim은 비원자(read-then-write)라 다중 인스턴스 시 중복 발송.
토스 멱등키 미지원이라 exactly-once 불가 → 보장 수준을 명시적으로 선언·검증·문서화.

## 핵심 결정 (codex 크로스체크 반영 완료)
- 보장 수준 = at-least-once + dedup_key unique + 원자 claim. "발송 후 mark 전 crash = 중복"은 제거 불가로 문서화
- v2 테이블과 통합 안 함(FK 다형성·상태머신 상이·회귀 반경) — 버스 전용 테이블 미러 + 코드 레벨 공통화(OutboxRetryPolicy)만
- DEAD(재시도 소진)를 FAILED(permanent 종결)와 분리. requeue는 attempt 보존 기본 + requeue cap (codex: 리셋안 기각)
- 버스 enqueue = 단일 SQL(CTE claim + INSERT ON CONFLICT DO NOTHING) (codex: 선확인+예외흡수안 기각 — rollback-only 함정)
- 버스 dedup_key = alertId 단독 (one-shot 계약). bus mark류 전부 부모 재검증(취소 경합 가드)
- flag on 시 구독 reclaim 비활성 + off→재-on은 잔존 outbox 정리 절차 강제 (안 지키면 이중 발송)

## 건드는 곳
V27·V28 마이그레이션 / RouteNotificationStatus·RouteArrivalAlertNotification(+repo) /
RouteAlertDispatchStateService·Scheduler / BusAlertDispatchService·BusAlertStateService /
신규: bus_alert_notification 엔티티·repo, OutboxEnqueue·OutboxState 서비스, RelayScheduler,
admin 컨트롤러 2개, OutboxRetryPolicy, IT 2벌, docs/notification-delivery-guarantees.md

## 리스크
- 경로 v2 claim 방식 교체 회귀 → stale 가드 4조건 SQL 1:1 이전 + 조건별 IT, flag로 발송 중지 가능
- 버스 flag 재-on 시 잔존 outbox와 구 경로 이중 발송 → 정리 절차 강제로 해소(문서 명시)
- SKIP LOCKED native 쿼리의 tx 경계 실수 → 함정 목록으로 고정(claim 커밋 후 mark), PG17 의미론 IT 선검증

## 검증법
`./gradlew test` (장애주입 IT 포함: 동시 claim 단일승자, crash 후 재발송 1회·유실 0, 중복 실증).
로컬 compose flag on E2E 발송 확인. prod는 flag off 배포 → 저트래픽 flag on.
