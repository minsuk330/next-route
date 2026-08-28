# 최근 검색 → 최근 장소 평탄화 (recent-place-flatten)

## 목표
`GET /api/route/my/searches` 응답을 "출발+도착 쌍(row)" → "등장한 장소를 좌표로 dedup 한 최근 장소 리스트"로 교체한다. 삭제/저장 경로는 변경 최소.

## 핵심 결정 (grilling 확정)
- **평탄화(B)**: 이력행 개념 버리고 등장 장소 전부 좌표 dedup → 단일 place 리스트. 출발/도착 구분 없음.
- **dedup 키 = 좌표 정확일치**. 이름은 대표(최근) 라벨.
- **정렬 = 장소별 max(searchedAt) DESC, tie rowId DESC.**
- **place 필드 = `{ id, lat, lng, name, searchedAt }`.** `pathCount/searchType` 버림.
- **좌표 매핑: lat = Y(startY/endY), lng = X(startX/endX).** (DB 는 X=경도, Y=위도)
- **id = 대표 row id (그 좌표 최근 등장 행).** 삭제는 기존 `DELETE /{id}` 로 그 행만 물리삭제(Q11=A). 같은 좌표 다른 행 남으면 다음 GET 에 재등장 — 감수.
- **구현 = 앱단 자바 스트림(Q5=A).** DB/포트/repository/마이그레이션 변경 없음.
- **계약 교체(Q2=A).** 기존 flat 필드 폐기 → 모바일 breaking, 동시배포 필요.
- **후속 보류**: 좌표기반 삭제 param(Q10) — 이번 스코프 밖.

## 변경 파일

### 1. 신규 `src/main/java/watoo/grd/nextroute/application/route/dto/RecentPlaceResponse.java`
```java
package watoo.grd.nextroute.application.route.dto;

import java.time.LocalDateTime;

/**
 * 최근 검색에서 등장한 장소(좌표 dedup). 출발/도착 구분 없이 평탄화한 단위.
 * id = 그 좌표가 최근 등장한 이력행 id(삭제 대상). 재검색은 좌표로 다시 호출한다.
 */
public record RecentPlaceResponse(
        Long id,
        Double lat,   // = Y (startY/endY)
        Double lng,   // = X (startX/endX)
        String name,
        LocalDateTime searchedAt
) {}
```

### 2. `application/route/port/in/GetSearchHistoryUseCase.java`
- 반환 타입 `List<SearchHistoryResponse>` → `List<RecentPlaceResponse>`
- 메서드명 `getAll` 유지 (호출부 churn 최소)
- import 교체

### 3. `application/route/service/RouteSearchHistoryService.java`
- `getAll` 구현을 평탄화 로직으로 교체. delete/clear 는 그대로.
- 알고리즘 (findByUser 는 이미 `searched_at DESC, id DESC` 정렬):
```java
@Override
public List<RecentPlaceResponse> getAll(Long userId) {
    Map<List<Double>, RecentPlaceResponse> byCoord = new LinkedHashMap<>();
    for (RouteSearchHistory h : managementPort.findByUser(userId)) {
        // 한 행 = 출발 좌표 + 도착 좌표 2개 후보. 행이 이미 최신순이라
        // 좌표별 첫 등장 = max(searchedAt), tie 시 큰 id → putIfAbsent 로 argmax 달성.
        putIfAbsentPlace(byCoord, h.getId(), h.getStartY(), h.getStartX(), h.getStartName(), h.getSearchedAt());
        putIfAbsentPlace(byCoord, h.getId(), h.getEndY(),   h.getEndX(),   h.getEndName(),   h.getSearchedAt());
    }
    return new ArrayList<>(byCoord.values());
}

private void putIfAbsentPlace(Map<List<Double>, RecentPlaceResponse> acc,
                              Long rowId, Double lat, Double lng, String name, LocalDateTime at) {
    acc.putIfAbsent(List.of(lng, lat),
            new RecentPlaceResponse(rowId, lat, lng, name, at));
}
```
  - 좌표 dedup 키 = `List.of(lng, lat)` (Double equals = 정확일치, 유니크제약과 일관).
  - 한 행 내 start 먼저 put → end. 두 후보 동일 searchedAt·id 라 순서 무관하나 start 우선 고정.
  - `putIfAbsent` = 이미 있으면(=더 최신 행에서 이미 등장) 무시 → 대표 = 최신.

