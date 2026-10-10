# 로그인 제거 → 기기 익명 계정 (device-anonymous-auth, L 티어)

> 결론: 독립 앱은 로그인 화면 없이 **기기 비밀값 하나로 계정을 받는다**. 서버의 users 행·JWT·user_id 테이블은 그대로 두고, 신원을 증명하는 입구만 하나 추가한다.
> 비유: 회원증(애플·카카오 계정) 대신 사물함 열쇠(기기 비밀값). 사물함은 그대로, 여는 열쇠만 바뀐다.

## 0. 배경·결정 (사용자, 2026-10-09)
- 독립 앱에서 로그인을 **아예 없앤다**(선택형 로그인도 안 남김).
- 앱인토스(토스 미니앱)는 **살아있다** → `/api/auth/toss/*`, `TossLoginService`, TOSS identity 는 손대지 않는다.
- 이유: 로그인이 막는 기능(즐겨찾기·알림·여정·검색이력·push 토큰)은 "데이터 주인 / 푸시 대상" 구분용 → 기기 단위로 충분. 애플 5.1.1(v)(강제 로그인 거절), 4.8(애플 로그인 의무), 계정삭제 의무가 같이 사라진다.
- 수용한 손실: 기기 교체 시 데이터 이전 불가(iOS 재설치는 Keychain 으로 대개 유지, Android 재설치는 유실), 다기기 동기화 없음.
- V17 에서 `users.device_id` 를 지우고 토스 키로 통일했던 결정의 **반전**이다. 이번엔 users 컬럼이 아니라 `auth_identity` 한 provider 로 넣는다(V33 이 만든 다중 신원 구조를 그대로 탄다).

## 1. 현재 구조 (조사로 확정, 경로는 `src/main/java/watoo/grd/nextroute/` 기준)
- 인증: `common/security/JwtAuthFilter.java` → principal = `Long userId`, `@AuthenticationPrincipal Long userId`. 토큰 무효화 = `users.token_generation`(V34). refresh 는 상태 없음(14일, `application.yaml:229-235`).
- 공개 여부: `common/security/SecurityConfig.java:44-67` — 적어 둔 경로만 authenticated, 나머지 permitAll. 익명 요청의 `@AuthenticationPrincipal Long` 은 null.
- 가입 패턴: `application/auth/service/OAuthLoginService.java`
  - `login:90` → 신원 조회(`findByProviderAndProviderSubjectAndDeletedAtIsNull`) → 없으면 `signUp:159`
  - `signUp`: `userRepository.saveAndFlush(User.builder().build())` + `AuthIdentity` 저장, `DataIntegrityViolationException` 시 `resolveRace:183`(TransactionTemplate REQUIRES_NEW 재조회)
  - `issueTokens:196`: `jwtProvider.createAccessToken/createRefreshToken(userId, generation)` → `OAuthLoginResult(access, refresh, userId, provider)`
- `users.toss_user_key` 는 V33 부터 nullable → 익명 user 행 생성 가능.
- `auth_identity`(V33): CHECK `auth_identity_provider_check provider in ('TOSS','APPLE','KAKAO')`. 부분 유니크(전부 `WHERE deleted_at IS NULL`):
  - `uq_auth_identity_provider_subject (provider, provider_subject)`
  - `uq_auth_identity_user_provider (user_id, provider)`
  - `uq_auth_identity_single_app_provider (user_id) WHERE provider <> 'TOSS'`
