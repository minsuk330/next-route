# 세션 관리 — 로그아웃·재발급·내 정보

> 티어 **M**. 기준 브랜치: fresh `main` → `feat/auth-session-management`
> 선행: PR #87(OAuth 신원) 머지 완료 (`main` = `5a16aef`)
> 출처: `docs/plan/journey-auth-oauth/PLAN.md` §5.8, D14

---

## 0. 결론

**로그아웃이 실제로 세션을 끝내게 만든다.** 지금은 앱 사용자가 로그아웃해도 서버가 하는 일이 없고, 이미 발급된 access 토큰이 만료(1시간)까지 계속 통한다.

방법은 **토큰 세대 번호**다. `users`에 정수 칸 하나를 두고 JWT에 같이 담는다. 로그아웃하면 번호를 올린다. 요청이 올 때 토큰 속 번호와 DB 번호가 다르면 거부한다.

같은 PR에서 앱 전용 재발급 경로와 `/api/me`도 함께 낸다. 셋 다 "로그인한 뒤"를 다루고, 세대 번호를 공유한다.

---

## 1. 지금 상태 (확인 완료)

| 사실 | 근거 |
|---|---|
| 앱 로그인은 `/api/auth/oauth/{challenge,login}` 둘뿐 | `OAuthController.java:45,53` |
| 로그아웃은 토스 경로만 존재 | `SecurityConfig.java:42`, `TossAuthController.java:41` |
| 그 로그아웃은 앱 사용자에게 **no-op** | `TossLoginService.java:73` — `tossUserKey == null` 이면 즉시 return |
| 재발급은 이미 provider 중립 | `TossLoginService.java:53-66` — 우리 JWT만 보고 새 쌍을 낸다 |
| `/api/me` 없음 | `api/auth/` 전체 확인 |
| access TTL 3600초, refresh TTL 1209600초 | `env/auth.env` |
| `JwtAuthFilter`는 `@Component`가 아니라 `SecurityConfig`가 손으로 생성 | `JwtAuthFilter.java:20`, `SecurityConfig.java:54` |
| `SecurityConfig`를 `@Import` 하는 슬라이스 테스트 5개 | 아래 §6 |
| 다음 마이그레이션 번호 = **V34** | `V33__create_auth_identity.sql` 이 마지막 |

---

## 2. 무엇이 어떻게 달라지는가 (요청/응답)

### 로그아웃

```
POST /api/auth/logout
Authorization: Bearer <access>
→ 204 No Content
```

이 호출 뒤에는 **그 사용자의 모든 access·refresh 토큰이 즉시 무효**다. 같은 토큰으로 다시 부르면:

```
GET /api/route/my/history
Authorization: Bearer <아까 그 access>
→ 401
```

토스 미니앱의 `/api/auth/toss/logout`은 **이 PR 에서 건드리지 않는다.** 그 경로는 세션 종료가 아니라 토스 연결 끊기이고, 미니앱은 별개 계정 체계다(`journey-auth-oauth/PLAN.md` 제품 정책). 앱 세션 규칙을 거기 얹을 이유가 없다.

### 재발급

```
POST /api/auth/reissue          (인증 불필요 — refresh 를 본문에 담는다)
{ "refreshToken": "<refresh>" }
→ 200 { "accessToken": "...", "refreshToken": "..." }
```

로그아웃 뒤의 refresh 로 부르면:

```
→ 401
```

### 내 정보

```
GET /api/me
Authorization: Bearer <access>
→ 200 {
    "userId": 42,
    "displayName": "길동",
    "email": "user@kakao.com",
    "identities": [ { "provider": "KAKAO", "linkedAt": "2026-07-30T15:00:00" } ]
  }
```

애플은 이름을 **최초 1회만** 준다. 서버가 `auth_identity`에 보관한 값을 앱이 되읽는 유일한 창구가 이 엔드포인트다.

---

## 3. 세대 번호가 하는 일

```
로그인      → users.token_generation = 0,  JWT 에 gen=0
요청        → 토큰 gen(0) == DB gen(0)     → 통과
로그아웃    → users.token_generation = 1
아까 그 토큰 → 토큰 gen(0) != DB gen(1)     → 거부
재로그인    → 새 토큰에 gen=1              → 통과
```

**이미 발급된 토큰(V34 이전)은 `gen` claim이 없다.** 이걸 거부하면 배포 순간 로그인 중인 사용자가 전부 튕긴다. 그래서 **claim이 없으면 0으로 본다.** 기본값이 0이므로 그대로 통과하고, 그 사용자가 한 번 로그아웃하는 순간부터는 정상 규칙을 탄다.

