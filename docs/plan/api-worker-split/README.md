# API / Worker 운영

Spring Boot 산출물과 Docker 이미지는 하나지만 실행 역할은 두 개다.

- `api`: `prod,api` — HTTP 요청과 JVM 로컬 경로검색 캐시 갱신
- `worker`: `prod,worker` — 수집, 배치, 알림, outbox relay와 알림 계산용 JVM 로컬 캐시 갱신

## 로컬 실행

운영 구성을 기준으로 로컬 차이만 덮는다.

```bash
./gradlew bootJar
docker compose -f compose.yaml -f compose.local.yaml up
```

Worker까지 실행하려면 Compose profile을 추가한다.

```bash
docker compose -f compose.yaml -f compose.local.yaml --profile worker up
```

## VPS 배포

1. `env/db.env`, `env/api.env`, `env/toss.env`, `env/auth.env`와
   `/srv/nextroute/toss.p12`가 VPS에 준비되어 있는지 확인한다.
2. 로컬에서 bootJar를 만들고 VPS의 `/root/apps/nextroute/app.jar`로 복사한다.
3. 최초 역할 분리는 `./deploy.sh all`, 이후에는 변경 역할만 배포한다.

```bash
./deploy.sh api
./deploy.sh worker
./deploy.sh rollback-api
./deploy.sh rollback-worker
```

모든 배포 명령은 `compose.yaml`만 명시적으로 사용하므로 VPS에 남은
`compose.override.yaml`은 병합되지 않는다. 역할별 현재/직전 이미지 태그는 `.env`에 저장된다.

API 배포는 Worker를 재생성하지 않는다. 배포 전후 Worker 컨테이너 ID가 달라지면
스크립트가 실패로 처리한다.

## 최초 전환 확인

- `api`, `worker`의 `/actuator/health`가 모두 `UP`
- Nginx가 계속 `127.0.0.1:8080`의 API로 연결
- API 로그에 수집·배치·알림 실행 로그가 없음
- Worker 로그에 버스·지하철 수집 주기가 이어짐
- Prometheus `nextroute-app` job에 `role=api`, `role=worker` target이 모두 `UP`
