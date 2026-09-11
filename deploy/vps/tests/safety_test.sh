#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

assert_rejected() {
    if ("$@" >/dev/null 2>&1); then
        printf 'Expected rejection: %s\n' "$*" >&2
        exit 1
    fi
}

validate_release_name "2026-09-11_190500-v0.4.0-a1b2c3d"
assert_rejected validate_release_name "../current"
assert_rejected validate_release_name "2026-09-11_190500/nested"

validate_archive_entry "bin/auth-backend"
validate_archive_entry "./lib/auth-backend-0.4.0.jar"
assert_rejected validate_archive_entry "../etc/passwd"
assert_rejected validate_archive_entry "lib/../../etc/passwd"
assert_rejected validate_archive_entry "/absolute/path"
assert_rejected validate_archive_entry "bin/name with spaces"

assert_backup_path "/var/backups/minecraft-auth/auth-backend-20260911T170501Z.db"
assert_backup_path "/var/backups/minecraft-auth/auth-backend-20260911T170501Z-2.db"
assert_rejected assert_backup_path "/var/backups/minecraft-auth/manual.db"
assert_rejected assert_backup_path "/var/backups/minecraft-auth/nested/auth-backend-20260911T170501Z.db"

temporary_env="$(mktemp)"
trap 'rm -f -- "${temporary_env}"' EXIT
printf '%s\n' \
    'HTTP_PORT=9090' \
    'AUTH_API_TOKEN=must-not-be-read-by-this-test' >"${temporary_env}"
ENV_FILE="${temporary_env}"
[[ "$(config_value HTTP_PORT 8080)" == "9090" ]]
[[ "$(config_value MISSING_VALUE fallback)" == "fallback" ]]

printf 'VPS safety helper tests passed.\n'
