# 앱 API 변경 목록 — 로그인 제거

> 결론: 앱이 고칠 API 는 **인증 4곳뿐**이다. 로그인 3개 호출을 지우고 `POST /api/auth/device` 하나로 바꾼다. 그 밖의 API(검색·즐겨찾기·여정·알림·push 토큰 등)는 지금처럼 `Authorization: Bearer <access>` 를 붙이면 그대로 동작한다.
> 대상: 독립 앱(iOS/Android). 앱인토스 빌드는 바꾸지 않는다(`/api/auth/toss/*` 그대로).
> 서버 PR: #328. 흐름·저장 방법 등 자세한 설명은 [HANDOFF.md](HANDOFF.md).

## 한눈에

| 구분 | API | 앱이 할 일 |
|---|---|---|
| 🆕 추가 | `POST /api/auth/device` | 첫 실행·토큰 복구 시 호출 |
| ❌ 호출 제거 | `POST /api/auth/oauth/challenge` | 지운다 |
| ❌ 호출 제거 | `POST /api/auth/oauth/login` | 지운다 |
| ❌ 호출 제거 | `POST /api/auth/logout` | 지운다(로그아웃 버튼 제거) |
| ✅ 그대로 | `POST /api/auth/reissue` | 계속 사용. 실패 시 처리만 바뀜(↓) |
| ✏️ 의미 변경 | `DELETE /api/auth/me` | "회원탈퇴" → "내 데이터 초기화" |
| ✏️ 응답 변경 | `GET /api/me` | `identities` 에 DEVICE 안 나옴, 이름·이메일 null 가능 |
| ✅ 그대로 | 그 밖의 모든 API | 변경 없음 |

> 서버는 구버전 앱을 위해 `/api/auth/oauth/*`·`/api/auth/logout` 을 당분간 살려 둔다. 새 앱에서만 호출을 지우면 된다.

---

## 🆕 `POST /api/auth/device` (공개, Bearer 선택)

요청
```json
{ "deviceSecret": "q3Zx...(43자)" }
```
- `deviceSecret`: 32바이트 난수를 base64url(패딩 없음)로 인코딩한 43자. 허용 `[A-Za-z0-9_-]`, 길이 43~128.
- 앱이 **첫 실행에 한 번 만들어 Keychain / EncryptedSharedPreferences 에 저장**하고 계속 같은 값을 보낸다.
- `Authorization: Bearer <access>` 는 **업데이트한 기존 애플·카카오 사용자가 기존 계정을 이어받을 때만** 붙인다. 그 외엔 붙이지 않는다.

응답 200 (기존 `/api/auth/oauth/login` 응답과 같은 모양)
```json
{ "accessToken": "eyJ...", "refreshToken": "eyJ...", "userId": 123, "provider": "DEVICE" }
```

오류 (본문 `{ "error": true, "code": "...", "message": "..." }`)

| HTTP | code | 언제 | 앱 처리 |
|---|---|---|---|
| 400 | `INVALID_DEVICE_SECRET` | 형식 위반 | 비밀값 생성 코드 버그. 새로 만들어 재시도 |
| 403 | `DEVICE_LINK_NOT_ALLOWED` | Bearer 계정에 애플·카카오 신원이 없음 | Bearer 빼고 재호출 |
| 409 | `DEVICE_ALREADY_LINKED` | Bearer 를 붙였는데 비밀값이 이미 다른 계정 소유 | **비밀값을 새로 만들어 같은 Bearer 로 재호출**(기존 데이터 유지) |
| 409 | `DEVICE_LINK_LIMIT_EXCEEDED` | Bearer 계정에 기기가 이미 3대 | Bearer 빼고 재호출 |
| 429 | `DEVICE_SIGNUP_RATE_LIMITED` | 같은 IP 에서 새 계정이 너무 많이 생김 | 잠시 후 재시도(백오프) |

## ❌ 지울 호출

| API | 이유 |
|---|---|
| `POST /api/auth/oauth/challenge` | 애플 로그인 nonce 용. 로그인이 없으니 불필요 |
| `POST /api/auth/oauth/login` | `/api/auth/device` 가 대체 |
| `POST /api/auth/logout` | 로그아웃해도 비밀값으로 바로 같은 계정에 돌아오므로 의미 없음 |

애플·카카오 로그인 SDK 호출과 로그인 화면도 같이 지운다.

