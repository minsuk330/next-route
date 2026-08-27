# Apple 로그인 토큰 폐기(revoke) — 실행 플랜

티어: L. 두 PR 로 분할. fresh main 기준 독립 브랜치 각각.

이 플랜은 컨텍스트 없이 읽어도 구현 가능하도록 쓴다.

---

## 배경 (한 문장 결론 먼저)

애플로 로그인시킨 사용자가 탈퇴하면, 애플에 "이 사용자↔우리앱 연결을 끊어라"를 HTTP 한 번으로
알려야 한다(App Store 심사 지침 5.1.1(v)). 이 호출을 **revoke** 라 한다.

```
POST https://appleid.apple.com/auth/revoke
  Content-Type: application/x-www-form-urlencoded
  body: client_id, client_secret, token=<refresh_token>, token_type_hint=refresh_token
→ HTTP 200 (본문 없음) = 성공
```

문제: 지금 애플 로그인은 **identity token(JWT) 검증만** 한다(`AppleIdentityVerifier`). 애플 서버와
토큰 교환을 하지 않아 **refresh_token 이 없다**. revoke 는 refresh_token 이 있어야 부를 수 있다.
그 토큰은 로그인 때 **authorization code 를 애플 `/auth/token` 에 교환**해야만 나온다.

그래서 두 가지를 해야 한다: ① 로그인에서 code 를 받아 교환·저장(PR-A) ② 탈퇴 때 저장된 토큰으로
revoke 호출(PR-B).

## 조사로 확정된 사실 (2026-08-27)

- 로그인 진입: `OAuthController` `POST /api/auth/oauth/login` → `OAuthLoginRequest`
  (`provider`, `credential`, `nonce`, `displayName`) → `OAuthLoginService.login(OAuthCredential)`.
  APPLE 은 `credential`=identity token, `nonce` 필수.
- `AuthIdentity`(userId, provider, providerSubject, email, displayName, linkedAt, lastLoginAt) — **토큰
  필드 없음**. 로그인은 (provider, providerSubject) 로 upsert.
- `TossUserToken`(userKey FK, accessToken, refreshToken) = 사용자별 외부 토큰 저장 **선례 패턴**.
  → 이걸 복제해 `AppleUserToken` 을 만든다.
- 애플 config 현재값: `OAuthProviderProperties.Apple`{jwkSetUri, issuer, audiences}. **clientId /
  teamId / keyId / privateKey(.p8) 전무** → 전부 신설.
- 아웃바운드 HTTP 선례: `ApiClientConfig` 의 `tossRestClient`(JdkClientHttpRequestFactory +
  SSLContext + 타임아웃, `@Qualifier` 주입). 애플은 mTLS 불요 → SSLContext 없이 타임아웃만.
- JWT: `build.gradle` 에 jjwt 0.12.6(ES256 지원). 현 `JwtProvider` 는 HMAC 전용. Nimbus 는 decode
  용(`JwkDecoderFactory`). → ES256 **서명** 헬퍼는 신규.
- 암호화 유틸 **없음** → AES-GCM 대칭암호 유틸 신규(PR-A).
- 탈퇴: `WithdrawService`(단일 `@Transactional`, 외부호출 0). `WithdrawalDeletionBoundaryIT` 는
  스키마를 `ddl-auto=create-drop` 로 엔티티에서 생성 → **새 엔티티 추가 시 자동 반영**.
- 마이그레이션 관례: V18+ untracked(디스크에만, CI 미반영). 새 마이그레이션은 **VPS 수동배치**.
  현재 최신 = V47. 새 파일 = **V48**.

## 확정 설계 (grilling 결과)

- **revoke = 동기 best-effort, outbox 없음**: 탈퇴 시 DB 삭제 트랜잭션 **앞(밖)**에서 revoke 1회
  호출. 성공/실패 무관 로그만 남기고, 그 뒤 기존 삭제 트랜잭션 진행. 애플 장애가 탈퇴를 막지 않음
  (best-effort). 재시도·전용 테이블 없음(실사용자 0, 탈퇴당 1회라 과함).
