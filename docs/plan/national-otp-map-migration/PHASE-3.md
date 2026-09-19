# Phase 3 — geometry 품질과 Naver 표시 검증

먼저 [AGENT-GUIDE.md](AGENT-GUIDE.md)를 읽는다.
선행: P1 원천/기본 graph/build 명령, P2-B API fixture와 P2-D 매핑. 준비 작업은 fixture로 가능하지만 전체 완료는 실제 매핑에 의존한다.
다음: [Phase 4](PHASE-4.md), [Phase 5](PHASE-5.md).
목적: OTP 경로를 Naver 지도에 정확한 좌표 순서로 그리고, 네비에 쓸 수 있는 형상인지 별도로 판정한다.

## P3-A — geometry 정규화와 원천 보강

변경: `JourneyNavigationGeometryService`, `JourneyNavigationGeometry`, route geometry DTO.
재사용 검토: 기존 서울 section polyline 생성/조회, `osm_gtfs_shape_poc.py`.

- [ ] OTP geometry 인코딩/precision을 실제 schema·fixture로 확인해 decode한다. GeoJSON [longitude, latitude]와 SDK latitude/longitude 객체 변환은 경계 adapter 한 곳에서 처리한다.
- [ ] NaN/범위 밖 좌표, 빈 선, 비정상 점 수/응답 크기를 차단한다. WGS84 여부를 알려진 정류장 좌표와 대조한다.
- [ ] 버스·도시철도 형상은 검증된 OSM route relation으로 만든다. 정류장 직선/도보 최단 경로 대체는 금지한다. 기존 서울 section은 품질 대조와 legacy reader용으로 보존한다.
- [ ] OSM_RELATION 출처와 relation/way hash를 보존한다. relation으로 생성한 GTFS shape를 OTP가 반환해도 원천 provenance를 유지한다. 기존 SEOUL_SECTION을 OSM 출처로 바꾸지 않는다.
- [ ] source, 원천 ID/hash, quality/reason, geometryVersion, networkDataVersion, stop occurrence anchor를 저장한다.
- [ ] 검증된 OSM route relation의 선택적 `colour`를 `routeDisplayColor`로 정규화한다. relation ID는 출처로만 보존하고 내부 route 식별자로 사용하지 않는다.
- [ ] 색상 매칭은 `ref` 단독이 아니라 지역·network/operator·방향/pattern 매핑과 함께 검증한다. 색상 누락·파싱 실패·방향 충돌은 `unknown/REVIEW`로 남기되 geometry 처리를 중단하지 않는다.
- [ ] shape_id/distances/trip 참조를 검증하고 P1 build 명령을 재사용해 최종 graph를 생성한다. 새 graphVersion/fixture를 P2·P4에 인계한다. 원본 PBF를 읽어 relation을 수집해도 운영 bbox를 확대하지 않는다.

OSM relation 보강 절차: source route/pattern 매핑 → 방향별 relation 후보 → way 방향/연결 정리 → 정류장 순서 제약 매칭 → gap/투영 오차/기종점 검사 → 승인 또는 REVIEW. nearest만으로 왕복을 고르지 않는다.

현재 502 POC는 양방향 중 일부가 불완전하므로 자동 승인 fixture로 쓰지 않는다. README의 매칭률 90%·평균 20m·30m 초과 gap 0개는 **보강 후보 선별 기준**이다. 모든 네비 구간의 품질을 보장하지 않는다. 승하차 anchor와 각 사용 section을 추가 검사해야 OK다.

502 색상 fixture는 relation `7172324`와 `4712439`의 `colour=#3d5bab`이 양방향에 동일하게 들어오는지 확인하는 용도로만 사용한다. 이 값이 없거나 변경되어도 geometry/경로 검색 회귀 실패로 처리하지 않는다.

## P3-B — 표시용/진행용 분리

- [ ] `points`는 표시용, `segments[].points`는 진행 원본으로 유지하고 계약을 명시한다.
- [ ] 단순화는 정류장/section 경계 anchor를 고정한다. 원본과의 최대 이격·거리 변화량을 보고한다.
- [ ] 인접 section은 같은 절단 anchor를 공유한다. 누락 section을 제거해 떨어진 구간끼리 직선 연결하지 않는다.
- [ ] relation 결측/단절은 빈 geometry 또는 검증된 부분 선과 사유로 반환한다. 떨어진 부분을 직선 연결하지 않는다. 해당 구간은 네비 진행 확정에서 제외한다.
- [ ] FAILED는 빈 geometry와 사유를 내리며 legIndex/segmentIndex를 조용히 재번호화하지 않는다.
- [ ] 장거리 geometry는 overlay 분할과 표시 단순화를 적용하고, 원본을 별도 immutable 저장/조회할 수 있게 한다.

시험: 좌표 뒤바뀜, encoded precision 오해, 긴 선, 자기 교차, 반복 정류장, section 공유 경계, 누락 구간, 직선 연결 금지.

## P3-C — 모바일 Naver spike

모바일은 사용자가 지정한 nextroute-mobile을 시작점으로 실제 저장소와 Naver 호출부를 확인한다. 이전 문서의 파일명을 가정하지 않는다. 서버만 배정됐으면 API/fixture/모바일 적용 지침을 완료하고 모바일 구현/실기기 검증은 미수행으로 남긴다.

- [ ] MapProvider의 카메라/마커/polyline/위치 인터페이스를 기존 RN 컴포넌트 구조에 맞게 감싼다. 기본 구현 Naver.
- [ ] 버스·도시철도·철도·도보 색상과 현재 leg 강조를 fixture 화면에 연결한다. 색상 출처는 개발 fixture/진단에 기록하고 사용자 화면에는 노선 색상을 표시한다.
- [ ] 전체 경로 bounds, 저줌/고줌, 위치 1초 갱신, 백그라운드 복귀를 시험한다.
- [ ] Android/iOS 실기기에서 점 수별 overlay 시간, frame stall, peak 메모리, 지도 최초 표시를 측정한다.
- [ ] 타일 수신 실패와 geometry decode/품질 실패를 다른 원인 코드로 기록한다.

SDK의 점 수 한도를 임의 상수로 단정하지 않는다. 작은·중간·수도권 장거리 fixture를 점진적으로 늘려 기기별 budget을 정한다. 초기 overlay 목표 p95 1.5초는 측정 시작/끝을 고정해 보고한다.

## 완료와 조건부 MapLibre

- [ ] geometry quality 보고서와 지원 가능한 노선/수단 범위가 있다.
- [ ] 양 플랫폼에서 정상 좌표열 표시와 원본/단순화 분리가 확인된다.
- [ ] 좌표·표시 조정 후에도 경로 정합성·긴 경로 성능·현재 leg 표시 호환성이 부족하면 OSM 기반 지도 전환안을 작성한다. SDK 결함 증명만을 조건으로 제한하지 않는다.

지도 교체로 해결 가능한 문제와 원천 결측을 구분한다. 전환이 필요하면 SDK/타일/모바일 변경 범위를 ADR로 구체화해 배정된 모바일 작업에 연결한다.

`handoffs/PHASE-3.md`에 relation 매핑·품질 보고서, 최종 GTFS/graph hash·버전, 재생성 명령, API fixture, Naver 확인 결과를 남긴다. 모바일 미검증이면 서버 형상 완료와 전체 완료를 구분한다.
