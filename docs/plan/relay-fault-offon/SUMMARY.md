# 발송 장애 주입: heartbeat 회수와 fencing token 끔/켬 (#464)

## 뭘 하나
worker 두 대에 아웃박스를 일정 속도로 직접 적재하면서 A 에 장애를 넣는다. 강제 종료(kill -9)로 회수 기준 180초 대 45초(heartbeat)를, 일시 정지(kill -STOP 60초)로 증서 끔 대 켬을 비교한다.

## 왜
포폴 2-1 의 "유실 3→0" 은 PULL·재시도 변경이 섞인 1회 값이고, fencing 은 부하 실험에서 작동한 적이 없다(kill 은 원 워커를 죽여 늦은 기록이 안 생김).

## 핵심 결정
- 회수 기준 180초 칸은 코드 우회 없이 유효시간 바닥 설정만 600초로 올려 부팅 검사를 통과(적재기가 expires_at 을 직접 씀)
- 증서 끔 = 테스트 전용 플래그 `relay.loadtest-unfenced`(sender=loadtest 아니면 부팅 거부). 기록 때 지금 시도 번호를 증서로 써 #451 이전 조건 재현, 상태 조건은 유지라 결과는 하한
- 정지 칸은 FCM 5~15초·일시 실패 30% 로 늦은 기록이 B 의 발송과 겹치게 함
- heartbeat 대가는 끔 칸 없이 pg_stat_statements 의 heartbeat UPDATE 몫으로 직접 잼

## 건드는 곳
JourneyNotificationProperties, JourneyNotificationOutboxStateService, JourneyNotificationRepository, JourneyNotificationMetrics, JourneyNotificationLateWriteIT, JourneyNotificationPropertiesTest, load/ladder/run_relay_fault.sh, relay_fault.py, RELAY-FAULT.md

## 리스크
- 노트북 한 대, 가짜 FCM 은 프로세스 안 sleep
- 정지 칸의 피해 건수는 주입 조건(FCM 지연, 실패율, 정지 길이)에 좌우된다

## 검증법
관련 테스트 통과(늦은 기록 IT 에 끔 대조군 추가). 칸 4개 × 3회 측정 뒤 RELAY-FAULT.md 에 칸별 잃음, 추가 발송, 회수 시각, heartbeat 몫 기록.
