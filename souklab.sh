#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# SOUKLAB UNIFIED CONTROLLER
# Single entrypoint to run, stop, inspect, and manage the full Souklab stack:
# - Backing services (MariaDB, RabbitMQ, MinIO, Redis, Elasticsearch, ClamAV)
# - Spring Boot 4 Backend (API, WebSocket STOMP relay, Actuator)
# - Next.js 16 Frontend (SSR & Static Assets)
# - Nginx Reverse Proxy (Unified gateway on port 80)
# - Ngrok Tunnel (Public HTTPS tunnel & Web Inspector)
# - Mailpit (Local email testing web UI)
# ==============================================================================

# Resolve canonical script directory even when invoked through symlinks (e.g. from ~/.local/bin/souklab)
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
    DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
    SOURCE="$(readlink "$SOURCE")"
    [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
done
SCRIPT_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
cd "${SCRIPT_DIR}"

COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
PROJECT_NAME="souklab"
DEFAULT_PROFILE="ngrok"

# Color constants
BOLD="\033[1m"
GREEN="\033[0;32m"
CYAN="\033[0;36m"
YELLOW="\033[1;33m"
RED="\033[0;31m"
DIM="\033[2m"
RESET="\033[0m"

log_info() {
    printf "${CYAN}[INFO]${RESET} %s\n" "$1"
}

log_success() {
    printf "${GREEN}[SUCCESS]${RESET} %s\n" "$1"
}

log_warn() {
    printf "${YELLOW}[WARN]${RESET} %s\n" "$1"
}

log_error() {
    printf "${RED}[ERROR]${RESET} %s\n" "$1" >&2
}

# ------------------------------------------------------------------------------
# PREREQUISITE & ENVIRONMENT INITIALIZATION
# ------------------------------------------------------------------------------
check_prerequisites() {
    if ! command -v docker >/dev/null 2>&1; then
        log_error "Docker is required but not installed. Please install Docker first."
        exit 1
    fi

    if ! docker compose version >/dev/null 2>&1; then
        log_error "Docker Compose (v2) is required but not installed or not working."
        exit 1
    fi

    if ! docker info >/dev/null 2>&1; then
        log_error "Docker daemon is not running or current user lacks docker group permissions."
        exit 1
    fi
}

ensure_env_files() {
    if [ ! -f "${SCRIPT_DIR}/.env" ]; then
        if [ -f "${SCRIPT_DIR}/.env.example" ]; then
            log_warn "Local .env file not found. Initializing from .env.example..."
            cp "${SCRIPT_DIR}/.env.example" "${SCRIPT_DIR}/.env"
        fi
    fi

    if [ ! -f "${SCRIPT_DIR}/.env.docker" ]; then
        if [ -f "${SCRIPT_DIR}/.env.docker.example" ]; then
            log_warn "Local .env.docker file not found. Initializing from .env.docker.example..."
            cp "${SCRIPT_DIR}/.env.docker.example" "${SCRIPT_DIR}/.env.docker"
        fi
    fi

    # Auto-detect NGROK_AUTHTOKEN from host config if not present in environment or .env
    if [ -z "${NGROK_AUTHTOKEN:-}" ]; then
        local token_found=""
        if [ -f "${HOME}/.config/ngrok/ngrok.yml" ]; then
            token_found=$(grep -E '^[[:space:]]*authtoken:' "${HOME}/.config/ngrok/ngrok.yml" | awk '{print $2}' | tr -d '"' | tr -d "'" || true)
        elif [ -f "${HOME}/.ngrok2/ngrok.yml" ]; then
            token_found=$(grep -E '^[[:space:]]*authtoken:' "${HOME}/.ngrok2/ngrok.yml" | awk '{print $2}' | tr -d '"' | tr -d "'" || true)
        fi

        if [ -n "${token_found}" ]; then
            export NGROK_AUTHTOKEN="${token_found}"
            # Keep .env and .env.docker synchronized with this token
            if [ -f "${SCRIPT_DIR}/.env" ] && ! grep -q "^NGROK_AUTHTOKEN=" "${SCRIPT_DIR}/.env"; then
                echo "NGROK_AUTHTOKEN=${token_found}" >> "${SCRIPT_DIR}/.env"
            fi
            if [ -f "${SCRIPT_DIR}/.env.docker" ] && ! grep -q "^NGROK_AUTHTOKEN=" "${SCRIPT_DIR}/.env.docker"; then
                echo "NGROK_AUTHTOKEN=${token_found}" >> "${SCRIPT_DIR}/.env.docker"
            fi
        fi
    fi
}

# ------------------------------------------------------------------------------
# COMMAND: START
# ------------------------------------------------------------------------------
cmd_start() {
    local do_build=false
    local with_ngrok=true
    local follow_logs=false

    while [ $# -gt 0 ]; do
        case "$1" in
            --build|-b)
                do_build=true
                shift
                ;;
            --no-ngrok)
                with_ngrok=false
                shift
                ;;
            --logs|-l)
                follow_logs=true
                shift
                ;;
            *)
                log_warn "Unknown start argument: $1"
                shift
                ;;
        esac
    done

    check_prerequisites
    ensure_env_files

    local profiles_arg=""
    if [ "${with_ngrok}" = true ]; then
        profiles_arg="--profile ngrok"
    fi

    # If images do not exist yet, enforce build
    if ! docker image inspect souklab-app:latest >/dev/null 2>&1 || \
       ! docker image inspect souklab-frontend:latest >/dev/null 2>&1; then
        log_info "Required container images not found. Triggering initial build..."
        do_build=true
    fi

    if [ "${do_build}" = true ]; then
        log_info "Building application containers (backend & frontend)..."
        docker compose ${profiles_arg} build
    fi

    log_info "Starting Souklab container stack..."
    docker compose ${profiles_arg} up -d --remove-orphans

    log_info "Waiting for backend and services to become healthy..."
    wait_for_health "${with_ngrok}"

    print_dashboard "${with_ngrok}"

    if [ "${follow_logs}" = true ]; then
        log_info "Attaching to logs (press Ctrl+C to detach)..."
        docker compose ${profiles_arg} logs -f
    fi
}

