#!/usr/bin/env bash
set -Eeuo pipefail

# Isolated behavior checks. No Docker daemon, credentials or production host used.
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT
mkdir -p "$TEST_ROOT/bin"
ORIGINAL_TAG="sha-1111111111111111111111111111111111111111"
TARGET_TAG="sha-2222222222222222222222222222222222222222"
export ORIGINAL_TAG TARGET_TAG

cat > "$TEST_ROOT/bin/flock" <<'MOCK'
#!/usr/bin/env bash
[[ "${SCENARIO:-}" != locked ]]
MOCK

cat > "$TEST_ROOT/bin/sudo" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ "$1" == -n ]] && shift
[[ "$1" == true ]] && exit 0
if [[ "$1" == env ]]; then
  shift
  export "$1"
  shift
fi
[[ "$1" == docker ]] || exit 99
shift
printf '%s | %s\n' "${IMAGE_TAG:-}" "$*" >> "$CASE_ROOT/calls"
if [[ "$1" == inspect ]]; then
  if [[ "$3" == '{{.Config.Image}}' ]]; then
    if [[ "${SCENARIO:-}" == wrong-ref && "$(cat "$CASE_ROOT/running-tag")" == "$TARGET_TAG" ]]; then
      printf '15688463267/thesis-frontend:%s\n' "$ORIGINAL_TAG"
    elif [[ "${SCENARIO:-}" == wrong-repo && "$(cat "$CASE_ROOT/running-tag")" == "$TARGET_TAG" ]]; then
      printf 'unexpected/frontend:%s\n' "$TARGET_TAG"
    else
      printf '15688463267/thesis-frontend:%s\n' "$(cat "$CASE_ROOT/running-tag")"
    fi
  elif [[ "$3" == '{{.Image}}' ]]; then
    if [[ "${SCENARIO:-}" == wrong-image && "$(cat "$CASE_ROOT/running-tag")" == "$TARGET_TAG" ]]; then
      echo sha256:unexpected
    else
      printf 'sha256:%s\n' "$(cat "$CASE_ROOT/running-tag")"
    fi
  elif [[ "$4" == frontend-id && ( "${SCENARIO:-}" == noop-unhealthy || ( "${SCENARIO:-}" =~ ^(unhealthy|registry-offline)$ && "$(cat "$CASE_ROOT/running-tag")" == "$TARGET_TAG" ) ) ]]; then
    echo unhealthy
  elif [[ "${SCENARIO:-}" == "noop-${4%-id}-unhealthy" ]]; then
    echo unhealthy
  else
    echo healthy
  fi
  exit 0
fi
if [[ "$1" == image && "$2" == inspect ]]; then
  if [[ "${SCENARIO:-}" == noop-image-missing ]]; then exit 1; fi
  if [[ "$3" == -f ]]; then
    if [[ "${SCENARIO:-}" == noop-wrong-image ]]; then
      echo sha256:unexpected
    else
      printf 'sha256:%s\n' "${5##*:}"
    fi
  fi
  exit 0
fi
if [[ "$1" == tag ]]; then
  [[ "$2" == "sha256:$ORIGINAL_TAG" && "$3" == "15688463267/thesis-frontend:$ORIGINAL_TAG" ]]
  exit $?
fi
if [[ "$1" == exec ]]; then
  if [[ "${SCENARIO:-}" == noop-unreachable || ( "${SCENARIO:-}" == unreachable && "$(cat "$CASE_ROOT/running-tag")" == "$TARGET_TAG" ) ]]; then
    exit 22
  fi
  echo '{"status":"UP"}'
  exit 0
fi
[[ "$1" == compose ]] || exit 98
shift
while [[ "$1" == -* ]]; do
  case "$1" in -p|--env-file|-f) shift 2 ;; *) exit 97 ;; esac
done
case "$1" in
  ps)
    if [[ "${2:-}" == -q ]]; then
      if [[ "${SCENARIO:-}" == other-changed && "$3" == backend && "$(cat "$CASE_ROOT/running-tag")" == "$TARGET_TAG" ]]; then
        echo backend-changed
      elif [[ "${SCENARIO:-}" == platform-changed && "$3" == platform && "$(cat "$CASE_ROOT/running-tag")" == "$TARGET_TAG" ]]; then
        echo platform-changed
      elif [[ "${SCENARIO:-}" == database-changed && "$3" == postgres && "$(cat "$CASE_ROOT/running-tag")" == "$TARGET_TAG" ]]; then
        echo postgres-changed
      else
        echo "$3-id"
      fi
    fi
    ;;
  config) ;;
  pull)
    [[ "$2" == frontend ]] || exit 96
    if [[ "${SCENARIO:-}" == pull-fails && "$IMAGE_TAG" == "$TARGET_TAG" ]]; then exit 1; fi
    if [[ "${SCENARIO:-}" == registry-offline && "$IMAGE_TAG" == "$ORIGINAL_TAG" ]]; then exit 1; fi
    ;;
  up)
    [[ "$*" == 'up -d --no-deps --force-recreate --pull never frontend' ]] || exit 95
    printf '%s\n' "$IMAGE_TAG" > "$CASE_ROOT/running-tag"
    if [[ "${SCENARIO:-}" == up-fails && "$IMAGE_TAG" == "$TARGET_TAG" ]]; then exit 1; fi
    ;;
  *) exit 94 ;;
