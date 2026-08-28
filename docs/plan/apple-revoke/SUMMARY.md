# Apple 로그인 토큰 폐기(revoke)

## 뭘 하나
애플로 로그인한 사용자가 탈퇴하면 애플에 "연결 끊어라"를 HTTP 한 번(revoke) 알린다.
그러려면 로그인 때 authorization code 를 애플과 교환해 refresh_token 을 받아 저장해 둬야 한다.

## 왜
App Store 심사 지침 5.1.1(v). Sign in with Apple 계정 삭제 시 revoke 는 애플이 요구하는 절차.
지금은 로그인이 identity token 검증만 해서 revoke 에 필요한 refresh_token 이 없다.

## 핵심 결정
- **revoke = 동기 best-effort, 삭제 tx 앞에서 1회**. 실패해도 탈퇴는 성공. outbox·재시도 없음(실사용자 0, 저빈도라 과함)
- **WithdrawService 내부 불변**. revoke 는 그 상위(tx 밖)에 둬 "외부호출 0·단일 tx" 계약 유지
- **로그인 code 필수(APPLE)** — code 없는 애플 로그인 거부(실사용자 0이라 안전)
- **refresh_token = 별도 엔티티 AppleUserToken + AES-GCM 암호화**(AuthIdentity 에 안 넣음, TossUserToken 패턴)
- **client_secret = ES256 JWT(.p8 서명), exp 5분, 캐시 없음**
- **2 PR**: A=로그인 교환+저장+config+마이그레이션(V48), B=탈퇴 revoke 호출+삭제버킷

## 건드는 곳
OAuthProviderProperties.Apple, application.yaml/env, ApiClientConfig(appleRestClient),
SecretCipher(신규), AppleClientSecretJwtBuilder(신규), AppleUserToken+Repository(신규), V48 마이그레이션,
OAuthLoginRequest/OAuthCredential/OAuthLoginService, AppleTokenExchanger(신규),
AppleTokenRevoker(신규), WithdrawController/WithdrawService, WithdrawalDeletionBoundaryIT

## 리스크
- 모바일 동반 필수(앱이 authorizationCode 전송) — 서버 PR-A/B + 앱빌드 동시 릴리스, HANDOFF 필요
- V48 VPS 수동배치 + 신규 시크릿(.p8 마운트, APP_SECRET_ENC_KEY) — 누락 시 프로덕션 로그인 깨짐
- 애플 장애 순간 탈퇴자는 revoke 유실(best-effort 수용, 리뷰어 감지 불가)

## 검증법
컴파일 + PR-A(SecretCipher/client_secret/exchanger 단위테스트) + PR-B(revoke 어댑터 + WithdrawalDeletionBoundaryIT 에 apple_user_token 물리삭제 반영). revoke 실패해도 탈퇴 완료됨을 테스트로 고정.