이 관용은 영구적이어도 안전하다. "gen 없음"은 "아직 한 번도 로그아웃한 적 없음"과 같은 뜻이기 때문이다.

---

## 4. 변경 파일

### 4.1 신규

| 경로 | 내용 |
|---|---|
| `src/main/resources/db/migration/V34__add_token_generation_to_users.sql` | §5.1 |
| `application/auth/port/out/TokenGenerationPort.java` | §5.4 |
| `application/auth/port/in/SessionUseCase.java` | §5.5 |
| `application/auth/service/SessionService.java` | §5.5 |
| `application/auth/dto/MeResult.java` | §5.5 |
| `application/auth/exception/SessionException.java` | 재발급 실패 시 401 매핑용 |
| `infrastructure/adapter/out/auth/UserTokenGenerationAdapter.java` | §5.4 |
| `infrastructure/adapter/in/api/auth/SessionController.java` | §5.6 |
| `infrastructure/adapter/in/api/auth/MeController.java` | §5.6 |
| `infrastructure/adapter/in/api/auth/dto/ReissueRequest.java` | `{ refreshToken }` |
| `infrastructure/adapter/in/api/auth/dto/TokenResponse.java` | `{ accessToken, refreshToken }` |
| `infrastructure/adapter/in/api/auth/dto/MeResponse.java` | §5.6 |

### 4.2 수정

| 경로 | 내용 |
|---|---|
| `domain/user/entity/User.java` | `tokenGeneration` 필드 + `bumpTokenGeneration()` |
| `common/security/JwtProvider.java` | `gen` claim 발급·해석, 1-인자 메서드 제거 |
| `common/security/JwtAuthFilter.java` | 세대 번호 대조 |
| `common/security/SecurityConfig.java` | 필터에 포트 주입, 새 경로 3개 규칙 |
| `application/auth/service/OAuthLoginService.java` | 토큰 발급 시 세대 번호 전달 |
| `application/auth/service/TossLoginService.java` | **발급 시그니처 대응만.** 세대 번호 전달 + 재발급 시 대조 |

### 4.3 테스트

| 경로 | 상태 |
|---|---|
| `common/security/JwtProviderTest.java` | 수정 (+3 케이스) |
| `common/security/JwtAuthFilterTest.java` | 수정 (+3 케이스) |
| `application/auth/service/SessionServiceTest.java` | 신규 |
| `application/auth/service/OAuthLoginServiceTest.java` | 수정 (시그니처) |
| `application/auth/service/TossLoginServiceTest.java` | 수정 (시그니처 + 위임) |
| `infrastructure/adapter/in/api/auth/SessionControllerTest.java` | 신규 |
| `application/auth/TokenGenerationIT.java` | 신규 — 이 기능의 증명 |
| §6 의 슬라이스 5개 | 수정 (`@MockBean` 1줄씩) |

---

## 5. 상세

### 5.1 V34

```sql
-- 로그아웃이 이미 발급된 JWT 를 무효화할 수 있게 한다.
-- JWT 는 상태가 없어서, 서버가 개입할 지점이 이 번호 하나뿐이다.
ALTER TABLE users
    ADD COLUMN token_generation integer NOT NULL DEFAULT 0;
```

기존 행은 전부 0이 된다. §3의 "claim 없으면 0" 규약과 맞물려 배포 시 강제 로그아웃이 일어나지 않는다.

`ddl-auto: validate` 이므로 엔티티 필드와 컬럼이 정확히 맞아야 한다.

### 5.2 `User`

```java
/**
 * 토큰 세대 번호. 로그아웃하면 올라가고, 그 순간 이전에 발급된 JWT 는 전부 거부된다.
 * JWT 는 상태가 없어서 서버가 개입할 수 있는 지점이 이 값뿐이다.
 */
@Column(nullable = false)
private int tokenGeneration;

public void bumpTokenGeneration() {
    this.tokenGeneration++;
}
```

`@Builder` 생성자는 그대로 둔다(`tossUserKey` 1개). `tokenGeneration` 은 primitive int 기본값 0으로 시작한다.

### 5.3 `JwtProvider`

```java
private static final String CLAIM_GENERATION = "gen";

public String createAccessToken(long userId, int generation)
public String createRefreshToken(long userId, int generation)

/** 토큰이 주장하는 신원. 세대 번호 대조는 호출자 몫이다. */
public record TokenPrincipal(long userId, int generation) { }

public TokenPrincipal parseAccessPrincipal(String token)
public TokenPrincipal parseRefreshPrincipal(String token)
```

