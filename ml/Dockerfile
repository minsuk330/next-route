FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1
ENV PATH="/app/.venv/bin:${PATH}"

WORKDIR /app

# lightgbm (serve extra) loads libgomp.so.1 at import; python:slim lacks it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir uv

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project --extra serve

COPY . .

# Batch jobs override CMD via `docker compose run`. The serving container sets its
# own command (uvicorn serve.app:app). Default to a no-op version print.
CMD ["python", "--version"]
