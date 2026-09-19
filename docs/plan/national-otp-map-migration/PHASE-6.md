# Phase 6 — 배포와 rollback 준비·실행

## 작업 계약

먼저 [AGENT-GUIDE.md](AGENT-GUIDE.md)를 읽는다.
선행: P5에서 검증한 앱/서버/graph 조합. 목표: 그 조합을 재현 가능하게 배포하고 새 검색을 복구할 수 있게 한다.
수정 범위: 실제 compose/env 예제·배포 스크립트·운영 runbook. 기존 배포 방식을 먼저 확인한다.
운영 실행은 배정된 환경·서비스·artifact가 명시된 경우 수행한다. 미지정이면 준비 결과와 미실행 항목을 남긴다.

## P6-A — 실행 가능한 배포 구성

- [ ] OTP build는 별도 작업으로 두고 API/worker 부팅에서 실행하지 않는다.
- [ ] GTFS/OSM/graph hash와 image digest가 고정된 release manifest를 만든다.
- [ ] OTP 내부 endpoint, heap/container 메모리, timeout/동시성 설정을 문서와 실제 구성에 연결한다.
- [ ] 기존 자원 측정을 사용하되 새 graph가 달라졌으면 해당 graph의 load/소수 smoke를 확인한다. host의 API/worker/DB/Redis 메모리와 합산한다.
- [ ] reader/guard → writer/API → 앱 호환 → provider 설정 순서를 실제 명령으로 작성한다.
- [ ] bbox/supported modes 정책은 최초 검색·재탐색·시작/수락 모두에서 유지한다.
- [ ] 기존 설정 체계를 사용한다. 이번 전환만을 위한 별도 운영 제어 API/상시 polling 저장소는 만들지 않는다.

## P6-B — 전환과 복구

1. hash 확인 → OTP load → GraphQL query → API smoke.
2. 배정 환경의 provider 설정으로 새 검색을 OTP로 전환.
3. 기존 버스·지하철 실시간/ML, 수도권 정적 leg, 범위 밖 거부, 활성 여정 조회 확인.
4. 로컬/스테이징에서 실패를 재현해 이전 provider 또는 graph로 새 검색을 복구.
5. 활성 OTP Journey snapshot/geometry가 복구 후에도 조회·진행·종료되는지 확인.

ODsay는 지원 범위와 키/쿼터가 실제 유효할 때만 복구 수단이다. 불가능하면 검증된 이전 OTP graph를 사용한다. 둘 다 없으면 새 검색 중단 응답을 정의한다.
blue/green 동시 load가 자원에 맞지 않으면 중단 교체 등 실제 가능한 절차와 예상 중단을 기록한다.

## 중단 기준

탈 수 없는 편, 대기 중복/시간 역전, 다른 지역 노선 오매핑, bbox 밖 노출, 미지원 Journey 허용, 기존 여정 손상은 영향 범위 전환을 중단한다.
지연/오류는 기존 서비스 기준과 비교하고 조건·표본 수를 기록한다. 1→10→50→100% canary, 100건 표본, 7일 관찰을 필수로 요구하지 않는다.

## 완료와 인계

- [ ] 실제 배포/복구 명령과 설정 파일이 일치함.
- [ ] graph health가 파일 존재가 아닌 실제 query로 확인됨.
- [ ] rollback 시험 결과와 소요시간을 기록함.
- [ ] 배정 환경에 실행한 작업과 준비만 한 작업을 구분함.

`deployment-runbook.md`, release manifest, `handoffs/PHASE-6.md`에 명령·결과·복구 경로를 남긴다.
실제 배포가 배정되지 않았다면 “배포 준비 완료 / 운영 미실행”으로 명시한다. 다음: [Phase 7](PHASE-7.md).