- **1-인자 `createAccessToken(long)` / `createRefreshToken(long)` 은 삭제한다.** 남겨 두면 세대 번호 없는 토큰을 실수로 발급할 수 있다. PR #86 에서 `parseUserId` 를 지운 것과 같은 이유다.
- `parseAccessUserId` / `parseRefreshUserId` 도 삭제하고 `*Principal` 로 대체한다. 세대 번호를 안 보고 통과시키는 경로를 남기지 않는다.
- `gen` claim 이 없으면 `0` (§3).
- **DB 를 보지 않는다.** 순수 코덱으로 남겨야 슬라이스 테스트가 `@MockBean JwtProvider` 관례를 그대로 쓸 수 있다.

읽기:
```java
Integer gen = claims.get(CLAIM_GENERATION, Integer.class);
return new TokenPrincipal(Long.parseLong(claims.getSubject()), gen == null ? 0 : gen);
```

### 5.4 세대 번호 조회 포트

```java
package watoo.grd.nextroute.application.auth.port.out;

/** 사용자의 현재 토큰 세대 번호. */
public interface TokenGenerationPort {
    /** 사용자가 없으면 빈 값 — 인증을 거부해야 한다. */
    OptionalInt currentGeneration(long userId);
}
```

어댑터는 `UserRepository.findById` 를 쓴다. `@Transactional(readOnly = true)`.

**Redis 캐싱은 이 범위에 넣지 않는다.** PK 단건 조회이고, 넣으면 무효화 타이밍이라는 새 문제가 생긴다. 부하 측정 후 별건으로 판단한다(`journey-auth-oauth/PLAN.md:246`).

### 5.5 `SessionUseCase` / `SessionService`

```java
public interface SessionUseCase {
    TokenResult reissue(String refreshToken);
    void logout(long userId);
    MeResult me(long userId);
}
```

**`reissue`**
1. `parseRefreshPrincipal` — 실패 시 `SessionException`
2. `userRepository.findById` — 없으면 `SessionException`
3. `principal.generation() != user.getTokenGeneration()` 이면 `SessionException`
4. 현재 세대 번호로 새 access·refresh 발급

**`logout`** — `@Transactional`
1. `userRepository.findById` — 없으면 `SessionException`
2. `user.bumpTokenGeneration()`
3. 저장 (dirty checking)

토스 연결 끊기는 **하지 않는다.** `/api/auth/logout` 은 세션 종료이고, 계정 해제가 아니다.

**`me`** — `@Transactional(readOnly = true)`
1. `userRepository.findById`
2. `identityRepository.findByUserIdAndDeletedAtIsNull(userId)`
3. 표시 이름·이메일은 **TOSS 가 아닌 신원**에서 먼저 찾고, 없으면 아무 신원에서 가져온다

```java
public record MeResult(long userId, String displayName, String email,
                       List<LinkedIdentity> identities) {
    public record LinkedIdentity(AuthProvider provider, LocalDateTime linkedAt) { }
}
```

`providerSubject` 는 응답에 싣지 않는다. 앱이 알 필요가 없다(`OAuthLoginResult` 주석과 같은 판단).

### 5.6 컨트롤러

`SessionController` — `@RequestMapping("/api/auth")`

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| POST | `/reissue` | 불필요 | `TokenResponse` |
| POST | `/logout` | **필요** | 204 |

`MeController` — `@RequestMapping("/api/me")`, GET, 인증 필요, `MeResponse`.

`@AuthenticationPrincipal Long userId` 로 받는다(`TossAuthController.java:42` 관례).

`SessionException` → 401, `@ExceptionHandler` 로 `OAuthController.java:78` 의 `{error, code, message}` 형태를 맞춘다.

### 5.7 `JwtAuthFilter`

```java
private final JwtProvider jwtProvider;
private final TokenGenerationPort tokenGenerationPort;

...
TokenPrincipal principal = jwtProvider.parseAccessPrincipal(token);
OptionalInt current = tokenGenerationPort.currentGeneration(principal.userId());
if (current.isEmpty() || current.getAsInt() != principal.generation()) {
    log.debug("[JWT] 폐기된 세대의 토큰");
    filterChain.doFilter(request, response);
    return;                       // 인증을 채우지 않는다 → 보호 경로는 401
}
```

지금과 같이 **인증을 채우지 않고 통과시킨다.** 공개 경로는 계속 익명으로 동작해야 하기 때문이다. 거부는 `SecurityConfig` 의 `authenticated()` 규칙이 담당한다.

