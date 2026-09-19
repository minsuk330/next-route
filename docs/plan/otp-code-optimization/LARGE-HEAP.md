# 큰 heap 실험 — "메모리가 넉넉하면 원본이 더 빠른가"에 답한다

작성일: 2026-09-11. 상태: **HISTORICAL / SUPERSEDED**. 이 문서는 당시 `-Xmx10g` 수도권 전체 실험의 원래 실행안과 해석을 보존한다. 실제 feasibility가 host swap-in 때문에 formal gate를 통과하지 못했고, 현재 재개 작업은 [LARGE-HEAP-AGENT-PLAN.md](handoffs/LARGE-HEAP-AGENT-PLAN.md) §P4-R의 GTFS 약 25% 축소 실험이다. 상위: [PLAN.md](PLAN.md), 배경 질문: [UNDERSTANDING.md](UNDERSTANDING.md) §4.3.

## 0. 한 줄 결론

**heap 을 3.5G → 10G 로 키워 같은 A/B 를 다시 잰다.** 지금까지의 개선율에는 "원본이 메모리 부족으로 굶은" 몫이 섞여 있을 수 있다. 그 몫을 빼도 H+F 가 뒤지지 않는지 확인해야 "메모리 vs 성능 트레이드오프" 프레임을 쓸지 버릴지 정할 수 있다.

## 1. 가설과 판정 규칙 (실행 전 고정)

| 가설 | 뜻 | 판정 |
|---|---|---|
| H-dom | 큰 heap 에서도 H+F 의 p50/p95 가 A 이상 | c1·c2·c4 각각 3쌍 중앙 개선율 ≥ 0 **이고** OD 별 p50/p95 가 3쌍 연속 5% 넘게 악화된 곳 없음 |
| H-trade | 큰 heap 에서 A 가 우세 | 어느 동시성이든 전체 중앙 개선율 < -5%, 또는 특정 OD 가 3쌍 연속 -5% 초과 |
| 미확정 | 그 사이 | 개선율이 [-5%, 0) 이고 bootstrap 구간이 0 을 걸침 → 쌍 2개 추가 후 재판정. 그래도 걸치면 "차이 없음" 으로 기록 |

개선율 = `(A - H+F) / A × 100`. 양수 = H+F 가 빠름. 기존 PLAN §1 과 같다.

**메모리(RSS)는 이번 판정 항목이 아니다.** 큰 heap 에서 G1 은 heap 을 느긋하게 채우므로 RSS 는 "얼마나 썼나" 가 아니라 "얼마나 허용됐나" 에 가깝다. 기록은 하되 결론에 안 쓴다.

## 2. 환경 사실

- 로컬 Docker VM: 14.9GB, 11 CPU, aarch64 (`docker info`).
- JVM 하나만 동시에 뜬다(측정기 구조). `-Xmx10g` 면 VM 에 ~4.9G 남음 → OS·메타스페이스·GC 부가 메모리 여유 충분. **12g 이상은 쓰지 않는다** (VM 스왑 시 측정 무효).
- graph: `/private/tmp/otp-seoul-dev-graph/graph.obj` (SHA `8b597d8b…3002385`), 기존과 동일.
- JAR: A = `/private/tmp/otp-baseline/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` (`914d9a58…`), H+F = `/Users/wa/golbob/개인/OpenTripPlanner/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` (`54bbdbcf…`). 실행 전 `shasum -a 256` 재확인.
- 실행 중 로컬 nextroute 컨테이너(postgres/redis/app)·Gradle·IDE 인덱싱·브라우저 무거운 탭 금지. 기존 계약(§4 "동일 호스트에서 OTP 한 대만").

## 3. 측정기 수정 (S 티어, OTP 저장소 `test/performance/seoul-metro/run_isolated.py`)

`java_args = ['-Xmx3500m']` 하드코딩 → `--xmx` 옵션 (기본값 `3500m` 유지, 기존 결과 재현성 보존).

```python
parser.add_argument('--xmx', default='3500m', help='JVM max heap, e.g. 3500m or 10g')
...
java_args = [f'-Xmx{args.xmx}']
```

- `environment.json`/manifest 에 `jvmOptions` 로 이미 기록되므로 추가 필드 불필요. 확인만.
- `test_*.py` 에 옵션 전달 테스트 1개 추가(기본값이 3500m 인지, 지정값이 command 에 들어가는지).
- 커밋은 큰 heap 결과와 함께 `codex/otp-phase0-3` 에.

## 4. 실행 매트릭스

