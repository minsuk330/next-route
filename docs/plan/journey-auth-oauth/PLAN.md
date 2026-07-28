# 여러 로그인 수단 지원 — 구현 계획

- 티어: **L** (테이블 신설 + 신원 방식 전환 + 설계 대안 논쟁 여지)
- 브랜치: `feat/oauth-multi-provider` (fresh main 기준)
- 상위 맥락: [journey-navigation/PHASE-1.md](../journey-navigation/PHASE-1.md) ①
- 후속 의존: `journey-persistence`

## 1. 한 줄 요약

**로그인 수단을 담는 `auth_identity` 테이블을 새로 만들고, 애플·구글·카카오 로그인을 연다.**

지금은 `users` 테이블에 "토스 사용자 번호" 칸이 직접 뚫려 있어서 토스로만 가입할 수 있다. 이 여정 기능은 iOS·Android 앱에서 쓰이는데 앱 안에서는 토스 로그인을 부를 수 없다. 토스 밖에서 가입할 길이 없으면 기능 자체를 못 쓴다.

## 2. 왜 테이블을 따로 두나

`users` 는 **지갑**, `auth_identity` 는 지갑에 든 **신분증 한 장 한 장**이다.

지갑에 "운전면허번호 칸"을 직접 뚫어 놓으면, 여권을 넣고 싶을 때 지갑을 다시 만들어야 한다. 신분증을 따로 두면 지갑은 그대로 두고 카드만 꽂으면 된다.

이 저장소는 이미 지갑을 두 번 뜯었다.

- `V16__toss_login.sql` — `users` 에 토스 번호 칸을 뚫음
- `V17__drop_device_id.sql` — 기기 번호 칸을 메우고 토스 번호를 필수로 승격

여기에 애플·구글·카카오를 같은 방식으로 넣으면 칸이 세 개 더 늘고, 각 칸마다 "비어 있어도 되지만 겹치면 안 된다"는 규칙을 따로 붙여야 한다. 로그인 수단이 하나 늘 때마다 테이블 변경이 따라온다.

**대안도 성립한다.** 뒤에 나오는 D9 규칙(계정당 앱 로그인 하나)을 지키면 칸 세 개로도 만들 수 있다 — 어떤 수단인지, 그 수단이 준 번호가 뭔지, 토스 번호가 뭔지. 조인이 없어 더 단순하다.

그런데도 테이블을 고르는 이유는 세 가지다.

1. 토스가 예외라 이미 "한 사람에 신분증 여러 장"이다. 관계가 이미 1:N
2. 정책이 바뀌면(토스 계정 연결 등) 칸 방식은 데이터를 옮기는 마이그레이션이 필요하다. 테이블 방식은 인덱스만 고친다
3. 수단별 부가정보(언제 연결했는지, 마지막 로그인이 언제인지, 그 수단이 준 이메일)를 담을 자리가 칸 방식에는 없다

치르는 값은 조회할 때 테이블 하나를 더 붙이는 것뿐이다. 로그인은 초당 몇 번 일어나지 않으므로 무시할 수준이다.

## 3. 선행 조건

### 3.1 앱 식별자 (확정)

**iOS Bundle ID 와 Android 패키지명 모두 `com.golbob.nextroute` 로 고정한다.**

두 플랫폼이 같은 문자열이라 OAuth 클라이언트·푸시·딥링크 설정을 한 벌로 관리한다. 스토어에 올린 뒤에는 사실상 변경할 수 없으므로 착수 전에 확정한 값이다.

개발용 접미사(`com.golbob.nextroute.dev` 등)를 쓸지는 아직 정하지 않았다. 쓰기로 하면 **OAuth 클라이언트가 플랫폼당 두 개**가 되고 서버의 허용 목록도 늘어난다.

### 3.2 발급받을 것

- [ ] Apple Developer — Identifiers 에 `com.golbob.nextroute` 등록. **Sign in with Apple 체크 필수**
  - 서명 키(`.p8`)는 탈퇴 기능 전용이라 이번엔 불필요
  - Android 에서도 애플 로그인을 제공할 계획이면 Service ID 를 별도로 만들어야 한다. 이때 토큰의 대상 앱 값이 Bundle ID 가 아니라 Service ID 가 된다
- [ ] Google Cloud — OAuth 클라이언트 **세 개**
  - iOS: Bundle ID `com.golbob.nextroute`
  - Android: 패키지명 `com.golbob.nextroute` + SHA-1 지문
  - **웹(서버)**: 서버가 검증할 대상 앱 값으로 쓴다 (§3.3)
- [ ] Kakao Developers — 앱 키 발급, 카카오 로그인 활성화

세 곳의 자격증명이 없으면 §7 검증을 못 한다. 착수 전에 확보한다.

### 3.3 구글 토큰의 "대상 앱" 값을 하나로 통일한다

**모바일이 구글 로그인 SDK 에 웹 클라이언트 ID 를 `serverClientId` 로 넘기게 한다.** 그러면 iOS·Android 가 발급받는 토큰의 대상 앱 값이 **웹 클라이언트 ID 하나**로 같아지고, 서버 허용 목록도 한 줄로 끝난다.

- Android: `GetGoogleIdOption.setServerClientId(webClientId)` (구버전이면 `requestIdToken(webClientId)`)
- iOS: `GIDConfiguration(clientID: iosClientId, serverClientID: webClientId)`

이 구성에서는 토큰의 "발급 요청자" 값에 플랫폼별 클라이언트 ID 가 들어간다. 서버는 대상 앱 값만 확인하면 된다.

`serverClientId` 를 넘기지 않으면 대상 앱 값이 플랫폼별 클라이언트 ID 가 되고, 허용 목록에 두 개를 넣어야 한다.

**구현 중에 실제 토큰으로 확인한다.** 구글 SDK 의 동작은 버전에 따라 달라져 왔다. 첫 로그인에서 받은 토큰을 디코드해 대상 앱·발급 요청자 값을 눈으로 보고 허용 목록을 확정한다. 문서 설명만 믿고 고정하지 않는다.

### 3.4 Android SHA-1 지문 — 놓치기 쉬운 것

- **debug 빌드와 release 빌드의 SHA-1 이 다르다.** 둘 다 등록하지 않으면 개발 중에만, 또는 배포본에서만 로그인이 깨진다
- **Play 앱 서명을 쓰면 실제 배포본의 SHA-1 은 구글이 재서명한 값이다.** 로컬 keystore 의 SHA-1 만 등록하면 스토어 배포본에서 로그인이 실패한다. Play Console 의 앱 서명 페이지에서 값을 가져와 함께 등록한다

## 4. 범위

**한다**

