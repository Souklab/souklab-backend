#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

# JaCoCo 0.8.12 supports the Java 17-21 class-file range; prefer the installed
# Java 21 runtime for reproducible coverage and to avoid instrumenting Java 26.
if [ -x /usr/lib/jvm/java-21-openjdk/bin/java ]; then
  export JAVA_HOME="/usr/lib/jvm/java-21-openjdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

export DB_NAME="${DB_NAME:-souklab_test}"
export DB_USERNAME="${DB_USERNAME:-souklab_test}"
export DB_PASSWORD="${DB_PASSWORD:-souklab_test_password}"
export DB_ROOT_PASSWORD="${DB_ROOT_PASSWORD:-souklab_root_password}"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-souklab-verification}"
export RABBITMQ_DEFAULT_USER="${RABBITMQ_DEFAULT_USER:-souklab_test}"
export RABBITMQ_DEFAULT_PASS="${RABBITMQ_DEFAULT_PASS:-souklab_test_password}"
export ANALYTICS_RABBIT_ENABLED="${ANALYTICS_RABBIT_ENABLED:-true}"
export ANALYTICS_RABBIT_HOST="${ANALYTICS_RABBIT_HOST:-localhost}"
export ANALYTICS_RABBIT_PORT="${ANALYTICS_RABBIT_PORT:-5672}"
export ANALYTICS_RABBIT_USERNAME="${ANALYTICS_RABBIT_USERNAME:-$RABBITMQ_DEFAULT_USER}"
export ANALYTICS_RABBIT_PASSWORD="${ANALYTICS_RABBIT_PASSWORD:-$RABBITMQ_DEFAULT_PASS}"
export REDIS_PASSWORD="${REDIS_PASSWORD:-souklab_test_redis_password}"
export REDIS_HOST="${REDIS_HOST:-localhost}"
export REDIS_PORT="${REDIS_PORT:-6379}"
export APP_RATE_LIMIT_BACKEND="${APP_RATE_LIMIT_BACKEND:-redis}"
export APP_RATE_LIMIT_KEY_PREFIX="${APP_RATE_LIMIT_KEY_PREFIX:-souklab:ci}"
export MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}"
export MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-minioadmin_secret}"
export MARIADB_HOST_PORT="${MARIADB_HOST_PORT:-3306}"
if ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq '(^|:)3306$'; then
  export MARIADB_HOST_PORT="${MARIADB_FALLBACK_PORT:-3307}"
