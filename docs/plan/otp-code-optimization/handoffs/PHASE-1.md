# Phase 1 handoff

- Phase 상태: PARTIAL
- 전체 목표 상태: INCONCLUSIVE
- 작업 위치: `/Users/wa/golbob/개인/OpenTripPlanner/test/performance/seoul-metro`

## 수행 변경

`compare_graphql.py`에 다음 관측 계약을 추가했다.

- warmup 요청도 measured 요청과 함께 JSONL에 보존한다.
- 각 요청에 `runId`, `variant`, `phase`, `requestId`, `concurrency`, `queuedAt`, `startedAt`, `finishedAt`를 기록한다.
- 서비스 지연(`elapsedMs`)과 worker queue 대기(`queueWaitMs`)를 분리한다.
- manifest에 실행 variant와 현재 Git revision을 기록한다.
- ThreadPool worker가 완료되는 즉시 다음 queued 요청을 실행하므로 동시성 1/2/4의 in-flight 수준을 유지한다.

검증:

- Python AST compile 검증 통과.
- 기존 측정 raw 파일은 변경하지 않았다.

## 2026-09-09 보완

- HTTP/GraphQL/null/partial/malformed/timeout fixture, 즉시 JSONL flush, nearest-rank p50/p95,
  확장 GTFS 정합성 query, 3window 예열 안정화 gate를 추가했다.
- `run_isolated.py`, `collect_process_memory.py`, `summarize_run.py`로 새 JVM/Java PID RSS/
  GC UTC log/측정 marker를 연결한다. container memory는 별도 지표다.
- 기존 4OD A/A는 각 80건 성공. p50 468.992→503.123ms, p95 750.277→814.358ms.
  같은 원본의 변동이 약 7~9%여서 작은 개선을 단일 run으로 단정할 수 없다.
- 확장 corpus 7OD×2회는 A/A/D 각각 14건 검증 통과, baseline 비교 불일치 0건.
  도보 경로+WALKING_BETTER_THAN_TRANSIT, 경계 NO_STOPS_IN_RANGE, 영역 밖 OUTSIDE_BOUNDS를
  원본에서 먼저 확인하고 expectationVersion=2로 고정했다. HTTP/timeout은 정상으로 바꾸지 않는다.
- `a1-corpus`는 원본 규약을 발견한 실패 기록으로 보존한다. v2 결과로 덮어쓰지 않는다.
- raw/실행 조건/한계는 `MEASUREMENT-REPAIR.md`에 기록했다.

## 남은 검토

- 반복 run 통계 도구와 수집기 실패 경계 검증을 Luna에 재배정했다.
- 기존 elapsedMs의 JSON 처리 포함 여부와 예열 안정화 변동폭 해석을 명확히 하며,
  정의 변경 시 기존 run과 섞어 비교하지 않는다.
- 정식 5쌍/모든 concurrency/soak는 아직 실행하지 않았다. 이는 Phase 4의 별도 gate다.

따라서 Phase 1은 측정기 구현은 진행됐지만 완료로 판정하지 않는다.