- `auth_identity` 테이블 신설 + 기존 토스 사용자 이관
- 애플 · 구글 · 카카오 로그인 API (토스는 앱에서 못 쓴다, §5.6)
- 수단과 무관한 공용 토큰 재발급 · 로그아웃 · 내정보 API
- `users` 의 토스 번호 칸을 "비어 있어도 됨"으로 완화 + **`User` 엔티티도 같이 완화**
- **토큰 종류 강제** — refresh 토큰이 API 접근에 쓰이지 못하게 막는다 (§5.7)
- **로그인 세션 폐기 수단** — 로그아웃·탈취 시 기존 토큰을 무효화한다 (§5.8)
- **애플 일회용 값을 서버가 발급·소비** (§5.9)
- **토스 신원이 없는 사용자의 알림 API 차단** (§5.10)

**안 한다 (별도 작업)**

- 로그인한 상태에서 토스 계정을 추가로 잇는 API. D9 때문에 이을 수 있는 건 토스뿐이다
- **회원 탈퇴 (`DELETE /api/me`)** — App Store 심사 제출 전에는 반드시 필요하다
- `users` 의 토스 번호 칸 제거
- 푸시 토큰 (→ Phase 5)
- 기기 식별·다중 기기 ([SCOPE-CHANGES.md](../journey-navigation/SCOPE-CHANGES.md) SC-1 로 범위 제외)

## 5. 설계

### 5.1 신원의 기준은 `users.id` 하나

```
users(id)  ←1:N─  auth_identity(user_id, provider, provider_subject)
```

`provider` 는 어느 회사 로그인인지, `provider_subject` 는 그 회사가 이 사람에게 매긴 번호다.

지켜야 할 규칙:

- 애플리케이션 코드는 토스 번호로 사용자를 찾지 않는다. 항상 `users.id`
- **한 회사의 한 번호는 한 사용자에게만 연결된다.** 애플이 준 번호 하나가 두 계정에 붙을 수 없다
- **한 사용자는 같은 회사를 두 번 연결할 수 없다**
- **한 계정은 앱 로그인 수단을 하나만 갖는다.** 애플 · 구글 · 카카오 중 하나
- **토스는 예외.** 앱 수단 하나와 별개로 붙을 수 있다. 토스+애플은 되고, 애플+구글은 안 된다
- **토스로는 앱에서 로그인할 수 없다.** 토스 신분증은 미니앱 로그인 경로로만 만들어진다 (§5.6)

| 부류 | 수단 | 계정당 개수 | 앱에서 로그인 |
|---|---|---|---|
| 앱 로그인 (하나만) | `APPLE`, `GOOGLE`, `KAKAO` | 최대 1 | 가능 |
| 미니앱 (예외) | `TOSS` | 최대 1 | **불가** |

### 5.2 로그인 토큰 검증 — 양보 없는 부분

**클라이언트가 "나 누구야"라고 말한 내용은 절대 믿지 않는다. 서버가 발급 회사에 직접 확인한다.**

비유하면 위조 신분증 검사다. 손님이 내민 신분증에 적힌 이름을 그대로 받아 적으면 아무나 남이 될 수 있다. 발급 기관의 도장이 진짜인지, 우리 가게용으로 발급된 게 맞는지, 유효기간이 지나지 않았는지를 우리가 확인해야 한다.

이 절의 항목이 하나라도 빠지면 아무나 다른 사람 계정으로 로그인할 수 있다.

| 수단 | 앱이 보내는 것 | 서버가 확인하는 것 |
|---|---|---|
| `APPLE` | 애플이 발급한 토큰 + 일회용 값 | ① 애플이 공개한 검증 열쇠로 도장이 진짜인지 ② 발급처가 애플이 맞는지 ③ 우리 앱을 위해 발급된 게 맞는지 ④ 유효기간이 남았는지 ⑤ 우리가 보낸 일회용 값이 토큰 안의 값과 같은지 |
| `GOOGLE` | 구글이 발급한 토큰 | ①②③④ 동일. 여기에 더해 **⑤ 요청자 값(`azp`)이 있으면 그것도 허용 목록에 있는지 확인한다** |
| `KAKAO` | 카카오 접근 토큰 | 서버가 카카오에 `GET https://kapi.kakao.com/v2/user/me` 를 직접 호출해 토큰이 살아 있는지 확인. 응답의 `id` 가 사용자 번호 |

용어를 한 번만 풀어 둔다.

- **일회용 값(nonce)** — 로그인 요청마다 한 번만 쓰이는 무작위 문자열. 예전에 훔친 토큰을 재사용하는 공격을 막는다. **서버가 발급하고 서버가 소비한다**(§5.9)
- **요청자 값(azp)** — 토큰을 실제로 요청한 앱의 식별자. `serverClientId` 구성에서는 대상 앱 값이 웹 클라이언트 ID 로 통일되므로, 어느 플랫폼이 요청했는지는 이 값에만 남는다
- **검증 열쇠 목록(JWKS)** — 애플·구글이 공개해 둔, 도장이 진짜인지 확인할 때 쓰는 열쇠 모음. 회사가 주기적으로 갈아 끼운다
- **사용자 번호(sub)** — 발급 회사가 이 사람에게 매긴 고유 번호. 이메일과 달리 바뀌지 않는다

추가 규칙:

- **"우리 앱용이 맞는지" 확인할 허용 목록이 비어 있으면 애플리케이션이 아예 켜지지 않게 한다.** 빈 목록이 "전부 통과"로 동작하면 검증이 없는 것과 같다
- 애플은 **이름을 토큰에 담지 않는다.** 최초 로그인 때 앱에 따로 한 번만 건네주고 그걸로 끝이다. 그러니 서버는 토큰에서 이름을 꺼낼 수 없고, **앱이 별도 필드로 넘겨 주어야** 한다. 이메일은 토큰에 있지만 역시 최초 1회뿐이다
- 앱이 넘긴 이름은 **표시용으로만** 저장한다. 신원 판단에 절대 쓰지 않는다. 클라이언트가 보낸 값이기 때문이다
- 애플의 가려진 이메일(`...@privaterelay.appleid.com`)은 사람을 구분하는 열쇠로 쓰지 않는다. 사용자 번호만 믿는다
- 받은 토큰 원문을 로그·지표·예외 메시지에 남기지 않는다
- **사용자 번호가 비었거나 공백이면 거부한다.** 애플·구글의 `sub`, 카카오의 `id` 중 하나라도 `null`·빈 문자열·`"0"` 같은 기본값이면 신원으로 쓰지 않는다. 이 값이 신원의 전부이므로 빈 값이 통과하면 서로 다른 사람이 한 계정으로 합쳐진다
- **대상 앱 값이 여러 개면 전부 허용 목록 안에 있어야 한다.** 하나만 맞으면 통과시키는 구현은 쓰지 않는다

