#!/usr/bin/env bash
set -Eeuo pipefail

TARGET_TAG="${1:-}"
if ! [[ "$TARGET_TAG" =~ ^sha-[0-9a-f]{40}$ ]]; then
  echo "用法：$0 sha-<40位小写提交号>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT/.env"
RELEASE_FILE="$ROOT/release.env"
COMPOSE_FILE="$ROOT/compose.prod.yaml"
MARKER_FILE="$ROOT/backend-current-tag"

exec 9>"$ROOT/deploy.lock"
if ! flock -n 9; then
  echo "另一次部署正在运行，未修改服务" >&2
  exit 3
fi

sudo -n true || { echo "需要免密 sudo，未修改服务" >&2; exit 4; }
for required in "$ENV_FILE" "$RELEASE_FILE" "$COMPOSE_FILE"; do
  [[ -f "$required" ]] || { echo "缺少 $required，未修改服务" >&2; exit 4; }
done

# The shell variable overrides IMAGE_TAG in release.env for this command only.
# Never change release.env/current-tag: they still describe the full release.
compose_for() {
  local tag="$1"
  shift
  sudo -n env IMAGE_TAG="$tag" docker compose -p docx-check \
    --env-file "$ENV_FILE" --env-file "$RELEASE_FILE" \
    -f "$COMPOSE_FILE" "$@"
}

container_id() {
  compose_for "$TARGET_TAG" ps -q "$1"
}

BACKEND_ID="$(container_id backend)"
PLATFORM_ID="$(container_id platform)"
FRONTEND_ID="$(container_id frontend)"
POSTGRES_ID="$(container_id postgres)"
if [[ -z "$BACKEND_ID" || -z "$PLATFORM_ID" || -z "$FRONTEND_ID" || -z "$POSTGRES_ID" ]]; then
  echo "现有服务不完整，未修改服务" >&2
  exit 4
fi

CURRENT_REF="$(sudo -n docker inspect -f '{{.Config.Image}}' "$BACKEND_ID")"
CURRENT_IMAGE_ID="$(sudo -n docker inspect -f '{{.Image}}' "$BACKEND_ID")"
case "$CURRENT_REF" in
  15688463267/thesis-backend:sha-* | docker.io/15688463267/thesis-backend:sha-*) ;;
  *) echo "无法确认当前审核引擎镜像：$CURRENT_REF，未修改服务" >&2; exit 4 ;;
esac
CURRENT_TAG="${CURRENT_REF##*:}"
if ! [[ "$CURRENT_TAG" =~ ^sha-[0-9a-f]{40}$ ]]; then
  echo "当前镜像 tag 格式异常，未修改服务" >&2
  exit 4
fi
if [[ "$CURRENT_TAG" == "$TARGET_TAG" ]]; then
  echo "审核引擎已使用 $TARGET_TAG，无需重建"
  exit 0
fi

wait_healthy() {
  local tag="$1" service="$2" timeout="$3" id state elapsed
  id="$(compose_for "$tag" ps -q "$service" 2>/dev/null || true)"
  [[ -n "$id" ]] || { echo "$service 没有容器" >&2; return 1; }
  for ((elapsed = 0; elapsed < timeout; elapsed += 2)); do
    state="$(sudo -n docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo unknown)"
    case "$state" in
      healthy) return 0 ;;
      unhealthy | exited | dead)
        echo "$service 状态为 $state" >&2
        return 1
        ;;
    esac
    sleep 2
  done
  echo "$service 健康检查超时（$state）" >&2
  return 1
}

platform_reaches_backend() {
  sudo -n docker exec "$PLATFORM_ID" \
    curl -fsS --max-time 15 http://backend:18098/api/inspections/health | grep -q 'UP'
}

other_containers_unchanged() {
  [[ "$(container_id platform)" == "$PLATFORM_ID" && \
     "$(container_id frontend)" == "$FRONTEND_ID" && \
     "$(container_id postgres)" == "$POSTGRES_ID" ]]
}

