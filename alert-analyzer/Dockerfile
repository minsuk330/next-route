FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1
ENV PATH="/app/.venv/bin:${PATH}"

WORKDIR /app

RUN pip install --no-cache-dir uv

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY analyzer ./analyzer
COPY scripts ./scripts

EXPOSE 8000
# Access logs off: the 30 s healthcheck would otherwise flood Loki.
CMD ["uvicorn", "analyzer.app:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
