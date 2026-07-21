# 사용자별 경로 검색 이력

## 뭘 하나
로그인 사용자에게 "내 경로 검색 기록" 정식 기능을 준다. 검색 시 요약 이력을 비동기로 저장하고,
목록·개별삭제·전체삭제 API 로 사용자가 직접 관리한다.

## 왜
경로 검색 회복탄력성 Step 5 의 남은 백로그. 익명 write-only 로 죽어있던 검색 로그를 사용자 기능으로 전환.

## 핵심 결정 (codex 논의 반영)
- 저장은 로그인 사용자만. `/search` 공용 유지 + optional auth → **보안설정 무변경**(익명 미저장)
- **비동기 bounded executor** 저장. 응답 경로 완전 분리, 큐 포화·실패시 drop+metric. (동기 저장은 lock·풀 대기가 응답에 전파 → 기각)
- dedup+cap = 단일 `INSERT ON CONFLICT DO UPDATE` + trim. 사전조회 race 제거·이름 갱신 포함
- `SearchRouteUseCase` 무변경, 컨트롤러가 record 위임. 기존 테스트·내부호출자 무파손
- 요약 필드만 저장(좌표·이름·시각). 신규 테이블 `route_search_history`(hard delete, FK ON DELETE CASCADE). 기존 route_search_log write 은퇴
- 유저당 최근 50건 cap + 좌표동일 collapse. 정렬 tie-breaker `searched_at DESC, id DESC`

## 건드는 곳
V29 마이그레이션 · RouteSearchHistory 엔티티/repo(native upsert/trim) · SearchHistoryCommandPort/어댑터 ·
SearchHistoryEntry · SearchHistoryExecutorConfig · RouteSearchHistoryRecorder/AsyncWriter · RouteSearchHistoryProperties ·
RouteSearchController · RouteSearchService(saveLog 제거) · RouteSearchMetrics · (PR2) RouteSearchHistoryService/Controller · SecurityConfig

## 리스크
- 마이그레이션 gitignore 컨벤션 → V29 미배포시 upsert 전량 drop. 배포 체크리스트로 완화
- dedup double 좌표 정확일치 — 미세 상이 좌표는 별도 행(허용)
- trim cap 은 동시 다발시 근사(N±). 요청당 한 자릿수라 허용

## 검증법
`./gradlew test` route-search 전체 그린. adapter IT(upsert 중복 1행·trim N유지·동시성), recorder 포화 drop,
optional auth 미저장/저장, 조회 소유검증(타인 삭제 404). 앱 부팅 V29 적용.