- `domain/auth/entity/AuthProvider.java`: `TOSS(false,false) APPLE(true,true) KAKAO(true,true)` — (appExclusive, appLoginAllowed). javadoc 에 "TOSS 만 false" 문구 있음 → DEVICE 추가 시 문구 수정.
- 탈퇴: `WithdrawController` → `AppleRevocationOnWithdrawal.revokeIfApple`(토큰 없으면 no-op) → `WithdrawService.withdraw:95-144`(auth_identity 물리삭제, deleted_at, generation++). provider 무관 → 기기 사용자에게 "데이터 초기화"로 그대로 쓴다.
- 토스 메신저 알림(`BusAlertService:130`, `RouteArrivalAlertService:245`)은 `User.hasTossIdentity()` 게이트 → 기기 사용자는 애플 사용자와 똑같이 막힘. 변화 없음.
- rate limit 인프라 없음. 패턴 원본 = `infrastructure/adapter/out/cache/bus/RedisBusApiQuota.java`(INCR, count==1 이면 expire, 예외·null → fail-closed).
- 프록시: VPS nginx `api.refit-100.site` 가 `proxy_set_header X-Real-IP $remote_addr`, 앱은 `127.0.0.1:8080` 만 노출 → `X-Real-IP` 신뢰 가능(없으면 `request.getRemoteAddr()`).
- 최신 마이그레이션 = **origin/main 기준 V61**(`V61__journey_leg_vehicle.sql`, 2026-10-10 확인, 마이그레이션 추가하는 열린 PR 없음) → 신규 **V62**. 구현 착수 시 `git ls-tree origin/main src/main/resources/db/migration/` 로 재확인. 폴더는 전부 git tracked(`e191bcab`)지만 VPS 배포는 **수동배치** 관례 유지.
- `MeResponse.identities`(`MeResponse.java:17`)는 신원 목록을 그대로 내보낸다 → DEVICE 는 **목록에서 뺀다**(구버전 앱이 provider 를 엄격 enum 으로 디코딩하면 깨질 수 있음, 기기 여러 대면 중복 노출).

## 2. 계약

### `POST /api/auth/device` (public)
요청:
```json
{ "deviceSecret": "<base64url, 43~128자, [A-Za-z0-9_-]>" }
```
- 앱이 첫 실행에 32바이트 난수 → base64url(43자) 생성 → iOS Keychain(`kSecAttrAccessibleAfterFirstUnlock`) / Android EncryptedSharedPreferences 저장.
- 서버 저장 = `provider_subject = hex(sha256(deviceSecret))`(64자). **원문 저장·로그 금지.** IDFV·광고 ID 는 신원에 쓰지 않는다(비밀값이 아님 → 아는 사람이면 행세 가능).
- 형식 위반 → 400 `INVALID_DEVICE_SECRET`.

응답 200 = 기존 `OAuthLoginResponse` 와 같은 모양(access, refresh, userId, provider=`DEVICE`). 갱신은 기존 `POST /api/auth/reissue`, 정보는 `GET /api/me`.

분기:
| 해시 상태 | Bearer 없음 | 유효 Bearer 있음(업데이트한 기존 애플·카카오 사용자) |
|---|---|---|
| 미등록 | 새 user + DEVICE identity 생성 (**가입 rate limit 적용**) | 연결 조건 충족 시 현재 user 에 DEVICE identity **연결** → 현재 user 토큰 (조건은 아래) |
| 같은 user 에 등록 | 토큰 발급, `last_login_at` 갱신 | 토큰 발급 |
| 다른 user 에 등록 | 그 user 토큰 발급 | **409 `DEVICE_ALREADY_LINKED`** |

- **연결 조건**(훔친 access 토큰 1시간 안에 공격자 비밀값을 영구 연결하는 경로를 좁힘 — 로그아웃은 DEVICE 신원을 안 지운다):
  - 현재 user 에 살아있는 APPLE 또는 KAKAO 신원이 있어야 한다. TOSS 만 있는 계정(미니앱 전용) → **403 `DEVICE_LINK_NOT_ALLOWED`**(미니앱 계정과 독립 앱 계정이 섞이는 것 방지).
  - 한 user 의 살아있는 DEVICE 신원은 **최대 3개**. 초과 → **409 `DEVICE_LINK_LIMIT_EXCEEDED`**.
  - 연결 분기는 **구버전 앱 이행용 한시 통로**다. 애플·카카오 로그인 코드 삭제 PR(§6)에서 같이 없앤다.
- "유효 Bearer" 판정 = `JwtAuthFilter` 가 이미 principal 을 세팅했는지(`@AuthenticationPrincipal Long userId != null`). 무효 Bearer 는 익명으로 떨어진다 → 새 계정 생성 경로(의도된 동작, HANDOFF 에 명시).
- 가입 경합: 같은 비밀값 동시 첫 호출 → `signUp` 과 같은 `DataIntegrityViolationException` → REQUIRES_NEW 재조회 패턴.
- 초과 → 429 `DEVICE_SIGNUP_RATE_LIMITED`.
- 클라이언트 IP = `X-Real-IP`(nginx 가 `$remote_addr` 로 덮어씀, 앱 포트는 127.0.0.1 바인딩) → 없으면 `getRemoteAddr()`. **`X-Forwarded-For` 는 읽지 않는다**(위조 가능). IPv6 는 **/64 프리픽스**로 키를 잡는다(대역 안 주소 회전 우회 방지).