**검증 열쇠를 직접 관리하지 않는다.** 회사가 열쇠를 갈아 끼울 때 따라가는 처리와 캐시를 직접 짜면 그 자체가 사고 지점이 된다. `spring-boot-starter-oauth2-resource-server` 를 넣고 그 안의 `NimbusJwtDecoder.withJwkSetUri(...)` 만 쓴다.

이 라이브러리를 넣어도 `SecurityConfig` 에서 `.oauth2ResourceServer(...)` 를 부르지 않으면 기존 인증 흐름은 그대로다. 기존 인증 API 회귀 테스트로 확인한다.

### 5.3 이메일이 같아도 계정을 합치지 않는다

**다른 회사 로그인으로 들어오면 이메일이 같아도 별개 계정을 만든다.**

이유는 두 가지다. 서버는 그 이메일이 정말 그 사람 것인지 확인한 적이 없다. 그리고 애플이 주는 가려진 이메일 때문에 이메일은 애초에 안정적인 열쇠가 아니다.

이메일이 같다는 이유로 자동으로 합치면, 남의 이메일을 아는 사람이 그 계정에 들어가는 길이 열린다.

저장하는 이메일은 **표시용일 뿐**이다. 비어 있을 수 있고, 다른 사람과 겹칠 수 있고, 나중에 바뀔 수 있다. 조회 키로 쓰거나 유일성을 가정하는 코드를 만들지 않는다.

### 5.4 "앱 로그인 하나만" 규칙은 어디서 걸리나

**로그인할 때는 안 걸린다. 미래에 만들 연결 API를 위한 안전장치다.**

한 회사의 한 번호는 한 계정에만 붙으므로, 처음 보는 번호로 로그인하면 언제나 새 계정이 생긴다(§5.3). 기존 계정에 두 번째 수단을 붙이는 일은 연결 API에서만 일어나고, 그건 이번 범위가 아니다.

그래도 지금 규칙을 DB에 새겨 두면, 나중에 연결 API를 만들 때 코드 실수로 두 번째 앱 수단이 붙는 걸 DB가 막아 준다.

**같이 기록해 둘 것:** 이번 범위에서는 토스와 앱 수단이 한 계정에 같이 붙을 경로가 없다. 토스 로그인은 미니앱에서만 불리고, 미니앱 계정과 앱 계정을 잇는 API가 없다. 이건 임시 상태가 아니라 확정된 정책이다 — §5.11 을 보라.

### 5.5 지운 표시가 된 행을 조회에서 거르기

**신원을 찾는 쿼리에는 "지워지지 않은 것만"을 직접 써야 한다.**

이 저장소는 지운 표시가 된 행을 자동으로 걸러 주는 장치를 쓰지 않는다. 대신 쿼리마다 조건을 손으로 넣는 관례다(`RouteArrivalAlertRepository.java:49` 등). 기존 `UserRepository.findByTossUserKey` 에도 이 조건이 없다.

그러니 `auth_identity` 조회 메서드는 처음부터 조건을 넣어 만든다. 중복 금지 규칙에도 같은 조건을 붙여, 나중에 계정을 지우는 기능이 생겨도 같은 회사 계정으로 다시 가입할 수 있게 둔다.

### 5.6 앱에서는 토스로 로그인할 수 없다

**공용 로그인 API 는 애플·구글·카카오만 받는다. 토스를 지정하면 400 으로 거절한다.**

토스 로그인은 토스 미니앱 안에서만 동작한다. 미니앱이 주는 인가코드를 앱이 얻을 방법이 없으므로, 앱에서 토스를 고를 수 있게 두면 반드시 실패하는 선택지를 노출하는 셈이다.

- 토스 로그인 경로는 기존 `/api/auth/toss/login` 하나뿐이고 미니앱만 호출한다
- 공용 `/api/auth/oauth/login` 은 `provider` 가 `TOSS` 면 `400 PROVIDER_NOT_ALLOWED`
- 이 판단을 컨트롤러의 조건문에 두지 않고 `AuthProvider` 가 스스로 답하게 한다. 수단이 늘어도 한 군데만 본다

결과적으로 토스 신분증은 미니앱 로그인으로만 생긴다. §5.4 에서 적은 "토스와 앱 수단이 한 계정에 같이 붙을 경로가 없다"는 한계가 여기서 한 번 더 확정된다.

### 5.11 미니앱 계정과 앱 계정은 합치지 않는다 (제품 정책)

**같은 사람이 토스 미니앱과 네이티브 앱을 모두 써도 계정은 둘이다. 합치는 기능을 만들지 않는다.**

토스 미니앱에서 가입한 사용자와 앱에서 애플·구글·카카오로 가입한 사용자는 서로 다른 `users.id` 를 받는다. 서버는 두 사람이 동일인인지 알 방법이 없다 — 이메일은 소유권을 확인한 적 없고 애플은 가려진 이메일을 준다(§5.3).

그래서 이렇게 된다.

| | 미니앱 계정 | 앱 계정 |
|---|---|---|
| 즐겨찾기 | 각자 | 각자 |
| 검색 이력 | 각자 | 각자 |
| 알림 구독 | 있음 | 없음 (§5.10) |
| 여정 | 없음 | 있음 |

**이것을 결함이 아니라 정책으로 확정한다(D17).** 계정 연결 플로우를 만들려면 토스 인증 상태와 새 provider 의 최근 재인증을 동시에 증명해야 하고, 그 자체가 계정 탈취 표면이 된다. 두 채널의 사용자층이 실제로 겹치는지 확인되기 전에 그 위험을 감수할 이유가 없다.

정책이므로 **제품 쪽에도 반영해야 한다.** 앱 안에서 "토스로 가입한 계정을 가져오기" 같은 기대를 만들지 않는다. 필요해지면 그때 별도 작업으로 연결 플로우를 설계한다.

### 5.7 refresh 토큰으로는 API 를 부를 수 없게 한다

**지금은 부를 수 있다. 이건 이번 작업에서 반드시 막아야 하는 기존 취약점이다.**

`JwtProvider` 는 access 토큰과 refresh 토큰을 같은 열쇠로 서명하고, 안에 `type` 칸으로만 구분한다. 그런데 요청을 검사하는 `JwtProvider.parseUserId`(`JwtProvider.java:52`)는 그 칸을 보지 않는다. `JwtAuthFilter`(`JwtAuthFilter.java:37`)도 이 메서드만 쓴다.

