# Nextroute 모바일 앱 초기 설정·이관 가이드

> 기준일: 2026-07-21  
> 대상: Android·iOS 전용 React Native 앱  
> 새 앱 저장소 권장 경로: `/Users/wa/golbob/개인/nextroute-mobile`

## 1. 확정된 의사결정

- 새 앱은 **Expo 기반 React Native + TypeScript + Expo Router**로 시작한다.
- Expo Go를 기준으로 개발하지 않고, 초기부터 **Development Build**를 사용한다.
- 배포 대상은 Android·iOS이며 웹은 범위에서 제외한다.
- 기존 `toss-app`은 UI/흐름의 참고 구현으로 사용한다.
- `@apps-in-toss/*`, Toss 로그인, Toss 메신저, `@toss/tds-*`는 새 앱에 넣지 않는다.
- 인증은 카카오 등 OAuth Authorization Code + PKCE로 구성하고, provider 토큰 교환과 앱 세션 발급은 백엔드가 담당한다.
- 푸시는 `expo-notifications`로 native device token을 획득하고, 백엔드가 Android FCM·iOS APNs로 직접 발송한다.
- 지도는 네이티브 네이버 지도 SDK wrapper를 1차 후보로 검증한다. 실기기 spike를 통과하기 전에 라이브러리를 확정하지 않는다.
- 1차 실시간 통신은 REST polling + FCM/APNs로 구성한다. SSE는 포그라운드 실시간 화면에서 효과를 측정한 뒤 도입한다.

## 2. 원본과 새 앱의 경계

| 소스 | 역할 | 처리 |
|---|---|---|
| `toss-app/src/core/*` | 장소·경로·도착·환승 순수 로직 | 편입 후 단위 테스트로 동일성 고정 |
| `toss-app/src/components/*` | 기존 UX, 화면 구성, 문구 | JSX를 복사하지 말고 RN 컴포넌트로 재구현 |
| `toss-app/*.css` | 색상·간격·타이포·레이아웃 참고 | design token으로 추출, CSS 직접 복사 금지 |
| `toss-app/src/lib/api.ts` | API endpoint·DTO 참고 | 네이티브 전용 API client로 재구현 |
| `toss-app/src/lib/storage.ts` | 토큰 저장 의도 참고 | `expo-secure-store`로 교체 |
| `toss-app/src/lib/naverMapSdk.ts` | 지도 동작 참고 | 사용 금지. Native map adapter로 교체 |
| `toss-app/public/icons/*` | 앱 자산 | 라이선스·해상도 확인 후 재사용 |
| `nextroute` | Spring 백엔드·ML·알림 스케줄러 | 기존 기능 유지, mobile auth/push adapter 추가 |

### 그대로 재사용할 1차 후보

- `src/core/place.ts`
- `src/core/transit.ts`
- `src/core/route-display.ts`
- `src/core/search-selection.ts`
- `src/core/transfer-prediction.ts`
- API request/response type 중 백엔드 DTO와 일치하는 부분

`window`, `document`, `navigator`, `localStorage`, `sessionStorage`, DOM type을 참조하는 코드는 순수 공통 로직으로 간주하지 않는다.

## 3. 목표 구조

```text
nextroute-mobile/
├─ src/
│  ├─ app/                       # Expo Router route만 둔다
│  │  ├─ _layout.tsx
│  │  ├─ (auth)/
│  │  ├─ (tabs)/
│  │  ├─ route/
│  │  ├─ transfer/
│  │  └─ settings/
│  ├─ features/
│  │  ├─ auth/
│  │  ├─ map/
│  │  ├─ route-search/
│  │  ├─ transfer-prediction/
│  │  ├─ favorites/
│  │  └─ alerts/
│  ├─ core/                      # runtime에 독립적인 순수 TypeScript
│  └─ shared/
│     ├─ api/
│     ├─ config/
│     ├─ storage/
│     ├─ ui/
│     ├─ theme/
│     └─ observability/
├─ assets/
├─ app.config.ts
├─ eas.json
├─ package.json
└─ tsconfig.json
```

규칙:

- `src/app` 파일은 라우팅과 화면 조립만 담당한다.
- API 호출, OAuth, push, 지도 SDK 코드를 route 파일에 직접 넣지 않는다.
- 서버 상태는 TanStack Query, 화면의 짧은 로컬 상태는 React state/reducer를 기본으로 한다.
- 여러 화면에 걸친 경로 탐색 draft만 경량 전역 store 도입을 허용한다.
- 파생 가능한 데이터를 전역 store에 중복 저장하지 않는다.

## 4. 초기 설정 체크리스트

