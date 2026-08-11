# 모바일 전달: 도보 turn-by-turn 안내점 (`maneuvers`)

엔드포인트·인증·요청 파라미터 변경 없음. 기존 응답의 각 leg에 `maneuvers` 배열이 추가됐다.

```
GET /api/journeys/{journeyId}/navigation-geometry?routeRevision={n}
```

서버는 현재 위치를 받지 않고 판정하지도 않는다. 안내점이 경로 위 어디에 있는지만 준다. GPS 매칭과 "다음 안내" 선택은 앱이 한다.

## 1. 실제 응답 JSON (직렬화 결과 그대로)

도보 leg 하나, 정점 5개 / 구간 4개 / 안내점 5개. 좌표는 서울 강남 부근 값.

```json
{
  "journeyId" : 1234,
  "routeRevision" : 1,
  "legs" : [ {
    "legIndex" : 0,
    "type" : "WALK",
    "routeId" : null,
    "plannedDurationSeconds" : 420,
    "totalDistanceMeters" : 257.3,
    "quality" : "OK",
    "points" : [ {
      "x" : 127.102,
      "y" : 37.5133
    }, {
      "x" : 127.10245,
      "y" : 37.51372
    }, {
      "x" : 127.1031,
      "y" : 37.51372
    }, {
      "x" : 127.1038,
      "y" : 37.5141
    }, {
      "x" : 127.10425,
      "y" : 37.51455
    } ],
    "segments" : [ {
      "geometrySegmentIndex" : 0,
      "sectionId" : null,
      "fromSeq" : null,
      "toSeq" : null,
      "distanceMeters" : 61.3,
      "plannedDurationSeconds" : 100,
      "quality" : "OK",
      "qualityReason" : null,
      "points" : [ {
        "x" : 127.102,
        "y" : 37.5133
      }, {
        "x" : 127.10245,
        "y" : 37.51372
      } ]
    }, {
      "geometrySegmentIndex" : 1,
      "sectionId" : null,
      "fromSeq" : null,
      "toSeq" : null,
      "distanceMeters" : 57.3,
      "plannedDurationSeconds" : 94,
      "quality" : "OK",
      "qualityReason" : null,
      "points" : [ {
        "x" : 127.10245,
        "y" : 37.51372
      }, {
        "x" : 127.1031,
        "y" : 37.51372
      } ]
    }, {
      "geometrySegmentIndex" : 2,
      "sectionId" : null,
      "fromSeq" : null,
      "toSeq" : null,
      "distanceMeters" : 74.8,
      "plannedDurationSeconds" : 122,
      "quality" : "OK",
      "qualityReason" : null,
      "points" : [ {
        "x" : 127.1031,
        "y" : 37.51372
      }, {
        "x" : 127.1038,
        "y" : 37.5141
      } ]
    }, {
      "geometrySegmentIndex" : 3,
      "sectionId" : null,
      "fromSeq" : null,
      "toSeq" : null,
      "distanceMeters" : 63.9,
      "plannedDurationSeconds" : 104,
      "quality" : "OK",
      "qualityReason" : null,
      "points" : [ {
        "x" : 127.1038,
        "y" : 37.5141
      }, {
        "x" : 127.10425,
        "y" : 37.51455
      } ]
    } ],
    "stops" : [ ],
    "maneuvers" : [ {
      "maneuverIndex" : 0,
      "type" : "DEPART",
      "instruction" : "출발지",
      "geometrySegmentIndex" : 0,
      "segmentProgress" : 0.0,
      "distanceFromLegStartMeters" : 0.0,
      "x" : 127.102,
      "y" : 37.5133
    }, {
      "maneuverIndex" : 1,
      "type" : "LEFT",
      "instruction" : "횡단보도 후 좌회전",
      "geometrySegmentIndex" : 1,
      "segmentProgress" : 0.0,
      "distanceFromLegStartMeters" : 61.3,
      "x" : 127.10245,
      "y" : 37.51372
    }, {
      "maneuverIndex" : 2,
      "type" : "STRAIGHT",
      "instruction" : "보행자도로 을 따라 62m 이동",
      "geometrySegmentIndex" : 2,
      "segmentProgress" : 0.0,
      "distanceFromLegStartMeters" : 118.6,
      "x" : 127.1031,
      "y" : 37.51372
    }, {
      "maneuverIndex" : 3,
      "type" : "RIGHT",
      "instruction" : "우회전 후 계단 진입",
      "geometrySegmentIndex" : 3,
      "segmentProgress" : 0.0,
      "distanceFromLegStartMeters" : 193.4,
      "x" : 127.1038,
      "y" : 37.5141
    }, {
      "maneuverIndex" : 4,
      "type" : "ARRIVE",
      "instruction" : "도착",
      "geometrySegmentIndex" : 3,
      "segmentProgress" : 1.0,
      "distanceFromLegStartMeters" : 257.3,
      "x" : 127.10425,
      "y" : 37.51455
    } ]
  } ],
  "sourceFetchedAt" : null
}
```

