#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_root
require_commands basename dirname find flock readlink realpath rm sort
acquire_management_lock

KEEP_RELEASES="${KEEP_RELEASES:-5}"
KEEP_BACKUPS="${KEEP_BACKUPS:-10}"
[[ "${KEEP_RELEASES}" =~ ^[0-9]+$ && "${KEEP_RELEASES}" -ge 2 ]] \
    || die "KEEP_RELEASES must be an integer of at least 2."
[[ "${KEEP_BACKUPS}" =~ ^[0-9]+$ && "${KEEP_BACKUPS}" -ge 1 ]] \
    || die "KEEP_BACKUPS must be a positive integer."

dry_run=false
if [[ "${1:-}" == "--dry-run" ]]; then
    dry_run=true
elif [[ $# -gt 0 ]]; then
    die "Usage: $0 [--dry-run]"
fi

current_name="$(current_release 2>/dev/null || true)"
previous_name="$(previous_release "${current_name}" 2>/dev/null || true)"
index=0
while IFS= read -r name; do
    [[ -n "${name}" ]] || continue
    if [[ ! "${name}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{6}(-[A-Za-z0-9._-]+)?$ ]]; then
        log_warn "Skipping directory with invalid release name: ${name}"
        continue
    fi
    index=$((index + 1))
    if ((index <= KEEP_RELEASES)) || [[ "${name}" == "${current_name}" || "${name}" == "${previous_name}" ]]; then
        continue
    fi
    path="${RELEASES_DIR}/${name}"
    if [[ "${dry_run}" == true ]]; then
        log_info "Would remove release: ${path}"
    else
        log_info "Removing release: ${path}"
        safe_remove_release "${path}"
        record_deployment cleanup "${name}" none release_removed
    fi
done < <(find "${RELEASES_DIR}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -r)

index=0
while IFS= read -r backup; do
    [[ -n "${backup}" ]] || continue
    index=$((index + 1))
    ((index > KEEP_BACKUPS)) || continue
    assert_backup_path "${backup}"
    [[ -f "${backup}" && ! -L "${backup}" ]] \
        || die "Refusing to remove unexpected backup path: ${backup}"
    if [[ "${dry_run}" == true ]]; then
        log_info "Would remove backup: ${backup}"
    else
        log_info "Removing backup: ${backup}"
        rm -f -- "${backup}"
    fi
done < <(find "${BACKUP_DIR}" -maxdepth 1 -type f -name 'auth-backend-*.db' -printf '%p\n' | sort -r)

log_info "Cleanup complete. Active and immediately previous releases were protected."