### 4. `infrastructure/adapter/in/api/route/search/RouteSearchHistoryController.java`
- `getAll` 반환 타입 `List<SearchHistoryResponse>` → `List<RecentPlaceResponse>`
- import 교체. delete/clear/@ExceptionHandler 변경 없음.

### 5. 삭제 `application/route/dto/SearchHistoryResponse.java`
- 읽기 전용 DTO였고 read 경로만 사용 → 제거. (`SearchHistoryEntry` 쓰기 DTO 는 유지)
- grep 로 잔존 참조 없는지 확인 후 삭제.

## 변경 없음 (명시)
- 저장: `RecordSearchHistoryUseCase`/`Recorder`/`AsyncWriter`/`SearchHistoryPersistenceAdapter`/`upsert`/`trim` — 무변경
- 삭제/clear: `DeleteSearchHistoryUseCase`/`ClearSearchHistoryUseCase`/`SearchHistoryManagementPort`/`Adapter`/repository — 무변경
- 엔티티 `RouteSearchHistory`, 테이블, 인덱스, **마이그레이션 없음**
- 보안 `/api/route/my/**` authenticated — 무변경

## 테스트

### 6. `application/route/service/RouteSearchHistoryServiceTest.java` (수정)
- 기존 `getAll` 매핑 테스트 삭제/교체 (SearchHistoryResponse import 제거, RecentPlaceResponse 로)
- 신규 케이스:
  - **평탄화**: 1행(start≠end 좌표) → place 2개, lat=Y·lng=X 매핑 검증
  - **dedup+대표**: `집→카페`(t1), `카페→회사`(t2>t1) → "카페" 좌표 1개, 대표 searchedAt=t2·name=그 방향 이름·id=t2행 id
  - **정렬**: 여러 장소 max(searchedAt) DESC 순
  - **tie-break**: 같은 searchedAt 두 행 → 큰 id 행이 대표
  - delete/clear 위임 테스트는 그대로 통과 (무변경)

### 7. `infrastructure/.../RouteSearchHistoryControllerTest.java` (수정)
- `getAll_returns200WithList`: given 을 `List<RecentPlaceResponse>` 로 교체, jsonPath 를 `$[0].id/$[0].lat/$[0].lng/$[0].name/$[0].searchedAt` 로 검증. `startX/pathCount` jsonPath 제거.
- delete(200/404)/clear 테스트 그대로.

## 작업 순서
- [ ] 1. fresh main 기준 브랜치 `feat/recent-place-flatten` 생성 (`git log -2` 로 부모 확인 — 공유 워크트리 드리프트 주의)
- [ ] 2. `RecentPlaceResponse` 신규 작성
- [ ] 3. `GetSearchHistoryUseCase` 반환 타입 교체
- [ ] 4. `RouteSearchHistoryService.getAll` 평탄화 구현
- [ ] 5. 컨트롤러 반환 타입 교체
- [ ] 6. `SearchHistoryResponse` 참조 grep → 없으면 삭제
- [ ] 7. 서비스 테스트 수정+평탄화 케이스 추가
- [ ] 8. 컨트롤러 테스트 수정
- [ ] 9. 검증 실행 → 노트 `### 검증` 기록
- [ ] 10. 커밋 + PR (플랜 파일 포함), SUMMARY 를 PR 본문 요약에

## 검증
```
./gradlew compileJava compileTestJava
./gradlew test --tests "*RouteSearchHistoryServiceTest" \
               --tests "*RouteSearchHistoryControllerTest"
```
- 계약당 테스트: 평탄화·dedup 대표·정렬·tie-break·좌표매핑 각 1개 이상
- 기존 `SearchHistoryPersistenceAdapterIT`/`RouteSearchControllerTest`(저장경로) 무영향 통과 확인
- `SearchHistoryResponse` 잔존 참조 0 확인 (`grep -rn SearchHistoryResponse src`)

## 참조 (기존 코드)
- 컨트롤러: `infrastructure/adapter/in/api/route/search/RouteSearchHistoryController.java:31`
- 서비스: `application/route/service/RouteSearchHistoryService.java:28`
- 포트(out, 재사용): `application/route/port/out/SearchHistoryManagementPort.java:10` (`findByUser`)
- 정렬 repo: `domain/route/log/repository/RouteSearchHistoryRepository.java:58`
- 엔티티 좌표: `domain/route/log/entity/RouteSearchHistory.java:41` (startX/Y/endX/Y)
