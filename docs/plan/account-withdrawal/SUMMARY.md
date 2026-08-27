# 회원 탈퇴

## 뭘 하나
`DELETE /api/auth/me` 로 사용자가 직접 계정을 탈퇴한다. 발송 자산·로그인 수단은 물리 삭제,
여정 기록은 소프트 삭제한다. 되돌리기·유예 없음. 재가입하면 새 계정.

## 왜
지금 계정을 끝내는 통로가 로그아웃·토스 연결끊기뿐이라, 앱 사용자가 스스로 탈퇴할 방법이 없다.

## 핵심 결정
- 삭제 경계 = 3버킷: 물리13(발송·알림·로그인·개인이력)/소프트9(journey 기록)/손대지않음2(삭제금지 계약 테이블). ADR-deletion-boundary.
- `users` 는 소프트 삭제 + 세대번호 증가. 인증 지점이 `deleted_at` 을 보게 해 토큰 즉시 무효화.
- 외부 호출 0, 트랜잭션 1개. 마이그레이션 0. Live Activity 종료 푸시 미포함 (토큰 삭제와 양립 불가).
- 자식 테이블 신설 시 조용한 깨짐 방지: FK 그래프 읽는 스키마 테스트(현재 미등록도 즉시 잡음).
- codex 반영: favorite_route·route_search_log 누락 추가, journey_notification·subway_leg_binding 은 삭제금지 계약이라 손대지않음, users 는 벌크 update(clearAutomatically detach 함정 회피).

## 건드는 곳
PR1: `UserRepository`, `UserTokenGenerationAdapter`, `SessionService`.
PR2: `WithdrawUseCase`(신규), `WithdrawService`(신규), `WithdrawController`(신규), 도메인 repository 벌크 메서드(물리13/소프트9), `WithdrawalDeletionBoundaryIT`(신규). `CONTEXT.md`+ADR 는 PR1 에 동봉.

## 리스크
- 벌크 update/delete 는 영속성 컨텍스트 우회 → `clearAutomatically` 누락 시 스테일. 테스트로 가드.
- 소프트 삭제 대상 조회 경로가 `deleted_at IS NULL` 빠뜨리면 탈퇴자 데이터 노출 → 스키마 테스트가 대칭 검증.

## 검증법
`WithdrawalDeletionBoundaryIT` 통과 = 물리 대상 행 0 + 소프트 대상 전부 deleted_at 찍힘 + FK 그래프에 미등록 테이블 없음. PR1 세대가드 테스트 통과. 컴파일 + 관련 test green.
