# NextRoute ML

버스 ML dataset 파이프라인은 Spring 애플리케이션과 분리해서 Python에서 실행한다.

## 역할

| Layer | Role | Storage |
|---|---|---|
| Spring | raw 수집, `bus_arrival_label_event` 생성 | Postgres |
| Python archive | label/position 원천 보존 | `data/bus_label`, `data/bus_position` |
| Python dataset | 학습용 X/y cache 생성 | `data/dataset` |
| Python training | baseline/model 학습과 평가 | `models`, `reports` |

archive parquet가 ML 장기 진실원이고, dataset parquet는 재생성 가능한 cache다.

상위 문서:

- `../docs/ml-bus/bus-arrival-ml-dataset-todo.md`
- `../docs/ml-bus/bus-ml-dataset-pipeline.md`

## Setup

VPS Postgres로 SSH tunnel을 연다.

```bash
ssh -L 5433:localhost:5432 vps
```

환경 파일을 만든다.

```bash
cd ml
cp .env.example .env
```

`.env`를 실제 DB 값으로 수정한다.

```text
DB_URL=postgresql://USER:PASSWORD@localhost:5433/DBNAME
DATA_DIR=./data
```

의존성을 설치한다.

```bash
uv sync
```

또는:

```bash
python -m venv .venv
source .venv/bin/activate
python -m pip install -e .
```

## Archive

서비스일 단위로 label/position 원천 parquet을 생성한다.

```bash
python archive.py 2026-06-10
```

이미 `data.parquet`가 있으면 기본적으로 skip한다.

```bash
python archive.py 2026-06-10
```

재생성하려면 `--overwrite`를 사용한다.

```bash
python archive.py 2026-06-10 --overwrite
```

출력:

```text
data/bus_label/service_date=2026-06-10/data.parquet
data/bus_label/service_date=2026-06-10/manifest.json

data/bus_position/service_date=2026-06-10/data.parquet
data/bus_position/service_date=2026-06-10/manifest.json
```

`archive.py`는 DB count와 parquet row count를 비교한다. 불일치하면 생성 파일을 삭제하고 비정상 종료한다.

## Sanity Check

```python
import polars as pl

labels = pl.scan_parquet("data/bus_label/**/*.parquet")
print(labels.select(pl.len()).collect())
```

## Next Phases

## Build Dataset

archive parquet에서 학습용 dataset cache를 생성한다.

```bash
python build_dataset.py 2026-06-10
```

이미 part 파일이 있으면 skip한다.

```bash
python build_dataset.py 2026-06-10 --overwrite
```

출력:

```text
data/dataset/service_date=2026-06-10/part-0000.parquet
data/dataset/service_date=2026-06-10/manifest.json
```

`build_dataset.py`는 route 단위로 조인해 part 파일을 즉시 쓴다. dataset parquet는 archive에서 언제든 재생성 가능한 cache다.

## Validate

archive와 dataset 품질을 검증한다.

```bash
python validate.py 2026-06-10
```

출력:

```text
data/dataset/service_date=2026-06-10/validation.json
```

검증 실패 시 exit code 1을 반환한다.

## Later

- baseline/model 학습
- VPS 컨테이너와 cron 이관
