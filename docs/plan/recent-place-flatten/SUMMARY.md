# 최근 검색 → 최근 장소 평탄화

## 뭘 하나
`GET /api/route/my/searches` 응답을 "출발+도착 쌍" 리스트에서 "등장한 장소를 좌표로 dedup 한 최근 장소" 리스트로 교체. 항목 = `{ id, lat, lng, name, searchedAt }`.

## 왜
클라가 출/도착을 쌍이 아닌 개별 장소 단위로 쓰고 싶어함. 재검색·자동완성에 "최근 장소" 형태가 맞음.

## 핵심 결정
- 좌표 정확일치로 dedup, 이름은 최근 라벨 — 좌표가 장소 정체성
- 정렬 = 장소별 max(searchedAt) DESC, tie 큰 id — 마지막 등장 순
- 앱단 자바 스트림 평탄화 — DB/포트/마이그레이션 무변경 (데이터 최대 50행)
- id = 대표(최근) 행 id, 삭제는 기존 `DELETE /{id}` 그대로 그 행만 — 좌표전파 삭제는 기각(후속)
- 계약 교체(flat 필드 폐기) — 모바일 breaking, 동시배포 필요

## 건드는 곳
RecentPlaceResponse(신규), GetSearchHistoryUseCase, RouteSearchHistoryService, RouteSearchHistoryController, SearchHistoryResponse(삭제), 서비스·컨트롤러 테스트

## 리스크
- 좌표 매핑 lat=Y/lng=X 뒤집으면 위치 틀어짐 (구현 시 고정)
- 대표 id 삭제 = 같은 좌표 다른 행 남아 다음 GET 재등장 (합의된 감수)
- 모바일 breaking — 동시배포 조율 필요

## 검증법
`./gradlew compileJava compileTestJava` + 서비스/컨트롤러 테스트 통과. 평탄화·dedup 대표·정렬·tie-break·좌표매핑 각 테스트 1개. 저장경로 IT 무영향.