결과적으로 **14일짜리 refresh 토큰을 `Authorization: Bearer` 에 그대로 넣으면 보호된 API 가 전부 열린다.** access 토큰 수명을 1시간으로 짧게 잡아 둔 의미가 사라진다.

고칠 것:

```java
// JwtProvider
/** access 토큰만 통과시킨다. refresh 토큰이면 JwtException. */
public long parseAccessUserId(String token);
```

- `JwtAuthFilter` 는 `parseAccessUserId` 만 쓴다
- 기존 `parseUserId` 는 삭제한다. 남겨 두면 누군가 다시 쓴다
- 재발급 경로는 지금처럼 refresh 전용 검사를 쓴다

이 변경은 이번 작업의 다른 부분과 독립적이지만, 로그인 수단을 늘리면서 취약한 토큰 검사를 그대로 두는 것은 앞뒤가 맞지 않는다.

### 5.8 로그아웃과 재발급이 실제로 무언가를 끝내게 한다

**지금의 로그아웃은 자체 토큰을 무효화하지 않는다.**

`TossLoginService.logout`(`TossLoginService.java:65`)은 토스 연결을 끊고 보관 중인 토스 토큰을 지우지만, 우리가 발급한 JWT 는 그대로 살아 있다. 재발급(`TossLoginService.java:49`)도 사용자가 존재하는지만 보고 무한정 새 토큰을 내준다.

토큰이 탈취되면 만료될 때까지 막을 방법이 없다. 로그인 수단이 늘어나면 이 문제도 같이 커진다.

두 가지 방식이 있다.

| 방식 | 하는 일 | 비용 |
|---|---|---|
| **토큰 세대 번호** | `users` 에 정수 칸 하나를 두고 JWT 에 함께 담는다. 로그아웃하면 번호를 올린다. 검사할 때 번호가 다르면 거부 | 요청마다 사용자 조회 1회 |
| refresh 세션 저장 | 발급한 refresh 토큰을 DB·Redis 에 기록하고, 쓸 때마다 새것으로 바꾸며 재사용을 탐지 | 저장소·회전 로직 추가 |

**1차 기본안은 토큰 세대 번호다.** 구조가 단순하고, 로그아웃·탈퇴·비밀 유출 대응이라는 목적을 모두 만족한다. 사용자 조회가 부담이 되면 Redis 에 세대 번호만 캐싱한다.

refresh 토큰 회전과 재사용 탐지는 이 범위에 넣지 않는다. 다만 세대 번호가 있으면 나중에 얹기 쉽다.

### 5.9 애플 일회용 값은 서버가 발급하고 한 번만 쓴다

**앱이 만든 값을 앱이 그대로 돌려주는 방식은 일회용이 아니다.**

애초 계획은 앱이 만든 일회용 값을 요청에 함께 실어 보내면 서버가 토큰 안의 값과 문자열 비교만 하는 방식이었다. 이러면 공격자가 토큰과 값을 함께 훔쳐 그대로 다시 보낼 때 막을 수 없다. 서버는 그 조합을 전에 본 적이 있는지 모르기 때문이다.

바꾼다.

```http
POST /api/auth/oauth/challenge      → { "nonce": "...", "expiresInSeconds": 300 }
POST /api/auth/oauth/login          → { "provider": "APPLE", "credential": "...", "nonce": "..." }
```

- 서버가 무작위 값을 만들어 Redis 에 짧게(5분) 저장하고 앱에 준다
- 앱은 그 값을 애플 로그인에 쓰고, 받은 토큰과 함께 서버로 보낸다
- 서버는 저장소에서 그 값을 **삭제하면서 꺼낸다.** 없으면 거부한다. 두 번째 시도는 반드시 실패한다

**모바일과 미리 합의할 것:** 애플 SDK 는 원본 값을 그대로 쓰는지, 해시한 값을 쓰는지에 따라 토큰에 담기는 값이 달라진다. 어느 쪽을 쓸지 정하고 서버 검증도 같은 방식으로 맞춘다. 구현 중 실제 토큰을 디코드해 확인한다.

### 5.10 토스 신원이 없는 사용자는 알림 API 를 쓸 수 없다

**기존 알림 기능은 토스 사용자만 전제로 만들어져 있다.**

발송 코드가 토스 번호를 그대로 꺼내 쓴다.

```java
// BusAlertDispatchService.java:116
long userKey = alert.getUser().getTossUserKey();
// RouteAlertDispatchStateService.java:84
long userKey = alert.getUser().getTossUserKey();
```

`getTossUserKey()` 는 `Long` 인데 받는 쪽이 `long` 이다. 값이 없으면 그 자리에서 터진다. 애플·구글·카카오로 가입한 사용자가 알림을 만들면, 요청은 성공하고 나중에 백그라운드 발송이 조용히 실패한다.

이번 작업에서 알림 채널을 새로 만들 수는 없다(FCM 은 Phase 5 다). 대신 **막는다.**

- 알림 구독 생성 API 는 요청자에게 `TOSS` 신원이 있는지 확인하고, 없으면 `422 TOSS_IDENTITY_REQUIRED` 로 거절한다
- 발송 코드의 언박싱도 방어적으로 고친다. 값이 없으면 그 건을 건너뛰고 경고를 남긴다

이건 임시 조치이며, 알림 채널이 토스에서 독립하는 Phase 5 에서 걷어낸다.

## 6. 작업 순서

### 6.1 마이그레이션

- [ ] `src/main/resources/db/migration/V33__create_auth_identity.sql` 작성

아래 인덱스 세 개가 §5.1 의 규칙을 그대로 옮긴 것이다. 셋 다 "지워지지 않은 행에만 적용"이라는 조건이 붙어 있다.