backend_matches() {
  local tag="$1" expected_image_id="$2" id actual_ref actual_image_id
  id="$(compose_for "$tag" ps -q backend)"
  [[ -n "$id" ]] || return 1
  actual_ref="$(sudo -n docker inspect -f '{{.Config.Image}}' "$id")"
  actual_image_id="$(sudo -n docker inspect -f '{{.Image}}' "$id")"
  case "$actual_ref" in
    "15688463267/thesis-backend:$tag" | "docker.io/15688463267/thesis-backend:$tag") ;;
    *) return 1 ;;
  esac
  [[ "$actual_image_id" == "$expected_image_id" ]]
}

restore_previous() {
  echo "尝试恢复原审核引擎镜像 $CURRENT_TAG" >&2
  # Recover the image that actually ran before the switch, even if the registry
  # is unavailable or its old tag has since moved. Do not pull over a local copy.
  if sudo -n docker image inspect "$CURRENT_IMAGE_ID" >/dev/null 2>&1; then
    if ! sudo -n docker tag "$CURRENT_IMAGE_ID" "$CURRENT_REF"; then
      echo "无法恢复本地原镜像标签，需人工恢复" >&2
      return 1
    fi
  elif ! compose_for "$CURRENT_TAG" pull backend; then
    echo "本地原镜像不存在且拉取失败，需人工恢复" >&2
    return 1
  fi
  if [[ "$(sudo -n docker image inspect -f '{{.Id}}' "$CURRENT_REF")" != "$CURRENT_IMAGE_ID" ]]; then
    echo "原镜像标签已变化，不能确认安全恢复，需人工处理" >&2
    return 1
  fi
  if ! compose_for "$CURRENT_TAG" up -d --no-deps --pull never backend ||
     ! wait_healthy "$CURRENT_TAG" backend 240 ||
     ! backend_matches "$CURRENT_TAG" "$CURRENT_IMAGE_ID" ||
     ! other_containers_unchanged ||
     ! platform_reaches_backend; then
    echo "自动恢复失败，需人工处理" >&2
    return 1
  fi
  echo "已恢复原审核引擎镜像 $CURRENT_TAG" >&2
}

echo "仅回滚审核引擎：$CURRENT_TAG → $TARGET_TAG"
compose_for "$TARGET_TAG" config -q
if ! compose_for "$TARGET_TAG" pull backend; then
  echo "目标镜像拉取失败，未修改服务" >&2
  exit 5
fi
TARGET_IMAGE_ID="$(sudo -n docker image inspect -f '{{.Id}}' "15688463267/thesis-backend:$TARGET_TAG")"
[[ -n "$TARGET_IMAGE_ID" ]] || { echo "无法确认目标镜像 ID，未修改服务" >&2; exit 5; }
if ! compose_for "$TARGET_TAG" up -d --no-deps backend ||
   ! wait_healthy "$TARGET_TAG" backend 240 ||
   ! backend_matches "$TARGET_TAG" "$TARGET_IMAGE_ID" ||
   ! other_containers_unchanged ||
   ! wait_healthy "$TARGET_TAG" platform 120 ||
   ! wait_healthy "$TARGET_TAG" frontend 90 ||
   ! platform_reaches_backend; then
  echo "目标审核引擎未通过健康检查" >&2
  restore_previous || true
  exit 1
fi

marker_tmp="$(mktemp "$ROOT/backend-current-tag.XXXXXX")" || {
  restore_previous || true
  exit 1
}
trap 'rm -f "$marker_tmp"' EXIT
if ! printf '%s\n' "$TARGET_TAG" > "$marker_tmp" ||
   ! chmod 600 "$marker_tmp" ||
   ! mv -f "$marker_tmp" "$MARKER_FILE"; then
  echo "版本记录写入失败" >&2
  restore_previous || true
  exit 1
fi

echo "审核引擎单服务回滚成功：$TARGET_TAG"
echo "platform、frontend 与 postgres 容器未重建；release.env/current-tag 未修改"
for service in backend platform frontend postgres; do
  id="$(container_id "$service")"
  printf '%s: ' "$service"
  sudo -n docker inspect -f '{{.Id}} {{.Config.Image}} {{.Image}} {{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id"
done
