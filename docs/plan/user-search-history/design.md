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
| 쓰기 경로 | **비동기 bounded executor**. 컨트롤러가 검색 후 이벤트 submit → 워커가 저장. 큐 포화·실패시 drop + metric | 응답 경로 완전 분리(동기 저장은 커넥션풀·lock 대기까지 응답에 전파 — codex 지적). outbox 는 완전무유실용이라 비목표 대비 과함 |
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
UNIQUE (user_id, startx, starty, endx, endy)   -- dedup 키(ON CONFLICT 타깃)
INDEX  (user_id, searched_at DESC, id DESC)    -- 목록 조회 + 동시각 tie-breaker
FK user_id → users(id) ON DELETE CASCADE       -- 회원 탈퇴시 이력 동반 삭제(FK 삭제 차단 방지)
```

- 컬럼명은 기존 `route_search_log`(`startx/starty/endx/endy`) 와 Hibernate 기본 매핑에 맞춘다.
- **마이그레이션 파일은 gitignore 컨벤션(V18+ untracked)** 을 따른다. 다음 번호 = `V29__create_route_search_history.sql`.
  git 에 커밋되지 않으며 로컬/VPS 에 존재한다(reference: VPS 배포 구조 메모리).

### 기존 `route_search_log` 처리
- write 중단(더 이상 저장하지 않음). 테이블은 **drop 하지 않는다**(비파괴).
- 기존 null-user 행은 방치. 신규 조회는 `route_search_history` 만 본다.

## 4. 쓰기 경로 (비동기)

```
Controller.search(@AuthenticationPrincipal Long userId, request)   -- 익명이면 userId=null
  → result = SearchRouteUseCase.search(request)                    -- 시그니처 무변경
  → userId != null 이면 RecordSearchHistoryUseCase.record(userId, request, result)
       → SearchHistoryEntry 로 변환 후 bounded executor 에 submit(즉시 반환)
       → 큐 포화(RejectedExecutionException) → drop metric, 응답엔 영향 0
  → return result
        [별도 워커 스레드]
        → SearchHistoryCommandPort.upsertAndTrim(entry, maxPerUser)  @Transactional
        → 워커 예외(제약위반·DB 오류) → 로그 + drop metric