```sql
-- 신원 방식 전환: 외부 로그인 수단을 users 에서 분리한다.
-- 신원의 기준은 users.id 이며, toss_user_key 는 더 이상 신원 식별자가 아니다.
-- (V16 에서 도입, V17 에서 필수로 올렸던 제약을 여기서 되돌린다.)

CREATE TABLE auth_identity (
    id               bigint generated by default as identity primary key,
    created_at       timestamp(6),
    updated_at       timestamp(6),
    deleted_at       timestamp(6),
    user_id          bigint       not null references users,
    provider         varchar(20)  not null
        constraint auth_identity_provider_check
            check (provider in ('TOSS', 'APPLE', 'GOOGLE', 'KAKAO')),
    provider_subject varchar(255) not null,
    email            varchar(320),   -- 회사가 준 값. 소유권을 확인한 적 없다. 표시용.
    display_name     varchar(100),
    linked_at        timestamp(6) not null,
    last_login_at    timestamp(6)
);

-- 한 회사의 한 번호는 한 사용자에게만.
CREATE UNIQUE INDEX uq_auth_identity_provider_subject
    ON auth_identity (provider, provider_subject) WHERE deleted_at IS NULL;
-- 한 사용자는 같은 회사를 두 번 연결 못 함.
CREATE UNIQUE INDEX uq_auth_identity_user_provider
    ON auth_identity (user_id, provider) WHERE deleted_at IS NULL;
-- 한 계정은 앱 로그인 수단을 하나만. TOSS 는 예외라 이 규칙에서 뺀다.
CREATE UNIQUE INDEX uq_auth_identity_single_app_provider
    ON auth_identity (user_id) WHERE provider <> 'TOSS' AND deleted_at IS NULL;
CREATE INDEX idx_auth_identity_user
    ON auth_identity (user_id) WHERE deleted_at IS NULL;

-- 기존 토스 사용자를 신분증 한 장으로 옮긴다.
-- 지운 표시가 된 사용자는 제외한다. 포함하면 삭제된 계정이 살아 있는 신분증을 얻어 다시 로그인된다.
INSERT INTO auth_identity (user_id, provider, provider_subject, linked_at, created_at, updated_at)
SELECT id, 'TOSS', toss_user_key::text, now(), now(), now()
  FROM users
 WHERE toss_user_key IS NOT NULL
   AND deleted_at IS NULL;

-- 토스 없이 가입하는 사용자를 위해 필수 조건을 푼다. 중복 금지는 값이 있을 때만 적용.
ALTER TABLE users ALTER COLUMN toss_user_key DROP NOT NULL;
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_toss_user_key;
CREATE UNIQUE INDEX uq_users_toss_user_key
    ON users (toss_user_key) WHERE toss_user_key IS NOT NULL;
```

#### 실행 주체는 Flyway 하나다

**손으로 SQL 을 넣지 않는다.**

`spring.flyway.enabled: true`(`application.yaml:4`) 라 앱이 뜰 때 Flyway 가 스스로 적용한다. 손으로 SQL 만 넣으면 `flyway_schema_history` 에 기록이 남지 않고, 다음 부팅에서 같은 SQL 을 또 실행하다 실패한다.

"V18 이후는 VPS 에서 수동 적용"이라는 기존 메모는 **git 추적 여부와 실행 주체를 뒤섞은 것**이다. 실제로는 앱을 로컬에서 빌드해 올리므로, git 에 없는 파일도 jar 안에는 들어가고 Flyway 가 정상적으로 적용해 왔다. 이번에도 같다.

- [ ] `.gitignore` 에 `!src/main/resources/db/migration/V33__create_auth_identity.sql` 추가
  - `.gitignore:9` 가 마이그레이션 폴더를 통째로 제외하고 파일별로 다시 허용한다
  - 이 줄은 **git 에서 보이게 할지**를 정할 뿐 실행과 무관하다. 리뷰와 이력 추적을 위해 추가한다
  - 참고로 V30·V31 은 이 줄이 없어 리포에 없다. 새 환경에서 스키마를 재현할 수 없는 상태이며 별도로 정리해야 한다

#### `users` 테이블 잠금

`ALTER TABLE` 과 여러 인덱스 생성이 한 트랜잭션에서 돈다. 지금 `users` 는 작지만, 적용 전에 행 수를 확인하고 `lock_timeout` 을 짧게 걸어 오래 잡히면 실패하도록 한다. 인덱스가 오래 걸리면 `CREATE INDEX CONCURRENTLY` 로 분리한다(트랜잭션 밖에서 실행해야 한다).

되돌리기는 DB 를 되감는 대신 **구버전 앱으로 복귀 + 앞으로 고치기**로 한다. OAuth 사용자가 한 명이라도 생기면 `NOT NULL` 복구는 데이터 정리 없이 불가능하다.

**토스 번호 칸은 지우지 않는다.** `toss_user_token.user_key` 와 토스 연결끊기 콜백(`TossLoginService.handleUnlinkCallback`, `TossLoginService.java:83`)이 이 값을 거쳐 간다. 이 작업 이후로는 옛 값을 비추기만 하는 칸이고, 신원 조회는 `auth_identity` 로만 한다.

#### 이관과 구버전 로그인이 겹치는 창

`INSERT … SELECT` 는 실행 시점에 있는 사용자만 옮긴다. 그 직후 구버전 앱이 토스 로그인을 처리하면 `User` 만 만들고 신분증은 만들지 않는다(`TossLoginService.java:90`). 신버전이 뜬 뒤 그 사용자가 다시 로그인하면 신분증이 없으니 새 `User` 를 만들려 하고, 토스 번호 중복 금지에 걸려 실패한다.

두 방법 중 하나를 고른다.

| 방법 | 내용 |
|---|---|
| **A. 짧은 정지 창** | 배포 중 인증 쓰기를 잠깐 막고 이관한다. 사용자 수가 적은 지금은 이쪽이 단순하다 |
| B. 단계 배포 | 테이블 생성 → 양쪽에 쓰는 버전 배포 → 재이관·대조 → 신분증 조회로 전환 |

**1차 기본안은 A** 다. 다만 이관 후 `SELECT count(*)` 로 `users`(삭제 안 된 것, 토스 번호 있는 것)와 `auth_identity`(`TOSS`)의 개수를 반드시 대조한다. 어긋나면 배포를 멈춘다.

### 6.2 build.gradle

- [ ] `implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'` 추가

### 6.3 domain

- [ ] `domain/auth/entity/AuthProvider.java`

```java
public enum AuthProvider {
    /** 미니앱 전용. 앱에서는 고를 수 없고, 앱 수단 하나와 공존할 수 있다. */
    TOSS(false, false),
    APPLE(true, true),
    GOOGLE(true, true),
    KAKAO(true, true);

    private final boolean appExclusive;
    private final boolean appLoginAllowed;

    /** 계정당 하나만 허용되는 앱 로그인 수단인지. TOSS 만 false. */
    public boolean isAppExclusive() { return appExclusive; }

    /** 공용 로그인 API 로 들어올 수 있는 수단인지. TOSS 만 false(§5.6). */
    public boolean isAppLoginAllowed() { return appLoginAllowed; }
}
```

- [ ] `domain/auth/entity/AuthIdentity.java` — `BaseEntity` 상속, `@Table(name = "auth_identity")`

```java
private Long userId;              // User 연관 대신 id 참조(경계 단순화)
@Enumerated(EnumType.STRING) private AuthProvider provider;
private String providerSubject;
private String email;             // 표시용. 소유권 미확인.
private String displayName;
private LocalDateTime linkedAt;
private LocalDateTime lastLoginAt;

public void touchLogin(LocalDateTime now);
```

