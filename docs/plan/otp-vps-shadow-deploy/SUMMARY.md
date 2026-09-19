# OTP 수도권 shadow 배포

## 뭘 하나
H+F 최적화 OTP 서버를 VPS 에 컨테이너로 처음 올린다. 기동만 하는 dormant shadow — 앱은 아직 OTP 를 안 부른다(otpEnabled=false, provider=ODSAY 유지). 검색 영향 0. 나중 전환용 게이트만 배선.

## 왜
ODsay API 호출 한도 압박 → OTP 로 수도권 길찾기 전환 준비. 실제 전환 전, 서버가 이 VPS 메모리에 실제로 뜨는지·기존 운영 안 깨는지부터 확인.

## 핵심 결정
- **dormant shadow**: OTP 기동만, HTTP 호출 0. 전환(별건)은 api/worker 재생성하며 3값(BASE_URL + DEFAULT_PROVIDER=OTP + OTP_ENABLED=true) — shadow 앱엔 base URL 미반영이라 토글만 바꾸면 localhost 로 감.
- **메모리 mem_limit 3000m / memswap 3000m(swap 0) / -Xmx1900m**: 관측 peak ~2.0G + 완충. 이웃 OOM·요청중 OOM 둘 다 차단. 검증값 아님 → soak gate 로 확정.
- **graph 재빌드 필수**: 기존 2.9 graph(id 249) 를 H+F JAR(id 276) 가 못 읽음. Mac 로컬 재빌드 후 scp(VPS 빌드 기각).
- **이미지 = GHCR pull**: VPS 로컬 빌드 기각(운영 자원 경쟁). ML 관행과 동일.
- **readiness = GraphQL `{ feeds }`**: 파일 존재 검사 기각(잘못된 graph 못 잡음).
- **V49 마이그레이션·raw mount 제외**: shadow 에 DB/원본 안 건드림 → "영향 0" 보존.

## 건드는 곳
OpenTripPlanner fork(H+F 프로덕션4+테스트3 commit), Dockerfile.otp, GHCR nextroute-otp, compose.yaml(otp 서비스+api/worker env), OtpRoutingProperties(graphVersion), soak wrapper, release-manifest.json, deployment-runbook.md

## 리스크
- limit worst-case 합 8.0G > 7.7G — 전부 동시 상한 칠 때만 OOM(actual ~5.9G). 가드=memswap 0 + MemAvailable<1G 중단.
- H+F = 성능 gate 미완 실험 fork(P4_FORMAL_PENDING). shadow 로 완화.
- PR-3 배포 시 `up -d otp` 만 — api/worker 재생성하면 운영 컨테이너 재시작.

## 검증법
OTP graph load + `{ feeds }` 통과, 3000m/-Xmx1900m 에서 c1/c2/c4 + 30분 soak 무OOM, rollback(otp stop) 시 앱 정상, manifest ↔ image digest/graph SHA/id 276 일치.
