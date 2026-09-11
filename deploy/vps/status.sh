#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_commands awk cut curl du find grep readlink realpath sqlite3 systemctl wc

service_state="$(systemctl is-active "${SERVICE_NAME}" 2>/dev/null || true)"
release="$(current_release 2>/dev/null || printf 'none')"
application_version="unknown"
if [[ "${release}" != "none" ]]; then
    application_version="$(property_value "${RELEASES_DIR}/${release}/release.properties" application.version || printf 'unknown')"
fi
database="$(database_path)"
schema="$(schema_version)"
if health_check; then health="OK"; else health="FAILED"; fi

uptime="n/a"
active_monotonic="$(systemctl show "${SERVICE_NAME}" -p ActiveEnterTimestampMonotonic --value 2>/dev/null || true)"
system_uptime="$(cut -d. -f1 /proc/uptime 2>/dev/null || true)"
if [[ "${active_monotonic}" =~ ^[0-9]+$ && "${system_uptime}" =~ ^[0-9]+$ && "${active_monotonic}" -gt 0 ]]; then
    active_seconds=$((active_monotonic / 1000000))
    if ((system_uptime >= active_seconds)); then
        uptime="$(human_duration $((system_uptime - active_seconds)))"
    fi
fi

disk_usage="$(du -sch "${APP_ROOT}" "${DATA_DIR}" "${BACKUP_DIR}" 2>/dev/null | awk '/total$/ {print $1}')"
backup_count="$(find "${BACKUP_DIR}" -maxdepth 1 -type f -name 'auth-backend-*.db' | wc -l)"

printf '%s\n\n' 'Minecraft Auth Backend'
printf '%-16s %s\n' 'Service:' "${service_state:-unknown}"
printf '%-16s %s\n' 'Release:' "${release}"
printf '%-16s %s\n' 'Application:' "${application_version}"
printf '%-16s %s\n' 'Database:' "${database}"
printf '%-16s %s\n' 'Schema version:' "${schema}"
printf '%-16s %s\n' 'Health:' "${health}"
printf '%-16s %s\n' 'Uptime:' "${uptime}"
printf '%-16s %s\n' 'Disk usage:' "${disk_usage:-unknown}"
printf '%-16s %s\n' 'Backups:' "${backup_count}"