## 3. 스키마 — `V62__add_device_auth_identity.sql`
```sql
ALTER TABLE auth_identity DROP CONSTRAINT auth_identity_provider_check;
ALTER TABLE auth_identity ADD CONSTRAINT auth_identity_provider_check
    CHECK (provider IN ('TOSS', 'APPLE', 'KAKAO', 'DEVICE'));

-- 애플·카카오 사용자가 기기 신원을 같이 가질 수 있게(업데이트 사용자 데이터 보존)
DROP INDEX uq_auth_identity_single_app_provider;
CREATE UNIQUE INDEX uq_auth_identity_single_app_provider
    ON auth_identity (user_id) WHERE provider NOT IN ('TOSS', 'DEVICE') AND deleted_at IS NULL;

-- 한 사용자가 기기 여러 대(아이폰+아이패드)를 각각 연결할 수 있게
DROP INDEX uq_auth_identity_user_provider;
CREATE UNIQUE INDEX uq_auth_identity_user_provider
    ON auth_identity (user_id, provider) WHERE provider <> 'DEVICE' AND deleted_at IS NULL;
```
- `(provider, provider_subject)` 유니크는 그대로 → 같은 해시는 한 사용자만.
- 인덱스 이름 3개·조건·`auth_identity_provider_check` 이름은 V33 원본과 일치함을 리뷰에서 확인. `AuthIdentity.java:24-28` javadoc 의 제약 목록도 같이 고친다.
- ddl-auto 를 쓰는 IT(`WithdrawalDeletionBoundaryIT:63` create-drop)는 Hibernate 6 가 enum CHECK 를 엔티티 값으로 만들어 DEVICE 를 자동 반영. 부분 유니크 인덱스는 ddl-auto 로 안 생기므로 제약·경합 검증은 Flyway 를 타는 IT 에서만.

## 4. 작업 순서
- [ ] **0. 브랜치**: fresh main 에서 `feat/device-anonymous-auth`(스택 금지, worktree 미사용). 이 PLAN/SUMMARY 를 첫 커밋에 포함.
- [ ] **1. V62 + 제약 IT** (의존: 없음)
  - `src/main/resources/db/migration/V62__add_device_auth_identity.sql`
  - `src/test/java/watoo/grd/nextroute/domain/auth/AuthIdentityConstraintIT.java` 케이스 추가: 애플+DEVICE 공존 OK / DEVICE 2개 한 user OK / 같은 DEVICE 해시 두 user 거절 / 애플+카카오 여전히 거절(회귀).
  - 검증: `./gradlew test --tests '*AuthIdentityConstraintIT'`
- [ ] **2. 도메인** (의존: 1)
  - `AuthProvider` 에 `DEVICE(false, false)` 추가 + javadoc "TOSS 만 false" → "TOSS·DEVICE". `AuthIdentity.java:24-28` javadoc 제약 목록 갱신.
  - `appLoginAllowed=false` 라 `/api/auth/oauth/login` 은 `ProviderNotAllowedException` 으로 거절됨 — `OAuthLoginServiceTest` 에 DEVICE 거절 케이스 1개.
- [ ] **3. 가입 rate limit** (의존: 없음)
  - port: `application/auth/port/out/DeviceSignupLimitPort.java` — `boolean tryAcquire(String clientIp)`
  - adapter: `infrastructure/adapter/out/cache/auth/RedisDeviceSignupLimitAdapter.java` — 키 `auth:device:signup:{ipKey}:{yyyyMMddHH}`(ipKey = IPv4 그대로 / IPv6 는 /64 프리픽스), INCR, count==1 이면 expire 1h. null/예외 → **fail-open(true) + ERROR 로그 + 카운터 메트릭**(`RedisBusApiQuota` 는 유료 쿼터라 fail-closed 지만, 여기는 users 행 생성이라 Redis 장애 동안 신규 사용자 전원이 앱을 못 쓰는 쪽이 더 나쁘다). INCR/expire 구조는 `RedisBusApiQuota` 복제.
  - 설정: 별도 Properties 클래스 없이 `@Value("${auth.device.signup-limit-per-hour:60}")`, `application.yaml` 의 `auth:` 아래 `device.signup-limit-per-hour: ${AUTH_DEVICE_SIGNUP_LIMIT_PER_HOUR:60}`(이통사 CGNAT 로 한 IP 에 사용자가 몰리므로 60).
  - 단위 테스트: 한도 내 true / 초과 false / Redis 예외 true(fail-open) / 첫 INCR 에만 expire / IPv6 같은 /64 는 같은 키.
