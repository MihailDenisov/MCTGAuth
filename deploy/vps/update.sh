#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_root
[[ $# -eq 1 ]] || die "Usage: $0 <installDist-directory-or-tar.gz>"
require_commands awk bash basename chmod chown cp curl date dirname find flock grep install ln mkdir \
    mktemp mv readlink realpath sleep sqlite3 systemctl tar
require_java21
acquire_management_lock

[[ ! -L "$1" ]] || die "Deployment package path must not be a symbolic link."
package="$(realpath -e -- "$1")" || die "Deployment package does not exist: $1"
[[ -d "${package}" || -f "${package}" ]] || die "Deployment package must be a directory or .tar.gz file."

temporary="$(mktemp -d)"
cleanup_temporary() {
    if [[ -d "${temporary}" && "${temporary}" == /tmp/* ]]; then
        find "${temporary}" -xdev -depth -delete
    fi
}
trap cleanup_temporary EXIT
staging="${temporary}/package"
mkdir -p "${staging}"

if [[ -d "${package}" ]]; then
    [[ -z "$(find "${package}" ! -type f ! -type d -print -quit)" ]] \
        || die "Deployment directory may contain only regular files and directories."
    cp -a --no-dereference -- "${package}/." "${staging}/"
elif [[ "${package}" == *.tar.gz ]]; then
    while IFS= read -r entry; do
        validate_archive_entry "${entry}"
    done < <(tar -tzf "${package}")
    while IFS= read -r permissions remainder; do
        case "${permissions:0:1}" in
            -|d) ;;
            *) die "Deployment archive may contain only regular files and directories." ;;
        esac
    done < <(tar -tvzf "${package}")
    tar --extract --gzip --file "${package}" --directory "${staging}" \
        --no-same-owner --no-same-permissions --delay-directory-restore
else
    die "Unsupported package format. Supply installDist or a .tar.gz deployment archive."
fi

[[ -z "$(find "${staging}" ! -type f ! -type d -print -quit)" ]] \
    || die "Extracted package may contain only regular files and directories."
[[ -f "${staging}/bin/auth-backend" && -d "${staging}/lib" \
    && -f "${staging}/release.properties" ]] \
    || die "Package must contain bin/auth-backend, lib/, and release.properties at its root."
compgen -G "${staging}/lib/auth-backend-*.jar" >/dev/null \
    || die "Package does not contain the backend application JAR."

version="$(property_value "${staging}/release.properties" application.version)" \
    || die "Cannot read application.version."
commit="$(property_value "${staging}/release.properties" git.commit || true)"
version="${version//[^A-Za-z0-9._-]/}"
commit="${commit//[^A-Za-z0-9._-]/}"
[[ -n "${version}" ]] || die "Application version is invalid."
suffix="-v${version}"
if [[ -n "${commit}" && "${commit}" != "unknown" ]]; then suffix+="-${commit:0:12}"; fi
release_name="$(date -u +%Y-%m-%d_%H%M%S)${suffix}"
new_release="$(release_path "${release_name}")"
[[ ! -e "${new_release}" ]] || die "Release already exists: ${release_name}"

database_backup="$(bash "${SCRIPT_DIR}/backup.sh" --quiet)"
log_info "Database backup created: ${database_backup}"

install -d -o root -g root -m 0755 "${new_release}"
cp -a --no-dereference -- "${staging}/." "${new_release}/"
chown -R root:root "${new_release}"
find "${new_release}" -type d -exec chmod 0755 {} +
find "${new_release}" -type f -exec chmod 0644 {} +
chmod 0755 "${new_release}/bin/auth-backend"
validate_release "${new_release}"

database_schema="$(schema_version)"
min_schema="$(property_value "${new_release}/release.properties" min.schema.version || printf '0')"
max_schema="$(property_value "${new_release}/release.properties" max.schema.version || printf '0')"
[[ "${min_schema}" =~ ^[0-9]+$ && "${max_schema}" =~ ^[0-9]+$ ]] \
    || die "Release schema compatibility metadata is invalid."
((database_schema >= min_schema && database_schema <= max_schema)) \
    || die "Database schema ${database_schema} is outside release range ${min_schema}-${max_schema}."

old_release="$(current_release 2>/dev/null || true)"
old_path=""
if [[ -n "${old_release}" ]]; then old_path="${RELEASES_DIR}/${old_release}"; fi

systemctl stop "${SERVICE_NAME}" 2>/dev/null || true
atomic_switch_release "${new_release}"

activation_failed=false
if ! systemctl start "${SERVICE_NAME}"; then
    activation_failed=true
elif ! wait_for_health; then
    activation_failed=true
fi

if [[ "${activation_failed}" == true ]]; then
    log_error "New release failed its health check."
    systemctl stop "${SERVICE_NAME}" 2>/dev/null || true
    if [[ -n "${old_path}" && -d "${old_path}" ]]; then
        log_info "Rolling back to previous release ${old_release}."
        atomic_switch_release "${old_path}"
        if systemctl start "${SERVICE_NAME}" && wait_for_health; then
            record_deployment update "${old_release}" "${release_name}" rolled_back
            log_info "Previous release restored."
        else
            record_deployment update "${old_release}" "${release_name}" rollback_failed
            die "Rollback also failed. Inspect systemctl status and journal logs immediately."
        fi
    else
        record_deployment update none "${release_name}" failed_no_previous
        die "Initial release failed and no previous release exists."
    fi
    exit 1
fi

record_deployment update "${old_release:-none}" "${release_name}" success
log_info "Release installed: ${release_name}"
log_info "Health check passed."
log_info "Release activated."