- [ ] `domain/user/entity/User.java` **수정** — `tossUserKey` 를 비어 있어도 되게 바꾼다

```java
/** 토스 미니앱 사용자 식별키. 앱 OAuth 로 가입하면 비어 있다. 신원 기준은 users.id 다. */
@Column(unique = true)      // nullable = false 제거
private Long tossUserKey;
```

DB 만 바꾸고 엔티티를 그대로 두면 안 된다. `@Column(nullable = false)` 가 있으면 Hibernate 가 저장 직전에 스스로 거부하므로, 토스 없이 가입하는 첫 사용자에서 실패한다. `ddl-auto: validate`(`application-prod.yaml:7`) 환경이라 DB 와 엔티티의 선언이 어긋나는 것 자체도 피해야 한다.

- [ ] `domain/auth/repository/AuthIdentityRepository.java`

```java
/** 지워지지 않은 것만 조회한다. 자동 필터 장치가 없으므로 조건을 직접 쓴다(§5.5). */
Optional<AuthIdentity> findByProviderAndProviderSubjectAndDeletedAtIsNull(
        AuthProvider provider, String subject);

List<AuthIdentity> findByUserIdAndDeletedAtIsNull(Long userId);
```

### 6.4 application — 검증 계약

로그인 수단마다 확인 방법이 다르므로, **"토큰을 받아 신원을 돌려준다"는 약속 하나만 정해 두고** 회사별 구현을 각각 만든다. 자물쇠 하나에 열쇠 세 개가 아니라, 열쇠 구멍 규격만 정하고 열쇠는 회사가 알아서 맞추는 식이다.

- [ ] `application/auth/dto/OAuthCredential.java`

```java
/**
 * @param displayName 애플 최초 로그인에서만 앱이 채워 보낸다. 표시용이며 신원 판단에 쓰지 않는다.
 *                    애플은 이름을 토큰에 담지 않으므로 서버가 스스로 얻을 방법이 없다.
 */
public record OAuthCredential(AuthProvider provider, String credential,
                              String nonce, String displayName) {}
```

- [ ] `application/auth/dto/VerifiedIdentity.java`

```java
public record VerifiedIdentity(AuthProvider provider, String subject, String email, String name) {}
```

- [ ] `application/auth/port/out/OAuthIdentityVerifier.java`

```java
public interface OAuthIdentityVerifier {
    AuthProvider provider();

    /**
     * 로그인 토큰을 확인하고 신원을 돌려준다.
     * 도장·발급처·대상앱·유효기간·일회용값 중 하나라도 어긋나면 OAuthVerificationException 을 던진다.
     * 클라이언트가 함께 보낸 사용자 정보는 쓰지 않는다.
     */
    VerifiedIdentity verify(OAuthCredential credential);
}
```

- [ ] `application/auth/port/in/OAuthLoginUseCase.java` — `LoginResult login(OAuthCredential credential)`
- [ ] `application/auth/exception/OAuthVerificationException.java`
- [ ] `application/auth/exception/IdentityAlreadyLinkedException.java` — 앱 수단 중복. `409 IDENTITY_ALREADY_LINKED`
- [ ] `application/auth/exception/ProviderNotAllowedException.java` — 앱에서 토스 지정. `400 PROVIDER_NOT_ALLOWED`

### 6.5 application — 설정·서비스

- [ ] `application/auth/config/OAuthProviderProperties.java`

```java
@ConfigurationProperties(prefix = "oauth")
public class OAuthProviderProperties {
    private Apple apple;    // jwkSetUri, issuer, audiences(List<String>)
    private Google google;  // jwkSetUri, issuers(List<String>), audiences(List<String>)
    private Kakao kakao;    // userInfoUri
}
```

`@PostConstruct` 에서 애플·구글의 허용 앱 목록이 비어 있으면 `IllegalStateException` 을 던져 기동을 막는다(§5.2).

- [ ] `application/auth/service/OAuthLoginService.java` — `OAuthLoginUseCase` 구현

```java
private final Map<AuthProvider, OAuthIdentityVerifier> verifiers;  // 생성자에서 List → Map 변환

@Transactional
public LoginResult login(OAuthCredential credential) {
    if (!credential.provider().isAppLoginAllowed()) {          // §5.6
        throw new ProviderNotAllowedException(credential.provider());
    }
    VerifiedIdentity verified = verifier(credential.provider()).verify(credential);
    AuthIdentity identity = identityRepo
            .findByProviderAndProviderSubjectAndDeletedAtIsNull(
                    verified.provider(), verified.subject())
            .orElseGet(() -> createUserWithIdentity(verified));   // 첫 로그인 = 가입
    identity.touchLogin(now());
    return issueTokens(identity.getUserId());
}
```

`createUserWithIdentity` 는 언제나 **새 사용자**를 만든다. 기존 계정에 붙이지 않으므로 "앱 수단 하나만" 규칙과 부딪히지 않는다(§5.4).

**충돌을 전부 409 로 바꾸지 않는다.** 같은 사용자가 로그인 버튼을 두 번 빠르게 누르면 두 요청이 동시에 "신분증 없음"을 보고 둘 다 만들려 한다. 이때 하나는 중복 금지에 걸리는데, 이건 오류가 아니라 **정상 경합**이다. 409 로 돌려주면 멀쩡한 로그인이 실패한다.

```java
catch (DataIntegrityViolationException e) {
    // 경합으로 상대가 먼저 만들었다면 그것을 쓰면 된다. 새 트랜잭션에서 재조회한다.
    return identityRepo
            .findByProviderAndProviderSubjectAndDeletedAtIsNull(
                    verified.provider(), verified.subject())
            .map(existing -> issueTokens(existing.getUserId()))
            // 재조회해도 없으면 "앱 수단 하나만" 규칙 위반이다(연결 API 가 생긴 뒤에나 발생).
            .orElseThrow(() -> new IdentityAlreadyLinkedException(verified.provider()));
}
```

재조회는 **새 트랜잭션**에서 해야 한다. 충돌이 난 트랜잭션은 이미 롤백 대상이라 같은 안에서 읽으면 안 된다.

- [ ] `application/auth/service/AccountService.java` — 내정보 조회

- [ ] `application/auth/service/TossLoginService.java` 수정
  - `upsertUser(long tossUserKey)` (`TossLoginService.java:90`) 를 `auth_identity` 경유로 변경
  - `findByProviderAndProviderSubjectAndDeletedAtIsNull(TOSS, String.valueOf(userKey))` 로 조회하고, 없으면 `User` 와 `AuthIdentity` 를 함께 생성
  - `users.toss_user_key` 는 계속 채운다(옛 값 비추기용). 연결끊기 콜백이 이를 쓴다

### 6.6 infrastructure — 회사별 검증 구현

