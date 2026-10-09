# 모바일 인계 — 로그인 제거, 기기 익명 계정

> 결론: 독립 앱은 로그인 화면을 없애고, 첫 실행에 만든 비밀값 하나로 `POST /api/auth/device` 를 불러 토큰을 받는다. 이후 API 호출·갱신은 지금과 똑같이 Bearer + `/api/auth/reissue` 다.
> 앱인토스(토스 미니앱) 빌드는 바꾸지 않는다 — 토스 로그인 그대로.

## 1. 비밀값 만들기·보관
- 첫 실행에 **32바이트 암호학적 난수** → base64url(패딩 없음, 43자). 허용 문자 `[A-Za-z0-9_-]`, 길이 43~128.
- iOS: Keychain(`kSecAttrAccessibleAfterFirstUnlock`, iCloud 동기화 끔). 재설치해도 대개 남는다.
- Android: EncryptedSharedPreferences. 재설치하면 사라진다(수용한 손실).
- IDFV·광고 ID·기기 모델 등을 비밀값 대신 쓰지 말 것. 남이 알 수 있는 값이면 그 사람이 계정에 들어온다.
- 비밀값은 로그·크래시 리포트·분석 이벤트에 절대 싣지 않는다.

## 2. API
`POST /api/auth/device` (공개)
```json
요청 { "deviceSecret": "<43자 base64url>" }
응답 200 { "accessToken": "...", "refreshToken": "...", "userId": 123, "provider": "DEVICE" }
```
| 상황 | 응답 |
|---|---|
| 형식 위반 | 400 `INVALID_DEVICE_SECRET` |
| Bearer 의 계정이 애플·카카오 계정이 아님(토스 전용·기기 전용) | 403 `DEVICE_LINK_NOT_ALLOWED` |
| Bearer 를 붙였는데 비밀값이 이미 다른 계정에 묶임 | 409 `DEVICE_ALREADY_LINKED` |
| Bearer 계정에 기기가 이미 3대 | 409 `DEVICE_LINK_LIMIT_EXCEEDED` |
| 같은 출발지 IP 의 새 가입이 시간당 한도 초과 | 429 `DEVICE_SIGNUP_RATE_LIMITED` (잠시 후 재시도) |

오류 본문: `{ "error": true, "code": "...", "message": "..." }`

## 3. 흐름
1. **신규 설치**: 비밀값 생성·저장 → Bearer 없이 `/api/auth/device` → 토큰 저장.
2. **업데이트한 기존 애플·카카오 사용자**(저장된 옛 토큰이 있음):
   1. 옛 refresh 로 `/api/auth/reissue` 를 먼저 시도한다.
   2. 성공하면 **새 access 를 Bearer 로 붙여** `/api/auth/device` 호출 → 기존 계정에 기기가 붙고 데이터가 그대로다. 이후부터는 1번 사용자와 같다.
   3. reissue 가 네트워크·5xx 로 실패하면 **Bearer 없는 호출을 하지 말고 재시도**한다. 먼저 익명으로 부르면 빈 기기 계정이 생긴다.
   4. reissue 가 401 로 확정 실패한 경우에만 옛 토큰을 버리고 1번처럼 새 계정으로 간다(옛 데이터는 복구 불가).
3. **Bearer 호출이 409 `DEVICE_ALREADY_LINKED`**: 이 비밀값이 이미 다른(대개 실수로 먼저 생긴 빈) 계정에 묶였다는 뜻. Bearer 를 버리지 말고 **비밀값을 새로 만들어 같은 Bearer 로 다시 호출**한다 → 기존 계정 데이터 유지.
4. **403 `DEVICE_LINK_NOT_ALLOWED`·409 `DEVICE_LINK_LIMIT_EXCEEDED`**: Bearer 없이 호출해 기기 계정으로 진행.
5. **401 처리(평소)**: reissue → 실패하면 Bearer 없이 `/api/auth/device` 재호출. 비밀값만 있으면 같은 계정으로 언제든 돌아온다(refresh 14일 만료도 이걸로 해결).

## 4. 화면·설정
- 로그인 화면, 애플·카카오 SDK 로그인 호출 제거.
- 설정의 "로그아웃" 제거(로그아웃해도 비밀값으로 바로 같은 계정이 된다).
- "회원탈퇴" → **"내 데이터 초기화"**: `DELETE /api/auth/me`(204) 후 **비밀값을 새로 생성**하고 1번 흐름.
- `GET /api/me`: `displayName`·`email` 은 null 일 수 있다. `identities` 에 DEVICE 는 나오지 않는다(기기 계정만 있으면 빈 배열).

## 5. 스토어
- App Store 개인정보 라벨에서 이메일·이름 수집 항목 제거(애플·카카오 로그인을 안 쓰면).
- 로그인이 없으므로 4.8(애플 로그인 의무)·계정삭제 의무 대상이 아니다. "내 데이터 초기화"는 남겨 둔다.

## 6. 서버 쪽 남은 것
- 애플·카카오 로그인 API 와 기기 연결 분기는 구버전 앱이 사라질 때까지 유지, 이후 서버에서 삭제한다. 그 시점을 알려 달라(최소 지원 버전 강제 시점).
