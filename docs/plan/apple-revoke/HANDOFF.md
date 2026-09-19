# 모바일 HANDOFF — 애플 로그인에 `authorizationCode` 추가

## 한 줄 결론

**애플 로그인 요청 본문에 `authorizationCode` 한 필드를 추가해 주세요.** 나머지 흐름은 그대로입니다.
이 값이 없으면 서버가 애플 로그인을 **400 으로 거부**합니다(실사용자 0인 지금 필수로 못 박음).

## 왜 필요한가

App Store 심사 지침 5.1.1(v): 애플로 가입한 사용자가 **탈퇴하면 서버가 애플 연결을 끊어야(revoke)**
합니다. revoke 하려면 애플 `refresh_token` 이 있어야 하고, 그 토큰은 로그인 때 받은
`authorizationCode` 를 서버가 애플에 교환해야만 나옵니다.

- `authorizationCode` = 애플이 **매 로그인**마다 발급하는 일회용 값(최초 로그인만이 아님).
- 서버가 이걸 refresh_token 으로 교환 → 암호화 저장 → 탈퇴 때 revoke 에 사용.
- 앱은 그냥 이 값을 로그인 요청에 실어 보내기만 하면 됩니다. 교환·저장·revoke 는 전부 서버가 함.

---

## iOS — `authorizationCode` 어디서 얻나

`ASAuthorizationAppleIDCredential.authorizationCode` 입니다. `Data` 타입이라 **UTF-8 문자열로 디코드**해서 보냅니다.

```swift
func authorizationController(controller: ASAuthorizationController,
                             didCompleteWithAuthorization authorization: ASAuthorization) {
    guard let cred = authorization.credential as? ASAuthorizationAppleIDCredential,
          let identityTokenData = cred.identityToken,
          let identityToken = String(data: identityTokenData, encoding: .utf8),
          let authCodeData = cred.authorizationCode,
          let authorizationCode = String(data: authCodeData, encoding: .utf8)  // ← 추가 추출
    else { return }

    // credential = identityToken, authorizationCode = authorizationCode 를 로그인 요청에 실어 보낸다.
}
```

- **매 로그인 발급됨.** "최초 로그인만 준다"는 건 이메일·이름(`fullName`) 얘기지 code 가 아닙니다.
- 일회용·수명 짧음(수 분). 받으면 **바로** 로그인 요청에 실어 보내세요(캐싱·재사용 금지 — 이미 쓴 code 는 교환 실패).

---

## 전체 로그인 흐름 (신규는 ③의 `authorizationCode` 뿐)

### ① nonce 발급 — `POST /api/auth/oauth/challenge`

요청 본문 없음. 응답:
```json
{ "nonce": "서버가-발급한-원본-nonce", "expiresInSeconds": 300 }
```

### ② 애플에 로그인 요청 (앱)

- `ASAuthorizationAppleIDRequest.nonce` = **`SHA256(원본 nonce)` 의 소문자 hex 문자열**.
  (서버가 id_token 의 nonce claim 을 `sha256Hex(원본)` 과 대조합니다. 애플 표준 절차.)
- 스코프: 이름·이메일 필요 시 그대로.

```swift
request.nonce = sha256Hex(nonce)   // 서버가 준 원본 nonce 를 SHA256 → 소문자 hex
```

### ③ 서버에 로그인 — `POST /api/auth/oauth/login`

```json
{
  "provider": "APPLE",
  "credential": "<애플 identity token(JWT) 문자열>",
  "nonce": "<①에서 받은 원본 nonce. SHA256 아님, 원본 그대로>",
  "displayName": "<최초 로그인 시 이름, 없으면 생략/null>",
  "authorizationCode": "<②에서 받은 authorizationCode 문자열>"   // ★ 신규 필수 필드
}
```

| 필드 | 필수 | 값 |
|---|---|---|
| `provider` | ✅ | `"APPLE"` (문자열 enum) |
| `credential` | ✅ | 애플 identity token(JWT) |
| `nonce` | APPLE ✅ | ①의 **원본** nonce (애플에 넣은 SHA256 아님) |
| `displayName` | 선택 | 최초 로그인 이름. 표시용, 신원판단 안 함 |
| **`authorizationCode`** | **APPLE ✅** | **②의 code. 없으면 400** |

성공 응답 `200`:
```json
{ "accessToken": "...", "refreshToken": "...", "userId": 123, "provider": "APPLE" }
```

---

## 에러 계약

응답 형식(에러): `{ "error": true, "code": "<코드>", "message": "..." }`

| 상황 | HTTP | code |
|---|---|---|
| **`authorizationCode` 누락(APPLE)** | **400** | **`MISSING_AUTHORIZATION_CODE`** |
| identity token·nonce 검증 실패 | 401 | `OAUTH_VERIFICATION_FAILED` |
| provider 에 TOSS 등 지정 | 400 | `PROVIDER_NOT_ALLOWED` |
| 이미 다른 계정에 연결된 신원 | 409 | `IDENTITY_ALREADY_LINKED` |

> **교환 실패는 로그인을 막지 않습니다.** code 를 보냈는데 서버-애플 교환이 어쩌다 실패해도(예: 재사용된
> code) 로그인 자체는 성공합니다(서버가 로그만 남김). 단 그 세션은 refresh_token 이 없어 탈퇴 시 revoke
> 가 생략될 수 있으니, **code 는 항상 새로 받은 값을 보내는 것**이 중요합니다.

---

## 릴리스 조율

- 서버는 **이미 배포 완료**(자격·서명 체인 실제 애플 검증 통과). 앱만 붙이면 동작.
- `authorizationCode` 를 **필수**로 못 박아 둔 상태라, 이 필드를 안 보내는 구버전 앱은 애플 로그인이 400
  으로 막힙니다. 실사용자 0이라 안전하지만, **이 필드를 넣은 앱 빌드로 심사 재제출**이 필요합니다.
- 검증(앱 반영 후): 애플 로그인 1회 → 서버 `apple_user_token` 1행 저장 → 탈퇴 → 서버 로그
  `[AppleRevoke] 연결 폐기 성공: userId=...` → Apple ID 설정의 "이 App으로 로그인" 목록에서 앱 사라짐.