- **`WithdrawService` 내부는 안 건드린다**. revoke 는 그 **상위**(탈퇴 유스케이스/서비스를 감싸는
  자리)에서 tx 앞에 둔다 → "외부호출 0, 단일 tx" 계약 유지.
- **로그인 code 필수(APPLE)**: `authorizationCode` 없는 애플 로그인은 거부. 실사용자 0이라 안전.
- **저장 = 별도 엔티티 `AppleUserToken` + refresh_token 암호화(AES-GCM)**.
- **client_secret = ES256 JWT**, exp 짧게(~5분), 캐시 없음(저빈도).

---

# PR-A — 로그인 code 교환 + 토큰 저장 + client_secret + config + 마이그레이션

브랜치: `feat/apple-token-exchange` (fresh main).

## A-1. config — 애플 자격 필드 추가
`src/main/java/watoo/grd/nextroute/application/auth/config/OAuthProviderProperties.java`
- `Apple` 중첩 클래스에 추가: `clientId`(=번들 ID, 토큰 교환 client_id), `teamId`, `keyId`,
  `privateKeyPath`(.p8 파일 경로, 볼륨 마운트), `tokenBaseUrl`(기본 `https://appleid.apple.com`).
- `src/main/resources/application.yaml` oauth.apple 블록에 `${OAUTH_APPLE_CLIENT_ID}` 등 placeholder,
  `env/oauth.env.example` + `env/oauth.env` 에 실제값 항목 추가(.p8 경로는 컨테이너 마운트 경로).

## A-2. client_secret 빌더 (ES256)
신규 `src/main/java/watoo/grd/nextroute/infrastructure/adapter/out/auth/AppleClientSecretJwtBuilder.java`
```java
@Component
public class AppleClientSecretJwtBuilder {
    // .p8(PKCS#8 EC 개인키) 로드 → ES256 서명
    // claims: iss=teamId, iat=now, exp=now+5m, aud="https://appleid.apple.com", sub=clientId
    // header: kid=keyId, alg=ES256
    String build(); // 반환: 서명된 JWT 문자열
}
```
- .p8 PEM 파싱: `-----BEGIN PRIVATE KEY-----` 제거 → Base64 decode → `PKCS8EncodedKeySpec` →
  `KeyFactory.getInstance("EC").generatePrivate(...)`.
- 서명: jjwt `Jwts.builder().header().keyId(keyId).and().issuer(teamId).subject(clientId)
  .audience().add("https://appleid.apple.com").and().issuedAt(..).expiration(..)
  .signWith(ecPrivateKey, Jwts.SIG.ES256).compact()`.
- 검증 테스트: 생성한 JWT 를 공개키로 파싱 → claims(iss/sub/aud/exp) 확인.

## A-3. 암호화 유틸 (AES-GCM)
신규 `src/main/java/watoo/grd/nextroute/common/security/SecretCipher.java`
```java
@Component
public class SecretCipher {
    // 키: env APP_SECRET_ENC_KEY (Base64, 32바이트=AES-256). 생성자 주입.
    String encrypt(String plain); // 반환: Base64(iv(12B) || ciphertext||tag). 매 호출 랜덤 IV
    String decrypt(String stored);
}
```
- `Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec(128, iv)`.
- 테스트: round-trip(encrypt→decrypt=원문), 서로 다른 IV 로 같은 평문이 다른 암호문.

## A-4. 애플 RestClient
`src/main/java/watoo/grd/nextroute/common/config/ApiClientConfig.java`
- `appleRestClient` bean 추가: `RestClient.builder().baseUrl(tokenBaseUrl)
  .requestFactory(JdkClientHttpRequestFactory + connect/read 타임아웃)`. mTLS 없음(일반 TLS).
  토스 bean 패턴 복제하되 SSLContext 부분 제거.

## A-5. AppleUserToken 엔티티 + 리포지토리 + 마이그레이션
신규 `src/main/java/watoo/grd/nextroute/domain/auth/entity/AppleUserToken.java` (extends BaseEntity)
- 필드: `Long userId`(users FK), `String refreshTokenEnc`(암호문, @Column length 넉넉히 1024+),
  선택 `String appleSub`(providerSubject 참고용). upsert 를 위한 unique(user_id).
