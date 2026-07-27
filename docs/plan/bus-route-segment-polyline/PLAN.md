# 버스 정류장 구간 Polyline 계획

## 1. 목표

- 노선·정류장·경유순서·Polyline을 정적 데이터 실행마다 upsert한다.
- 정류장을 각각 최근접점으로 잘라지 않고, 노선 전체의 `seq` 순서를 제약해 투영한다.
- 결과는 `route_id + from_seq + to_seq`로 미리 저장하고 네비게이션 조회 시 필요한 구간만 합친다.

## 2. 데이터 동기화

`BusStaticDataService.execute()` 실행 흐름:

1. 서울시 노선 ID 목록 조회
2. 기존 노선을 포함한 노선 메타데이터 upsert
3. API 응답이 성공한 노선의 정류장 좌표 upsert
4. 경유 관계를 `route_id + seq` 기준으로 갱신하고 사라진 seq 제거
5. 전체 노선 Polyline upsert
6. Polyline이 갱신된 노선의 정류장 구간 재생성

빈 응답·단일 노선 실패 시에는 기존 스냅샷을 삭제하지 않는다.

## 3. 매칭 알고리즘 `ordered-v1`

1. 전체 Polyline의 각 point 사이 누적거리를 계산한다.
2. 정류장의 `sectionDistance`(미터) 누적값을 노선 길이에 정규화해 예상 위치를 만든다.
3. 예상 위치 앞뒤 1.5km 범위에서 정류장을 Polyline에 투영한다.
4. 다음 정류장은 반드시 이전 투영점 이후에서만 선택한다.
5. 모든 정류장의 투영점을 한 번 결정한 뒤 인접한 두 투영점 사이를 자른다.

인접 section은 같은 투영점 객체를 공유하므로 끝점과 다음 시작점의 좌표·fraction이 같다.

## 4. 품질 판정

- `OK`: 두 정류장 투영오차 20m 이하, `sectionDistance`와 절단 길이 차이 100m 이하
- `REVIEW`: 투영은 가능하지만 엄격 기준 초과
- `FAILED`: 좌표 누락, 투영 실패, 0m 절단

`REVIEW`/`FAILED`가 하나라도 포함된 범위는 단일 path로 합치지 않는다. 중간 공백을
지도 SDK가 직선으로 연결하는 문제를 방지한다.

## 5. 저장·조회

- 원본 반환용 JSONB와 PostGIS `geometry(LineString, 4326)`를 함께 저장한다.
- `section_id`는 노선 내에서도 중복될 수 있으므로 식별자로 사용하지 않는다.
- 연속 구간 조회:

```http
GET /api/bus/routes/{routeId}/polyline?fromSeq=12&toSeq=20
```

`OK` section을 순서대로 합치고 공유 접점은 한 번만 반환한다. 중간 구간이 누락되거나
품질 기준을 통과하지 못하면 `hasGap=true`, `points=[]`를 반환한다.

## 6. 후속 보정

1차 순서 제약에서 `REVIEW`로 남은 노선만 다음을 적용한다.

1. 정류장별 복수 투영 후보 생성
2. 동적 계획법으로 순서·구간거리 전역 점수 최소화
3. 그래도 불량한 구간에만 `bus_position_raw` GPS 적용

## 7. 검증

- 직선 노선 `OK` 판정
- 같은 도로 왕복·회차 노선 fraction 역전 0건
- 인접 section 공유 좌표·fraction 일치
- PostGIS LineString 저장
- 연속 section 병합 시 공유 point 중복 제거
- `REVIEW` 포함 시 직선 연결 금지
