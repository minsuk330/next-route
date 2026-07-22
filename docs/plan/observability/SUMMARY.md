# 관측 스택 (Grafana + Loki + Alloy + Prometheus)

## 뭘 하나
VPS에 셀프호스팅 관측 스택 구축: 컨테이너 4개 로그 → Loki, Spring/노드 메트릭 → Prometheus,
Grafana 대시보드 3개(로그·Spring·노드)를 `grafana.refit-100.site` HTTPS로 제공.
설정 전체를 repo `ops/observability/`에 코드로 커밋(재현 가능).

## 왜
운영 로그 실시간 파악 불가 문제 해소 + 포트폴리오 재료(관측 인프라 직접 구축).

## 핵심 결정
- 수집기 Alloy — Promtail 2026-03 EOL (기각: Promtail)
- actuator를 management 포트 8081(미공개)로 격리 — `permitAll`+8080 공개 구조라 8080 노출 불가
- app 콘솔 로그 logstash JSON 전환(prod만) — Loki level 라벨/필드 검색용. 파일 로그는 텍스트 유지
- Loki retention 30d 명시 + docker json-file 로테이션 유지 — 기본값이 무한 증가라
- 라벨은 `app`/`env`/`level`만 — traceId 등 고카디널리티는 JSON 필드로만 (기각: label 승격)
- 관측 compose는 `/root/apps/observability/` 별도 프로젝트 — app 배포와 라이프사이클 분리

## 건드는 곳
`build.gradle`, `application-prod.yaml`, `ops/observability/*`(신설), `.gitignore`,
VPS: nginx conf, `/root/apps/observability/`, DNS(사용자)

## 리스크
- management 포트 분리로 기존 8080 actuator 참조 깨질 가능성 — 배포 전 grep(repo+VPS nginx/systemd/스크립트) 확인 단계 포함
- Alloy docker.sock = Docker 관리자 권한 동급 — 내부망 전용으로 수용, socket-proxy는 백로그
- retention 30d≠용량상한, ~700M 메모리 낙관치 — df/free 경보 초기 운영 체크 포함
- Loki/Alloy 설정 키 버전 편차 — 구현 시 공식 문서 대조 (loki.source.docker labels 등)

## 검증법
Grafana에서 4개 컨테이너 로그 + level 필터 동작, Prometheus targets 전부 up,
외부에서 3000/3100/9090/8081 직접 접근 불가, `grafana.refit-100.site` HTTPS 로그인 성공.