- [ ] **4. 서비스** (의존: 2, 3)
  - `application/auth/port/in/DeviceLoginUseCase.java` — `OAuthLoginResult login(String deviceSecret, Long currentUserIdOrNull, String clientIp)`
  - `application/auth/service/DeviceLoginService.java` — 2절 분기표 그대로. 해시는 `MessageDigest SHA-256` → hex(256비트 난수라 salt·pepper 불필요). 형식 검증(정규식 `^[A-Za-z0-9_-]{43,128}$`) 실패 → `InvalidDeviceSecretException`. 409 → `DeviceAlreadyLinkedException` / `DeviceLinkLimitExceededException`. 403 → `DeviceLinkNotAllowedException`. 429 → `DeviceSignupRateLimitedException`. 가입 경합 재조회는 `OAuthLoginService.resolveRace` 와 같은 TransactionTemplate REQUIRES_NEW 방식. 토큰 발급은 `OAuthLoginService.issueTokens` 와 같은 몇 줄을 그대로 둔다(헬퍼 추출 안 함). **원문 비밀값은 로그·예외 메시지에 절대 넣지 않는다.**
  - rate limit 은 **새 user 를 만드는 분기에서만** 호출(재진입·연결은 무제한).
  - 연결 분기: 이미 그 user 의 DEVICE 로 등록된 해시면 그냥 토큰.
- [ ] **5. 컨트롤러** (의존: 4)
  - `infrastructure/adapter/in/api/auth/DeviceAuthController.java` `@RequestMapping("/api/auth")` `@PostMapping("/device")`, `@AuthenticationPrincipal Long userId`(nullable).
  - `HttpServletRequest` 에서 `X-Real-IP` → 없으면 `getRemoteAddr()`(XFF 무시).
  - dto: `infrastructure/adapter/in/api/auth/dto/DeviceLoginRequest.java`(`@NotBlank deviceSecret`), 응답은 `OAuthLoginResponse.from(result)` 재사용.
  - 예외 → `{code, message}` 형식은 `OAuthController` 의 `@ExceptionHandler`/`error(...)` 스타일을 따른다: 400 `INVALID_DEVICE_SECRET`, 403 `DEVICE_LINK_NOT_ALLOWED`, 409 `DEVICE_ALREADY_LINKED` / `DEVICE_LINK_LIMIT_EXCEEDED`, 429 `DEVICE_SIGNUP_RATE_LIMITED`.
  - `SecurityConfig` 규칙 추가 없음(기본 permitAll) — 대신 슬라이스 테스트로 "익명 200" 고정.
- [ ] **6. /api/me** — `MeResponse.identities` 에서 DEVICE 제외. `SessionServiceTest`/`MeControllerTest`: DEVICE 만 가진 user → 이름·이메일 null, identities 빈 목록 / 애플+DEVICE → identities=[APPLE], 이름은 애플 쪽(`preferAppProvider` 가 DEVICE null 을 건너뜀).
- [ ] **7. 끝단 IT** — `src/test/java/watoo/grd/nextroute/application/auth/DeviceLoginIT.java`(`TokenGenerationIT` 스타일, `@SpringBootTest @AutoConfigureMockMvc`, 수집기·relay off). ⚠ 기본 프로필 IT 는 로컬 개발 DB·실제 Redis 를 쓴다(`application.yaml:3-6`) → 비밀값은 **매번 무작위 생성**, 한도는 `@TestPropertySource(auth.device.signup-limit-per-hour=100000)` 로 덮어써 반복 실행 시 429·해시 충돌을 막는다. 시나리오: device 발급 → `GET /api/route/fav` 200 → `DELETE /api/auth/me` 204 → 같은 비밀값 재호출 → **새 userId** + 옛 토큰 401.
- [ ] **8. 문서**
  - `docs/plan/device-anonymous-auth/HANDOFF.md`(모바일 인계, 5절 내용)
  - `implementation_note.md` 에 `### 확정`(V17 반전 이유, 해시만 저장, 무효 Bearer=익명 가입, 연결 조건·한시 통로, rate limit fail-open 이유), `### 배포`(**V62 VPS 수동배치 필요**, env `AUTH_DEVICE_SIGNUP_LIMIT_PER_HOUR` 선택), `### 검증`(명령+결과).