> **구현에서 바꾼 점.** 조기 `return` 대신 대조 결과를 `if` 로 감쌌다. `filterChain.doFilter` 호출부가
> 메서드 끝 한 곳으로 남아 중복 호출이 구조적으로 불가능해진다.

### 5.8 `SecurityConfig`

```java
.requestMatchers(HttpMethod.POST, "/api/auth/toss/logout").authenticated()
.requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
.requestMatchers("/api/me").authenticated()
```

`/api/auth/reissue` 는 `permitAll` 로 남긴다. access 가 만료된 상태에서 부르는 경로라 Bearer 를 요구할 수 없다.

필터 생성자에 포트를 넘긴다:
```java
.addFilterBefore(new JwtAuthFilter(jwtProvider, tokenGenerationPort), ...)
```

### 5.9 기존 서비스 수정

**`OAuthLoginService`** — 토큰 발급 지점에서 `user.getTokenGeneration()` 을 넘긴다. 신규 가입이면 저장 후의 엔티티를 쓴다(0).

**`TossLoginService`** — **토스는 이 PR 의 기능 범위 밖이다.** `JwtProvider` 시그니처가 바뀌어 컴파일이 깨지는 만큼만 고친다.

- `login` — 발급 시 `user.getTokenGeneration()` 전달
- `reissue` — `parseRefreshPrincipal` 로 바꾸고, 세대 번호가 다르면 `TossLoginException(…, false)`. 발급도 현재 세대 번호로. 토스 사용자는 세대가 오를 일이 없으므로 **동작은 오늘과 같다.** 규칙을 예외로 두지 않으려고 넣는 한 줄이다
- `logout` — **손대지 않는다.** 토스 연결 끊기이지 세션 종료가 아니다

`SessionUseCase` 에 위임하지 않는다. 이 PR 이 토스 경로에 새 의존성을 만들지 않게 한다.

---

## 6. 슬라이스 테스트 영향

`SecurityConfig` 를 `@Import` 하는 5개 파일에 `@MockBean TokenGenerationPort` 한 줄씩 추가한다.

- `infrastructure/adapter/in/api/auth/TossAuthSecurityTest.java`
- `infrastructure/adapter/in/api/route/search/RouteSearchHistoryControllerTest.java`
- `infrastructure/adapter/in/api/route/favorite/FavoriteControllerTest.java`
- `infrastructure/adapter/in/api/notification/BusAlertControllerTest.java`
- `infrastructure/adapter/in/api/admin/AdminApiKeySecurityTest.java`

> **확인 결과 정정.** 계획 단계에서 `BusAlertControllerTest` 4건을 기존 실패로 적었는데, 그건
> `feat/oauth-multi-provider` 시점의 관찰이었다. 현재 `main`(`5a16aef`)에서는 `@MockBean AdminProperties`
> 가 이미 들어 있어 통과한다. 이 PR 이 고칠 것은 없다.

### 6.1 IT 전용 스키마 스크립트 — 계획에서 놓친 곳

`users` 를 손으로 정의하는 IT 스키마가 셋 있다. Flyway 를 끄고(`spring.flyway.enabled=false`)
`ddl-auto: none` 으로 도는 테스트들이라 V34 가 닿지 않는다.

- `src/test/resources/db/it/bus-alert-it-schema.sql`
- `src/test/resources/db/it/bus-alert-outbox-it-schema.sql`
- `src/test/resources/db/it/search-history-it-schema.sql`

세 곳 모두 `token_generation` 을 추가한다. 빠뜨리면 `User` 엔티티가 없는 컬럼을 조회해
**BusAlert 계열 IT 16건이 조용히 0건을 반환하며 깨진다**(실제로 그렇게 발견했다).
드리프트를 IT 가 잡아 주도록 만든 장치가 의도대로 동작한 것이다.

---

## 7. 작업 순서

- [ ] **1.** `main` 최신에서 `feat/auth-session-management` 분기
- [ ] **2.** V34 작성 → `./gradlew compileJava`
- [ ] **3.** `User.tokenGeneration` + `bumpTokenGeneration()`
- [ ] **4.** `JwtProvider` — `gen` claim, `TokenPrincipal`, 구 메서드 삭제
      → 컴파일 깨진 호출부(OAuthLoginService, TossLoginService, JwtAuthFilter)를 여기서 같이 고친다
      → `./gradlew test --tests '*JwtProviderTest'`