## ✅ `POST /api/auth/reissue` — 그대로, 실패 처리만 변경

요청·응답은 변경 없음.
```json
요청 { "refreshToken": "eyJ..." }
응답 200 { "accessToken": "eyJ...", "refreshToken": "eyJ..." }   // 둘 다 교체 저장
실패 401
```
- **바뀐 점**: 401 이면 지금까지는 로그인 화면으로 보냈지만, 이제는 **Bearer 없이 `POST /api/auth/device` 를 다시 부른다**. 같은 비밀값이면 같은 계정이 돌아온다(refresh 14일 만료도 이걸로 해결).

## ✏️ `DELETE /api/auth/me` — "내 데이터 초기화"

- 요청·응답 변경 없음(Bearer 필수, 204).
- 화면 문구를 "회원탈퇴" → "내 데이터 초기화"로 바꾼다.
- 204 후 **비밀값을 새로 만들어 저장**하고 `POST /api/auth/device` 로 새 계정을 받는다. 옛 토큰은 모두 401 이 된다.

## ✏️ `GET /api/me` — 응답 값 변화

모양은 그대로다.
```json
{ "userId": 123, "displayName": null, "email": null, "identities": [] }
```
- `identities` 에 `DEVICE` 는 **절대 나오지 않는다**. 기기 계정만 있으면 빈 배열이다. 기존 애플·카카오 사용자는 `APPLE`/`KAKAO` 만 보인다.
- `displayName`·`email` 은 null 일 수 있다 — 화면에서 이름 표시를 null 안전하게 처리하거나 제거한다.

## ✅ 그 밖의 API — 변경 없음

- 보호 API(`/api/route/fav/**`, `/api/route/my/**`, `/api/notifications/**`, `/api/journeys/**`, `/api/me/**`)는 **기기 계정 토큰으로 그대로 통과**한다. 호출 코드 변경 없음.
- 이제 앱 사용자 전원이 토큰을 가지므로 "로그인 안 했으면 이 기능 숨김" 분기는 지운다 — 항상 토큰이 있다.
- 토스 메신저 알림(`/api/notifications/bus-alert`, `/api/notifications/route-alert` 생성)은 지금 애플 사용자처럼 **422 `TOSS_IDENTITY_REQUIRED`** 를 받는다. 동작 변화 없음(앱인토스 전용 기능).

---

## 호출 순서 요약

```
[앱 시작]
 ├─ 저장된 access/refresh 있음 ─→ 평소처럼 사용
 │                                └ 401 → reissue → 401 → POST /api/auth/device (Bearer 없이)
 │
 ├─ 옛 버전의 애플·카카오 토큰만 있음(업데이트 직후, 1회)
 │    reissue 성공 → POST /api/auth/device + Bearer(새 access)
 │       ├ 200 → 기존 계정 유지, 이후 위와 같음
 │       ├ 409 DEVICE_ALREADY_LINKED → 비밀값 새로 생성 → 같은 Bearer 로 재호출
 │       └ 403 / 409 LIMIT → Bearer 없이 재호출
 │    reissue 네트워크·5xx 실패 → 재시도(Bearer 없는 호출 금지)
 │    reissue 401 → 옛 토큰 버리고 아래 "신규"로
 │
 └─ 아무것도 없음(신규 설치)
      비밀값 생성·저장 → POST /api/auth/device (Bearer 없이)
```

## 체크리스트

- [ ] 비밀값 생성(32바이트 CSPRNG → base64url 43자)·보관(Keychain / EncryptedSharedPreferences)
- [ ] `POST /api/auth/device` 클라이언트 + 오류 5종 처리
- [ ] `oauth/challenge`·`oauth/login`·`logout` 호출과 로그인 화면·SDK 제거
- [ ] reissue 401 → `/api/auth/device` 재호출로 교체
- [ ] 업데이트 사용자 1회 이행(옛 토큰 → Bearer 연결)
- [ ] "회원탈퇴" → "내 데이터 초기화"(204 후 비밀값 재생성)
- [ ] `/api/me` 이름·이메일 null 처리, DEVICE 없는 `identities` 대응
- [ ] "로그인 안 됨" 분기 제거
- [ ] 비밀값이 로그·크래시 리포트에 안 찍히는지 확인
- [ ] App Store 개인정보 라벨에서 이메일·이름 수집 제거
