# 발송 장애 주입 끔/켬 (#464) — 실행 계획

티어 M. 운영 동작 변경 없음(테스트 전용 플래그 기본 false + 하네스). 기준 origin/main 10f081c9.

## 코드
- `JourneyNotificationProperties.Relay.loadtestUnfenced`(기본 false). `validateRelay`: true 인데 sender≠loadtest 면 부팅 거부
- `JourneyNotificationRepository.findAttemptCount(id)`
- `JourneyNotificationOutboxStateService.writeToken(claim, op)`: 플래그 꺼짐 → claim.token(). 켜짐 → 지금 attempt_count, 다르면 `journey.notification.loadtest.unfenced-late-write{op}` 증가. markSent(claim, task)·markTransientFailure·markSkipped·markPermanentFailure 가 사용
- 테스트: `JourneyNotificationPropertiesTest.unfencedOnlyWithLoadtestSender`, `JourneyNotificationLateWriteIT.unfencedLateTransientFailureRequeuesReclaimedRow`(같은 장면에서 끔이면 PENDING 으로 되돌아감 = 대조군)

## 하네스
- `load/ladder/run_relay_fault.sh <이름>`: run_relay_capacity.sh 기반, worker A(8081)·B(8082), 일정 속도 적재, FAULT_AT 초마다 A 에 KILL(kill -9 → 8초 뒤 재기동) 또는 STOP(kill -STOP → STOP_S 뒤 kill -CONT). 접수 기록은 worker 별 TSV, 행 덤프에 requeue_count·last_requeued_at
- `load/ladder/relay_fault.py`: 잃음, 행별 접수 횟수(3번 이상 = 추가 발송), 장애 → 회수 시각, 재접수 시각, stale-write·unfenced-late-write, heartbeat UPDATE 의 DB 시간 몫

## 칸 (각 3회, 끔/켬 번갈아)
| 칸 | 장애 | 설정 |
|---|---|---|
| HB-OFF / HB-ON | 120초에 A kill -9, 8초 뒤 재기동 | 회수 기준 180 / 45초, 20건/초 × 420초, FCM 2~5초, TTL 180초 50%·600초 50% |
| FENCE-OFF / FENCE-ON | 60·200·340초에 A kill -STOP 60초 | 증서 끔 / 켬, 회수 45초, 8건/초 × 420초, FCM 5~15초, UNAVAILABLE 30%, TTL 600초 |

## 결과 문서
`load/ladder/RELAY-FAULT.md`: 결론 첫 줄, 칸별 표, 한계(노트북, 프로세스 안 가짜 FCM, 끔 칸은 상태 조건 유지라 하한).
