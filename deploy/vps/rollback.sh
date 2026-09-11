#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_root
require_commands awk basename curl dirname find flock grep ln mv readlink realpath sleep sqlite3 systemctl
acquire_management_lock

force_incompatible=false
confirm=no
target_name=""
for argument in "$@"; do
    case "${argument}" in
        --yes) confirm=yes ;;
        --force-incompatible) force_incompatible=true ;;
        -*) die "Unknown option: ${argument}" ;;
        *) [[ -z "${target_name}" ]] || die "Only one release name may be supplied."; target_name="${argument}" ;;
    esac
done

current_name="$(current_release)" || die "No active release is configured."
if [[ -z "${target_name}" ]]; then
    target_name="$(previous_release "${current_name}")" || die "No older release is available."
fi
target_path="$(release_path "${target_name}")"
[[ -d "${target_path}" ]] || die "Release does not exist: ${target_name}"
validate_release "${target_path}"
[[ "${target_name}" != "${current_name}" ]] || die "The requested release is already active."

database_schema="$(schema_version)"
max_schema="$(property_value "${target_path}/release.properties" max.schema.version || printf '0')"
[[ "${max_schema}" =~ ^[0-9]+$ ]] || die "Target release schema metadata is invalid."
if ((database_schema > max_schema)); then
    log_warn "Database schema ${database_schema} is newer than target maximum ${max_schema}."
    [[ "${force_incompatible}" == true ]] \
        || die "Refusing incompatible rollback. Review migrations; use --force-incompatible only after validation."
fi

log_info "Current release: ${current_name}"
log_info "Rollback target: ${target_name}"
log_warn "Application rollback does not restore the database."
if [[ "${confirm}" != "yes" ]]; then
    read -r -p "Switch application release? Type 'yes' to continue: " answer
    [[ "${answer}" == "yes" ]] || die "Rollback cancelled."
fi

current_path="${RELEASES_DIR}/${current_name}"
systemctl stop "${SERVICE_NAME}"
atomic_switch_release "${target_path}"
if systemctl start "${SERVICE_NAME}" && wait_for_health; then
    record_deployment rollback "${current_name}" "${target_name}" success
    log_info "Rollback completed and health check passed."
    exit 0
fi

log_error "Rollback target failed; restoring ${current_name}."
systemctl stop "${SERVICE_NAME}" 2>/dev/null || true
atomic_switch_release "${current_path}"
if systemctl start "${SERVICE_NAME}" && wait_for_health; then
    record_deployment rollback "${current_name}" "${target_name}" reverted
    die "Rollback target failed; original application release was restored."
fi
record_deployment rollback "${current_name}" "${target_name}" revert_failed
die "Both target and original release failed health checks. Inspect service logs."