# ------------------------------------------------------------------------------
# COMMAND: STOP & DOWN
# ------------------------------------------------------------------------------
cmd_stop() {
    local do_down=false
    local clean_volumes=false

    while [ $# -gt 0 ]; do
        case "$1" in
            --down|-d)
                do_down=true
                shift
                ;;
            --clean|--volumes|-v)
                do_down=true
                clean_volumes=true
                shift
                ;;
            *)
                shift
                ;;
        esac
    done

    if [ "${do_down}" = true ]; then
        cmd_down ${clean_volumes:+--volumes}
        return
    fi

    check_prerequisites
    log_info "Stopping Souklab application containers (in-place)..."
    docker compose --profile ngrok stop
    log_success "Souklab containers stopped. (Container states preserved; run 'souklab down' if you wish to tear down container instances)."
}

cmd_down() {
    local clean_volumes=false

    while [ $# -gt 0 ]; do
        case "$1" in
            --clean|--volumes|-v)
                clean_volumes=true
                shift
                ;;
            *)
                shift
                ;;
        esac
    done

    check_prerequisites
    log_info "Tearing down Souklab stack (ephemeral containers & virtual network)..."
    if [ "${clean_volumes}" = true ]; then
        log_warn "Tearing down containers and deleting persistent database/storage volumes..."
        docker compose --profile ngrok down -v --remove-orphans
    else
        docker compose --profile ngrok down --remove-orphans
    fi

    log_success "Souklab stack torn down cleanly. (Images and persistent database volumes remain intact)."
}

cmd_install() {
    local target_dir="${HOME}/.local/bin"
    mkdir -p "${target_dir}"
    local target_link="${target_dir}/souklab"
    ln -sf "${SCRIPT_DIR}/souklab.sh" "${target_link}"
    chmod +x "${SCRIPT_DIR}/souklab.sh"
    log_success "Created global CLI link: ${target_link} -> ${SCRIPT_DIR}/souklab.sh"
    log_info "You can now run 'souklab' commands from any directory!"
}

# ------------------------------------------------------------------------------
# COMMAND: RESTART
# ------------------------------------------------------------------------------
cmd_restart() {
    log_info "Restarting Souklab stack..."
    cmd_stop "$@"
    cmd_start "$@"
}

# ------------------------------------------------------------------------------
# COMMAND: STATUS
# ------------------------------------------------------------------------------
cmd_status() {
    check_prerequisites
    printf "\n${BOLD}=== Souklab Container Status ===${RESET}\n"
    docker compose --profile ngrok ps -a
    print_dashboard true
}

# ------------------------------------------------------------------------------
# COMMAND: LOGS
# ------------------------------------------------------------------------------
cmd_logs() {
    check_prerequisites
    docker compose --profile ngrok logs -f "$@"
}

# ------------------------------------------------------------------------------
# HEALTH WAITER
# ------------------------------------------------------------------------------
wait_for_health() {
    local with_ngrok="$1"
    local max_wait=120
    local elapsed=0

    # Wait for app container readiness
    printf "  Checking backend readiness "
    while [ $elapsed -lt $max_wait ]; do
        local health_status
        health_status=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' souklab_app 2>/dev/null || echo "starting")

        if [ "${health_status}" = "healthy" ]; then
            printf " ${GREEN}[UP]${RESET}\n"
            break
        fi

        printf "."
        sleep 2
        elapsed=$((elapsed + 2))
    done

    if [ $elapsed -ge $max_wait ]; then
        printf " ${RED}[TIMEOUT]${RESET}\n"
        log_warn "Backend health check timed out. Inspect logs via: ./souklab.sh logs app"
    fi
}

