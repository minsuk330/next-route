# 도보 turn-by-turn maneuver 추가 (Dynamic Island용)

## 뭘 하나
기존 `GET /api/journeys/{id}/navigation-geometry` 응답의 도보 leg에 "여기서 좌회전" 같은 안내점 목록(`maneuvers`)을 얹는다. 각 안내점은 폴리라인 몇 번째 선분의 어디쯤인지와 출발부터의 거리를 갖는다. 신규 엔드포인트·DB 변경 없고, 서버는 현재 위치를 받지도 판정하지도 않는다.

## 왜
iOS가 Dynamic Island에 "200m 앞 좌회전"을 띄우려면 방향 정보가 필요하다. 이미 여정 시작 시 얼려 둔 TMAP 응답 안에 있는데 응답에서 버리고 있었다.

## 핵심 결정
- 안내점 좌표는 이미 폴리라인 정점 그 자체라서, 최근접 선분 투영이 아니라 **정점 정확일치 단조 스캔**으로 붙인다 — 루프·교차 경로에서 뒤쪽 선분에 잘못 붙는 문제가 사라진다. 투영은 폴백만.
- anchor 기준은 dedupe된 `points`가 아니라 **원본 폴리라인**(= segment를 만든 그 리스트). 안 그러면 인덱스가 어긋난다.
- 직선 대체(APPROXIMATE)·좌표 없음(FAILED) 도보에는 안내점을 아예 안 준다. 거짓 방향보다 없는 편이 낫다.
- anchor 실패는 그 안내점만 버린다. 전체 응답은 200으로 나가고 `maneuvers`는 최악의 경우 빈 배열(null 아님).
- 회전 코드는 `ManeuverType`으로 정규화해 TMAP 코드를 계약에 노출하지 않는다. 횡단보도·계단 같은 시설 코드는 `STRAIGHT`로 접고 안내 문구는 `instruction`에 남긴다.

## 건드는 곳
`ManeuverType`(신규), `WalkManeuverTypeMapper`(신규), `WalkManeuverAnchorer`(신규), `JourneyNavigationGeometry`, `JourneyNavigationGeometryService`, `GeometrySegmentAllocator`, 그리고 위 3개의 테스트 + 기존 geometry 서비스/컨트롤러 테스트.

## 리스크
- **TMAP turnType 매핑표의 실측 근거가 없다.** 레포에 실제 TMAP 응답 fixture가 없어(합성 테스트에 200/201/11만) 공식 문서 표를 쓰고 테스트로 고정한다. 미등록 코드는 UNKNOWN + 문구 노출로 흘려보낸다.
- 시설 세부 코드(211~)가 실측에서 흔하면 UNKNOWN 비율이 높아질 수 있다. 운영 로그로 확인 후 표를 넓히는 후속.

## 검증법
`./gradlew test` 전체 green. 기존 geometry 서비스·컨트롤러 테스트 무수정 통과(생성자 인자 추가만), 신규 mapper·anchorer 테스트 통과, 컨트롤러 JSON에 `maneuvers` 8개 필드 노출 확인. 마이그레이션 디렉터리 무변경.
