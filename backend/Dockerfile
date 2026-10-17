FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app

WORKDIR /app

COPY backend/requirements.lock ./requirements.lock
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && python -m pip install --no-cache-dir --require-hashes -r requirements.lock \
    && groupadd --gid 10001 partrace \
    && useradd --uid 10001 --gid partrace --no-create-home --shell /usr/sbin/nologin partrace

COPY --chown=partrace:partrace backend/ ./backend/
COPY --chown=partrace:partrace agent/ ./agent/

USER partrace
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "backend.app:app", "--host", "0.0.0.0", "--port", "8000", "--no-proxy-headers"]
