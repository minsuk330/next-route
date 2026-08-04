# 여정 만료 처리 배치 (M)

## 뭘 하나

worker 컨테이너에서 30초마다 돌면서, 앱에서 연락이 끊긴 여정을 `EXPIRED/CLIENT_LEASE_EXPIRED` 로 정리한다. 접는 동시에 `next_evaluation_at` 을 비워 평가 대상에서도 뺀다.

## 왜

지금은 여정을 접는 주체가 사용자뿐이다. 앱을 지우거나 배터리가 끊겨도 그 여정은 영원히 ACTIVE 로 남는다. 사람당 ACTIVE 여정은 하나라서 다음 여정을 시작할 수도 없고, 다음 PR 에서 주기 평가가 붙으면 죽은 여정에 계속 외부 API 를 쓰게 된다.

## 접는 기준

세 조건을 모두 지나야 접는다.

| 조건 | 값 |
|---|---|
| lease 만료 | `guidance_lease_until` 이 지남 (3분) |
| 유예 | 그 뒤로 2분 더 지남 (SC-3 1항) |
| 지하철 보호 | `subway_offline_protection_until` 이 없거나 지남 (SC-12 4항) |

유예를 두는 이유 — iOS·Android 는 정상 동작 중에도 background callback 이 늦게 도착한다. lease 만 보고 접으면 잘 쓰고 있는 사용자의 여정이 끊긴다.

## 핵심 결정

- **고르기와 바꾸기를 SQL 한 문장으로 한다.** `SKIP LOCKED` + `LIMIT` + `RETURNING` 하나로 claim 과 상태 전이를 끝낸다. 골라 놓고 애플리케이션에서 하나씩 바꾸면 그 사이에 다른 인스턴스가 같은 여정을 두 번 접는다. `BusAlertNotificationRepository.claimBatchForProcessing` 과 같은 모양이다
- **유예를 뺀 시각을 파라미터로 넘긴다.** 쿼리 안에서 `guidance_lease_until + 유예 < now` 로 쓰면 `idx_journey_expiration` 을 못 쓴다. `guidance_lease_until < (now - 유예)` 로 비교해 인덱스를 그대로 훑는다 → **마이그레이션 없음**
- **`next_evaluation_at` 을 같은 문장에서 비운다.** 두 문장으로 나누면 이미 끝난 여정에 외부 API 를 쓰는 틈이 생긴다
- **트랜잭션은 이 SQL 한 문장만 감싼다.** 외부 호출도, 다른 계산도 넣지 않는다
- **worker 프로필 + 끌 수 있는 플래그.** api 컨테이너에서도 돌면 사용자 요청용 스레드·DB 커넥션을 배치가 나눠 쓴다. `journey.navigation.expiry.enabled=false` 로 즉시 멈춘다
- 판정 기준은 `JourneyNavigationProperties`, 훑는 속도는 새 `JourneyExpiryProperties`. 부하 조정이 만료 계약 변경이 되지 않게 나눴다

## 건드는 곳

```
domain/journey/repository/JourneySessionRepository.java            (claimExpiredBatch 추가)
application/journey/config/JourneyExpiryProperties.java            (신규)
application/journey/service/JourneyLeaseExpiryService.java         (신규)
infrastructure/adapter/in/scheduler/journey/JourneyLeaseExpiryScheduler.java (신규)
application.yaml                                                    (journey.navigation.expiry 블록)
```

마이그레이션 없음 — V37 의 `idx_journey_expiration` 으로 충분하다.

## 검증법

`JourneyLeaseExpiryIT`(7) — 유예까지 지나면 접힌다, `next_evaluation_at` 이 비워진다, 유예 안이면 기다린다, 지하철 보호 중이면 기다린다, 보호까지 지나면 접고 보호 시각도 지운다, 이미 끝난 여정은 안 건드린다(멱등), **두 워커 동시 실행 시 claim 이 겹치지 않는다**.
`JourneyLeaseExpirySchedulerTest`(2) — tick 위임, 실패 삼킴.

## 하지 않는 것

- 대기 중인 알림 취소 — 여정 알림 테이블 자체가 Phase 5 다. 알림이 생기면 이 배치에 한 줄 추가한다
- 만료를 `journey_event` 에 남기기 — `journey_event` 는 `eventId` 를 가진 앱 명령의 이력이다. 서버가 접은 사실은 `end_reason` 이 말한다

## 리스크

지하철에서 앱을 강제 종료하면 보호 시각까지(최대 60분) ACTIVE 로 남는다. SC-12 가 수용한 리스크다. 만료가 과하게 접는 상황은 `journey.lease.expired` 카운터로 보고, 필요하면 플래그로 끈다.
