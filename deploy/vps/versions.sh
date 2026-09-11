#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_commands awk find readlink realpath sort sqlite3

current_name="$(current_release 2>/dev/null || true)"
previous_name="$(previous_release "${current_name}" 2>/dev/null || true)"
current_version="unknown"
if [[ -n "${current_name}" ]]; then
    current_version="$(property_value "${RELEASES_DIR}/${current_name}/release.properties" \
        application.version || printf unknown)"
fi

printf 'Current release: %s\n' "${current_name:-none}"
printf 'Previous release: %s\n' "${previous_name:-none}"
printf 'Application version: %s\n' "${current_version}"
printf 'Database schema version: %s\n\n' "$(schema_version)"
printf 'Installed releases:\n'

found=false
while IFS= read -r name; do
    [[ -n "${name}" ]] || continue
    found=true
    marker=' '
    [[ "${name}" == "${current_name}" ]] && marker='*'
    version="$(property_value "${RELEASES_DIR}/${name}/release.properties" application.version \
        2>/dev/null || printf unknown)"
    printf '%s %s (%s)\n' "${marker}" "${name}" "${version}"
done < <(find "${RELEASES_DIR}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -r)
[[ "${found}" == true ]] || printf '  none\n'
