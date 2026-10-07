# #299 역 도착 응답에 '방금 떠난 열차(departed)' 추가 (M 티어, slug: subway-departed-trains)

## Context
모바일 지하철역 시트는 방향마다 "이미 탔다면 · 방금 떠난 열차(최대 3대)"를 위에 보여 주고 `[탔어요]`를 받는다. 사용자는 대개 타고 나서 앱을 열기 때문이다(#265). 서버엔 떠난 열차를 주는 곳이 없어 앱은 자리표시(`use-departed-trains.ts`)로 그린다. #277 응답(`GET /api/subway/stations/{stationId}/arrivals`)의 방향마다 `departed` 를 싣는다.

**이슈의 전제("떠난 시각을 꺼낼 곳이 없어 새 버퍼가 필요")는 틀렸다** — M7 위치 수집기가 이미 만든다:
- `SubwayTrainTransitionTracker`(worker)가 위치 스냅샷 두 장을 비교해 출발 사건을 만들고 `SubwayTrainLogPort`(Redis `subway:dep:{date}:{line}:{dir}:{station}` ZSET, TTL 15m) 에 쌓는다.
- 조회 `SubwayTrainLogPort.findDepartures(serviceDate, lineId, direction, stationId, from, to)` 가 있고, 탑승 판정(`SubwayBindingMatchService`)이 같은 조회를 쓴다 → `trainNo` 는 #293 열차 선택과 같은 체계.
- 어댑터 `RedisSubwayTrainLogAdapter` 는 프로필 제한 없음 → api 에서 바로 읽힌다.
- 운영 실측(2026-10-07): 강남 2호선 내선 최근 출발 3건, 사건 구간 폭 150초, 품질 CORROBORATED. 출발 사건이 쌓이는 노선 = 1001~1009·1032·1065·1077·1092. **1063·1067·1075·1081·1093·1094 는 없음**(원천이 위치를 안 줌) → `departed: []`.

## 계약 (추가만, 하위 호환)
```jsonc
"directions": [{
  "direction": "내선", "label": "내선",
  "trains": [ ... ],                        // 그대로
  "departed": [                             // 이 역을 떠난 열차, 최근 순, 최대 3대, 10분 이내
    { "trainNo": "2306", "destinationName": "성수", "trainType": "일반",
      "departedAt": "2026-10-07T15:51:29+09:00",   // 사건 구간 가운데(estimate). 원천 해상도 60~150초
      "currentStationName": "역삼" }        // 지금 위치. 모르면 null
  ]
}]
```
- 방향 목록 = 곧 오는 열차 방향 ∪ 떠난 열차 방향(곧 오는 열차가 없어도 떠난 열차만 있는 방향을 보여 준다).
- `label` 은 곧 오는 열차로 정하고, 없으면 떠난 열차 행선지로 정한다(같은 규칙).
- `destinationName` = 사건 `terminalId` → 역 마스터 `statn_nm`. 모르면 null.
- `trainType` = `SubwayExpressType` → NORMAL "일반", EXPRESS "급행", SPECIAL "특급", UNKNOWN null.
- `currentStationName` = `lastSeen(TrainIdentity)` 의 역 이름. 출발 사건보다 오래된 관측이면 null.

## 구현
- `application/arrival/dto/SubwayStationArrivalsResponse.java`: `Direction` 에 `List<Departed> departed`, record `Departed(trainNo, destinationName, trainType, OffsetDateTime departedAt, currentStationName)`.
- `application/arrival/service/SubwayStationArrivalQueryService.java`:
  - 의존 추가: `SubwayTrainLogPort`, `ServiceDateResolver`, `Clock`.
  - 노선 방향 목록 = 2호선 `내선/외선`, 그 외 `상행/하행`. 방향마다 `findDepartures(serviceDate, lineId, dir, stationId, now−10m, now)`.
  - 자정을 넘긴 직후면 `resolve(now−10m)` 서비스일도 함께 조회한다.
  - 같은 trainNo 는 하나로, 최근 순 3대.
  - 조회 실패(Redis 예외)는 `departed: []` + warn. 곧 오는 열차 응답을 깨뜨리지 않는다.
  - `group(...)` 은 departed 를 받아 방향 합집합·label 대체 규칙을 적용한다.
  - 행선지 이름은 `SubwayStationRepository.findByStationId` 결과를 요청 안에서 Map 으로 들고 재사용한다.

## 테스트 (계약당 1개+)
`SubwayStationArrivalQueryServiceTest` 에 추가:
- 떠난 열차가 최근 순 최대 3대, 10분 창으로 조회(인자 검증)
- 떠난 열차만 있는 방향도 나온다, label 은 떠난 열차 행선지로 대체
- terminalId → 행선지 이름, express → 일반/급행, lastSeen 이 더 최신이면 현재 역 / 아니면 null
- 같은 trainNo 중복 제거
- 로그 조회 예외 → departed [] 이고 trains 는 그대로
- 자정 직후 이전 서비스일도 조회

`SubwayStationControllerTest`: `departed[0].trainNo/departedAt` JSON.

## 검증
- 위 테스트 + subway·arrival 패키지 회귀.
- 배포 후 VPS 내부 실호출: 강남 2호선(1002000222) `departed` 내선·외선 각 ≤3대, `departedAt` 10분 이내, 신분당선 강남(1077000687) 도 나옴, 수인분당 역은 `[]`.

## 문서
`docs/plan/subway-departed-trains/{PLAN,SUMMARY}.md`, `docs/plan/subway-station-arrivals/HANDOFF.md` 에 `departed` 절 추가(노선 갭·시각 해상도 명시), `implementation_note.md`.

## 배포
마이그레이션 없음. api 이미지 교체.
