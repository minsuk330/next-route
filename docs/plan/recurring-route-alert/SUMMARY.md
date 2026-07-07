# 목표 도착 알림 반복(요일) 기능

## 뭘 하나
목표 도착 출발 알림(v2)에 **반복(요일 선택)** + **일회성**을 추가. 날짜 없이 시각(HH:mm)만 입력, 요일 선택 시 매주 발송. 기존 occurrence 상태머신은 그대로 재사용.

## 왜
현재는 절대 datetime 단발(1회 발송 후 종료)뿐. 사용자가 "매주 월/수/금 08:00" 같은 반복 통근 알림을 원함.

## 핵심 결정
- 신규 `RouteAlertSchedule`(구독규칙) → spawner가 occurrence 생성. 기존 alert 상태머신 무변경 (재무장 방식은 dedup 유니크 충돌로 기각).
- occurrence마다 새 alert id + 부분 유니크 `(schedule_id, target_arrival_at)`로 중복 방지 (다중 인스턴스 무관).
- weekdays 저장 = 정수 bitmask (bit0=월…bit6=일). API는 요일코드 배열 ↔ mask 변환.
- 첫 occurrence는 spawn window(≤6h)내면 즉시 생성, 밖이면 spawner 위임 (days-early baseline 방지).
- 기존 절대-datetime API 제거 (미배포라 하위호환 불필요). spawner flag off 기본.

## 건드는 곳
RouteWeekdays, RouteAlertSchedule(+Status enum), RouteAlertScheduleRepository, RouteArrivalAlert(scheduleId), RouteArrivalAlertRepository, RouteArrivalAlertService, RouteAlertScheduleSpawner, RouteAlertRequest/Response, 3 UseCase 포트, RouteAlertController, RouteArrivalAlertProperties, V25 마이그레이션.

## 리스크
- 좌표 컬럼 naming (startx/endx) — V23 부팅실패 교훈, V25 재확인 필수.
- `processScheduleSpawn` 트랜잭션 경계 (self-invocation 프록시 회피 — 별도 빈 필요).
- 즉시생성 실패 시 schedule rollback 여부 (일회성 FAILED / 반복 ACTIVE 유지 의미).

## 검증법
단위테스트(nextOccurrence·service·spawner) 통과 + 로컬 DB 부팅 validate(엔티티↔V25). flag off라 머지 무영향, 스테이징서 flag on + 반복 2주기 e2e.