- [ ] `infrastructure/adapter/out/auth/JwkDecoderFactory.java`

```java
/** 회사별 검증기를 한 번 만들어 재사용한다. 열쇠 교체 추적과 캐시는 Nimbus 가 담당. */
public JwtDecoder decoder(String jwkSetUri, List<String> issuers, List<String> audiences);
```

발급처·대상앱·유효기간 확인은 `OAuth2TokenValidator` 를 이어 붙여 처리한다. 대상앱은 허용 목록과 겹치는 게 있는지 보는 검사기를 직접 만든다.

- [ ] `infrastructure/adapter/out/auth/AppleIdentityVerifier.java`
  - 위 검증기를 통과시킨 뒤, 토큰 안의 일회용 값과 요청의 일회용 값을 비교한다. 비교는 문자열 길이에 관계없이 같은 시간이 걸리는 방식으로 한다
  - 사용자 번호·이메일만 토큰에서 꺼낸다. **이름은 토큰에 없다**(§5.2). 이름은 요청의 `displayName` 을 그대로 쓴다
  - 사용자 번호가 비었거나 공백이면 거부한다
- [ ] `infrastructure/adapter/out/auth/GoogleIdentityVerifier.java`
- [ ] `infrastructure/adapter/out/auth/KakaoIdentityVerifier.java`
  - `RestClient` 로 `GET /v2/user/me`. 401·403 이면 `OAuthVerificationException`
  - 관측성 계측이 붙도록 `RestClient` 를 쓴다(`OdSayApiAdapter` 참고)

### 6.7 infrastructure — API

- [ ] `infrastructure/adapter/in/api/auth/OAuthController.java`

```http
POST /api/auth/oauth/challenge
→ 200 { "nonce": "...", "expiresInSeconds": 300 }

POST /api/auth/oauth/login
{
  "provider": "APPLE",
  "credential": "<토큰>",
  "nonce": "<challenge 로 받은 값. APPLE 필수>",
  "displayName": "<애플 최초 로그인에서만. 표시용>"
}
→ 200 { "accessToken", "refreshToken", "userId", "provider" }
→ 400 provider 가 TOSS   (앱에서는 토스 로그인 불가, §5.6)
→ 401 검증 실패 (서명·대상앱·발급처·기간·일회용 값)

`provider` 로 받을 수 있는 값은 `APPLE` · `GOOGLE` · `KAKAO` 뿐이다.
```

```http
POST /api/auth/reissue     # 수단과 무관한 공용
POST /api/auth/logout
```

- [ ] `infrastructure/adapter/in/api/auth/AccountController.java`

```http
GET /api/me      → { "userId", "identities": [{ "provider", "linkedAt" }] }
```

- [ ] `infrastructure/adapter/in/api/auth/dto/OAuthLoginRequest.java` / `OAuthLoginResponse.java`

기존 `/api/auth/toss/*` 는 미니앱이 계속 쓰므로 **그대로 둔다**. 앱은 이 경로를 호출하지 않는다.

### 6.8 SecurityConfig

- [ ] `common/security/SecurityConfig.java:45` 아래에 추가

```java
.requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
.requestMatchers("/api/me").authenticated()
```

이 설정은 "적어 둔 것만 보호하고 나머지는 전부 개방"(`SecurityConfig.java:47`) 방식이다. 규칙을 넣지 않으면 로그인 없이 열린다.

`.oauth2ResourceServer(...)` 는 **부르지 않는다.** 기존 `JwtAuthFilter` 를 그대로 쓴다.

### 6.9 설정 파일

- [ ] `application.yaml` 에 `oauth.*` 추가. 비밀값은 환경변수로만 참조

```yaml
oauth:
  apple:
    jwk-set-uri: https://appleid.apple.com/auth/keys
    issuer: https://appleid.apple.com
    audiences: ${OAUTH_APPLE_AUDIENCES}
  google:
    jwk-set-uri: https://www.googleapis.com/oauth2/v3/certs
    issuers: accounts.google.com,https://accounts.google.com
    audiences: ${OAUTH_GOOGLE_AUDIENCES}
  kakao:
    user-info-uri: https://kapi.kakao.com/v2/user/me
```

## 7. 검증

### 7.1 테스트

**검증기 테스트가 이 작업의 핵심이다.** 통과 케이스 하나보다 거부 케이스 다섯 개가 중요하다.

- [ ] `AppleIdentityVerifierTest` — 정상 토큰 통과 / 도장 위조 · 다른 앱용 · 발급처 위조 · 유효기간 만료 · 일회용 값 불일치를 각각 거부
- [ ] `GoogleIdentityVerifierTest` — 같은 매트릭스. 허용 목록에 없는 앱 식별자 거부
- [ ] `KakaoIdentityVerifierTest` — 카카오가 401 을 주면 거부
- [ ] `OAuthProviderPropertiesTest` — 허용 앱 목록이 비면 기동 실패
- [ ] `OAuthLoginServiceTest` — 첫 로그인 시 사용자와 신분증이 함께 생성 / 재로그인 시 행이 늘지 않고 기존 사용자 재사용
- [ ] `OAuthLoginServiceTest` — 이메일이 같아도 다른 회사면 **다른 사용자**
- [ ] `OAuthLoginServiceTest` — `provider = TOSS` 로 공용 로그인을 부르면 400, 검증기를 호출하지 않음
- [ ] `AuthProviderTest` — 앱 로그인 허용 목록에 토스가 없음
- [ ] `AuthIdentityConstraintTest` (Testcontainers) — 한 사용자에 애플+구글 넣으면 실패
- [ ] `AuthIdentityConstraintTest` — 한 사용자에 토스+애플 넣으면 **성공**
- [ ] `AuthIdentityConstraintTest` — 앱 수단을 지운 표시한 뒤 다른 앱 수단 넣으면 성공
- [ ] `AuthIdentityMigrationTest` (Testcontainers) — V33 적용 후 기존 토스 사용자가 전부 옮겨지고 개수가 일치
- [ ] `AuthIdentityRepositoryTest` — 지운 표시된 신분증은 조회되지 않음
- [ ] `SecurityConfigTest` — 새 라이브러리를 넣어도 기존 인증 API 동작 불변

codex 리뷰(§10)로 추가된 것:

