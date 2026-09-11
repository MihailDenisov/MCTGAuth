#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_root
require_command systemctl
systemctl start "${SERVICE_NAME}"
log_info "Started ${SERVICE_NAME}."
