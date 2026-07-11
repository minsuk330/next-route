# 플랜 ①: k6 부하 측정 baseline (스케줄러·서빙 동거 간섭 정량화)

## Context

포폴 로드맵 1단계. collector 분리 *전* 동거 간섭 baseline 확보 — "측정→분리→재측정" 서사의 앞단.

**전제 수정 (2026-07-08)**: 라벨 생성 DB 배치 및 `/api/admin/bus/batch/label/trigger`는 퇴역. 학습 데이터는 전부 Redis 경유(snapshot/realtime cache) 수집. 따라서 간섭 가설을 "HikariCP 커넥션풀 경합"에서 **"동일 JVM 자원 경합(스케줄러 스레드·CPU·GC) + Redis 경합"**으로 재정의한다. 매분 도는 collector(버스 도착/위치, 외부 API 호출+Redis 쓰기)와 30s 알림 dispatcher가 상시 간섭원.

## 스코프 변경 이력

- PR-A ArchUnit → 백로그 (collector 분리는 additive 변경이라 계층 가드 효과 제한적)
- PR-B admin 봉합 → 사용자 직접 진행, PR #58 머지됨 (`X-Admin-Key`). 라벨 트리거 퇴역으로 이번 측정에선 admin API 불필요
- 간섭원 변경: 라벨 배치 트리거(퇴역) → **플래그 A/B** 방식

## 측정 설계: 플래그 A/B (간섭원을 만들지 않고 끄고 켜서 비교)

스케줄러 대부분이 이미 yaml 플래그 게이트됨 (`collector.*`, `notification.*`, `realtime.subway`, `batch.*`). 이걸 그대로 활용:

- **A (현재 = 동거)**: 플래그 전부 현행 유지, k6 4분
- **B (분리 시뮬레이션 = api-only)**: collector·notification 플래그 off로 재기동, 동일 k6 4분
- A/B의 p50/p95/p99 차이 = **collector 분리로 얻을 이득의 사전 정량화**. B가 곧 분리 후 api 프로세스 상태라 서사 직결
- 보너스: k6 JSON 시계열에서 A구간의 매분 collector tick과 지연 스파이크 상관 확인 (초 단위 히스토그램)

비용: prod 재기동 2회(B 진입/복귀), B 구간 ~10분간 수집 공백. 수집 데이터 특성상 공백 허용 가능(다음 tick에 회복).

## 산출물: PR-C `chore/load-baseline` 1개

### 1. actuator 내부 노출 (application-prod.yaml)

```yaml
management:
  server:
    port: 9090
  endpoints:
    web:
      exposure:
        include: health,metrics
```

- compose 9090 포트 미매핑 유지 → 외부 차단, 측정 시 `ssh vps docker exec nextroute-app-1 curl localhost:9090/actuator/metrics/...`
- 관찰 지표: `jvm.threads.states`, `process.cpu.usage`, `jvm.gc.pause`, (보조) `hikaricp.connections.pending`

### 2. k6 시나리오 `load/baseline.js`

- 대상 (전부 permitAll):
  - `GET /api/nearby/bus-stops?lat&lng` — DB geo (4 rps)
  - `GET /api/search/suggest?keyword` — DB 검색 (3 rps)
  - `GET /api/arrivals/subway?lat&lon` — 캐시+DB (3 rps)
  - `/api/route/search` 지속부하 제외 (ODsay 쿼터 + 외부 지연 오염)
- constant-arrival-rate 합 ~10rps, 4분, 서울 좌표/키워드 풀 랜덤
- 엔드포인트 tag + 에러율 threshold(<1%), `BASE_URL` env
- `load/README.md`: A/B 절차 재현 문서

## 측정 절차 (actuator 배포 후)

1. 로컬 `brew install k6`
2. **A(동거)**: 새벽 자동배치 없는 시간대 `k6 run -e BASE_URL=http://<vps>:8080 --out json=load/results/a.json load/baseline.js` + 중간 actuator 스냅샷 2~3회
3. **B(api-only)**: VPS compose.override env로 collector/notification/realtime 플래그 off → 재기동 → 동일 실행 → 플래그 복원 재기동
4. `load/results/2026-07-XX.md`에 A/B p50/p95/p99 비교표 + tick 상관 기록 — 포폴 원자료

해석: 차이 유의미 → 분리의 정량 근거 / 미미 → "장애 격리·독립 배포·리소스 독립 스케일" 근거로 서사 전환 (둘 다 유효).

## 리스크

- B 구간 수집 공백 ~10분: 실시간 캐시 TTL 15s라 사용자 화면에 stale 노출 가능 — 트래픽 적은 시간대 실행
- 재기동 2회: deploy.sh 관례 확인 후 env만 바꿔 restart (이미지 재빌드 불필요)
- 10rps = 운영 영향 무시 수준, 에러율 threshold로 이상 시 중단