- [ ] `JwtProviderTest` — **refresh 토큰을 `parseAccessUserId` 에 넣으면 거부**된다 (§5.7)
- [ ] `JwtAuthFilterTest` — refresh 토큰을 `Authorization: Bearer` 로 보내면 인증되지 않는다
- [ ] `JwtProviderTest` — 로그아웃으로 세대 번호가 오르면 이전 토큰이 거부된다 (§5.8)
- [ ] `AppleNonceChallengeTest` — 서버가 준 값은 **한 번만** 통과하고 두 번째 시도는 거부된다 (§5.9)
- [ ] `AppleNonceChallengeTest` — 저장된 적 없는 값이면 거부된다
- [ ] `GoogleIdentityVerifierTest` — `azp` 가 허용 목록에 없으면 거부된다
- [ ] `*IdentityVerifierTest` — 사용자 번호가 `null`·빈 문자열이면 거부된다 (세 provider 모두)
- [ ] `OAuthLoginServiceTest` — 같은 신원으로 동시 로그인 두 건이 **둘 다 성공**하고 사용자는 하나만 생긴다 (§6.5)
- [ ] `AuthIdentityMigrationTest` — **지운 표시가 된 사용자는 이관되지 않는다** (§6.1)
- [ ] `AuthIdentityMigrationTest` — 이관 후 개수 대조: `users`(미삭제·토스번호 있음) = `auth_identity`(TOSS)
- [ ] `UserEntityTest` — 토스 번호 없이 `User` 를 저장할 수 있다 (§6.3)
- [ ] `BusAlertControllerTest` — 토스 신원이 없는 사용자의 알림 생성이 `422` 로 거절된다 (§5.10)

애플·구글의 검증 열쇠는 테스트 전용 키쌍을 만들어 MockWebServer 로 흉내 낸다. `com.squareup.okhttp3:mockwebserver` 는 이미 `build.gradle` 에 있다.

### 7.2 명령

```bash
./gradlew compileJava
./gradlew test --tests '*Identity*' --tests '*OAuth*' --tests '*Account*'
./gradlew test          # 전체 회귀
```

### 7.3 손으로 확인

- [ ] 애플 · 구글 · 카카오 각각 실기기 로그인 성공
- [ ] 도장 위조 · 다른 앱용 토큰이 401 로 거부
- [ ] 기존 토스 미니앱 로그인 회귀 없음

### 7.4 배포

- [ ] VPS 에 V33 수동 적용 (V18+ untracked 관례)
- [ ] `OAUTH_*` 환경변수를 compose 에 주입

## 8. 리스크

| 리스크 | 대응 |
|---|---|
| 토스 번호를 "비어도 됨"으로 바꾸는 게 기존 토스 로그인을 깨뜨림 | 칸은 계속 채운다. `TossLoginServiceTest` 회귀로 확인 |
| 새 라이브러리가 인증 흐름을 자기 마음대로 바꿈 | `.oauth2ResourceServer(...)` 미호출 + `SecurityConfigTest` 로 확인 |
| 애플 이름·이메일을 최초 1회에 못 받고 놓침 | 첫 로그인 트랜잭션에서 저장. 테스트로 고정 |
| 이관 쿼리가 운영 데이터에서 번호 충돌 | 토스 번호가 이미 중복 불가라 충돌할 수 없다. 이관 후 개수 대조 |
| 이관 직후 구버전이 만든 사용자가 신분증 없이 남음 | 인증 쓰기를 잠깐 멈추고 이관. 개수 대조로 확인 (§6.1) |
| `NOT NULL` 을 풀었는데 엔티티를 안 고쳐 첫 OAuth 가입이 실패 | `User` 수정을 같은 변경 단위에 포함 (§6.3) |
| 토큰 세대 번호 도입이 요청마다 DB 조회를 늘림 | Redis 캐싱. 부하 측정 후 결정 (§5.8) |
| 애플 SDK 의 원본/해시 규약이 서버와 어긋남 | 구현 중 실제 토큰 디코드로 확인 (§5.9) |

## 9. 열린 결정

| # | 결정 | 기본안 |
|---|---|---|
| D2 | 카카오 포함 여부 | 포함. 빼면 검증기 하나와 테스트가 줄어든다 |
| D6 | 이메일 같은 다른 회사 계정 자동 병합 | 하지 않는다 (§5.3) |
| D9 | 계정당 앱 로그인 수단 개수 | **하나.** 토스만 예외로 공존 (§5.1) |
| D12 | 앱에서 토스 로그인 지원 | **하지 않는다.** 공용 로그인 API 가 토스를 400 으로 거절 (§5.6) |
| D14 | 토큰 폐기 방식 | **사용자 토큰 세대 번호.** refresh 회전·재사용 탐지는 후속 (§5.8) |
| D15 | 이관 배포 방식 | **짧은 인증 정지 창.** 사용자 수가 적어 단계 배포는 과함 (§6.1) |
| D16 | 토스 신원 없는 사용자의 알림 API | **422 로 거절.** Phase 5 에서 FCM 붙이면 해제 (§5.10) |
| D17 | 기존 토스 사용자의 앱 전환 | **전환하지 않는다.** 미니앱과 앱은 별개 계정이다 (§5.11) |

## 10. codex 리뷰 반영 기록

2026-07-28 codex 설계 리뷰(session `019fa6ef-0b29-7b00-aadf-5c55181c10c6`)에서 High 6건·Medium 6건·Low 1건이 나왔다. 지적은 모두 실제 코드로 확인한 뒤 반영했다.

| 지적 | 반영 |
|---|---|
| refresh 토큰이 access 로 통과 | §5.7 신설 — `parseAccessUserId` 도입, `parseUserId` 삭제 |
| 로그아웃이 자체 토큰을 안 지움 | §5.8 신설 — 토큰 세대 번호(D14) |
| 애플 일회용 값이 서버에 안 묶임 | §5.9 신설 — 서버 발급·1회 소비 challenge |
| 구글 `azp` 미검증 | §5.2 표에 추가 |
| 토스↔앱 사용자 데이터 분리 | §5.11 신설 — 별개 계정을 제품 정책으로 확정(D17) |
| 애플 이름은 토큰에 없음 | §3.2·§5.2 정정 — 앱이 별도 전달, 신원에 사용 금지 |
| 알림 API 가 토스 전제 | §5.10 신설 — 422 거절 + 언박싱 방어 |
| 동시 로그인 409 오탐 | §6.5 — 재조회 후 성공 처리 |
| `User` 엔티티 nullable 불일치 | §6.3 — 엔티티 수정 항목 추가 |
| 이관·구버전 쓰기 경합 | §6.1 — 정지 창 + 개수 대조(D15) |
| Flyway vs 수동 적용 충돌 | §6.1 — Flyway 단일 실행으로 정정 |
| 잠금·롤백 계획 없음 | §6.1 — `lock_timeout`, forward-fix |
| 지운 사용자도 이관됨 | §6.1 SQL 에 `deleted_at IS NULL` |
| `sub` null·blank 미검증 | §5.2 규칙 추가 |
