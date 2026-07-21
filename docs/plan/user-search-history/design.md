# 사용자별 경로 검색 이력 — 설계 (spec)

> 상태: 설계 확정(브레인스토밍 완료). 다음 단계 = `/plan-split user-search-history L`.
> 관련: route-search-resilience Step 5 item 4(검색 이력 저장)에서 파생. 응답 완전성 계약(PR #75)과 별개 축.

## 1. 목적 · 범위

로그인 사용자에게 "내 경로 검색 기록"을 정식 기능으로 제공한다. 저장은 로그인 사용자만,
검색 응답 경로를 느리게 하지 않으며, 목록·삭제 API 로 사용자가 직접 관리한다.

**범위 밖(비목표)**
- 익명 검색 이력/분석 로깅 — 저장하지 않는다.
- 완전 무유실 보장 — 외부 브로커 없이 단일 VPS Postgres 로는 불가. best-effort(아래 §4).
- 검색 결과(폴리라인 등) 재현 — 재검색은 좌표로 다시 호출한다.

## 2. 결정 요약 (브레인스토밍 확정)

| 항목 | 결정 | 근거 |
|---|---|---|
| 인증 | `/api/route/search` 공용 유지 + optional auth. 로그인시만 저장 | 기존 공용 클라이언트 무파손. 보안설정 무변경 |
| 쓰기 경로 | 동기 insert, 폴백 없음, 실패시 drop + metric | 단일 indexed insert ~1ms. DB 기반 폴백은 DB 다운엔 무력 → 과설계 회피 |
| 저장 내용 | 요약 필드만(좌표·이름·시각·pathCount·searchType). responseJson 없음 | 이력 리스트용. 용량·PII·스키마변경 취약성 축소 |
| 조회/관리 | 목록 + 개별삭제 + 전체삭제 | 정식 이력 기능 표준 CRUD |
| 보존 | 유저당 최근 N(기본 50) cap + 좌표동일 중복 collapse | 무한 증가·중복 지저분함 방지 |
| 익명 | 저장 안 함. 기존 `route_search_log` always-save 중단 | 그 테이블은 코드에서 읽는 곳 0 (write-only 죽은 데이터) |

## 3. 저장 모델

### 신규 테이블 `route_search_history`
```
id           bigint identity PK
user_id      bigint NOT NULL  → users(id)
startx       double precision NOT NULL
starty       double precision NOT NULL
endx         double precision NOT NULL
endy         double precision NOT NULL
start_name   varchar(255)
end_name     varchar(255)
path_count   integer
search_type  integer
searched_at  timestamp(6) NOT NULL      -- collapse 시 갱신(리스트 정렬 키)
created_at / updated_at / deleted_at    -- BaseEntity(컬럼은 유지하되 삭제는 물리삭제, §5 참고)
UNIQUE (user_id, startx, starty, endx, endy)   -- dedup 키
INDEX  (user_id, searched_at DESC)             -- 목록 조회
```

- 컬럼명은 기존 `route_search_log`(`startx/starty/endx/endy`) 와 Hibernate 기본 매핑에 맞춘다.
- **마이그레이션 파일은 gitignore 컨벤션(V18+ untracked)** 을 따른다. 다음 번호 = `V29__create_route_search_history.sql`.
  git 에 커밋되지 않으며 로컬/VPS 에 존재한다(reference: VPS 배포 구조 메모리).

### 기존 `route_search_log` 처리
- write 중단(더 이상 저장하지 않음). 테이블은 **drop 하지 않는다**(비파괴).
- 기존 null-user 행은 방치. 신규 조회는 `route_search_history` 만 본다.

## 4. 쓰기 경로

```
Controller.search(@AuthenticationPrincipal Long userId, request)   -- 익명이면 userId=null
  → SearchRouteUseCase.search(request, userId)
     → 검색 수행(기존 회복탄력성 흐름 그대로)
     → userId != null 이면 RouteSearchHistoryRecorder.record(userId, request, result)
     → recorder 예외는 삼키고 drop metric. 검색 응답은 정상 반환
```

- **optional auth**: `/api/route/search` 는 `.anyRequest().permitAll()` 대상이고 `JwtAuthFilter` 가
  JWT 있으면 principal(Long userId)을 세팅한다. 따라서 컨트롤러에 `@AuthenticationPrincipal Long userId`
  파라미터만 추가하면 되고 **SecurityConfig 변경 불필요**. 익명 요청은 userId=null.
- `SearchRouteUseCase.search` 에 nullable `userId` 파라미터 추가. `searchWithoutRealtime` 내부 호출자
  (`RouteSearchTravelTimeEstimator`)는 null 을 넘긴다.
- **RouteSearchHistoryRecorder** (`@Transactional`) — dedup + cap:
  1. `(user_id, 좌표)` 로 기존 행 조회
  2. 있으면 → `searched_at`·`path_count`·`search_type` 갱신(collapse; 새 행 만들지 않음)
  3. 없으면 → insert 후 `count(user_id) > N` 이면 오래된(`searched_at ASC`) 초과분 물리삭제
- 저장 실패(예외·제약위반 등)는 `RouteSearchService` 가 catch → drop metric 기록, 검색 결과 반환에 영향 없음.
- 기존 `RouteSearchService.saveLog`(route_search_log 저장 + responseJson strip)는 제거하고 위 recorder 로 대체.

## 5. 조회 · 관리 API (회원 전용)

베이스 경로 `/api/route/my/searches`.

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/api/route/my/searches` | 본인 이력 최신순(`searched_at DESC`) 목록. cap N 이내라 페이지네이션 생략 |
| DELETE | `/api/route/my/searches/{id}` | 본인 소유 행만 물리삭제. 없거나 타인 소유면 404 |
| DELETE | `/api/route/my/searches` | 본인 이력 전체 물리삭제 |

- **삭제는 물리삭제(hard delete)**. BaseEntity 의 soft-delete(`deletedAt`)를 쓰면 `UNIQUE(user_id, 좌표)`
  때문에 같은 경로 재검색 시 제약 위반이 나므로, 이력에 한해 물리삭제로 한다.
- 소유 검증: `DELETE /{id}` 는 `id` 와 `userId` 가 함께 일치하는 행만 지운다(영향 0 → 404).
- SecurityConfig 에 `.requestMatchers("/api/route/my/**").authenticated()` 추가.

### 응답 DTO `SearchHistoryResponse`
```
id, startX, startY, endX, endY, startName, endName,
pathCount, searchType, searchedAt
```

## 6. 헥사고날 구조

- **port/in** (application/route/port/in)
  - `RecordSearchHistoryUseCase` — 쓰기(내부, RouteSearchService 가 사용)
  - `GetSearchHistoryUseCase`, `DeleteSearchHistoryUseCase`, `ClearSearchHistoryUseCase` — 조회/관리
- **application service** — `RouteSearchHistoryRecorder`(쓰기), `RouteSearchHistoryService`(조회/삭제)
- **port/out** — `SearchHistoryRepositoryPort`(조회·저장·cap·삭제)
- **domain** — `RouteSearchHistory` 엔티티 + JPA repository
- **infrastructure/adapter/in** — `RouteSearchHistoryController`
- 기존 `FavoriteController`(`@AuthenticationPrincipal Long userId`, 유스케이스 위임) 패턴을 그대로 따른다.

## 7. 설정

- `route.search.history.max-per-user` (기본 50) — cap N.
- 프로퍼티 클래스로 노출(기존 `RouteSearch*Properties` 패턴).

## 8. 테스트

- **Recorder**: (a) 중복 좌표 재검색 → 새 행 아님·`searched_at` 갱신, (b) N 초과 → 오래된 행 삭제로 N 유지,
  (c) 저장 실패 → drop metric + 검색 응답 정상.
- **Optional auth**: 익명(userId=null) → 미저장, 로그인 → 저장.
- **조회 API**: 최신순 목록, `DELETE /{id}` 타인 소유 → 404·미삭제, 전체삭제.
- **마이그레이션**: 테이블/제약/인덱스 생성 스모크(IT).

## 9. PR 분할 (L)

- **PR1 — 저장 파이프라인**: V29 마이그레이션 + `RouteSearchHistory` 엔티티/repo + `SearchHistoryRepositoryPort`
  + `RouteSearchHistoryRecorder`(dedup/cap) + write-path 배선(optional auth, authed-only, drop-on-fail) +
  `route_search_log` write 은퇴. recorder·auth·dedup/cap 단위 테스트.
- **PR2 — 조회/관리 API**: `/api/route/my/searches` 컨트롤러 + Get/Delete/Clear 유스케이스 + 보안 규칙 +
  `max-per-user` 설정 + 조회/삭제 테스트.

각 PR 은 fresh main 기준 독립 브랜치(스택 금지). PR1 머지 후 PR2 를 새 브랜치로.

## 10. 리스크 · 오픈 포인트 (codex 논의 대상)

- **dedup 좌표 정확도**: `UNIQUE`·조회가 double 정확 일치. 저장이력에서 재검색(동일 좌표 재전송)엔 정확하나,
  사용자가 장소를 다시 찍어 미세하게 다른 좌표면 별도 행. 허용 범위로 본다(반올림 정규화는 비목표).
- **cap enforce 경쟁**: 동일 유저 동시 검색 다발 시 count 기반 삭제가 근사. 요청당 한 자릿수라 허용.
- **동기 저장의 응답 영향**: 정상 ~1ms. tail(락 경합·DB 포화)에서만 지연 전파. 회복탄력성의 deadline 은
  검색 provider 단계에만 적용되고 이력 저장은 그 밖 — 필요시 저장에 짧은 statement timeout 검토.
- **SearchRouteUseCase 시그니처 변경** 파급: 컨트롤러·내부 호출자·테스트. userId nullable 오버로드 vs 파라미터 추가 택1.