신규 `AppleUserTokenRepository`:
```java
Optional<AppleUserToken> findByUserId(Long userId);
@Modifying @Query("delete from AppleUserToken t where t.userId = :userId")
void deleteAllByUserId(@Param("userId") Long userId);   // PR-B 탈퇴에서 씀(미리 넣어도 무방)
```
마이그레이션 `src/main/resources/db/migration/V48__create_apple_user_token.sql`:
```sql
create table apple_user_token (
    id                bigint generated by default as identity primary key,
    created_at        timestamp(6),
    updated_at        timestamp(6),
    deleted_at        timestamp(6),
    user_id           bigint not null references users,
    refresh_token_enc varchar(1024) not null,
    apple_sub         varchar(255),
    constraint uq_apple_user_token_user unique (user_id)
);
alter table apple_user_token owner to myuser;
```
- `### 배포`: V48 **VPS 수동배치 필요**.

## A-6. 로그인 code 교환 배선
- `src/main/java/watoo/grd/nextroute/infrastructure/adapter/in/api/auth/dto/OAuthLoginRequest.java`:
  `authorizationCode`(String) 필드 추가. APPLE 은 필수(검증: provider=APPLE 인데 code 없으면 400).
- `application/auth/dto/OAuthCredential.java`: `authorizationCode` 전달 필드 추가.
- 신규 `infrastructure/adapter/out/auth/AppleTokenExchanger.java`:
  ```java
  @Component
  public class AppleTokenExchanger {
      // POST {tokenBaseUrl}/auth/token, x-www-form-urlencoded
      // grant_type=authorization_code, code, client_id, client_secret(=builder.build())
      // (native 라 redirect_uri 없음)
      String exchangeForRefreshToken(String authorizationCode); // 응답 JSON 의 refresh_token
  }
  ```
- `application/auth/service/OAuthLoginService.login()`: APPLE 경로에서 identity 검증 성공 후,
  `authorizationCode` 로 `AppleTokenExchanger.exchangeForRefreshToken` 호출 → `SecretCipher.encrypt`
  → `AppleUserToken` upsert(findByUserId 있으면 갱신, 없으면 저장). 최초/재로그인 모두 최신 토큰 유지.
  - 주의: 애플은 **첫 로그인에만** authorization code 를 넉넉히 주지만 code 는 매 로그인 발급됨
    (앱이 ASAuthorization 로 매번 받음). 재로그인 시 새 refresh_token 이 오면 갱신, code 교환이
    실패(이미 쓴 code 등)하면 로그인 자체는 성공시키고 토큰만 유지(로그 경고). ← 로그인을 막지 않음.

## PR-A 검증
- `./gradlew compileJava compileTestJava`
- `./gradlew test --tests "*SecretCipher*" --tests "*AppleClientSecret*" --tests "*AppleTokenExchang*"`
- client_secret round-trip 테스트, SecretCipher round-trip 테스트, exchanger 는 응답 파싱 단위테스트
  (실제 애플 호출 대신 RestClient mock/MockWebServer).

## PR-A 작업 순서
- [ ] OAuthProviderProperties.Apple + application.yaml + env 항목
- [ ] SecretCipher (AES-GCM) + 테스트
- [ ] AppleClientSecretJwtBuilder (ES256) + 테스트
- [ ] appleRestClient bean
- [ ] AppleUserToken 엔티티 + 리포지토리 + V48 마이그레이션
- [ ] OAuthLoginRequest/OAuthCredential authorizationCode + AppleTokenExchanger + login() 배선
- [ ] 컴파일 + 테스트

---

# PR-B — 탈퇴 시 revoke 호출

브랜치: `feat/apple-revoke-on-withdrawal` (fresh main, PR-A 머지 후).

