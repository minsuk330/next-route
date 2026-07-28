# Phase 1 — PR 분해 인덱스

- 상위 계획: [PLAN.md](./PLAN.md) §7 Phase 1
- 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md)
- 개정일: 2026-07-28 (SC-1 다중 기기 제거 반영)

이 문서는 **인덱스**다. 실행 상세는 각 PR 의 `PLAN.md` 에 있다. 여기에 DDL·시그니처를 중복 기재하지 않는다(두 곳이 어긋나는 것을 막기 위해).

## 1. PR 목록

| 순서 | 슬러그 | 티어 | 마이그레이션 | 선행 |
|---|---|---|---|---|
| ① | [journey-auth-oauth](../journey-auth-oauth/SUMMARY.md) | **L** | V33 `auth_identity` | — |
| ② | [journey-route-snapshot](../journey-route-snapshot/SUMMARY.md) | **M** | 없음 | — (①과 병렬 가능) |
| ③ | [journey-persistence](../journey-persistence/SUMMARY.md) | **L** | V34 journey 5테이블 | ①② |

브랜치는 각각 fresh main 기준 독립 브랜치다. 스택하지 않는다.

②는 인증과 독립이라 ①과 병렬로 진행해도 된다. ③만 ①② 둘 다에 의존한다.

기기 세션 PR(`journey-auth-device`)은 [SCOPE-CHANGES.md](./SCOPE-CHANGES.md) SC-1 로 **삭제**됐다. 다중 기기를 지원하지 않기로 했다.

## 2. 티어 판정 근거

CLAUDE.md 의 L 판별 기준 — ①diff 모양이 미리 안 그려짐 ②스키마·API·모듈경계 신설 ③PR 여러 개 필요 ④설계 대안 논쟁 여지.

| 슬러그 | 티어 | 근거 |
|---|---|---|
| journey-auth-oauth | L | 신원 모델 전환(②) + 계정 병합 정책 논쟁(④) + provider 3사 검증 설계 |
| journey-route-snapshot | M | 포트/어댑터 1쌍 + 응답 필드 추가. 단일 관심사 |
| journey-persistence | L | 새 모듈 경계 신설(②) + 5테이블 + 컴파일 규칙 설계 여지(④) |

L 두 건은 착수 전 codex 논의를 거치고 구현은 세션을 분리한다. M 한 건은 SUMMARY 승인 후 끝까지 자율 구현한다.

## 3. Phase 1 공통 함정

각 PLAN.md 에도 적혀 있지만, 착수 전 한 번 훑을 것.

- **마이그레이션 번호** — V30·V31·V32 는 polyline 이 선점했다. journey 계열은 V33 부터.
- **`.gitignore` 파일별 whitelist** — `.gitignore:9` 가 migration 디렉터리를 통째로 제외한다. 새 SQL 마다 `!` 라인을 추가하지 않으면 VPS 배포 시 파일이 없다.
- **`SecurityConfig` 는 화이트리스트** — `anyRequest().permitAll()`(`SecurityConfig.java:47`)이므로 새 보호 엔드포인트마다 `authenticated()` 규칙을 명시해야 한다. 빠뜨리면 익명에 열린다.
- **principal 타입 불변** — `@AuthenticationPrincipal Long userId` 가 5개 컨트롤러 15곳에서 쓰인다. 바꾸지 않는다.
- **마이그레이션 실행 주체는 Flyway 하나다.** `spring.flyway.enabled: true`(`application.yaml:4`) 라 앱이 뜰 때 스스로 적용한다. 손으로 SQL 을 넣으면 `flyway_schema_history` 에 기록이 없어 다음 부팅에서 재실행되다 실패한다. 기존의 "V18+ VPS 수동 적용" 메모는 **git 추적 여부와 실행 주체를 뒤섞은 것**이다 — 앱을 로컬 빌드해 올리므로 git 에 없는 파일도 jar 에는 들어가고 Flyway 가 적용해 왔다.
- **착수 전 정리** — 작업트리의 `bus-route-segment-polyline` 변경(V32 + untracked 15개)을 먼저 머지한다.

## 4. Phase 1 완료 조건

- [ ] PR 3건 순차 머지
- [ ] VPS 에 V33·V34 적용
- [ ] Apple·Google·Kakao 로 가입한 계정이 `POST /api/journeys` 를 통과
- [ ] 기존 토스 미니앱 로그인 회귀 없음
- [ ] 기존 경로 검색·즐겨찾기·알림 API p95 회귀 없음

## 5. Phase 2 이후로 넘긴 것

- Journey 상태머신 전이 규칙과 이벤트 적용 API (Phase 2)
- ETA·환승 위험 계산 (Phase 3)
- `next_evaluation_at` 을 실제로 채우는 주체 (Phase 4)
- 푸시 토큰(`user_push_token`)·Journey 알림 outbox (Phase 5)
- 회원 탈퇴(`DELETE /api/me`) — App Store 심사 제출 전 필요
- `TOSS` identity 연결 API, `users.toss_user_key` 컬럼 제거 (별도)

## 6. 전역 결정 기록

| # | 결정 | 상태 | 근거 위치 |
|---|---|---|---|
| D1 | 다중 provider OAuth 도입 | **확정** — 토스는 미니앱 전용 채널로 격하 | journey-auth-oauth §1 |
| D2 | 지원 provider 범위 | 기본안 Apple·Google·Kakao | journey-auth-oauth §8 |
| D3 | 스냅샷 TTL 600초 | 기본안 | journey-route-snapshot §7 |
| D4 | 익명 검색 스냅샷으로 Journey 시작 허용 | 허용 | journey-route-snapshot §4.2 |
| D13 | 스냅샷 크기 줄이기(폴리라인 제외 등) | **1차 미적용** — 통째로 저장하고 측정 후 재검토 | journey-route-snapshot §4.1.1 |
| D5 | `replace=true` 로 기존 Journey 교체 | 허용, 명시적 파라미터 필수 | journey-persistence §3.3 |
| D6 | 같은 이메일 다른 provider 자동 병합 | **하지 않는다** | journey-auth-oauth §4.3 |
| D7 | `JourneySession` 의 `BaseEntity` 상속 | 상속하지 않음(uuid PK) | journey-persistence §8 |
| D8 | 다중 기기 지원 | **하지 않는다** — 기기 세션 PR 삭제 | [SCOPE-CHANGES.md](./SCOPE-CHANGES.md) SC-1 |
| D9 | 계정당 앱 로그인 수단 개수 | **최대 1개**, `TOSS` 만 예외로 공존 | journey-auth-oauth §5.1 |
| D12 | 앱에서 토스 로그인 지원 | **하지 않는다** — 토스는 미니앱 전용, 공용 로그인 API 가 400 | journey-auth-oauth §5.6 |
| D14 | 토큰 폐기 방식 | 사용자 토큰 세대 번호 | journey-auth-oauth §5.8 |
| D15 | 이관 배포 방식 | 짧은 인증 정지 창 + 개수 대조 | journey-auth-oauth §6.1 |
| D16 | 토스 신원 없는 사용자의 알림 API | `422` 로 거절, Phase 5 에서 해제 | journey-auth-oauth §5.10 |
| D17 | 기존 토스 사용자의 앱 전환 | **전환 불가 — 별개 계정을 제품 정책으로 확정** | journey-auth-oauth §5.11 |
