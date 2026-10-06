# 재탐색 후보 경로 조회 + 하차 좌표 (#259)

## 뭘 하나
재탐색 후보마다 하차 정류장 좌표(`alight.x/y`)를 싣는다.
말풍선을 누를 때 쓸 후보 경로 조회 API(`GET …/candidates/{cid}/route`)를 만든다.
응답은 `path`(검색 결과와 같은 모양)와 `ride`(지금 타는 구간)다.

## 왜
앱의 말풍선 UI가 상세 경로와 지도 겹쳐 그리기를 하려면 경로 원본이 필요하다. 정류장 id 매칭 없이 말풍선을 놓으려면 좌표가 필요하다.

## 핵심 결정
- `path` 는 저장된 수락 원천을 그대로 준다. 재검색은 하지 않는다. 그래야 사용자가 본 경로가 곧 적용되는 경로다.
- 탑승 구간은 `ride` 칸으로 따로 준다. path 앞에 합성해 붙이는 안은 원천이 달라져서 기각했다.
- `rideSec` 대신 `alightAt` 을 준다. GET 시점에는 이미 낡은 값이라서다.
- 후보 읽기와 stale 판정을 수락과 한 컴포넌트로 공유한다. 두 경로가 갈라지지 않게 하기 위해서다.
- 능동 제안 테스트 완화는 하지 않는다. 앱 개발 빌드가 맡는다.

## 건드는 곳
RerouteCandidateDisplay, RemainingPathCandidateProvider, RerouteAcceptTxService, RerouteCandidateReader(신규), RerouteCandidateRouteQueryService(신규), GetRerouteCandidateRouteUseCase(신규), RerouteCandidateRoute(신규), JourneyRerouteProposalController, 관련 테스트, HANDOFF.md

## 리스크
- 배포 중 옛 이미지가 새 행(x/y)을 읽는 경우 → ObjectMapper 의 unknown property 설정을 확인한다.
- 수락 코드를 공용 컴포넌트로 옮기면서 생길 수 있는 회귀 → RerouteAcceptIT 로 지킨다.

## 검증법
계약마다 단위 테스트를 둔다. 조회 path 와 저장된 수락 원천이 같은지는 IT 로 고정한다. 컴파일과 관련 테스트가 녹색이면 완료다.
