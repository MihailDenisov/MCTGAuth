#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_root
require_commands basename chmod chown curl date dirname flock grep install mktemp mv readlink realpath rm \
    sleep sqlite3 systemctl
acquire_management_lock

yes=false
backup_argument=""
for argument in "$@"; do
    case "${argument}" in
        --yes) yes=true ;;
        -*) die "Unknown option: ${argument}" ;;
        *) [[ -z "${backup_argument}" ]] || die "Only one backup path may be supplied."; backup_argument="${argument}" ;;
    esac
done
[[ -n "${backup_argument}" ]] || die "Usage: $0 [--yes] <backup.db>"

[[ ! -L "${backup_argument}" ]] || die "Backup path must not be a symbolic link."
backup="$(realpath -e -- "${backup_argument}")" || die "Backup does not exist."
assert_backup_path "${backup}"
[[ -f "${backup}" && ! -L "${backup}" ]] || die "Backup must be a regular non-symlink file."
[[ "$(sqlite3 "${backup}" 'PRAGMA integrity_check;')" == "ok" ]] || die "Selected backup failed integrity check."

database="$(database_path)"
log_warn "This replaces ${database}; application release remains unchanged."
if [[ "${yes}" != true ]]; then
    read -r -p "Type 'yes' to restore this database backup: " answer
    [[ "${answer}" == "yes" ]] || die "Restore cancelled."
fi

emergency_backup="$(create_database_backup)"
log_info "Emergency backup created: ${emergency_backup}"

restore_tmp="$(mktemp "${DATA_DIR}/.auth-restore.XXXXXX.db")"
cleanup_restore() {
    if [[ -f "${restore_tmp}" && "${restore_tmp}" == "${DATA_DIR}/.auth-restore."*.db ]]; then
        rm -f -- "${restore_tmp}"
    fi
}
trap cleanup_restore EXIT
sqlite3 "${restore_tmp}" ".restore '${backup}'"
[[ "$(sqlite3 "${restore_tmp}" 'PRAGMA integrity_check;')" == "ok" ]] \
    || die "Restored temporary database failed integrity check."
chown minecraft-auth:minecraft-auth "${restore_tmp}"
chmod 0640 "${restore_tmp}"

systemctl stop "${SERVICE_NAME}"
rm -f -- "${database}-wal" "${database}-shm"
mv -f -- "${restore_tmp}" "${database}"
trap - EXIT

if systemctl start "${SERVICE_NAME}" && wait_for_health; then
    record_deployment restore "$(current_release 2>/dev/null || printf none)" \
        "$(basename -- "${backup}")" success
    log_info "Database restored and health check passed."
else
    record_deployment restore "$(current_release 2>/dev/null || printf none)" \
        "$(basename -- "${backup}")" health_failed
    log_error "Database was restored, but the backend failed its health check."
    log_error "Emergency backup: ${emergency_backup}"
    exit 1
fi