```

- **컨트롤러 오케스트레이션**: `SearchRouteUseCase.search(request)` 는 **그대로 두고**(codex 권고 —
  인터페이스·기존 테스트·내부 호출자 무파손), 컨트롤러가 검색 후 `RecordSearchHistoryUseCase` 에 위임.
  facade port 불필요. `FavoriteController` 가 유스케이스 위임하는 패턴과 동형.
- **optional auth**: `/api/route/search` 는 `.anyRequest().permitAll()` 대상이고 `JwtAuthFilter` 가
  JWT 있으면 principal(Long userId)을 세팅. 컨트롤러에 `@AuthenticationPrincipal Long userId` 추가만,
  **SecurityConfig 변경 불필요**. 익명 요청은 userId=null.
- **비동기 저장** — 응답 경로 완전 분리:
  - 전용 bounded `ThreadPoolTaskExecutor`(core/max/queue 유한, 거부정책 Abort).
  - `record()` 는 executor 에 submit 만 하고 즉시 반환. 큐 포화 → `RejectedExecutionException` catch → drop metric.
  - 워커가 `@Transactional` 로 upsert+trim 수행. 짧은 `statement_timeout`·`lock_timeout` 을 워커 tx 에 적용
    가능(응답과 무관하므로 안전).
- **dedup+cap = 단일 upsert + trim** (사전조회 race 제거, codex 권고):
  - `INSERT INTO route_search_history (...) VALUES (...) ON CONFLICT (user_id, startx, starty, endx, endy)
    DO UPDATE SET searched_at=EXCLUDED.searched_at, path_count=EXCLUDED.path_count,
    search_type=EXCLUDED.search_type, start_name=EXCLUDED.start_name, end_name=EXCLUDED.end_name`
    (이름도 최신 검색값으로 갱신).
  - trim: `DELETE FROM route_search_history WHERE user_id=? AND id NOT IN
    (SELECT id FROM route_search_history WHERE user_id=? ORDER BY searched_at DESC, id DESC LIMIT ?)`.
- 기존 `RouteSearchService.saveLog`(route_search_log 매 검색 동기 저장 + responseJson strip)는 **제거**한다.
  이건 지금도 응답 경로의 동기 DB 쓰기이므로 제거 자체가 순이득. route_search_log 는 읽는 곳이 없어 파손 없음.

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
  - `RecordSearchHistoryUseCase.record(Long userId, RouteSearchRequest, RouteSearchResult)` — 쓰기(컨트롤러가 사용, async submit)
  - `GetSearchHistoryUseCase`, `DeleteSearchHistoryUseCase`, `ClearSearchHistoryUseCase` — 조회/관리(PR2)
- **application service**
  - `RouteSearchHistoryRecorder` — `RecordSearchHistoryUseCase` 구현. entry 변환 + executor submit + 포화 drop metric.
  - `SearchHistoryAsyncWriter` — 워커(@Transactional). `SearchHistoryCommandPort.upsertAndTrim` 호출.
  - `RouteSearchHistoryService` — 조회/삭제(PR2).
- **port/out** (중간 두께, persistence-neutral)
  - `SearchHistoryCommandPort.upsertAndTrim(SearchHistoryEntry entry, int maxPerUser)` — 원자적 upsert+trim.
  - `SearchHistoryManagementPort` — `findByUser`, `deleteByIdAndUser`, `deleteByUser` (PR2).
  - `SearchHistoryEntry` — 좌표·이름·시각·pathCount·searchType·userId 를 담은 application DTO(엔티티/요청 DTO 비노출).
- **domain** — `RouteSearchHistory` 엔티티 + JPA repository(native upsert 는 `@Query`/`@Modifying`).
- **infrastructure/adapter/in** — `RouteSearchHistoryController`(PR2).
- **config** — `SearchHistoryExecutorConfig`(전용 bounded executor 빈).
- 기존 `FavoriteController`(`@AuthenticationPrincipal Long userId`, 유스케이스 위임) 패턴을 그대로 따른다.

## 7. 설정

- `route.search.history.max-per-user` (기본 50) — cap N.
- `route.search.history.executor.core-size`(기본 2), `.max-size`(기본 4), `.queue-capacity`(기본 500) — bounded executor.
- 프로퍼티 클래스로 노출(기존 `RouteSearch*Properties` 패턴).

## 8. 테스트

- **Recorder(submit)**: (a) 큐 포화(`RejectedExecutionException`) → drop metric, (b) userId=null 경로는 컨트롤러에서 미호출.
- **Async writer + upsert(IT, 실 DB)**: (a) 중복 좌표 재검색 → 새 행 아님·`searched_at`·이름 갱신, (b) N 초과 → 오래된 행 삭제로 N 유지,
  (c) 동시 동일좌표 insert → unique 충돌 없이 1행(ON CONFLICT), (d) 워커 예외 → 검색 응답 무관·drop metric.
- **Optional auth(컨트롤러)**: 익명 → record 미호출, 로그인 → record 호출.
- **조회 API(PR2)**: 최신순 목록(tie-breaker id DESC), `DELETE /{id}` 타인 소유 → 404·미삭제, 전체삭제.
- **마이그레이션**: 테이블/제약/인덱스/FK CASCADE 생성 스모크(IT).

## 9. PR 분할 (L)

- **PR1 — 저장 파이프라인**: V29 마이그레이션(FK CASCADE, tie-breaker 인덱스) + `RouteSearchHistory` 엔티티/repo
  + `SearchHistoryCommandPort`/어댑터(native upsert+trim) + `SearchHistoryEntry` + bounded executor 빈 +
  `RouteSearchHistoryRecorder`(submit/drop) + `SearchHistoryAsyncWriter` + 컨트롤러 배선(optional auth, authed-only) +
  `RouteSearchService.saveLog` 제거. recorder·auth 단위 + upsert/trim/동시성 IT.
- **PR2 — 조회/관리 API**: `/api/route/my/searches` 컨트롤러 + Get/Delete/Clear 유스케이스 + `SearchHistoryManagementPort`
  + 보안 규칙 + 조회/삭제 테스트.

각 PR 은 fresh main 기준 독립 브랜치(스택 금지). PR1 머지 후 PR2 를 새 브랜치로.

## 10. 리스크 · 결정 (codex 논의 반영 완료)

- **dedup 좌표 정확도**: `UNIQUE`·upsert 가 double 정확 일치. 저장이력에서 재검색(동일 좌표 재전송)엔 정확.
  사용자가 장소를 다시 찍어 미세하게 다른 좌표면 별도 행 — **허용 범위**(반올림 정규화는 비목표).
- **동시성**: 단일 `INSERT ON CONFLICT DO UPDATE` 로 동일좌표 동시 insert 를 원자 처리(사전조회 race 제거).
  trim 은 근사(동시 다발시 잠깐 N±). 요청당 한 자릿수라 허용. 동시성 IT 로 검증.
- **응답 영향 = 제거됨**: 저장을 bounded executor 로 완전 비동기화. 커넥션풀·lock 대기가 응답에 전파되지 않음.
  워커 tx 에 짧은 `statement_timeout`/`lock_timeout` 적용.
- **SearchRouteUseCase 무변경**: 시그니처 유지, 컨트롤러가 record 위임(codex 권고). 기존 테스트·내부 호출자 무파손.
- **마이그레이션 미추적 배포 리스크**: V29 는 gitignore 컨벤션상 미커밋 → 코드만 배포되고 마이그레이션 누락 시
  테이블 부재로 upsert 전량 실패(→ drop, 조용한 유실). **완화**: 배포 체크리스트에 V29 존재 확인 추가(§11).
  Flyway 는 present 파일만 적용하므로 파일 배치가 곧 계약. 컨벤션 자체 변경은 이 작업 범위 밖.
- **PII/보존/탈퇴**: 좌표·장소명 저장. 회원 탈퇴시 FK `ON DELETE CASCADE` 로 이력 동반 삭제. 기간 기반 retention 은
  현재 비목표(cap N 으로 상한만). 필요해지면 후속.

## 11. 배포 체크리스트 (VPS)

- [ ] `V29__create_route_search_history.sql` 이 VPS 빌드 컨텍스트에 존재(코드와 함께 배치) — 누락시 이력 전량 drop.
- [ ] 부팅 로그에서 Flyway V29 적용 확인.
