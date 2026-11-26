# 운영(VPS) 설정 관리

- **VPS = 운영, 최우선.** `/root/apps/nextroute`, `/root/apps/observability` (git 아님)
- **로컬 = 테스트.** `compose.yaml` + `compose.local.yaml`

## 파일 구성

| 파일 | 로컬 | 운영 |
|---|---|---|
| `compose.yaml` | base | base |
| `compose.local.yaml` | 로컬 override | – |
| `compose.prod.yaml` | – | 운영 override (기능 플래그, 전국 OTP, 메모리 상한, worker2) |
| `ops/observability/compose.yaml` | Tempo 켜짐 | base |
| `ops/observability/compose.prod.yaml` | – | Tempo 끔, Prometheus 9090 loopback |

운영은 각 디렉터리 `.env` 의 `COMPOSE_FILE=compose.yaml:compose.prod.yaml` 로 두 파일을 합친다.
`docker compose ...` 를 그냥 쓰면 된다. **`-f compose.yaml` 을 주면 운영 override 가 빠진다.**

운영에서만 다른 값은 `compose.prod.yaml` 에만 넣는다. VPS 파일을 손으로 고치지 않는다.

## 반영

```bash
ops/prod-sync.sh diff      # 저장소 ↔ VPS 차이
ops/prod-sync.sh apply     # origin/main 과 같은 커밋에서만. 바뀐 파일만 백업 후 전송, 재생성 명령 출력
ops/prod-drift.sh          # 차이 파일 수(주간 요약용)
```

- 백업: `/root/apps/{nextroute,observability}-backups/sync-<시각>/`
- 컨테이너 재생성은 출력된 명령으로 직접. api·worker 는 `./deploy.sh`
- 보내는 파일 목록은 `ops/prod-sync.sh` 의 `FILES`. 비밀값(env/*.env, /srv/nextroute/*)은 대상 아님

## 정리 (주 1회 cron)

`ops/prod-prune.sh`: `.env` 의 현재·직전 이미지만 남기고 `nextroute:*` 삭제, jar 백업 최근 2개, build cache 전부.

```cron
0 4 * * 0 /root/apps/nextroute/ops/prod-prune.sh >> /var/log/nextroute-prune.log 2>&1
```

## 변경 검증 방법

설정을 옮기거나 나눌 때는 VPS 에서 합쳐진 결과를 비교한다(읽기만 함):

```bash
docker compose --project-directory <dir> -f <old files...> config --format json > /tmp/a.json
docker compose --project-directory <dir> -f <new files...> config --format json > /tmp/b.json
# 서비스별로 비교 후 /tmp 파일 삭제(env 값이 들어 있다)
```

2026-11-26 분리 때 앱 8개·관측 6개 서비스 모두 차이 0 으로 확인했다.
