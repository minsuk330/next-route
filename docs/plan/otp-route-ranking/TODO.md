# OTP 경로 후보 정렬 후속 작업

상태: TODO — 현재 운영 전환과 분리. 운영 설정은 변경하지 않는다.

## 문제

Nextroute는 `routing.otp.first=5`를 `planConnection(first: $first)`에 그대로 넘기고,
OTP가 반환한 순서를 보존해 응답한다. OTP의 depart-after 기본 순서는 도보 전용 우선 뒤
이른 도착시각 기준이므로, 제품이 원하는 "최소 총 소요시간" 순서와 일치하지 않을 수 있다.
특히 다섯 후보 밖의 지하철 경로는 앱에서 평가·정렬할 기회가 없다.
또한 같은 탑승 노선·정류장 흐름의 경로가 출발/도착 도보 구간 등의 미세한 차이만으로
여러 번 노출될 수 있어, 사용자에게 실질적으로 다른 대안이 부족하게 보인다.
도보 leg는 OTP가 `legGeometry.points`를 이미 반환하고 중립 모델도 보존하지만, legacy 응답
매퍼가 `SubPathResult.polyline`을 `null`로 내려 현재 클라이언트에 도보 폴리라인이 없다.

## 확정된 제품 정렬 규칙

정적 검색 결과를 아래 키의 오름차순으로 안정 정렬한다.

1. 총 소요시간(초): `moveSeconds + waitSeconds + walkSeconds`
2. 도보시간(초): `walkSeconds`
3. 환승 횟수: `transferCount`
4. OTP 원래 순서: 동률 안정성 확보

버스/지하철 자체를 우선순위 키로 두지 않는다.

## 구현 범위

- OTP 요청 후보 수와 사용자 노출 수를 분리한다. 예: 후보 `20`, 최종 응답 `5`.
- `OtpRoutingProperties.first`의 역할을 후보 수로 명확히 하거나 `candidateFirst`로 분리하고,
  최종 노출 상한(`displayLimit=5`)을 별도 설정으로 둔다.
- `RoutingResult` 단계에서 위 규칙으로 정렬·상위 5개만 남긴 뒤 `LegacyRouteResponseMapper`에 넘긴다.
  매퍼는 변환 전용으로 유지한다.
- 정렬 전에 중복 후보를 제거한다. 대중교통 leg의 모드·노선 ID·승차/하차 정류장·순서를 기반으로
  여정 fingerprint를 만들고, 같은 fingerprint에서는 위 제품 정렬상 더 좋은 후보 하나만 남긴다.
  도보 구간의 미세한 차이만으로 같은 대중교통 여정을 여러 개 노출하지 않는다.
- 서로 다른 환승/노선 조합은 총 소요시간이 비슷해도 중복으로 합치지 않는다.
- OTP 도보 leg의 encoded polyline(`LegGeometry.points`)을 `CoordPoint` 목록으로 디코드해
  해당 도보 `SubPathResult.polyline`에 전달한다. 대중교통 형상과 별도 범위로 유지한다.
  geometry가 없거나 디코드에 실패하면 검색 전체를 실패시키지 않고 빈/null 폴리라인으로
  degrade하며 원인을 관측 가능하게 남긴다.
- OTP `steps`는 legacy `walkSteps`의 좌표 기반 TMAP 계약과 다르므로, 폴리라인 전달과
  turn-by-turn 안내 변환은 별도 하위 작업으로 분리한다.
- `first=5/10/20`의 OTP CPU·HTTP total·응답 바이트·앱 매핑 시간을 같은 OD/시각에서 반복 측정해
  후보 수를 확정한다. 무제한은 금지한다(OTP 내부 반환 상한은 200이며, 응답/매핑 비용이 증가한다).

## 검증

- 동률, 최소 도보 우선, 최소 환승 우선, OTP 원래 순서 보존 단위 테스트.
- 후보 6번째 이후가 새 정렬 상위 5개에 들어오는 회귀 테스트.
- 같은 대중교통 fingerprint의 후보는 하나만 노출되고, 다른 환승/노선 조합은 보존되는 중복 제거 테스트.
- 도보 leg polyline의 encoded 값이 좌표 목록으로 변환되어 API에 내려오는지, 누락·손상 geometry가
  전체 검색 실패 없이 처리되는지 테스트.
- API 응답은 항상 최대 5개인지 확인.
- OTP on/off 및 실패·무경로 동작 회귀 확인.
