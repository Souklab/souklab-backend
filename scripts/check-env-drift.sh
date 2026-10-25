#!/usr/bin/env bash
set -euo pipefail

python3 - <<'EOF'
import os
import re
import sys

properties_path = "src/main/resources/application.yml"
prod_properties_path = "src/main/resources/application-prod.yml"

env_targets = [
    ".env.example",
    ".env.docker.example",
    "deploy/.env.production.example"
]

# Check properties files
for p in [properties_path, prod_properties_path]:
    if not os.path.isfile(p):
        print(f"Error: {p} not found", file=sys.stderr)
        sys.exit(1)

with open(properties_path, "r", encoding="utf-8") as f:
    properties_content = f.read()

with open(prod_properties_path, "r", encoding="utf-8") as f:
    prod_properties_content = f.read()

# Variables without default values in application.properties are strictly required
required_vars = set(re.findall(r"\$\{([A-Za-z0-9_]+)\}", properties_content))
# All variables referenced in application properties (with or without default)
app_property_vars = set(re.findall(r"\$\{([A-Za-z0-9_]+)[:\}]", properties_content)) | \
                    set(re.findall(r"\$\{([A-Za-z0-9_]+)[:\}]", prod_properties_content))

# Known infrastructure, compose, CI, and deployment environment variables
INFRA_VARS = {
    "COMPOSE_PROJECT_NAME",
    "MARIADB_HOST_PORT",
    "DB_NAME",
    "DB_ROOT_PASSWORD",
    "MINIO_ROOT_USER",
    "MINIO_ROOT_PASSWORD",
    "ELASTICSEARCH_HOST",
    "PHASE9_MARIADB_INTEGRATION",
    "APP_IMAGE",
    "PUBLIC_DOMAIN",
    "GRAFANA_ADMIN_PASSWORD",
    "BACKUP_METRICS_PUSHGATEWAY",
    "REDIS_CONNECTION_TIMEOUT",
    "REDIS_COMMAND_TIMEOUT",
    "RABBITMQ_DEFAULT_USER",
    "RABBITMQ_DEFAULT_PASS"
}

allowed_vars = app_property_vars | INFRA_VARS

has_drift = False

# Read variables defined in each environment file
defined_by_file = {}
for env_file in env_targets:
    if not os.path.isfile(env_file):
        print(f"Error: Target env file {env_file} does not exist", file=sys.stderr)
        has_drift = True
        continue

    defined = set()
    with open(env_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            match = re.match(r"^([A-Za-z0-9_]+)=", line)
            if match:
                defined.add(match.group(1))
    defined_by_file[env_file] = defined

# 1. Verify each file defines all strictly required application properties
for env_file, defined in defined_by_file.items():
    missing_required = sorted(list(required_vars - defined))
    if missing_required:
        has_drift = True
        print(f"Drift in {env_file}: Missing {len(missing_required)} strictly required variables:", file=sys.stderr)
        for var in missing_required:
            print(f"  - {var}", file=sys.stderr)

# 2. Check for unrecognized / misspelled variables against allowed set
for env_file, defined in defined_by_file.items():
    unrecognized = sorted(list(defined - allowed_vars))
    if unrecognized:
        has_drift = True
        print(f"Drift in {env_file}: Contains {len(unrecognized)} unrecognized variables:", file=sys.stderr)
        for var in unrecognized:
            print(f"  - {var}", file=sys.stderr)

# 3. Exact bidirectional parity check across all environment files
baseline_file = env_targets[0]
baseline_vars = defined_by_file.get(baseline_file, set())

for env_file in env_targets:
    defined = defined_by_file.get(env_file, set())
    missing_from_file = sorted(list(baseline_vars - defined))
    extra_in_file = sorted(list(defined - baseline_vars))

    if missing_from_file or extra_in_file:
        has_drift = True
        print(f"Parity mismatch in {env_file} compared to {baseline_file}:", file=sys.stderr)
        if missing_from_file:
            print(f"  Missing ({len(missing_from_file)}):", file=sys.stderr)
            for var in missing_from_file:
                print(f"    - {var}", file=sys.stderr)
        if extra_in_file:
            print(f"  Extra ({len(extra_in_file)}):", file=sys.stderr)
            for var in extra_in_file:
                print(f"    - {var}", file=sys.stderr)
    else:
        print(f"OK: {env_file} defines all {len(defined)} variables with exact parity.")

if has_drift:
    print("\nEnvironment drift check FAILED.", file=sys.stderr)
    sys.exit(1)

print("\nEnvironment drift check passed successfully. Exact bidirectional parity verified across all files.")
EOF