- [ ] **5.** `TokenGenerationPort` + `UserTokenGenerationAdapter`
- [ ] **6.** `JwtAuthFilter` 대조 + `SecurityConfig` 배선 + 슬라이스 5개 `@MockBean`
      → `./gradlew test --tests '*JwtAuthFilterTest' --tests '*SecurityTest' --tests '*ControllerTest'`
- [ ] **7.** `SessionUseCase` / `SessionService` / `MeResult` / `SessionException`
      → `SessionServiceTest`
- [ ] **8.** `SessionController` / `MeController` / DTO 3개 + `SecurityConfig` 경로 규칙
      → `SessionControllerTest`
- [ ] **9.** `TossLoginService` 시그니처 대응 → `TossLoginServiceTest` (기존 케이스가 그대로 통과해야 한다)
- [ ] **10.** `TokenGenerationIT` (§8)
- [ ] **11.** `./gradlew test` 전체 → §9 기준으로 판정
- [ ] **12.** 커밋 (플랜 파일 포함) → PR

4번에서 시그니처를 먼저 깨는 이유: 컴파일러가 남은 호출부를 전부 짚어 준다. 세대 번호를 빠뜨린 발급 경로가 남을 수 없다.

---

## 8. `TokenGenerationIT` — 이 기능의 증명

`@SpringBootTest` + Testcontainers PostgreSQL. `AuthIdentityConstraintIT.java:22-28` 의 프로퍼티 억제 관례를 그대로 쓴다.

| 케이스 | 기대 |
|---|---|
| 로그아웃 전 access 로 보호 경로 호출 | 200 |
| 로그아웃 → 같은 access 로 호출 | **401** |
| 로그아웃 → 같은 refresh 로 재발급 | **401** |
| 로그아웃 후 재로그인 → 새 access | 200 |
| `gen` claim 없는 토큰 (V34 이전 모사) + DB gen 0 | 200 |
| `gen` claim 없는 토큰 + 로그아웃 후(DB gen 1) | **401** |
| 없는 userId 의 서명 유효 토큰 | **401** |

---

## 9. 완료 판정

```
./gradlew test
```

**결과: 706건 전부 통과, 실패 0, 건너뜀 2 (`GRADLE_EXIT=0`).**

계획 단계에서 기존 실패로 예상했던 `GongApiAdapterTest` 6건과 `BusAlertControllerTest` 4건은
현재 `main` 에서 이미 통과한다. 기준선을 직접 확인했다 — 작업분을 `git stash` 로 걷어내고
같은 테스트를 돌려 36건 전부 통과함을 본 뒤 되돌렸다.

수동 확인은 필요 없다. `TokenGenerationIT` 가 실제 DB 로 전 구간을 돈다.

---

## 10. 리스크

| 리스크 | 대응 |
|---|---|
| 인증된 요청마다 DB 조회 1회 늘어남 | PK 단건 조회. Redis 캐싱은 측정 후 별건 (§5.4) |
| 배포 순간 기존 로그인 사용자 강제 로그아웃 | `gen` claim 없으면 0 으로 해석 (§3). `TokenGenerationIT` 가 고정 |
| `SecurityConfig` 의존성 추가로 슬라이스 5개 깨짐 | §6 에 파일 목록 확정. 같은 커밋에서 처리 |
| `JwtAuthFilter` 분기에서 `doFilter` 중복 호출 | §5.7 — 분기 뒤 즉시 `return` |
| 토스 미니앱 로그인 회귀 | 토스는 범위 밖 — 시그니처 대응만 하고 `TossLoginServiceTest` 기존 케이스를 그대로 통과시킨다 (§5.9) |

---

## 11. 범위 밖

- **refresh 토큰 회전·재사용 탐지** — 세대 번호가 있으면 나중에 얹기 쉽다 (`journey-auth-oauth/PLAN.md:248`)
- **계정 탈퇴** — 사용자가 논외로 지정
- **세대 번호 Redis 캐싱** — 측정 후 판단
- **다중 기기 개별 로그아웃** — 기기 구분을 하지 않기로 이미 결정 (`SCOPE-CHANGES.md` SC-1). 세대 번호는 그 사용자의 모든 토큰을 한꺼번에 끊는다
- **토스 미니앱 로그아웃** — 미니앱은 별개 계정 체계이고, `/api/auth/toss/logout` 은 세션 종료가 아니라 연결 끊기다. 세대 규칙을 얹지 않는다. 결과적으로 토스 사용자의 JWT 는 지금과 똑같이 만료로만 죽는다