fi
export FLYWAY_MARIADB_HOST_PORT="${FLYWAY_MARIADB_HOST_PORT:-3308}"
export DB_URL="${DB_URL:-jdbc:mariadb://localhost:${MARIADB_HOST_PORT}/${DB_NAME}?createDatabaseIfNotExist=true&useSsl=false&serverTimezone=UTC}"
export JPA_DATABASE_PLATFORM="${JPA_DATABASE_PLATFORM:-org.hibernate.dialect.MariaDBDialect}"
export JPA_HIBERNATE_DDL_AUTO="${JPA_HIBERNATE_DDL_AUTO:-create-drop}"
export JPA_SHOW_SQL="${JPA_SHOW_SQL:-false}"
export JPA_FORMAT_SQL="${JPA_FORMAT_SQL:-false}"
export JPA_DEFAULT_BATCH_FETCH_SIZE="${JPA_DEFAULT_BATCH_FETCH_SIZE:-50}"
export DB_POOL_MAX_SIZE="${DB_POOL_MAX_SIZE:-20}"
export DB_POOL_MIN_IDLE="${DB_POOL_MIN_IDLE:-5}"
export DB_POOL_CONNECTION_TIMEOUT="${DB_POOL_CONNECTION_TIMEOUT:-30000}"
export DB_POOL_VALIDATION_TIMEOUT="${DB_POOL_VALIDATION_TIMEOUT:-5000}"
export DB_POOL_LEAK_DETECTION_THRESHOLD="${DB_POOL_LEAK_DETECTION_THRESHOLD:-0}"
export FLYWAY_ENABLED="${FLYWAY_ENABLED:-false}"
export PORT="${PORT:-8080}"
export APP_JWT_SECRET="${APP_JWT_SECRET:-local-test-secret-must-be-at-least-32-characters}"
export APP_JWT_ACCESS_EXP="${APP_JWT_ACCESS_EXP:-3600000}"
export APP_JWT_REFRESH_EXP="${APP_JWT_REFRESH_EXP:-86400000}"
export APP_STORAGE_UPLOADS="${APP_STORAGE_UPLOADS:-storage/uploads}"
export APP_STORAGE_THUMBNAILS="${APP_STORAGE_THUMBNAILS:-storage/thumbnails}"
export APP_STORAGE_INDEXES="${APP_STORAGE_INDEXES:-storage/indexes}"
export APP_STORAGE_FILE_SERVING_PREFIX="${APP_STORAGE_FILE_SERVING_PREFIX:-/api/v1/files/}"
export APP_LOGGING_LEVEL_ROOT="${APP_LOGGING_LEVEL_ROOT:-INFO}"
export APP_LOGGING_LEVEL_APP="${APP_LOGGING_LEVEL_APP:-INFO}"
export APP_LOGGING_LEVEL_SEARCH="${APP_LOGGING_LEVEL_SEARCH:-INFO}"
export APP_LOGGING_LEVEL_SEARCH_REQUESTS="${APP_LOGGING_LEVEL_SEARCH_REQUESTS:-WARN}"
export APP_LOGGING_LEVEL_ELASTICSEARCH="${APP_LOGGING_LEVEL_ELASTICSEARCH:-INFO}"
export ARTISAN_GALLERY_ALLOWED_MIME_TYPES="${ARTISAN_GALLERY_ALLOWED_MIME_TYPES:-image/jpeg,image/png,image/webp}"
export ARTISAN_CERTIFICATION_ALLOWED_MIME_TYPES="${ARTISAN_CERTIFICATION_ALLOWED_MIME_TYPES:-application/pdf,image/jpeg,image/png}"
export FORMATION_THUMBNAIL_ALLOWED_MIME_TYPES="${FORMATION_THUMBNAIL_ALLOWED_MIME_TYPES:-image/jpeg,image/png,image/webp}"
export FORMATION_FILE_ALLOWED_MIME_TYPES="${FORMATION_FILE_ALLOWED_MIME_TYPES:-application/pdf,image/jpeg,image/png}"
export FEED_ALLOWED_IMAGE_MIME_TYPES="${FEED_ALLOWED_IMAGE_MIME_TYPES:-image/jpeg,image/png,image/webp}"
export STORAGE_ALLOWED_MIME_TYPES="${STORAGE_ALLOWED_MIME_TYPES:-image/jpeg,image/png,application/pdf}"
export AVATAR_ALLOWED_MIME_TYPES="${AVATAR_ALLOWED_MIME_TYPES:-image/jpeg,image/png,image/webp}"
export STORAGE_PROVIDER="${STORAGE_PROVIDER:-in-memory}"
export APP_ADMIN_DEFAULT_EMAIL="${APP_ADMIN_DEFAULT_EMAIL:-admin@souklab.test}"
export APP_ADMIN_DEFAULT_PASSWORD="${APP_ADMIN_DEFAULT_PASSWORD:-local-test-password}"
export APP_CORS_ALLOWED_ORIGINS="${APP_CORS_ALLOWED_ORIGINS:-http://localhost:3000}"
export SMTP_CONNECTION_TIMEOUT="${SMTP_CONNECTION_TIMEOUT:-5000}"
export SMTP_READ_TIMEOUT="${SMTP_READ_TIMEOUT:-10000}"
export SMTP_WRITE_TIMEOUT="${SMTP_WRITE_TIMEOUT:-10000}"
# Never inherit developer or production mail credentials during local
# verification. A local debugging SMTP server accepts messages without
# authenticating or delivering them to an external provider.
export SMTP_HOST="127.0.0.1"
export SMTP_PORT="1025"
export SMTP_USERNAME=""
export SMTP_PASSWORD=""
export SMTP_AUTH="false"
export SMTP_STARTTLS="false"
export APP_EMAIL_USE_SMTP="true"
export MAILERSEND_CONNECTION_TIMEOUT="${MAILERSEND_CONNECTION_TIMEOUT:-5s}"
export MAILERSEND_READ_TIMEOUT="${MAILERSEND_READ_TIMEOUT:-10s}"
export RELAY_HOST="${RELAY_HOST:-localhost}"
export RELAY_PORT="${RELAY_PORT:-61613}"
export RELAY_CLIENT_LOGIN="${RELAY_CLIENT_LOGIN:-souklab_test}"
export RELAY_CLIENT_PASSCODE="${RELAY_CLIENT_PASSCODE:-souklab_test_password}"
export RELAY_SYSTEM_LOGIN="${RELAY_SYSTEM_LOGIN:-souklab_test}"
export RELAY_SYSTEM_PASSCODE="${RELAY_SYSTEM_PASSCODE:-souklab_test_password}"
export GOOGLE_OAUTH_CLIENT_ID="${GOOGLE_OAUTH_CLIENT_ID:-local-test-client}"
export GOOGLE_OAUTH_CLIENT_SECRET="${GOOGLE_OAUTH_CLIENT_SECRET:-local-test-secret}"
export GOOGLE_OAUTH_REDIRECT_URI="${GOOGLE_OAUTH_REDIRECT_URI:-http://localhost:8080/login/oauth2/code/google}"
# The S3 verification class explicitly selects MinIO and must be able to test
# missing-property fail-fast behavior without inherited environment values.
export STORAGE_VIRUS_SCAN_ENABLED="true"
export STORAGE_VIRUS_SCAN_HOST="localhost"
export STORAGE_VIRUS_SCAN_PORT="3310"
export STORAGE_VIRUS_SCAN_CONNECTION_TIMEOUT="${STORAGE_VIRUS_SCAN_CONNECTION_TIMEOUT:-2000ms}"
export STORAGE_VIRUS_SCAN_READ_TIMEOUT="${STORAGE_VIRUS_SCAN_READ_TIMEOUT:-10000ms}"
export STORAGE_VIRUS_SCAN_FAIL_OPEN="false"
export HIBERNATE_SEARCH_ENABLED="true"
export ELASTICSEARCH_URIS="http://localhost:9200"
export ELASTICSEARCH_VERSION="8.15"
export ELASTICSEARCH_VERSION_CHECK_ENABLED="false"
export HIBERNATE_SEARCH_SCHEMA_MANAGEMENT="create-or-update"
export SEARCH_SYNC_ON_STARTUP="true"
export PHASE10_MARIADB_INTEGRATION="${PHASE10_MARIADB_INTEGRATION:-true}"
export PHASE10_RABBIT_INTEGRATION="${PHASE10_RABBIT_INTEGRATION:-true}"
export PHASE10_REDIS_INTEGRATION="${PHASE10_REDIS_INTEGRATION:-true}"
export PHASE9_MARIADB_INTEGRATION="${PHASE9_MARIADB_INTEGRATION:-true}"
export PHASE10_ENDPOINT_SECURITY="${PHASE10_ENDPOINT_SECURITY:-true}"
# Bound Spring's broker-relay lifecycle wait so a successful test run cannot
# remain attached forever when RabbitMQ closes a relay session slowly.
export SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE="${SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE:-10s}"

