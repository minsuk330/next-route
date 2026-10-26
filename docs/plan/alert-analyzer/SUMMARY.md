# 장애 알림 AI 분석기 (alert-analyzer)

## 뭘 하나
Grafana 알림이 Slack에 울리면, 별도 FastAPI 분석기가 그 시각의 로그와 메트릭을 모아 Claude Sonnet 5.5로 원인 가설을 만듭니다. 가설은 같은 알림의 스레드 답글로 답니다(근거 로그, Grafana 링크 포함).

## 왜
지금 알림은 "무엇이 났나"만 알려 주고, "왜"는 사람이 Grafana에서 로그를 뒤져야 압니다.

## 핵심 결정
- 알림 경로에 AI를 넣지 않습니다. Grafana가 Slack과 분석기에 따로 보내므로, 분석기가 죽어도 알림은 갑니다.
- 로그 원문 대신 로거별 정확한 집계와 작은 표본을 넣습니다. worker WARN이 하루 2.2만 건이고 99%가 한 종류입니다.
- 근거 없는 가설은 버리고, 배포 관련 여부는 코드가 판정합니다. 평가는 규칙 기반 비교선과 나란히 냅니다.
- 운영 로그에 서울 API 키가 평문으로 있습니다. 그래서 URL 경로 마스킹을 하고, fixture는 커밋 전에 VPS env 값과 대조합니다.
- PR-1에서 DRY_RUN으로 먼저 배포해 실측(메모리, 실제 payload)을 얻습니다. PR-3에서 켭니다(메모리 상한, 로컬 모델 기각).

## 건드는 곳
alert-analyzer/ (신규), .github/workflows/alert-analyzer-image.yml, ops/observability/compose.yaml, ops/observability/grafana/provisioning/alerting/contact-points.yaml, ops/observability/README.md, ops/observability/env/

## 리스크
- 평가용 과거 장애 로그가 11-19에 보관 만료됩니다. PR-1 배포 직후 fixture로 떠 둬야 합니다.
- Slack 알림 문구 변경(PR-3)은 실제 알림 경로를 건드립니다. 렌더를 미리 확인하고 백업해 둡니다.
- 대화에 노출된 Slack 토큰을 폐기해야 합니다(사용자 작업).

## 검증법
- pytest 전부 통과: 계약, 마스킹, 근거 검증, 실패 경로.
- 과거 장애 5건 평가: LLM 적중 수를 규칙 비교선과 비교하고, 건당 비용을 기록합니다.
- VPS에서 임시 규칙으로 "알림 → 스레드 답글"을 실물로 확인합니다.
