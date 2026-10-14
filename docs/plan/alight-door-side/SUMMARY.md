# 지하철 내리는 문 방향 (#335)

## 뭘 하나
지하철 탑승 단계에 `alightDoorSide`(LEFT/RIGHT/null)를 붙인다. 하차역 승강장이 섬식인지 상대식인지(KRIC)와 그 노선이 좌측·우측 통행인지로 정한다.
KRIC 원천은 도구로 한 번 받아 CSV 로 넣고, 런타임에는 부르지 않는다.

## 왜
#266 안내 시트에서 마지막으로 빈 항목이다. 문 좌우를 직접 주는 데이터는 없다(`subwayHeading` 낮에도 null, ODsay `door` 는 칸 번호).

## 핵심 결정
- 원천 = KRIC stPlf 노선 단위 47회 → CSV 커밋. 실시간 호출 기각(분기 갱신 데이터, 과도 GET 경고)
- 통행 방향은 출처 확인 노선 + 연장 구간만. 공항·GTX-A·신분당·경춘·경강·우이·신림은 null(사용자 결정)
- 종착 하차는 null. 도착 선로가 번갈아 바뀌어 문이 뒤집힌다. 노선 끝은 이미 데워 둔 OTP 패턴 캐시로 판정
- 복합 승강장(1섬1상대·쌍섬식)·형식 null 은 null. 추정을 지어내지 않는다
- 저장은 빠른 하차와 같은 길(여정 생성 시 1회, 재탐색 시 하차 쪽 교체), V65 컬럼 1개

## 건드는 곳
tools/subway-platform-type, resources/subway/platform-type.csv, SubwayDoorSideIndex, SubwayLineEnds, JourneyLegGuidance, JourneyLeg, V65 마이그레이션, JourneyGuidanceInfoEnricher, JourneyLegView, JourneyQueryService, guidance-stop-info/HANDOFF.md

## 리스크
- 통행 방향 표가 틀리면 문 방향이 정반대로 나온다 → 배포 후 실탑승 역 10곳 대조
- 커버리지 ≈ 70%. 수인분당·경춘은 원천 형식이 비어 있어 대부분 null
- V65 를 VPS 에 수동으로 넣지 않으면 새 jar 가 깨진다

## 검증법
번들 CSV 실데이터 테스트(섬/상대 × 좌/우 4조합, 4호선 운영기관 경계, 노선별 하한)와 Enricher·복제·컨트롤러 계약 테스트가 통과하고, `*Journey*`·`*Subway*` 단위 묶음에 새로 깨지는 테스트가 없으면 완료.
