# 재탐색 제안 저장소 (Phase 6 ②)

## 뭘 하나

수락 전 재탐색 대안을 담는 `reroute_proposal` 표(V42)와 조회·요청 API 를 만든다.
후보는 화면 요약과 **적용 재료**를 함께 저장하되 적용 재료는 API 밖으로 안 나간다.
이 PR 만으로는 아무것도 안 보인다 — 트리거(①)와 후보 생성기(③④)가 없어 항상 "제안 없음"이다.

## 왜

Phase 6 의 뒤 PR 넷(③④⑤⑥)이 전부 이 계약을 따른다. 표와 후보 모양을 먼저 얼려야
세 PR 이 서로 다르게 읽는 일이 없다.

## 핵심 결정

- **후보를 두 겹으로 저장**(`apply` + `display`) — 화면 요약만 두면 다른 경로 수락 시 새 leg 를 만들 재료가 없다. `apply` 는 `PathResult` 원본이라 ⑤가 `JourneyCompiler` 에 그대로 먹인다
- **`apply` 는 API 응답에서 뺀다** — 후보 3개면 수십 KB 다. 앱은 `candidateId` + `display` 만 쓴다. 저장은 inline 유지(Redis 참조 기각 — best-effort 캐시라 수락 순간 사라져 있을 수 있다)
- **`candidateId` 는 UUID 값**(순번 기각) — 순번은 정렬이 바뀌면 다른 경로를 적용한다
- **만료는 lazy + 배치 sweep 둘 다** — lazy 만 두면 앱이 죽은 채 TTL 이 지난 행이 `ACTIVE` 로 남아 새 제안을 막는다. 여정이 끝날 때도 기존 알림 취소 자리에서 함께 접는다
- **다형 `apply` 의 이름을 손으로 고정**(`@JsonSubTypes`, V42 `kind` 와 같은 문자열) — 클래스명이 JSON 에 박히면 리팩터링이 저장된 행을 깬다. sealed 는 유지(종류 추가는 DB·⑤까지 바뀌는 호환성 변경이라 드러나야 한다)
- **부분 unique**(`WHERE status='ACTIVE'`) + **기본값은 항상 거절** — 전자는 Phase 5 `delivery_key` 전례 회피, 후자는 ①이 늦어져도 외부 검색이 안 돌게

## 건드는 곳

`V42__create_reroute_proposal.sql` · `.gitignore` · `db/it/journey-reset.sql` ·
`domain/journey/entity/RerouteProposal*` · `domain/journey/repository/RerouteProposalRepository` ·
`application/journey/dto/RerouteCandidate*`·`RerouteProposalView`·`RerouteContext` ·
`application/journey/port/out/RerouteCandidateProvider`·`RerouteEligibilityPolicy` ·
`application/journey/service/RerouteProposalService`·`RerouteProposalTxService`·`RerouteProposalExpiryService` ·
`scheduler/journey/RerouteProposalExpiryScheduler` ·
`JourneyCommandService`·`JourneyLeaseExpiryService`(각 한 줄) ·
`config/JourneyRerouteProperties` · `observability/JourneyRerouteMetrics` ·
`api/journey/JourneyRerouteProposalController` · `JourneyErrorCode`

## 리스크

- **후보 계약이 틀리면 ⑤에서야 드러난다** — `PathResult` 왕복 → `JourneyCompiler.compile` 성공과 discriminator 이름 고정을 contract test 로 못 박는다
- **부분 unique 를 놓치면 같은 환승에서 두 번째 제안이 영영 안 생긴다** — 스키마 IT 가 "만료 뒤 삽입 성공"을 직접 확인한다
- **`apply` 유출** — 뷰를 그대로 응답에 쓰면 새어 나간다. 변환을 한 자리로 모으고 응답 문자열에 `path`·`subPaths` 가 없음을 단언
- **V42 whitelist 누락** — `.gitignore` 가 파일별이라 빠뜨리면 배포에 SQL 이 안 올라간다(반복된 사고)

## 검증법

`./gradlew test --tests '*RerouteProposal*' --tests '*RerouteCandidate*'` 통과 +
여정·알림 기존 테스트 회귀 없음. 요청이 하나도 없는 상태에서 sweep 이 만료 행을 접고 그 뒤 새
제안 삽입이 되면 만료 설계가 맞는 것이다. 기본 상태에서 `POST` 가 `409 REROUTE_NOT_ELIGIBLE`,
`GET` 이 `proposal: null` — 기능이 조용히 켜지지 않는 것까지가 통과 조건이다.
