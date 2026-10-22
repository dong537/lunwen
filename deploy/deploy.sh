#!/usr/bin/env bash
set -Eeuo pipefail

TAG="${1:-}"
if [[ -z "$TAG" ]]; then
  echo "usage: $0 <image-tag>" >&2
  exit 2
fi
if ! [[ "$TAG" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "invalid image tag: $TAG" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT/.env"
RELEASE_FILE="$ROOT/release.env"
COMPOSE_FILE="$ROOT/compose.prod.yaml"

exec 9>"$ROOT/deploy.lock"
if ! flock -n 9; then
  echo "another deploy is already running" >&2
  exit 3
fi

sudo -n true || { echo "需要 ubuntu 用户免密 sudo" >&2; exit 4; }
[[ -f "$ENV_FILE" ]] || { echo "缺少 $ENV_FILE" >&2; exit 4; }
[[ -f "$COMPOSE_FILE" ]] || { echo "缺少 $COMPOSE_FILE" >&2; exit 4; }

LOCAL_PORT="$(sudo -n awk -F= '/^PLATFORM_HTTP_PORT=/{value=$2} END{print value}' "$ENV_FILE" 2>/dev/null || true)"
LOCAL_PORT="${LOCAL_PORT:-18090}"
LOCAL_URL="http://127.0.0.1:${LOCAL_PORT}"
PUBLIC_URL="https://docx-check.test.chatgqt.top"

PREV=""
if [[ -f "$ROOT/current-tag" ]]; then
  PREV="$(<"$ROOT/current-tag")"
fi

printf 'IMAGE_TAG=%s\n' "$TAG" > "$RELEASE_FILE"
chmod 600 "$RELEASE_FILE"

compose() {
  sudo -n docker compose -p docx-check \
    --env-file "$ENV_FILE" \
    --env-file "$RELEASE_FILE" \
    -f "$COMPOSE_FILE" "$@"
}

wait_healthy() {
  local service="$1" timeout="${2:-180}" id state i
  id="$(compose ps -q "$service" 2>/dev/null || true)"
  if [[ -z "$id" ]]; then
    echo "service $service has no container" >&2
    return 1
  fi
  for ((i = 0; i < timeout; i += 2)); do
    state="$(sudo -n docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo unknown)"
    case "$state" in
      healthy)
        echo "$service healthy"
        return 0
        ;;
      unhealthy | exited | dead)
        echo "$service state=$state" >&2
        return 1
        ;;
    esac
    sleep 2
  done
  echo "service $service health timeout (state=$state)" >&2
  return 1
}

check_http() {
  local url="$1" needle="${2:-}"
  if [[ -n "$needle" ]]; then
    curl -fsS --max-time 15 "$url" | grep -q "$needle"
  else
    curl -fsS --max-time 15 -o /dev/null "$url"
  fi
}

rollback() {
  local reason="$1"
  echo "部署失败：$reason" >&2
  compose logs --tail=120 platform backend frontend >&2 || true
  if [[ -z "$PREV" || "$PREV" == "$TAG" ]]; then
    echo "没有可回退的上一版本，保留现场" >&2
    return 1
  fi
  echo "回退到 $PREV" >&2
  printf 'IMAGE_TAG=%s\n' "$PREV" > "$RELEASE_FILE"
  compose pull || true
  compose up -d --no-deps backend
  wait_healthy backend 180 || { echo "回退 backend 失败" >&2; return 1; }
  compose up -d --no-deps platform
  wait_healthy platform 180 || { echo "回退 platform 失败" >&2; return 1; }
  compose up -d --no-deps --force-recreate frontend
  wait_healthy frontend 90 || { echo "回退 frontend 失败" >&2; return 1; }
  if check_http "$LOCAL_URL/api/health" '"status":"UP"' && check_http "$PUBLIC_URL/api/health" '"status":"UP"'; then
    printf '%s\n' "$PREV" > "$ROOT/current-tag"
    echo "已回退到 $PREV" >&2
    return 1
  fi
  echo "回退后健康检查仍失败" >&2
  return 1
}

echo "部署 $TAG（上一版本 ${PREV:-无}）"
compose config -q

for i in {1..12}; do
  if compose pull; then
    break
  fi
  if [[ "$i" == 12 ]]; then
    echo "镜像拉取失败" >&2
    exit 5
  fi
  echo "镜像拉取失败，10 秒后重试（$i/12）" >&2
  sleep 10
done

if ! compose up -d postgres; then rollback "启动 postgres 失败"; exit 1; fi
if ! wait_healthy postgres 120; then rollback "postgres 不健康"; exit 1; fi

if ! compose up -d --no-deps backend; then rollback "启动 backend 失败"; exit 1; fi
if ! wait_healthy backend 240; then rollback "backend 不健康"; exit 1; fi

if ! compose up -d --no-deps platform; then rollback "启动 platform 失败"; exit 1; fi
if ! wait_healthy platform 240; then rollback "platform 不健康"; exit 1; fi

if ! compose up -d --no-deps --force-recreate frontend; then rollback "启动 frontend 失败"; exit 1; fi
if ! wait_healthy frontend 90; then rollback "frontend 不健康"; exit 1; fi

if ! check_http "$LOCAL_URL/api/health" '"status":"UP"'; then rollback "本地 /api/health 检查失败"; exit 1; fi
if ! check_http "$LOCAL_URL/"; then rollback "本地首页检查失败"; exit 1; fi
if ! check_http "$PUBLIC_URL/api/health" '"status":"UP"'; then rollback "公网 /api/health 检查失败"; exit 1; fi

printf '%s\n' "$TAG" > "$ROOT/current-tag"
if [[ -n "$PREV" ]]; then
  printf '%s\n' "$PREV" > "$ROOT/previous-tag"
fi
echo "部署成功：$TAG"
compose ps