# ------------------------------------------------------------------------------
# DASHBOARD BANNER
# ------------------------------------------------------------------------------
print_dashboard() {
    local with_ngrok="${1:-true}"
    local lan_ip
    lan_ip=$(ip route get 1.1.1.1 2>/dev/null | awk '{print $7}' || hostname -I 2>/dev/null | awk '{print $1}' || echo "localhost")

    local ngrok_url=""
    if [ "${with_ngrok}" = true ]; then
        local tries=0
        while [ $tries -lt 6 ]; do
            ngrok_url=$(curl -s http://localhost:4040/api/tunnels 2>/dev/null | grep -o 'https://[^"]*\.ngrok[^"]*' | head -n 1 || true)
            if [ -n "${ngrok_url}" ]; then
                break
            fi
            sleep 1
            tries=$((tries + 1))
        done
    fi

    printf "\n"
    printf "${BOLD}====================================================================${RESET}\n"
    printf "  ${GREEN}${BOLD}SOUKLAB APPLICATION PLATFORM${RESET}\n"
    printf "${BOLD}====================================================================${RESET}\n"
    printf "  ${CYAN}• Local Gateway (Nginx):${RESET}      http://localhost\n"
    printf "  ${CYAN}• Local Network (LAN):${RESET}        http://%s\n" "${lan_ip}"
    printf "  ${CYAN}• Next.js Frontend Direct:${RESET}    http://localhost:3000\n"
    printf "  ${CYAN}• Spring Boot Backend API:${RESET}    http://localhost:8080/api/v1\n"
    printf "  ${CYAN}• OpenAPI / Swagger UI:${RESET}       http://localhost/swagger-ui/index.html\n"
    printf "  ${CYAN}• Mailpit Email Sandbox:${RESET}      http://localhost:8025\n"
    printf "  ${CYAN}• MinIO Object Storage:${RESET}       http://localhost:9001 (Console)\n"

    if [ -n "${ngrok_url}" ]; then
        printf "\n"
        printf "  ${GREEN}${BOLD}• Public Ngrok Tunnel:${RESET}        ${BOLD}%s${RESET}\n" "${ngrok_url}"
        printf "  ${CYAN}• Ngrok Web Inspector:${RESET}        http://localhost:4040\n"
    elif [ "${with_ngrok}" = true ]; then
        printf "\n"
        printf "  ${YELLOW}• Ngrok Tunnel:${RESET}               Starting or requires NGROK_AUTHTOKEN (check http://localhost:4040)\n"
    fi

    printf "${BOLD}====================================================================${RESET}\n"
    printf "  Commands:  ${DIM}souklab stop${RESET}    - Stop all containers in-place\n"
    printf "             ${DIM}souklab down${RESET}    - Tear down stack (ephemeral containers & network)\n"
    printf "             ${DIM}souklab restart${RESET} - Restart all containers\n"
    printf "             ${DIM}souklab logs [app|frontend|nginx|ngrok]${RESET} - Tail logs\n"
    printf "             ${DIM}souklab status${RESET}  - Check stack status\n"
    printf "${BOLD}====================================================================${RESET}\n\n"
}

# ------------------------------------------------------------------------------
# USAGE HELP
# ------------------------------------------------------------------------------
usage() {
    cat <<EOF
Souklab Management CLI

Usage:
  souklab [command] [options]

Commands:
  start | up [options]    Start all services and ngrok tunnel (default action)
  stop [options]          Stop all running containers (in-place pause)
  down [options]          Tear down stack (removes containers & networks cleanly)
  restart [options]       Restart all services
  status | ps             Display running container status and public tunnel URL
  logs [service]          Stream logs (e.g. souklab logs app)
  install                 Install 'souklab' CLI globally into ~/.local/bin
  help | -h | --help      Show this help message

Options for 'start' / 'restart':
  --build, -b             Rebuild Docker images before starting
  --no-ngrok              Start services without launching the ngrok public tunnel
  --logs, -l              Follow container logs after start

Options for 'stop':
  --down, -d              Tear down containers and network (alias for 'souklab down')
  --clean, -v             Tear down containers and wipe persistent database volumes

Options for 'down':
  --clean, -v             Remove persistent database/storage volumes (fresh wipe)

Examples:
  souklab                 # Starts the complete stack with ngrok tunnel
  souklab start -b        # Rebuilds and starts all services
  souklab stop            # Pauses all running containers in-place
  souklab down            # Tears down containers and network cleanly
  souklab logs app        # Follows Spring Boot backend logs
EOF
}

# ------------------------------------------------------------------------------
# MAIN ENTRYPOINT
# ------------------------------------------------------------------------------
main() {
    local cmd="${1:-start}"
    if [ $# -gt 0 ]; then
        shift
    fi

    case "${cmd}" in
        start|up)
            cmd_start "$@"
            ;;
        stop)
            cmd_stop "$@"
            ;;
        down)
            cmd_down "$@"
            ;;
        restart)
            cmd_restart "$@"
            ;;
        status|ps)
            cmd_status "$@"
            ;;
        logs)
            cmd_logs "$@"
            ;;
        install)
            cmd_install "$@"
            ;;
        help|-h|--help)
            usage
            ;;
        *)
            log_error "Unknown command: ${cmd}"
            usage
            exit 1
            ;;
    esac
}

main "$@"
