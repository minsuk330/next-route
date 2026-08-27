# 0001. 탈퇴는 발송 자산만 지우고 여정 기록은 남긴다

- 상태: 채택
- 날짜: 2026-08-27

## 맥락

탈퇴(계정 삭제)는 `users` 를 참조하는 자식 테이블 21개를 건드려야 한다. 이들은 성격이
갈린다 — 어떤 것은 남으면 탈퇴한 사람에게 계속 푸시를 쏘고, 어떤 것은 그냥 지난 이동 기록이다.

전부 물리 삭제하면 두 가지가 걸린다.

첫째, 이 저장소는 물리 삭제를 한 번도 하지 않는다. 모든 테이블에 `deleted_at` 이 있고,
삭제는 그 값을 찍는 것이며, 조회는 모두 `deleted_at IS NULL` 을 명시하는 관례로 통일돼 있다.
물리 삭제는 이 저장소가 검증한 적 없는 동작이다.

둘째, 물리 삭제는 부모→자식 역순으로 정확히 지워야 한다. `journey_session` 아래로 자식이
2단 깊이로 매달려 있어서, 앞으로 자식 테이블이 하나 추가될 때마다 탈퇴 코드가 조용히 깨진다 —
그 깨짐은 누군가 실제로 탈퇴를 호출해야만 외래키 위반으로 드러난다.

## 결정

두 부류로 가른다.

**물리 삭제** — 남으면 탈퇴자에게 무언가가 나가거나, 재가입을 막거나, 개인정보가 남는 것:
`auth_identity`, `user_push_token`, `subway_live_activity_token`, `subway_ring_push_outbox`,
`route_arrival_alert`(+`route_arrival_alert_notification`), `bus_arrival_alert`(+`bus_alert_notification`),
`route_alert_schedule`, `route_search_history`, `favorite_route`(집·회사 좌표),
`route_search_log`(검색 이력+응답 본문). 그리고 `users.toss_user_key` 를 NULL 로 비우고
`TossUserToken` 을 지운다.

**소프트 삭제**(`deleted_at` 만 찍음) — 지난 이동 기록·측정 자산:
`journey_session` 과 그 아래 여정 계열(`journey_leg`, `journey_leg_route_option`,
`transfer_connection`, `journey_event`, `journey_revision`, `journey_telemetry_event`,
`reroute_proposal`, `subway_binding_event`).

**손대지 않음** — 자체 마이그레이션이 삭제(소프트 포함)를 금지한 표:
`journey_notification`(V40: 지우면 `delivery_key` 가 다시 열려 재발송), `subway_leg_binding`
(V44: 지우고 새로 만들면 `version`·`reset_generation` 이 0 으로 돌아가 늦게 온 갱신이 되살아남).
이 두 표는 `journey_session.deleted_at` 이 읽기·발송 경로를 이미 막으므로(둘 다 due 쿼리가
`journey_session` 을 조인해 `deleted_at IS NULL` 을 본다) 건드리지 않아도 "다시 못 보고 안 울린다"가
성립한다.

`users` 자신은 소프트 삭제한다. 자식이 그 행을 외래키로 물고 있어도 위반이 나지 않고,
소프트 삭제된 사용자는 인증을 통과하지 못하도록 세대 번호 검증 지점에서 `deleted_at` 을 함께 본다.

발송 주소(`user_push_token` 등)는 원저자 주석이 "탈퇴 시 soft delete 하라"고 적어 두었으나,
그 방향은 채택하지 않는다. 소프트 삭제해도 발송 경로가 그 행을 다시 집어 쏠 위험이 남기 때문에,
발송 주소는 물리적으로 지운다.

## 결과

- 탈퇴는 외부 호출이 없는 순수 DB 트랜잭션 하나가 된다. Live Activity 종료 푸시는 넣지 않는다 —
  발송 플래그가 켜져 있어도 종료 푸시 경로(`subway_ring_push_outbox` → 릴레이)는 토큰이 살아
  있어야 동작하는데 탈퇴가 그 토큰을 지우므로 양립하지 않는다. 잠금화면 위젯은 앱이 로컬로
  그리다 스스로 만료된다.
- 탈퇴가 "모두 삭제"가 아니라 "다시 못 보고, 다시 안 울린다"가 된다. 개인정보를 물리적으로
  완전히 파기하겠다는 약속은 하지 않는다. 규제 요건이 물리 파기를 요구하면 이 결정을 다시 연다.
- 자식 테이블이 새로 생겨도 조용히 깨지지 않도록, `information_schema` 로 외래키 그래프를 읽어
  물리 삭제 대상엔 행이 0, 소프트 삭제 대상엔 모든 행에 `deleted_at` 이 찍혔는지 확인하는
  테스트를 둔다. DB 스키마가 진실의 원천이라 사람이 목록을 갱신하는 것을 잊어도 낡지 않는다.