### A. 시작 전 식별자 확정

- [ ] 앱 표시 이름 확정: `제때`
- [ ] Expo slug 확정: 예) `nextroute-mobile`
- [ ] URL scheme 확정: 예) `nextroute`
- [ ] iOS Bundle ID 확정: 예) `com.<organization>.nextroute`
- [ ] Android Application ID 확정: 예) `com.<organization>.nextroute`
- [ ] 개발·프리뷰·운영 앱을 한 기기에 동시 설치할지 결정
- [ ] 동시 설치한다면 환경별 Bundle/Application ID suffix 정의
- [ ] Apple Developer 조직·Google Play Console 개발자 계정 소유주 확정
- [ ] 카카오 OAuth redirect URI와 iOS/Android platform 앱 등록 정보 확정
- [ ] 네이버 지도 iOS Bundle ID·Android Application ID/서명 제한 정보 확정

Bundle ID와 Application ID는 스토어 등록·OAuth·푸시·지도 키와 연결되므로 첫 외부 연동 전에 확정한다.

### B. 새 앱 생성

- [ ] Node.js LTS, Xcode, Android Studio, JDK 설치 확인
- [ ] 기존 `nextroute-app`과 다른 경로에 새 저장소 생성
- [ ] 공식 `create-expo-app` default template로 생성
- [ ] TypeScript strict mode 확인
- [ ] 새 Git 저장소 초기화 후 최초 상태 커밋
- [ ] 템플릿 예제 화면 제거

