# 모바일 인계 — 재탐색 후보 경로 조회 + 하차 좌표 (#259)

서버 PR 이 머지·배포되면 아래 계약이 살아 있다. 지도 말풍선 UI 를 이 값으로 그린다.

## 1. 제안 응답의 하차 좌표

`GET /reroute-proposals/active`·`POST /reroute-proposals` 의 `candidates[].display.alight` 에 두 칸이 늘었다.

```jsonc
"alight": {
  "stopId": "121000123",
  "name": "성수역",
  "alightAt": "2026-10-06T09:12:30Z",
  "planned": false,
  "x": 127.0559,   // 신규. 경도(WGS84)
  "y": 37.5446     // 신규. 위도. 못 구했거나 옛 저장 행이면 둘 다 null
}
```

- 말풍선은 `x`/`y` 에 놓는다. null 일 때만 기존 `stopId` 매칭으로 대체한다.

## 2. 후보 경로 조회 (신규)

```
GET /api/journeys/{journeyId}/reroute-proposals/{proposalId}/candidates/{candidateId}/route
Authorization: Bearer …
```

```jsonc
{
  "proposalId": 812,
  "candidateId": "c-2",
  "ride": {                       // 지금 타는 구간. 타고 있지 않으면 null
    "legIndex": 1,
    "routeName": "2412",          // nullable
    "fromStopName": "뚝섬역",      // nullable
    "alightStopId": "121000123",
    "alightStopName": "성수역",    // nullable
    "alightX": 127.0559,          // nullable
    "alightY": 37.5446,           // nullable
    "stopCount": 3,               // 지금 위치 → 하차까지. 위치 모르면 null
    "alightAt": "2026-10-06T09:12:30Z"  // nullable. 남은 시간 = alightAt - 지금
  },
  "path": { … }                   // /api/route/search 응답 paths[] 원소와 같은 모양
}
```

- `path` 는 수락하면 적용될 값 **그대로**다(서버가 다시 검색하지 않는다). `toRouteOptions` → `RouteDetail` 에 바로 넣는다.
- `path` 는 **하차 정류장부터** 시작한다. 상세 타임라인 첫 줄은 `ride` 로 그린다.
- 지도 겹쳐 그리기는 `path.subPaths[].polyline` 을 쓴다.
- 조회는 제안을 소모하지 않는다. 같은 후보는 만료 전까지 결과가 변하지 않으니 캐시해도 된다.

### 오류

| 상황 | 응답 |
|---|---|
| 여정이 없거나 남의 여정 | `404 JOURNEY_NOT_FOUND` |
| 제안이 없음 | `404 REROUTE_PROPOSAL_NOT_FOUND` |
| 후보가 없음 | `404 REROUTE_CANDIDATE_NOT_FOUND` |
| 만료·처리됨·여정 종료·여정이 움직임 | `409 REROUTE_PROPOSAL_STALE` → `/active` 다시 받기 |

## 3. 능동 제안 테스트

서버 변경 없음. 개발 빌드에 `POST /reroute-proposals` 진입점을 남긴다. 테스트 계정 완화(#257)가 그 경로에 걸려 있다.