## 2. 필드 뜻

| 필드 | 뜻 |
| --- | --- |
| `maneuverIndex` | 이 배열 안 순번. 0부터 연속. 원천 순번이 아니다 |
| `type` | 정규화한 회전 종류. §3 표 |
| `instruction` | 원천 안내 문구. 화면에 그대로 읽어 주면 된다 |
| `geometrySegmentIndex` | **같은 leg `segments` 배열의 인덱스.** 그 구간 위에 이 안내점이 있다 |
| `segmentProgress` | 그 구간 안의 위치 비율. 0…1. 정점에 정확히 있으면 0.0, 마지막 정점이면 1.0 |
| `distanceFromLegStartMeters` | leg 시작점부터의 경로 거리(m). 0.1m 단위 |
| `x` / `y` | 경도 / 위도. 지도 핀 좌표 |

## 3. TMAP `turnType` → `type` 매핑표

서버가 이미 변환해서 내려주므로 앱은 `type`만 보면 된다. 참고용.

| turnType | type | 비고 |
| --- | --- | --- |
| 11 | `STRAIGHT` | 직진 |
| 12 | `LEFT` | 좌회전 |
| 13 | `RIGHT` | 우회전 |
| 14 | `UTURN` | 유턴 |
| 16 | `SHARP_LEFT` | 8시 방향 |
| 17 | `SLIGHT_LEFT` | 10시 방향 |
| 18 | `SLIGHT_RIGHT` | 2시 방향 |
| 19 | `SHARP_RIGHT` | 4시 방향 |
| 184, 185 | `STRAIGHT` | 경유지 계열 |
| 125~131 | `STRAIGHT` | 육교·지하보도·계단·경사로·보행자전용도로·횡단보도 등 시설 진입. 시설 이름은 `instruction`에 있다 |
| 200 | `DEPART` | 출발 (`pointType == "SP"`) |
| 201 | `ARRIVE` | 도착 (`pointType == "EP"`) |
| 그 외 | `UNKNOWN` | 표에 없는 코드. 추측해서 방향을 만들지 않는다 |

`pointType`이 `SP`/`EP`면 turnType보다 우선한다.

## 4. 앱이 지켜야 할 계약

- `maneuvers`는 **항상 배열**이다(null 아님). **빈 배열은 정상이다** — "안내점 없음"이며 경로·좌표·구간은 그대로 유효하다. 다음 경우에 비게 된다: 검색 시 TMAP 보강이 생략됨(시간 예산·서비스 지역 외), 좌표가 없어 직선으로 대체됨(`quality == "APPROXIMATE"`), 좌표 자체가 없음(`quality == "FAILED"`), 버스·지하철·대기 단계.
- `geometrySegmentIndex`는 같은 leg의 `segments` 인덱스다. `points` 인덱스가 아니다 — `points`는 중복 좌표를 지우므로 개수가 다를 수 있다.
- `distanceFromLegStartMeters`는 **단조 증가**한다. "다음 안내까지 남은 거리"는 이 값으로 계산한다.
- `type == "UNKNOWN"`이면 방향 아이콘 대신 `instruction` 문구를 그대로 보여 준다.
- `type`은 앞으로 값이 늘 수 있다. 모르는 값은 `UNKNOWN`처럼 다뤄라(하드 실패 금지).
- 응답은 `(journeyId, routeRevision)`로 캐시한다. 개정본이 바뀌지 않는 한 안내점도 바뀌지 않는다.
