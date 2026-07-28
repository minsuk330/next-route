# 검색 결과 스냅샷 (M)

## 뭘 하나

검색 API 가 결과를 돌려줄 때 그 결과를 Redis 에 10분 저장하고, 응답에 `searchSessionId`·`pathKey`·`expiresAt` 을 추가한다.
여정 시작 API(다음 PR)가 경로 JSON 대신 `{ searchSessionId, pathKey }` 두 값만 받게 하려는 준비다.

## 왜

여정을 시작하려면 서버가 어느 경로인지 알아야 한다. 앱이 경로 JSON 을 통째로 올리면 서버는 그게 자기가 만든 경로인지 확인할 수 없고, 임의의 좌표·시간이 들어와도 통과한다.

## 핵심 결정

- 저장 실패해도 검색은 `200` 유지, `meta.degraded` 에 `journey_snapshot` 표시만. 호출량이 가장 많은 엔드포인트라 여정 기능이 발목 잡으면 안 됨
- `SET` 한 번, 재시도 없음. TTL 600초
- `pathKey` 는 `paths` 인덱스 문자열. 서명 기각 — `searchSessionId` 가 이미 서버가 만든 UUID 라 바꿔도 같은 검색 안의 다른 경로일 뿐
- 붙이는 위치는 `RouteSearchService` 내부가 아니라 **컨트롤러**. `withMeta` 와 같은 자리이고 중간 결과를 오염시키지 않음
- 익명 검색도 저장. `ownerUserId` 를 nullable 로 함께 넣고 검증은 여정 시작 쪽에서. 키에는 `userId` 를 안 넣음(UUID 라 이미 고유, 익명은 조립 불가)
- **폴리라인 포함해 통째로 저장.** 크기 최적화는 실제 응답 크기를 재고 나서(D13)
- 응답 필드는 `@JsonInclude(NON_NULL)` — 저장 실패 시 필드가 사라져 기존 앱에 영향 없음

## 건드는 곳

`application/route/port/out/RouteSearchSnapshotPort.java`
`application/route/config/RouteSnapshotProperties.java`
`application/route/service/RouteSearchSnapshotRecorder.java`
`application/route/dto/` (PathResult, RouteSearchResult)
`infrastructure/adapter/out/cache/route/RedisRouteSearchSnapshotAdapter.java`
`infrastructure/adapter/in/api/route/search/RouteSearchController.java`
`application.yaml` (새 의존성 없음 — 테스트는 기존 Testcontainers `GenericContainer` 사용)

## 리스크

- `PathResult` 에 `pathKey` 추가 → 호출부 19곳(main 5, test 14). 보조 생성자로 흡수해 기존 코드는 안 고침
- 직렬화·저장이 검색 p95 를 늘릴 수 있음 → `SET` 한 번, 회귀 측정으로 확인

## 검증법

Redis 를 내린 상태에서 `/api/route/search` 가 `200` + `journey_snapshot` degraded 표시, TTL 만료·범위 밖 `pathKey`·숫자 아닌 `pathKey` 조회가 `empty`, 기존 검색 응답 필드와 상태코드 회귀 없음. 스냅샷 1건 크기를 측정해 기록(D13 판단 근거).
