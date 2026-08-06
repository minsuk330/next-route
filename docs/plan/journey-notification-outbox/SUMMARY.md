# 여정 알림 큐 (Phase 5 ②, L)

> **구현 완료**(2026-08-06). codex 설계 리뷰 반영 내역은 [PLAN.md](./PLAN.md) §12,
> 구현하며 플랜에서 벗어난 5가지는 §14.

## 뭘 하나

환승 상태가 나빠진 순간(탈 수 있음→놓칠 듯, 놓칠 듯→놓침)에 알림 한 건을 새 표
`journey_notification`(V40)에 쌓고, 별도 루프가 꺼내 보낸다. 실제 발송(FCM)은 다음 PR 이라
이번엔 아무것도 안 보내는 가짜 sender 를 끼운다. 여정이 끝나면 안 보낸 알림은 취소한다.

## 왜

Phase 4 로 서버는 환승 위험을 알지만 그 판단이 DB 행에만 적히고 아무 데도 안 나간다. 평가기
트랜잭션 안에서 바로 FCM 을 부르면 응답 기다리는 동안 DB 커넥션을 붙잡는다(Phase 3 문제).

## 핵심 결정

- **나빠질 때만 알림.** 좋아지거나 제자리면 화면만 갱신. 기억은 V35 가 비워 둔
  `transfer_connection.last_notified_status` 에 — 평가기가 10초마다 도는데 매번 보내면 소음
- **enqueue 는 평가기 쓰기 트랜잭션 안, 소유권 확인 뒤.** 마커와 알림 행이 함께 커밋돼야 한다 —
  나뉘면 "마커만 찍힘" = 그 환승은 영영 알림 없음
- **체크포인트 이름에 상태를 담고**(`TRANSFER_RISK_AT_RISK`/`_MISSED`), **새 알림이 낡은 것을
  밀어낸다.** 안 하면 재시도로 순서가 뒤집혀 "놓쳤다" 다음에 "놓칠 것 같다"가 온다
- **⑤·⑥ 이 쓸 `commandVersion`·`evaluationVersion` 을 지금 넣는다.** 그 PR 들에 마이그레이션이
  없어서, 나중에 붙이면 이미 쌓인 행의 기본값을 소급 결정해야 한다
- **relay 는 버스 것을 복제**(공유 추상화 기각). 일반화하면 살아 있는 토스 발송 경로 파일을 고치게
  되고 Phase 5 완료 조건이 그걸 금지

## 건드는 곳

신규: `V40__create_journey_notification.sql`, `domain/journey/entity·repository`(알림 6개),
`application/journey/{port/out,dto,service,config,observability}`(15개),
`infrastructure/adapter/{out/notification,in/scheduler/journey}`(3개)
수정: `JourneyEvaluationService`·`JourneyEvaluationScheduleWriter`·`TransferConnection`·
`TransferStatus`·`TransferRiskPolicy`·`JourneyCommandService`·`JourneyStartTxService`·
**`JourneyLeaseExpiryService`**·`.gitignore`·`application.yaml`·`JourneySchemaIT`

## 리스크

- **만료 배치가 지금 트랜잭션 없이 돈다**(codex 가 잡고 코드로 확인). `sweep()` 이 같은 클래스
  메서드를 직접 불러 `@Transactional` 이 안 걸린다. SQL 한 문장이라 우연히 안전했을 뿐 — 취소
  쿼리를 더하면 깨진다. 이 PR 이 먼저 고친다(Step 4.5)
- 평가기 쓰기 트랜잭션에 코드를 넣는다. 알림이 예외를 던지면 여정 평가가 통째로 실패 — 키 충돌은
  SQL 안에서 흡수하고, DB 를 읽을 수 있는 채널 결정은 트랜잭션 밖으로 뺐다
- **발송은 "최소 한 번"이다.** provider 에 넘긴 직후 죽으면 회수 후 또 간다. 원자적으로 묶을 방법이
  없어 계약으로 못박고 IT 로 재현한다 — ⑥ 이 payload 에 상태를 안 담아 중복의 해가 작다

## 검증법

`./gradlew test` 전부 통과 + 버스 경로 회귀. 핵심 5개: ①AT_RISK 로 10초씩 6번 재계산해도 1행
②놓칠 듯→놓침은 2행(키 다름)이고 앞 행은 취소됨 ③claim 뺏기면 0행 + 마커 그대로 ④여정 종료
시 PENDING 만 취소되고 발송 중인 것은 무손상 ⑤만료 경로 IT 가 `expireBatch()` 가 아니라
`sweep()` 을 부른다(트랜잭션 문제를 잡는 유일한 방법).
