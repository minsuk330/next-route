# 지하철 링 — 앞 열차 실측 구간시간 ETA

## 뭘 하나
전역 출발 뒤 도착 예정을 API 초 대신 "오늘 같은 구간을 지난 최근 3대의 실측 소요시간"으로 만든다.
수집기가 열차별 출발·도착을 처음 본 시각을 기록해 구간 표본을 쌓고, 링은 그 값으로 고정 ETA 를 쓴다.

## 왜
API 초가 전역 출발 뒤 50~60초 과대라 링이 천천히 차다 도착에서 튄다. 1시간 실측: 오차 55초→15초, 치우침 +54초→0.

## 핵심 결정
- 원칙 변경: 오늘 실제 지나간 열차의 실측은 쓴다(시간표·정적값은 여전히 기각) — 사용자 결정
- 구간 시작·표본은 수집 계층에서 열차 단위로 공유(여정별 기록 기각)
- 표본 없으면 API 초 대신 기존 STALE 폴백
- 전역 정차 중엔 진행 고정, ETA 경과 후엔 0.90 에서 멈춤
- 위치 API 역할은 그대로(원천 섞기 기각)

## 건드는 곳
SubwaySegmentTimePort·RedisSubwaySegmentTimeAdapter·SubwaySegmentTimeRecorder(신규), RealtimeSubwayCollector, SubwayRingObservation, SubwayRingSignalReader, SubwayRingCalculator

## 리스크
- 공항철도·GTX-A 는 표본이 드물어 개선이 작다(57~70초)
- 출근 혼잡 시간대 정차 지연은 미측정 — 배포 후 실여정으로 확인

## 검증법
어댑터 IT(표본 3개 유지·범위 밖 버림), 같은 ETA 반복 시 링 불변, 정차 중 0 유지, ETA 경과 0.90. 배포 후 실여정 outbox 에서 도착 시 튐 감소.
