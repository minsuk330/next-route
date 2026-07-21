# 사용자별 경로 검색 이력

## 뭘 하나
로그인 사용자에게 "내 경로 검색 기록" 정식 기능을 준다. 검색 시 요약 이력을 저장하고,
목록·개별삭제·전체삭제 API 로 사용자가 직접 관리한다.

## 왜
경로 검색 회복탄력성 Step 5 의 남은 백로그. 익명 write-only 로 죽어있던 검색 로그를 사용자 기능으로 전환.

## 핵심 결정
- 저장은 로그인 사용자만. `/search` 는 공용 유지 + optional auth → **보안설정 무변경**(익명은 미저장)
- 동기 insert, 폴백 없음, 실패시 drop+metric. (DB 폴백은 DB 다운엔 무력 → 기각)
- 요약 필드만 저장(좌표·이름·시각). responseJson 제거 → 용량·PII 축소
- 유저당 최근 50건 cap + 좌표동일 중복 collapse. 무한증가·중복 방지
- 신규 테이블 `route_search_history`(hard delete). 기존 `route_search_log` write 은퇴(읽는 곳 0, drop 안 함)

## 건드는 곳
V29 마이그레이션 · RouteSearchHistory 엔티티/repo · SearchHistoryRepositoryPort/어댑터 ·
RouteSearchHistoryRecorder · RouteSearchHistoryProperties · SearchRouteUseCase · RouteSearchService ·
RouteSearchController · RouteSearchMetrics · (PR2) RouteSearchHistoryService/Controller · SecurityConfig

## 리스크
- dedup 이 double 좌표 정확일치 — 미세하게 다른 좌표는 별도 행(허용 범위로 판단)
- 동기 저장 tail(락·DB 포화) 지연이 응답에 전파 — 필요시 statement timeout
- `SearchRouteUseCase` 시그니처 변경 파급(컨트롤러·내부 호출자·테스트)

## 검증법
`./gradlew test` route-search 전체 그린. recorder collapse/cap, optional auth 미저장/저장,
조회 소유검증(타인 삭제 404)·전체삭제 통과. 앱 부팅시 V29 적용.
