# Phase 0 — 입력과 기존 증거 인계

## 작업 계약

먼저 [AGENT-GUIDE.md](AGENT-GUIDE.md)를 읽는다. 선행 Phase 없음.
목표: 다음 에이전트가 202503 원천을 기존 bbox로 변환할 수 있게 실제 경로·범위·재사용 증거를 확정한다.
수정 범위: 이 계획의 manifest/보고서/정책 목록. 제품 provider 전환과 대규모 실험은 배정 범위에 없다.

## 수행 순서

1. [capacity-report.md](capacity-report.md), benchmark README·manifest·runner를 확인한다. 전국/기존 수도권 graph의 입력·버전·측정 조건을 분리해 기록한다.
2. Downloads의 202503_GTFS_DataSet 경로를 실제 확인한다. 파일 목록, 원천 dialect, 필수 파일, calendar 유효 범위를 조사한다. 기존 2024 결과를 복사하지 않는다.
3. OSM 원본과 기존 수도권 PBF의 실제 경로/hash/bbox를 확인한다. 범위는 126.5,37.2,127.4,38.0이다.
4. `data-scope.json`을 작성한다. 필드: bbox, sourcePaths, sourceHashes, sourceVersion, feedTimezone, allowedModes, region/agency 판정 근거, excludedTripPolicy, validationDate. 파일명은 상대 경로이며 큰 원천은 포함하지 않는다.
5. 코드에서 ODsay 호출량 절감용 제한을 찾고 `policy-inventory.md`에 기록한다. 각 행에 코드 위치, 현재 값, 도입 목적/근거, 영향 기능, REMOVE/KEEP/REVIEW, P2 담당 항목을 넣는다. 근거가 없으면 추측 대신 REVIEW.
6. 현재 사용되는 버스/지하철 실시간·ML 보강 경로도 기록한다. P2가 기존 기능을 빠뜨리지 않도록 provider별 매핑 입력과 호출 위치를 인계한다.
7. 기존 smoke를 재사용할 3~5개 OD/출발시각과 의도된 범위 밖 케이스를 선정한다. 실제 운행 여부를 모르면 정답을 만들지 않는다.

## 확인과 완료

- [ ] 202503 입력과 OSM 파일을 실제 읽을 수 있으며 경로/hash가 기록됨.
- [ ] 현재 graph와 신규 입력이 같은 데이터인지 구분됨.
- [ ] bbox가 행정구역 전체가 아니라는 점과 경계 넘는 trip 제외 원칙이 명시됨.
- [ ] 외부 호출비용 제한과 자원/중복 방지 제한이 구분됨.
- [ ] 기존 증거 재사용 범위와 신규 변환 확인 항목이 정리됨.

입력 접근 불가나 해석 불가능한 필수 필드는 차단 사유다. 다른 독립 조사는 완료하고 누락 파일/필드와 해결에 필요한 정보를 기록한다.
재부하 시험, 전국 build, 100건 holdout 생성은 수행하지 않는다.

## 다음 에이전트에 전달

`handoffs/PHASE-0.md`에 data-scope.json, policy-inventory.md, 원천 경로, 재사용 검증 위치와 미해결 항목을 연결한다. 다음: [Phase 1](PHASE-1.md).
