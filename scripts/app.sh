#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' 'Usage: bash scripts/app.sh <start|stop|reset|clear|start-pgadmin|stop-pgadmin> [--project name] [--yes]'
    printf '%s\n' 'Reset deletes the selected project database and pgAdmin volumes, then rebuilds without layer cache.'
    printf '%s\n' 'Clear removes the selected project containers, networks, volumes and referenced images; it does not restart.'
    printf '%s\n' '--yes skips the reset/clear confirmation. Stop preserves containers, networks and data; stop-pgadmin stops only pgAdmin.'
}
fail() { printf '%s\n' "$*" >&2; exit 1; }

action=start
project_name=''
yes=false
action_seen=false
while (($#)); do
    case "$1" in
        start|stop|reset|clear|start-pgadmin|stop-pgadmin)
            [[ "$action_seen" == false ]] || fail 'Specify only one action.'
            action=$1; action_seen=true; shift ;;
        --project)
            [[ $# -ge 2 && -n "$2" ]] || fail '--project requires a name.'
            project_name=$2; shift 2 ;;
        --yes) yes=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; fail "Unknown argument: $1" ;;
    esac
done
[[ "$yes" == false || "$action" == reset || "$action" == clear ]] || fail '--yes is only valid with reset or clear.'
[[ -z "$project_name" || "$project_name" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail 'Invalid Compose project name.'

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
[[ -f "$repo_root/.env" ]] || fail 'Missing root .env. Copy .env.example to .env and configure it first.'
command -v docker >/dev/null 2>&1 || fail 'Docker is not installed or is not on PATH.'
docker info --format '{{.ServerVersion}}' >/dev/null || fail 'Docker Engine is unavailable. Start Docker Desktop/Engine first.'

# Explicit local files prevent COMPOSE_FILE from selecting a deployment/test stack.
compose=(docker compose --project-directory "$repo_root" --env-file "$repo_root/.env"
    -f "$repo_root/compose.yaml" -f "$repo_root/compose.override.yaml")
if [[ -n "$project_name" ]]; then compose+=(--project-name "$project_name"); fi
"${compose[@]}" config -q
# Match only the root identity line, discarding all resolved settings/secrets.
resolved_project=$("${compose[@]}" config --format yaml | sed -n 's/^name: \([a-z0-9][a-z0-9_-]*\)$/\1/p')
[[ "$resolved_project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail 'Unable to determine a valid Compose project name.'
if [[ -z "$project_name" ]]; then compose+=(--project-name "$resolved_project"); fi
printf 'OCC local project: %s; action: %s\n' "$resolved_project" "$action"

case "$action" in
    start) "${compose[@]}" up -d --build --wait ;;
    stop) "${compose[@]}" --profile tools stop ;;
    reset)
        if [[ "$yes" == false ]]; then
            printf "Delete containers, networks, database and pgAdmin data for '%s'? Type RESET: " "$resolved_project"
            answer=''
            if ! IFS= read -r answer; then
                printf '%s\n' 'Reset cancelled.'; exit 0
            fi
            # Accept Windows CRLF input as well as Unix LF (for Git Bash/pipelines).
            answer=${answer%$'\r'}
            if [[ "$answer" != RESET ]]; then
                printf '%s\n' 'Reset cancelled.'; exit 0
            fi
        fi
        "${compose[@]}" --profile tools down --volumes --remove-orphans
        "${compose[@]}" --profile tools build --pull --no-cache
        "${compose[@]}" up -d --force-recreate --wait ;;
    clear)
        if [[ "$yes" == false ]]; then
            printf "Delete all containers, networks, volumes and referenced images for '%s'? Type CLEAR: " "$resolved_project"
            answer=''
            if ! IFS= read -r answer; then
                printf '%s\n' 'Clear cancelled.'; exit 0
            fi
            answer=${answer%$'\r'}
            if [[ "$answer" != CLEAR ]]; then
                printf '%s\n' 'Clear cancelled.'; exit 0
            fi
        fi
        "${compose[@]}" --profile tools down --volumes --remove-orphans --rmi all ;;
    start-pgadmin) "${compose[@]}" --profile tools up -d --wait pgadmin ;;
    stop-pgadmin) "${compose[@]}" --profile tools stop pgadmin ;;
esac