## B-1. revoke 어댑터
신규 `infrastructure/adapter/out/auth/AppleTokenRevoker.java`
```java
@Component
public class AppleTokenRevoker {
    // POST {tokenBaseUrl}/auth/revoke, x-www-form-urlencoded
    // client_id, client_secret(builder.build()), token=<refreshToken>, token_type_hint=refresh_token
    void revoke(String refreshToken); // 200 기대. 예외는 상위에서 잡아 로그(best-effort)
}
```

## B-2. 탈퇴 유스케이스에 revoke 훅 (tx 앞)
- **WithdrawService 내부는 수정 금지**(단일 tx 계약 유지). revoke 는 그 **앞**에서 부른다.
- 배치 위치: `WithdrawController` 가 `WithdrawUseCase.withdraw` 를 부르기 직전, 또는 `WithdrawUseCase`
  구현을 감싸는 얇은 조율 지점. 권장: 신규 `application/auth/service/AppleRevocationOnWithdrawal` 를
  두고, `WithdrawController` 가 `revokeIfApple(userId)` → 그다음 `withdrawUseCase.withdraw(userId)`.
  ```java
  // revokeIfApple: (트랜잭션 밖)
  appleUserTokenRepository.findByUserId(userId).ifPresent(t -> {
      try { appleTokenRevoker.revoke(secretCipher.decrypt(t.getRefreshTokenEnc())); }
      catch (Exception e) { log.warn("[AppleRevoke] 실패(무시, 탈퇴는 진행): userId={}", userId, e); }
  });
  ```
- 그다음 `withdraw(userId)` 가 기존대로 실행되며 그 안에서 `AppleUserToken` 도 물리삭제(B-3).

## B-3. WithdrawService 물리삭제 버킷에 AppleUserToken 추가
- `WithdrawService`: `appleUserTokenRepository.deleteAllByUserId(userId)` 를 물리삭제 구간에 추가
  (로그인 수단 그룹, auth_identity 근처).
- `WithdrawalDeletionBoundaryIT`:
  - `PHYSICAL` 목록에 `apple_user_token` 추가 + `userRowCount` switch 에 `user_id` 규칙 추가.
  - `seedAll` 에 apple_user_token 1행 삽입(user_id, refresh_token_enc, ...).
  - ddl-auto 라 스키마는 자동 생성됨.

## PR-B 검증
- `./gradlew compileJava compileTestJava`
- `./gradlew test --tests "*WithdrawalDeletionBoundaryIT" --tests "*AppleTokenRevok*"`
- revoke 어댑터 단위테스트(MockWebServer 200/에러), 탈퇴 시 revoke 1회 호출 + 실패해도 삭제 진행
  검증(mock revoker throw → withdraw 정상 완료 + apple_user_token 0행).

## PR-B 작업 순서
- [ ] AppleTokenRevoker
- [ ] AppleRevocationOnWithdrawal 조율 + WithdrawController 배선(tx 앞)
- [ ] WithdrawService 물리삭제에 AppleUserToken 추가
- [ ] WithdrawalDeletionBoundaryIT 반영(PHYSICAL+seed+count)
- [ ] 컴파일 + 테스트

---

## 배포
- **V48__create_apple_user_token.sql = VPS 수동배치**(PR-A). untracked 관례라 CI 로 안 나감.
- 신규 시크릿: `.p8` 파일 볼륨 마운트 + env(`OAUTH_APPLE_CLIENT_ID/TEAM_ID/KEY_ID/PRIVATE_KEY_PATH`,
  `APP_SECRET_ENC_KEY`). compose.yaml 에 마운트·env 추가.
- **모바일 동반 필수**: 앱이 애플 로그인 호출에 `authorizationCode` 추가. 실사용자 0이라 플래그
  불요, 단 심사 재제출 앱빌드 + 서버 PR-A/PR-B **동시 릴리스**. → HANDOFF 문서로 모바일 전달.

## 후속 (범위 밖)
- 토스 unlink 콜백의 계정 미삭제 비대칭 정리.
- revoke 실패 영구 유실 보강(재시도/outbox)은 실사용자·트래픽 생기면 재검토(현재 best-effort 충분).
