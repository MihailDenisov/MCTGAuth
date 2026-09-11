#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_root
require_commands systemctl curl grep
systemctl restart "${SERVICE_NAME}"
if wait_for_health; then
    log_info "Restarted ${SERVICE_NAME}; health check passed."
else
    die "Service restarted but failed its health check."
fi
