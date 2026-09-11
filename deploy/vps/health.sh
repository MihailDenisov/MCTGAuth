#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_commands curl awk grep

if health_check; then
    log_info "Health check passed: $(health_url)"
else
    log_error "Health check failed: $(health_url)"
    exit 1
fi