- [ ] **9. 전체 검증** → PR (body 에 경계 리뷰 4항목: 계약·상태전이·실패경로·플래그 기본값).

## 5. 모바일 인계 (HANDOFF.md 에 옮길 내용)
1. 첫 실행: 비밀값 생성·저장 → `POST /api/auth/device` → 토큰 저장. 로그인 화면·애플/카카오 SDK 호출 제거.
2. **업데이트 사용자**: 저장된 기존 토큰이 있으면 먼저 reissue 로 살린 뒤 **Bearer 를 붙여 첫 호출** → 기존 데이터 유지. reissue 가 일시 실패(네트워크·5xx)면 **Bearer 없는 호출 금지, 재시도**(먼저 익명 호출하면 빈 기기 계정이 생긴다). reissue 가 401 로 확정 실패한 경우에만 기존 토큰을 버리고 새 계정(복구 불가, 수용한 손실).
3. 401 처리: reissue → 실패하면 `/api/auth/device` 재호출(비밀값만 있으면 같은 계정으로 언제든 복구; refresh 14일 만료도 이걸로 해결).
4. Bearer 호출이 409 `DEVICE_ALREADY_LINKED`: 그 비밀값이 이미 다른(대개 빈) 기기 계정에 묶였다는 뜻. **Bearer 를 버리지 말고 비밀값을 새로 생성해 Bearer 와 함께 재호출** → 기존 계정 데이터 보존, 빈 기기 계정은 버려진다. 403 `DEVICE_LINK_NOT_ALLOWED`(미니앱 전용 계정)·409 `DEVICE_LINK_LIMIT_EXCEEDED` 는 Bearer 없이 호출해 새 기기 계정으로 진행.
5. 설정 화면: "로그아웃" 제거. "회원탈퇴" → "내 데이터 초기화" = `DELETE /api/auth/me` 후 **비밀값을 새로 생성**(같은 비밀값이면 새 user 가 생기긴 하지만 명확성을 위해 교체).
6. `GET /api/me` 의 이름·이메일은 null 일 수 있고, `identities` 에 DEVICE 는 나오지 않는다.
7. 앱스토어 개인정보 라벨: 이메일·이름 수집 제거.
8. 앱인토스 빌드는 변경 없음(토스 로그인 유지).

## 6. 범위 밖 / 후속
- 애플·카카오 로그인 코드·엔드포인트 삭제 + **기기 연결 분기 삭제**: 구버전 앱이 쓰는 동안 유지. 구버전 소멸 뒤 별도 PR(애플 revoke 는 기존 애플 사용자 탈퇴용으로 남겨야 할 수 있음 — 그때 결정).
- 여정 생성 남용(지하철 위치 API 쿼터) 제한: 계정 하나로도 여정을 무제한 만들 수 있어 가입 제한으로는 못 막는다 → 별건(user 당 활성 여정 상한 등).
- 이슈 #320(track 익명 401): 앱 사용자는 전부 토큰을 갖게 되므로 "로그인 필요 유지"로 종결 제안.

## 7. 검증 (완료 조건)
> 리뷰 이력: 2026-10-10 Opus 5.5 리뷰 12건 반영(번호 V62, 연결 조건, fail-open, IP 키, IT 격리, /api/me DEVICE 제외, 409 처리).

- `./gradlew compileJava compileTestJava`
- `./gradlew test --tests '*auth*' --tests '*Auth*' --tests '*DeviceLogin*' --tests '*SecurityConfig*'` 통과, 기존 auth 테스트 회귀 없음.
- 계약당 테스트: 분기표 6칸 + 연결 조건 2건(TOSS 만 → 403, DEVICE 3개 → 409) + 형식 400 + 429 + 재진입·연결은 한도 미차감 + 경합 재조회 + `/oauth/login` DEVICE 거절 + X-Real-IP 우선·getRemoteAddr 폴백 + 원문 비밀값 로그 미노출 + `/api/me` DEVICE 제외 + 제약 IT 4건 + 끝단 IT.
- 결과(명령+출력 요약)를 `implementation_note.md ### 검증` 에. 기존부터 깨진 테스트는 "기존 무관" 라벨.
- 배포 후(다른 세션/사용자 승인): V62 수동배치 → api 교체 → VPS 에서 curl 로 `/api/auth/device` 발급 → `/api/me` 200.
