# 여러 로그인 수단 지원 (L)

## 뭘 하나

로그인 수단을 담는 `auth_identity` 테이블을 새로 만들고, 애플·구글·카카오 로그인을 연다.
토스는 여러 수단 중 하나로 내려가고 앱에서는 고를 수 없다(미니앱 전용). 여기에 codex 리뷰로 드러난 기존 토큰 취약점 두 건을 같이 고친다. 회원 탈퇴는 별도 작업.

## 왜

지금은 `users` 에 "토스 사용자 번호" 칸이 직접 뚫려 있어 토스로만 가입된다. 이 기능은 iOS·Android 앱에서 쓰이는데 앱 안에서는 토스 로그인을 부를 수 없다.

## 핵심 결정

- `users` 는 지갑, `auth_identity` 는 신분증 한 장. 지갑에 칸을 뚫는 방식은 수단이 늘 때마다 테이블을 뜯게 돼서 기각
- **앱이 "나 누구야"라고 말한 건 안 믿는다.** 서버가 발급 회사에 직접 확인하고, 회사가 매긴 번호만 신원으로 씀. 그 번호가 비었으면 거부
- **refresh 토큰으로 API 를 못 부르게 막는다** — 지금은 부를 수 있다. `parseUserId` 가 토큰 종류를 안 봄
- **로그아웃이 실제로 토큰을 죽이게 한다** — 사용자 토큰 세대 번호 방식. refresh 회전은 후속
- **애플 일회용 값을 서버가 발급·1회 소비** — 앱이 만든 값을 되돌려받는 방식은 재전송을 못 막음
- 이메일이 같아도 다른 회사면 **별개 계정**. 애플 이름은 토큰에 없으므로 앱이 별도로 넘기고 표시용으로만 저장
- **계정당 앱 로그인 하나** (애플/구글/카카오 중 택1). 토스만 예외로 공존. DB 규칙으로 강제
- **마이그레이션 실행 주체는 Flyway 하나.** "VPS 수동 적용"은 git 추적과 실행을 혼동한 메모였음
- 토스 신원 없는 사용자는 알림 API 를 `422` 로 거절. 발송 코드가 토스 번호를 언박싱해서 터짐

## 건드는 곳

`V33__create_auth_identity.sql`, `.gitignore`, `build.gradle`, `application.yaml`
`domain/user/entity/User.java` (nullable 완화), `domain/auth/` (AuthIdentity, AuthProvider, repository)
`common/security/` (JwtProvider, JwtAuthFilter — 토큰 종류·세대 번호)
`application/auth/` (OAuthLoginService, AccountService, OAuthProviderProperties, TossLoginService, nonce challenge)
`infrastructure/adapter/out/auth/` (애플·구글·카카오 검증기, JwkDecoderFactory)
`infrastructure/adapter/in/api/auth/` (OAuthController, AccountController)
알림 발송 2곳 (`BusAlertDispatchService`, `RouteAlertDispatchStateService` — 언박싱 방어)

## 리스크

- 이관 직후 구버전이 만든 사용자가 신분증 없이 남음 → 인증 쓰기를 잠깐 멈추고 이관, 개수 대조로 확인
- `NOT NULL` 을 풀었는데 `User` 엔티티를 안 고치면 첫 OAuth 가입이 실패 → 같은 변경 단위에 포함
- 토큰 세대 번호가 요청마다 DB 조회를 늘림 → Redis 캐싱, 부하 측정 후 결정
- 애플·구글·카카오 자격증명 미확보 시 검증 불가 → 착수 전 선행 조건

## 검증법

검증기 거부 매트릭스(도장 위조·다른 앱용·`azp` 불일치·발급처·기간·일회용 값·빈 사용자 번호), refresh 토큰이 API 에서 거부되는지, 일회용 값이 두 번째에 거부되는지, 동시 로그인 두 건이 모두 성공하고 사용자는 하나인지, 지운 사용자가 이관에서 빠지는지와 개수 대조, 실기기 3사 로그인, 기존 전체 테스트 회귀 없음.