# Supply remaining application properties from the checked-in example config
# when the caller has not already provided them. Never evaluate the example as
# shell code; its values are plain text, and explicit verification settings
# above take precedence.
while IFS='=' read -r verification_key verification_value; do
  [[ "$verification_key" =~ ^[A-Z][A-Z0-9_]*$ ]] || continue
  [[ -v $verification_key ]] && continue
  case "$verification_value" in
    \"*\") verification_value="${verification_value:1:${#verification_value}-2}" ;;
    \'*\') verification_value="${verification_value:1:${#verification_value}-2}" ;;
  esac
  export "$verification_key=$verification_value"
done < .env.example

smtp_sink_pid=""
if command -v python3 >/dev/null 2>&1; then
  python3 -u - <<'PY' >/tmp/souklab-verification-smtp.log 2>&1 &
import socketserver

class SmtpSink(socketserver.StreamRequestHandler):
    def reply(self, value):
        self.wfile.write((value + "\r\n").encode())
        self.wfile.flush()

    def handle(self):
        self.reply("220 souklab-local-smtp ESMTP")
        while True:
            line = self.rfile.readline()
            if not line:
                return
            command = line.decode(errors="replace").strip().upper()
            if command.startswith(("EHLO", "HELO")):
                self.reply("250-souklab-local-smtp")
                self.reply("250 OK")
            elif command.startswith("MAIL FROM") or command.startswith("RCPT TO"):
                self.reply("250 OK")
            elif command == "DATA":
                self.reply("354 End data with <CR><LF>.<CR><LF>")
                while self.rfile.readline().strip() != b".":
                    pass
                self.reply("250 OK")
            elif command == "QUIT":
                self.reply("221 Bye")
                return
            else:
                self.reply("250 OK")

class ReusableServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True

with ReusableServer(("127.0.0.1", 1025), SmtpSink) as server:
    server.serve_forever()
PY
  smtp_sink_pid="$!"
else
  echo "python3 is required to start the local SMTP sink" >&2
  exit 1
fi

cleanup() {
  if [ -n "$smtp_sink_pid" ]; then
    kill "$smtp_sink_pid" 2>/dev/null || true
    wait "$smtp_sink_pid" 2>/dev/null || true
  fi
  if [ "${KEEP_LOCAL_SERVICES:-false}" = "true" ]; then
    echo "Keeping verification services running because KEEP_LOCAL_SERVICES=true"
    return
  fi
  # This project name is dedicated to verification; remove only its containers,
  # network, and test volumes so the next run starts from a fresh schema.
  docker compose down --volumes --remove-orphans
}
trap cleanup EXIT

docker compose up -d mariadb mariadb-flyway rabbitmq minio elasticsearch clamav redis
docker compose ps

for service in mariadb mariadb-flyway rabbitmq minio elasticsearch clamav redis; do
  printf 'Waiting for %s...\n' "$service"
  deadline=$(( $(date +%s) + 300 ))
  until [ "$(docker compose ps --format '{{.Service}} {{.Health}}' | awk -v s="$service" '$1 == s {print $2}')" = "healthy" ]; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      docker compose ps
      docker compose logs --tail=80 "$service"
      exit 1
    fi
    sleep 3
  done
done

./scripts/verify-flyway.sh
./mvnw clean test ${MAVEN_TEST_ARGS:-}
