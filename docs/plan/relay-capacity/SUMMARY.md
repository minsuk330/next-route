# 발송(relay) 용량 실험

## 뭘 하나
아웃박스에 알림 행을 정해진 속도로 직접 넣어, 발송기만 따로 계단식 부하를 준다. FCM 60ms와 2~5초 두 조건에서 발송이 SLO를 깨는 지점과 그때의 병목을 찾는다. 운영 코드는 바꾸지 않는다.

## 왜
지금까지는 폭주 한 번(약 860건)만 장애 조건에서 봤고, 알림 양의 한계점과 병목은 측정한 적이 없다.

## 핵심 결정
- 평가기를 거치지 않고 아웃박스 직접 적재: 지하철 API와 평가기 상한이 섞이지 않고 속도를 정할 수 있음
- 정해진 속도로 계속 넣기(open-loop): 처리 후 넣기는 한계를 숨김(coordinated omission)
- 발송 경로는 운영 그대로, 바꾸는 건 가짜 FCM 지연뿐
- worker JVM 하나만, 평가기와 수집기 끔
- 칸마다 3회, 한계점 = 처리량이 못 따라감, 긴급 p95 32초 초과, 잃음 발생 중 첫 번째

## 건드는 곳
RelayCapacitySeedIT, load/ladder/relay_load_gen.py, run_relay_capacity.sh, relay_capacity.py, load/ladder/README.md, RELAY-CAPACITY.md, implementation_note.md

## 리스크
- 가짜 FCM이 프로세스 안 sleep이라 실제 HTTP 클라이언트 한계는 못 잼
- 직접 적재는 깨우기 신호가 없어 발송 지연에 약 1초 하한이 생김
- 노트북 한 대라 생성기, DB, JVM이 CPU를 나눠 씀(각각 따로 기록)

## 검증법
10건/초 60초 스모크에서 적재 수와 발송 수가 같고 잃음과 중복이 0. 칸 A, B, B' 3회 측정 뒤 RELAY-CAPACITY.md에 칸별 한계점과 병목이 근거 지표와 함께 기록되면 완료.
