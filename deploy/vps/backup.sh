#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_root
require_commands sqlite3 date install realpath chmod chown

quiet=false
if [[ "${1:-}" == "--quiet" ]]; then
    quiet=true
elif [[ $# -gt 0 ]]; then
    die "Usage: $0 [--quiet]"
fi

backup="$(create_database_backup)"
if [[ "${quiet}" == false ]]; then
    log_info "Database backup created: ${backup}"
else
    printf '%s\n' "${backup}"
fi