2026-07-21 기준 Expo 공식 문서는 SDK 57 템플릿을 제시한다. 실행 직전 [Create a project](https://docs.expo.dev/get-started/create-a-project/)의 현재 권장 명령을 다시 확인한다.

```bash
cd /Users/wa/golbob/개인
npx create-expo-app@latest nextroute-mobile --template default@sdk-57
cd nextroute-mobile
```

### C. Development Build

- [ ] `expo-dev-client` 설치
- [ ] EAS project 연결 또는 local native build 방식 결정
- [ ] Android development build 실기기 설치
- [ ] iOS development build 실기기 설치
- [ ] Native module/config plugin 변경 후 재빌드 규칙을 README에 기록
- [ ] `npx expo-doctor` 통과

```bash
npx expo install expo-dev-client
npx eas-cli@latest init
npx eas-cli@latest build --platform android --profile development
npx eas-cli@latest build --platform ios --profile development
```

OAuth custom scheme과 native 지도/푸시 모듈 때문에 Expo Go만으로 검증하지 않는다. 참고: [Expo Development Build](https://docs.expo.dev/develop/development-builds/create-a-build/)

### D. 필수 기반 모듈

- [ ] 앱 생명주기·라우팅: Expo Router, `expo-linking`
- [ ] OAuth: `expo-auth-session`, `expo-web-browser`
- [ ] 보안 저장소: `expo-secure-store`
- [ ] 푸시: `expo-notifications`, `expo-device`
- [ ] 위치: `expo-location`
- [ ] safe area/screens 구성
- [ ] 서버 상태: TanStack Query
- [ ] API 응답 runtime 검증: Zod
- [ ] 폼이 복잡해지면 React Hook Form 추가

```bash
npx expo install expo-linking expo-auth-session expo-web-browser expo-secure-store
npx expo install expo-notifications expo-device expo-location
npx expo install react-native-safe-area-context react-native-screens
npm install @tanstack/react-query zod
```

신규 라이브러리는 역할이 중복되지 않을 때만 추가한다. Axios는 필수가 아니며 표준 `fetch`와 작은 wrapper로 시작해도 충분하다.

### E. 환경 분리와 비밀값

- [ ] `development`, `preview`, `production` 세 환경 정의
- [ ] `EXPO_PUBLIC_API_BASE_URL`만 앱 runtime 환경변수로 노출
- [ ] 환경별 OAuth redirect URI 등록
- [ ] 환경별 푸시 credential/file 분리
- [ ] `.env.local`, `google-services.json`, APNs key, signing file Git 제외
- [ ] CI/EAS 환경별 build profile 연결
- [ ] 번들에 포함된 값은 모두 공개값으로 간주

```text
EXPO_PUBLIC_API_BASE_URL=https://api.example.com
EXPO_PUBLIC_APP_ENV=development
```

아래 값은 앱에 넣지 않는다.

- Kakao REST client secret
- OAuth client secret
- JWT signing secret
- FCM service-account JSON
- APNs `.p8` private key
- 서버 관리자 API key

`EXPO_PUBLIC_*`는 컴파일된 앱에 포함된다. 참고: [Expo environment variables](https://docs.expo.dev/guides/environment-variables/), [EAS environment variables](https://docs.expo.dev/eas/environment-variables/)

### F. 앱 설정

- [ ] `app.config.ts`로 이름, scheme, bundleIdentifier, package 정의
- [ ] iOS deployment target·supported device 정책 결정
- [ ] Android edge-to-edge·predictive back 동작 검증
- [ ] 세로 모드 제한 여부 결정
- [ ] 앱 아이콘, adaptive icon, splash, notification icon 최종 자산 준비
- [ ] 한국어 기본 locale, timezone 처리 규칙 정의
- [ ] 새프 어리어·키보드·텍스트 크기 확대 기본 QA
- [ ] iOS/Android 모두에서 deep link cold/warm start 검증

### G. 테스트·품질 기본선

- [ ] `npm run typecheck`, `npm run lint`, `npm test` 스크립트 정의
- [ ] 복사한 `src/core/*` 행동 보존 테스트
- [ ] API client의 401 refresh single-flight·timeout·abort 테스트
- [ ] OAuth success/cancel/error/deep-link 테스트
- [ ] push foreground/background/terminated 테스트
- [ ] 위치 허용/거부/정밀도 제한 테스트
- [ ] 오프라인·저속·API partial response 화면 테스트
- [ ] Android 뒤로가기, iOS swipe-back 검증
- [ ] 최소 iPhone 1대·Android 1대 실기기 smoke test
- [ ] crash reporting에 auth token·좌표·PII 마스킹

## 5. 라우팅 설계

```text
Root
├─ (auth)
│  └─ login
└─ (app)
   ├─ (tabs)
   │  ├─ map
   │  ├─ favorites
   │  └─ alerts
   ├─ place-search
   ├─ route-results
   ├─ route/[pathId]
   ├─ transfer
   └─ settings
```

- 로그인 여부와 route guard는 root layout에서 조립하되, 토큰 복구 중 화면이 깜빡이지 않도록 bootstrap state를 둔다.
- 출발지·도착지·선택 경로처럼 공유 가능한 상태는 route param/deep link 계약을 우선한다.
- 전체 경로 JSON을 URL에 넣지 말고 `searchId`, `pathIndex` 같은 안정적 식별자만 사용한다.
- 푸시 deep link은 `alertId` 또는 `navigationSessionId`를 전달하고 앱이 REST로 최신 상태를 다시 조회한다.

## 6. 인증 계약

### 앱

1. 사용자 클릭으로 OAuth 시작.
2. Authorization Code + PKCE로 provider 인증.
3. 앱은 one-time authorization code와 redirect 검증값을 백엔드에 전달.
4. 백엔드가 provider와 token exchange 후 Nextroute access/refresh token 발급.
5. 앱은 access/refresh token을 `expo-secure-store`에 저장.
6. API 401 시 refresh는 동시에 한 번만 실행하고 대기 요청을 재시도.
7. refresh 실패 시 로컬 세션을 폐기하고 로그인 화면으로 이동.

### 백엔드 선행 체크리스트

- [ ] `User.tossUserKey`를 공통 user identity로 사용하는 구조 제거
- [ ] `auth_identity(user_id, provider, provider_subject)` 또는 동등 모델 추가
- [ ] provider + provider subject unique constraint 추가
- [ ] OAuth authorization code exchange endpoint 추가
- [ ] Nextroute refresh token rotation·revoke 정책 정의
- [ ] 로그아웃, 회원 탈퇴, 전체 기기 로그아웃 API 정의
- [ ] Toss auth는 기존 Apps-in-Toss를 위해 유지하되 mobile auth와 adapter/route 분리
- [ ] API 명세에 OAuth 오류 코드·JWT 만료·회원 상태 계약 문서화

OAuth provider client secret은 앱 번들에 넣지 않는다. 참고: [Expo OAuth authentication](https://docs.expo.dev/guides/authentication/)

## 7. FCM/APNs 푸시 계약

### 앱

- 로그인 직후 자동으로 OS 허용 팝업을 띄우지 않는다.
- 사용자가 첫 알림을 설정할 때 용도를 설명하고 허용을 요청한다.
- `getDevicePushTokenAsync()`로 Android FCM token·iOS APNs token을 획득한다.
- 토큰을 얻은 후 backend device registration API에 upsert한다.
- 토큰 갱신, 로그아웃, 회원 탈퇴 시 서버 토큰 상태를 동기화한다.
- 포그라운드, 백그라운드, terminated 상태를 모두 실기기에서 테스트한다.

### 백엔드

- [ ] `device_push_token` 모델 추가
- [ ] 권장 필드: `id`, `user_id`, `platform`, `provider`, `token`, `app_version`, `environment`, `enabled`, `last_seen_at`
- [ ] `provider + token + environment` unique constraint 정의
- [ ] token 등록·갱신·비활성 API 추가
- [ ] `TossMessengerPort`에 직접 의존하는 발송 흐름을 일반 `NotificationSender` port로 분리
- [ ] FCM adapter·APNs adapter 추가
- [ ] 기존 outbox의 channel을 `TOSS | FCM | APNS`로 확장
- [ ] 재시도 가능/영구 실패 분류
- [ ] invalid/unregistered token은 즉시 비활성화
- [ ] 중복 발송 방지 idempotency key 추가
- [ ] 푸시 payload에 민감정보·전체 경로 데이터를 넣지 않는다.
- [ ] `alertId`, `type`, deep-link 식별자만 보내고 앱이 REST로 최신 상태를 재조회

Expo 앱은 Expo Push Service를 사용하지 않고도 native token을 획득해 FCM/APNs로 직접 발송할 수 있다. 참고: [Send notifications with FCM and APNs](https://docs.expo.dev/push-notifications/sending-notifications-custom/)

## 8. 지도·위치 spike

Native 지도 wrapper를 선택하기 전에 1~2일 범위의 독립 spike를 진행한다.

- [ ] iOS/Android development build 컴파일 성공
- [ ] 마커 3종: 현재 위치·출발·도착
- [ ] polyline 여러 개 표시·교체·제거
- [ ] 500~2,000개 좌표 경로 렌더링 프레임 확인
- [ ] 카메라 이동, fit bounds, tab 전환 후 resize 확인
- [ ] 지도 위 bottom sheet gesture 충돌 확인
- [ ] Android back·iOS gesture 충돌 확인
- [ ] precise/approximate/denied 위치 상태 확인
- [ ] 앱 백그라운드 전환 후 map/location 회복
- [ ] 사용 중인 Expo SDK/New Architecture 호환성 확인
- [ ] 최근 release/issue 활성도와 native SDK 버전 대응 확인

1차 배포에서는 foreground location만 요청한다. 자체 turn-by-turn 길안내를 시작하기 전에 background location을 추가하지 않는다.

## 9. API 통신 규칙

- API base URL은 환경 설정으로만 주입한다.
- access token은 `Authorization: Bearer` 헤더로 전달한다.
- 서버와 앱에 `requestId`/correlation ID를 전파한다.
- GET 등 멱등 요청만 일시 오류에 제한적으로 retry한다.
- 사용자 조작으로 시작한 POST에는 필요 시 idempotency key를 붙인다.
- 화면 이동/검색어 변경 시 AbortSignal로 이전 요청을 취소한다.
- 도착 정보에 `updatedAt`, `expiresAt`, `source`, `status`를 포함한다.
- 앱은 empty/loading/error/stale/partial/offline 상태를 구분한다.
- 카카오 REST 검색을 앱에서 직접 호출하지 않고 기존 백엔드 `KakaoApiPort`를 통한 API로 제공한다.
- OpenAPI 명세를 기준으로 TypeScript DTO/client 생성을 검토하고, 수동 타입 중복을 줄인다.

### 실시간 통신 단계

1. MVP: REST snapshot + 화면 활성 중 adaptive polling + push.
2. 계측: freshness p95, 요청 수, payload, 실패율, 배터리 영향.
3. 임계를 넘으면 foreground 도착 화면에 SSE 도입.
4. SSE는 snapshot version, event sequence, reconnect, stale timeout, polling fallback을 함께 구현.
5. 앱이 background로 가면 SSE를 종료하고 FCM/APNs로 전환.

## 10. UI 이관 순서

### 0단계: 디자인 토큰

- [ ] CSS에서 color, spacing, radius, typography, shadow 추출
- [ ] `shared/theme` TypeScript token으로 정의
- [ ] 플랫폼별 elevation/shadow 구현 분리
- [ ] 스크린샷을 디자인 reference로 고정

### 1단계: 기본 UI

- [ ] `AppButton` → RN `Pressable` 기반 button
- [ ] `BottomNavigation` → Expo Router tabs
- [ ] loading/error/empty/result 공통 화면
- [ ] confirm dialog/bottom sheet
- [ ] 키보드·safe area·screen reader 기본선

### 2단계: 주요 사용자 흐름

- [ ] 지도 홈
- [ ] 장소 검색
- [ ] 출발지·도착지 선택
- [ ] 경로 결과
- [ ] 경로 상세/타임라인
- [ ] 환승 예측
- [ ] 즐겨찾기
- [ ] 알림 설정/목록

### 3단계: 플랫폼 마감

- [ ] iOS swipe-back·Android system back
- [ ] 지도 gesture·bottom sheet gesture 조율
- [ ] Dynamic Type/폰트 크기 확대
- [ ] VoiceOver/TalkBack label·focus order
- [ ] reduced motion
- [ ] 저사양 Android 렌더링·리스트 스크롤

### 이관 원칙

- 기존 JSX/CSS 라인 단위 변환을 하지 않는다.
- 화면별로 `스크린샷 → 디자인 토큰 → RN layout → interaction → accessibility`를 완료한다.
- 기존 화면의 문구·정보 계층·상태 전이는 유지하되 native navigation·gesture 규칙은 따로 적용한다.
- 지도 화면과 일반 화면을 동시에 이관하지 않고, native map spike를 먼저 완료한다.

## 11. 포트폴리오용 기술 기록

아래 ADR(Architecture Decision Record)를 결정 시점에 작성한다.

- [ ] ADR-001: Expo managed/CNG + Development Build 선택
- [ ] ADR-002: CSS UI 재사용 대신 디자인 토큰·순수 로직 재사용
- [ ] ADR-003: OAuth provider identity와 Nextroute user 분리
- [ ] ADR-004: native FCM/APNs direct delivery와 notification outbox 일반화
- [ ] ADR-005: REST polling 기준선 후 SSE 도입 임계
- [ ] ADR-006: native map wrapper 선택 spike 결과

각 ADR은 다음을 포함한다.

```text
문제 / 제약 / 후보 / 결정 / 포기한 선택지 / 결과 / 재검토 조건 / 측정 결과
```

스크린샷만 보여주기보다 아래 before/after를 보존한다.

- cold start 시간
- 첫 경로 결과 표시 p50/p95
- 도착 정보 freshness p95
- 폴링 대비 SSE 요청 수·payload 감소
- push enqueue-to-provider·provider-to-device latency
- native map 최대 polyline 렌더링 FPS/메모리
- 네트워크 단절 후 복구 시간

## 12. 초기 개발 순서

| Phase | 결과물 | 통과 조건 |
|---|---|---|
| 0 | ID·스토어·OAuth·푸시 식별자 확정 | 환경별 값 표 완료 |
| 1 | 빈 Expo 앱 + development build | iOS/Android 실기기 실행 |
| 2 | theme, router, error boundary, API client, Query provider | mock API 화면 표시 |
| 3 | core 로직 이관 | 단위 테스트 통과 |
| 4 | native map spike | 양 플랫폼 마커/polyline/gesture 통과 |
| 5 | 지도 홈 → 장소 검색 → 경로 결과 | 비로그인 critical path 완주 |
| 6 | OAuth + secure session | cold/warm/cancel/refresh/logout 통과 |
| 7 | 즐겨찾기·알림 + FCM/APNs | foreground/background/terminated 푸시 통과 |
| 8 | 오프라인·장애·관측성 마감 | stale/partial/retry/PII masking 통과 |
| 9 | TestFlight·Play internal testing | 스토어 메타데이터·개인정보·탈퇴 완료 |

## 13. MVP Definition of Done

- [ ] Android·iOS 실기기에서 동일 API 환경 접속
- [ ] 앱 실행 → 위치 또는 수동 출발지 → 도착지 → 경로 결과 → 상세 완주
- [ ] 카카오 OAuth 성공·취소·재시도·로그아웃·회원 탈퇴 완주
- [ ] access token 만료 시 refresh single-flight 작동
- [ ] 푸시 알림을 탭하면 해당 알림 상세로 이동 후 최신 데이터 재조회
- [ ] 위치·푸시 권한 거부 시 대체 흐름 제공
- [ ] API 지연·오프라인·partial response 시 명시적 UI
- [ ] 백그라운드에서 불필요한 폴링·위치 수집 중지
- [ ] 앱 번들에 비밀키·provider secret·server credential 미포함
- [ ] 로그·crash report에 JWT, OAuth code, push token, 정밀 좌표 미노출
- [ ] 자동화 테스트·실기기 smoke test·release checklist 통과

## 14. 초기 비범위

- 자체 turn-by-turn 네비게이션
- background continuous location
- WebSocket
- 근거 없는 SSE 선도입
- web 빌드·SEO·desktop layout
- Toss 인증·Toss 메신저·TDS
- 플랫폼별 별도 기능의 대규모 확장

초기 핵심은 **새 네이티브 기반을 양 플랫폼에서 먼저 검증하고, 기존 UI를 화면별로 재구현하며, 인증·푸시의 서버 계약을 Toss와 분리하는 것**이다.