| 항목 | 값 |
|---|---|
| heap | **10g** (주). 시간 남으면 **6g** (중간값, 선택) |
| 동시성 | 1, 2, 4 |
| 쌍 수 | 각 동시성 3쌍 (AB, BA, AB 순서). 미확정이면 +2 |
| 요청 | OD 당 warmup 100 + measured 100 (= 기존 최종 계약과 동일) |
| 예열 게이트 | `--require-warmup-stability --warmup-stability-window 40`, 600초 내 미안정 시 그 run 무효 처리하고 재실행 1회 |
| GC | 기본(G1) + StringDeduplication (기존과 동일). ZGC 는 안 함 |
| query | 기존 성능 query. correctness 는 이미 통과했으므로 생략 |

명령 예 (한 쌍의 A 쪽, c1):

```bash
cd /Users/wa/golbob/개인/OpenTripPlanner
python3 -B test/performance/seoul-metro/run_isolated.py \
  --jar /private/tmp/otp-baseline/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar \
  --graph /private/tmp/otp-seoul-dev-graph/graph.obj \
  --output-dir /private/tmp/otp-lh10-a-c1-p1 --run-id lh10-a-c1-p1 --variant A \
  --concurrency 1 --warmup-per-case 100 --requests-per-case 100 \
  --require-warmup-stability --warmup-stability-window 40 --xmx 10g
```

H+F 쪽은 `--jar` 와 `--variant HF`, 디렉터리 `otp-lh10-hf-c1-p1`. 쌍 순서를 파일명에 남긴다(`p1`=AB, `p2`=BA, `p3`=AB).

요약·비교:

```bash
python3 -B test/performance/seoul-metro/summarize_run.py /private/tmp/otp-lh10-a-c1-p1
python3 -B test/performance/seoul-metro/compare_runs.py \
  --pair /private/tmp/otp-lh10-a-c1-p1 /private/tmp/otp-lh10-hf-c1-p1 \
  --pair /private/tmp/otp-lh10-a-c1-p2 /private/tmp/otp-lh10-hf-c1-p2 \
  --pair /private/tmp/otp-lh10-a-c1-p3 /private/tmp/otp-lh10-hf-c1-p3 --seed 20260911
```

## 5. 시간 예산

run 하나 ≈ 기동 70초 + 예열(안정화까지, 보통 2~5분) + 측정 400건(c1 ≈ 3~4분, c4 ≈ 1~2분). 쌍 ≈ 10~15분. 3쌍 × 3 동시성 = 9쌍 ≈ **2~2.5시간**(10g). 6g 까지 하면 5시간. 중간에 끊어도 되게 동시성 단위로 끝내고 결과를 바로 적는다.

## 6. 결과 기록 (`handoffs/LARGE-HEAP-RESULT.md`)

| heap | 동시성 | 쌍 | A p50/p95 | HF p50/p95 | 개선율 p50/p95 | OD 회귀 (>5% 3연속) | 예열 안정 |
|---|---|---|---|---|---|---|---|
| 10g | 1 | p1(AB) | | | | | |
| … | | | | | | | |

동시성별 `compare_runs.py` 출력(중앙·95% 구간) 그대로 붙임. 마지막에 §1 규칙으로 H-dom / H-trade / 미확정 중 하나를 적고, **기존 3.5G 표 옆에 나란히** 둔다.

추가 관측(결론엔 안 쓰지만 적어 둘 것): 측정 구간 GC 횟수·총 정지(summarize 가 뽑아 줌). 큰 heap 에서 A 의 GC 정지가 실제로 줄었는지가 "굶었던 몫" 의 크기다. GC 가 0회면 after-GC heap 은 N/A 로 남긴다.

## 7. 결과별 다음 행동

| 판정 | 포폴/PR 프레임 | 후속 |
|---|---|---|
| H-dom | "메모리·지연 모두 개선. 메모리 넉넉한 환경(10g)에서도 지연 회귀 없음" | 그대로 upstream PR-1. 이 표를 본문에 넣음 |
| H-trade | "메모리 제약 환경용 최적화. 넉넉한 환경에선 지연 n% 손해" | 우리 배포는 그대로 H+F(VPS 8G). upstream 엔 두 표 정직하게. 다음 후보 = **정류장 행 단위 lazy 생성**(읽히는 행만 연속 배열로 만들기: A 의 지역성 + H 의 할당 절감). 별도 PLAN |
| 미확정 | "10g 에서 차이 없음(구간이 0 포함)" | 쌍 2개 추가. 그래도 걸치면 "차이 없음" 으로 기록하고 H-dom 에 준해 진행 |

## 8. 하지 않는 것

- 6g/10g 결과로 3.5G 결과를 덮어쓰지 않는다. 환경별 표를 따로 둔다.
- heap 크기와 코드 변경 효과를 섞어 "총 개선율" 을 만들지 않는다(PLAN §1 "JVM 설정 변경을 코드 성과로 섞지 않음").
- VPS 에서는 하지 않는다(8G 라 큰 heap 불가). 로컬 전용.
