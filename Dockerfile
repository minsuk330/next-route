FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1
ENV PATH="/app/.venv/bin:${PATH}"

WORKDIR /app

RUN pip install --no-cache-dir uv

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project --extra serve

COPY . .

# Batch jobs override CMD via `docker compose run`. The serving container sets its
# own command (uvicorn serve.app:app). Default to a no-op version print.
CMD ["python", "--version"]