esac
MOCK
chmod +x "$TEST_ROOT/bin/"*
export PATH="$TEST_ROOT/bin:$PATH"

run_case() {
  local name="$1" expected_status="$2" expected_tag="$3" argument="${4:-$TARGET_TAG}" status
  export CASE_ROOT="$TEST_ROOT/$name" SCENARIO="$name"
  mkdir -p "$CASE_ROOT"
  cp "$SOURCE_DIR/deploy-frontend.sh" "$CASE_ROOT/deploy-frontend.sh"
  printf 'IMAGE_TAG=%s\n' "$ORIGINAL_TAG" > "$CASE_ROOT/release.env"
  printf '%s\n' "$ORIGINAL_TAG" > "$CASE_ROOT/current-tag"
  printf '%s\n' "$ORIGINAL_TAG" > "$CASE_ROOT/frontend-current-tag"
  printf '%s\n' "$ORIGINAL_TAG" > "$CASE_ROOT/backend-current-tag"
  printf '%s\n' "$ORIGINAL_TAG" > "$CASE_ROOT/running-tag"
  : > "$CASE_ROOT/.env"
  : > "$CASE_ROOT/compose.prod.yaml"
  : > "$CASE_ROOT/calls"
  status=0
  bash "$CASE_ROOT/deploy-frontend.sh" "$argument" > "$CASE_ROOT/output" 2>&1 || status=$?
  if [[ "$status" != "$expected_status" ]]; then
    cat "$CASE_ROOT/output" >&2
    echo "$name: expected exit $expected_status, got $status" >&2
    exit 1
  fi
  [[ "$(cat "$CASE_ROOT/running-tag")" == "$expected_tag" ]]
  [[ "$(cat "$CASE_ROOT/release.env")" == "IMAGE_TAG=$ORIGINAL_TAG" ]]
  [[ "$(cat "$CASE_ROOT/current-tag")" == "$ORIGINAL_TAG" ]]
  [[ "$(cat "$CASE_ROOT/backend-current-tag")" == "$ORIGINAL_TAG" ]]
  if [[ "$status" == 0 && "$name" != noop* ]]; then
    [[ "$(cat "$CASE_ROOT/frontend-current-tag")" == "$TARGET_TAG" ]]
    grep -q "$TARGET_TAG | compose .* up -d --no-deps --force-recreate --pull never frontend" "$CASE_ROOT/calls"
  else
    [[ "$(cat "$CASE_ROOT/frontend-current-tag")" == "$ORIGINAL_TAG" ]]
  fi
  if [[ "$name" == invalid || "$name" == locked || "$name" == noop* ]]; then
    ! grep -Eq ' (pull|up) ' "$CASE_ROOT/calls"
  fi
  if [[ "$name" == noop ]]; then
    grep -q 'image inspect -f {{.Id}}' "$CASE_ROOT/calls"
    for service in frontend backend platform postgres; do
      grep -q "inspect -f .* $service-id" "$CASE_ROOT/calls"
    done
    grep -q 'exec frontend-id wget -q -T 15 -O - http://127.0.0.1:8080/api/health' "$CASE_ROOT/calls"
  fi
  if [[ "$status" == 1 && "$name" != noop* ]]; then
    grep -q "$ORIGINAL_TAG | compose .* up -d --no-deps --force-recreate --pull never frontend" "$CASE_ROOT/calls"
    ! grep -q "$ORIGINAL_TAG | compose .* pull frontend" "$CASE_ROOT/calls"
  fi
  echo "PASS $name"
}

run_case success 0 "$TARGET_TAG"
run_case invalid 2 "$ORIGINAL_TAG" 'sha-invalid;echo unsafe'
run_case locked 3 "$ORIGINAL_TAG"
run_case noop 0 "$ORIGINAL_TAG" "$ORIGINAL_TAG"
run_case noop-unhealthy 1 "$ORIGINAL_TAG" "$ORIGINAL_TAG"
run_case noop-backend-unhealthy 1 "$ORIGINAL_TAG" "$ORIGINAL_TAG"
run_case noop-platform-unhealthy 1 "$ORIGINAL_TAG" "$ORIGINAL_TAG"
run_case noop-postgres-unhealthy 1 "$ORIGINAL_TAG" "$ORIGINAL_TAG"
run_case noop-unreachable 1 "$ORIGINAL_TAG" "$ORIGINAL_TAG"
run_case noop-wrong-image 1 "$ORIGINAL_TAG" "$ORIGINAL_TAG"
run_case noop-image-missing 1 "$ORIGINAL_TAG" "$ORIGINAL_TAG"
run_case pull-fails 5 "$ORIGINAL_TAG"
run_case up-fails 1 "$ORIGINAL_TAG"
run_case unhealthy 1 "$ORIGINAL_TAG"
run_case unreachable 1 "$ORIGINAL_TAG"
run_case other-changed 1 "$ORIGINAL_TAG"
run_case database-changed 1 "$ORIGINAL_TAG"
run_case platform-changed 1 "$ORIGINAL_TAG"
run_case registry-offline 1 "$ORIGINAL_TAG"
run_case wrong-ref 1 "$ORIGINAL_TAG"
run_case wrong-repo 1 "$ORIGINAL_TAG"
run_case wrong-image 1 "$ORIGINAL_TAG"
echo 'All 22 frontend deployment behavior checks passed.'
