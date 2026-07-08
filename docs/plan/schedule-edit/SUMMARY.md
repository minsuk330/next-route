# 알림 스케줄 수정(edit)

## 뭘 하나
기존 반복/일회성 알림 구독의 시간/요일/repeat/lead/buffer를 수정하는 `PUT` 추가. 좌표는 불변. 수정 시 진행 중 occurrence를 발송 여부·repeat에 따라 재처리.

## 왜
현재 구독은 생성/조회/취소만 가능. 사용자가 통근 알림 시각·요일을 취소 없이 바로 고치고 싶어 함.

## 핵심 결정
- 발송 전 occurrence → `SUPERSEDED`+soft-delete+재생성 (미발송 폐기, CANCELED와 구분).
- 발송 후: repeat=true=유지(다음 주부터) / one-time=DONE+soft-delete+재생성 (one-time은 항상 새 1회 보장, 같은 target 재발송 수용).
- soft-delete로 부분유니크 `(schedule_id,target)` 슬롯 해제 (재생성 충돌·조기 COMPLETED 방지).
- dispatcher guard 추가: claim에 alert ACTIVE 조건 + markDepartureSent inactive no-op (supersede된 건 발송 차단).
- update tx1 / spawn tx2 분리 (create와 동일, rollback 격리).

## 건드는 곳
RouteAlertStatus, RouteArrivalAlert, RouteAlertSchedule, RouteAlertUpdateRequest, UpdateRouteAlertUseCase, ScheduleNotEditableException, RouteArrivalAlertService, RouteAlertController, RouteArrivalAlertNotificationRepository, RouteAlertDispatchStateService, V26 마이그레이션.

## 리스크
- markDepartureSent/markChangeSent inactive를 cancel 안 하면 PROCESSING 방치→reclaimStale 반복발송(codex#1).
- update가 spawn 백오프 메타 리셋 안 하면 tx2 재spawn 조용히 skip(codex#2).
- one-time 발송후 DONE에 soft-delete 누락 시 유니크 충돌→"새 1회 보장" 붕괴.
- 잔여 race: edit 직전 claim된 PROCESSING 푸시는 회수 불가(accepted, 문서화).

## 검증법
단위(update repeat/one-time × 발송전/후, 404/409, dispatcher guard) 통과 + 로컬 DB 부팅 validate(enum↔V26). 기존 create/spawn/dispatch 회귀 없음.
