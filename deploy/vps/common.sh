#!/usr/bin/env bash
set -Eeuo pipefail

APP_NAME="minecraft-auth"
SERVICE_NAME="minecraft-auth.service"
APP_ROOT="/opt/minecraft-auth"
RELEASES_DIR="${APP_ROOT}/releases"
CURRENT_LINK="${APP_ROOT}/current"
TOOLS_DIR="/opt/minecraft-auth-tools"
DATA_DIR="/var/lib/minecraft-auth"
BACKUP_DIR="/var/backups/minecraft-auth"
ENV_FILE="/etc/minecraft-auth.env"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}"
DEPLOY_LOG="/var/log/minecraft-auth-deploy.log"
LOCK_FILE="/run/lock/minecraft-auth-update.lock"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-5}"
HEALTH_RETRIES="${HEALTH_RETRIES:-30}"

log_info() { printf '[INFO] %s\n' "$*"; }
log_warn() { printf '[WARN] %s\n' "$*" >&2; }
log_error() { printf '[ERROR] %s\n' "$*" >&2; }
die() { log_error "$*"; exit 1; }

require_root() {
    [[ "${EUID}" -eq 0 ]] || die "This operation must be run as root."
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command is not installed: $1"
}

require_commands() {
    local command_name
    for command_name in "$@"; do
        require_command "${command_name}"
    done
}

require_java21() {
    require_command java
    local java_version java_major
    java_version="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
    java_major="${java_version%%.*}"
    [[ "${java_major}" == "21" ]] || die "Java 21 is required; detected ${java_version:-unknown}."
}

config_value() {
    local key="$1"
    local fallback="${2:-}"
    local value=""
    if [[ -r "${ENV_FILE}" ]]; then
        value="$(awk -v wanted="${key}" '
            index($0, wanted "=") == 1 {
                sub(/^[^=]*=/, "")
                sub(/\r$/, "")
                print
                exit
            }
        ' "${ENV_FILE}")"
    fi
    if [[ -z "${value}" ]]; then
        printf '%s\n' "${fallback}"
        return
    fi
    if [[ "${value}" == \'*\' && "${value}" == *\' ]]; then
        value="${value:1:${#value}-2}"
    elif [[ "${value}" == \"*\" && "${value}" == *\" ]]; then
        value="${value:1:${#value}-2}"
    fi
    printf '%s\n' "${value}"
}

database_path() {
    local path
    path="$(config_value AUTH_DATABASE_PATH "${DATA_DIR}/auth-backend.db")"
    [[ "${path}" == /* ]] || die "AUTH_DATABASE_PATH must be absolute on the VPS."
    [[ "${path}" != *$'\n'* && "${path}" != *"'"* ]] || die "AUTH_DATABASE_PATH contains unsafe characters."
    local normalized
    normalized="$(realpath -m -- "${path}")"
    [[ "${normalized}" == "${DATA_DIR}/"* ]] || die "Database path must remain under ${DATA_DIR}."
    printf '%s\n' "${normalized}"
}

validate_release_name() {
    [[ "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{6}(-[A-Za-z0-9._-]+)?$ ]] \
        || die "Invalid release name: $1"
}

validate_archive_entry() {
    local entry="${1#./}"
    [[ -n "${entry}" || "$1" == "." || "$1" == "./" ]] || die "Archive contains an empty entry."
    [[ "${entry}" != /* && "${entry}" != ".." && "${entry}" != ../* \
        && "${entry}" != *"/../"* && "${entry}" != *"/.." ]] \
        || die "Archive contains path traversal."
    [[ "${entry}" =~ ^[A-Za-z0-9._/@+=:-]+$ ]] \
        || die "Archive contains an unsupported path name."
}

release_path() {
    validate_release_name "$1"
    printf '%s/%s\n' "${RELEASES_DIR}" "$1"
}

assert_release_path() {
    local path="$1"
    local normalized
    normalized="$(realpath -m -- "${path}")"
    [[ "${normalized}" == "${RELEASES_DIR}/"* ]] || die "Release path escapes ${RELEASES_DIR}: ${path}"
    [[ "$(dirname -- "${normalized}")" == "${RELEASES_DIR}" ]] \
        || die "Release path must be a direct child of ${RELEASES_DIR}: ${path}"
}

assert_backup_path() {
    local path="$1"
    local normalized name
    normalized="$(realpath -m -- "${path}")"
    name="$(basename -- "${normalized}")"
    [[ "$(dirname -- "${normalized}")" == "${BACKUP_DIR}" ]] \
        || die "Backup path must be a direct child of ${BACKUP_DIR}: ${path}"
    [[ "${name}" =~ ^auth-backend-[0-9]{8}T[0-9]{6}Z(-[0-9]+)?\.db$ ]] \
        || die "Backup filename is invalid: ${name}"
}

current_release_path() {
    [[ -L "${CURRENT_LINK}" ]] || return 1
    local target
    target="$(readlink -f -- "${CURRENT_LINK}")" || return 1
    assert_release_path "${target}"
    [[ -d "${target}" ]] || return 1
    printf '%s\n' "${target}"
}

current_release() {
    local path
    path="$(current_release_path)" || return 1
    basename -- "${path}"
}

previous_release() {
    local current="${1:-}"
    local seen_current=false
    local name
    while IFS= read -r name; do
        [[ -n "${name}" ]] || continue
        if [[ -z "${current}" ]]; then
            printf '%s\n' "${name}"
            return 0
        fi
        if [[ "${seen_current}" == true ]]; then
            printf '%s\n' "${name}"
            return 0
        fi
        [[ "${name}" == "${current}" ]] && seen_current=true
    done < <(find "${RELEASES_DIR}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -r)
    return 1
}

property_value() {
    local file="$1"
    local key="$2"
    [[ -r "${file}" && ! -L "${file}" ]] || return 1
    awk -v wanted="${key}" '
        index($0, wanted "=") == 1 {
            sub(/^[^=]*=/, "")
            sub(/\r$/, "")
            print
            exit
        }
    ' "${file}"
}

validate_release() {
    local directory="$1"
    assert_release_path "${directory}"
    [[ -d "${directory}" && ! -L "${directory}" ]] || die "Release directory is invalid: ${directory}"
    [[ -z "$(find "${directory}" -type l -print -quit)" ]] || die "Release must not contain symbolic links."
    [[ -f "${directory}/bin/auth-backend" && ! -L "${directory}/bin/auth-backend" ]] \
        || die "Release is missing bin/auth-backend."
    [[ -x "${directory}/bin/auth-backend" ]] || die "bin/auth-backend is not executable."
    [[ -d "${directory}/lib" && ! -L "${directory}/lib" ]] || die "Release is missing lib/."
    compgen -G "${directory}/lib/auth-backend-*.jar" >/dev/null \
        || die "Release is missing the backend application JAR."
    [[ -r "${directory}/release.properties" && ! -L "${directory}/release.properties" ]] \
        || die "Release is missing readable release.properties."
    [[ -n "$(property_value "${directory}/release.properties" application.version)" ]] \
        || die "release.properties has no application.version."
}

atomic_switch_release() {
    local target="$1"
    assert_release_path "${target}"
    [[ -d "${target}" ]] || die "Cannot activate a missing release: ${target}"
    ln -sfn -- "${target}" "${CURRENT_LINK}.new"
    mv -Tf -- "${CURRENT_LINK}.new" "${CURRENT_LINK}"
}

acquire_management_lock() {
    require_command flock
    exec 9>"${LOCK_FILE}"
    flock -n 9 || die "Another update, rollback, restore, or cleanup operation is active."
}

schema_version() {
    local database
    database="$(database_path)"
    [[ -f "${database}" ]] || { printf '0\n'; return; }
    local table_exists
    table_exists="$(sqlite3 "${database}" \
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='schema_migrations';")"
    if [[ "${table_exists}" == "1" ]]; then
        sqlite3 "${database}" "SELECT COALESCE(MAX(version), 0) FROM schema_migrations;"
    else
        printf '0\n'
    fi
}

health_url() {
    local host port
    host="$(config_value HTTP_BIND_ADDRESS 127.0.0.1)"
    port="$(config_value HTTP_PORT 8080)"
    case "${host}" in
        127.0.0.1|localhost) ;;
        ::1) host="[::1]" ;;
        *) die "HTTP_BIND_ADDRESS must be loopback for local health checks." ;;
    esac
    [[ "${port}" =~ ^[0-9]+$ && "${port}" -ge 1 && "${port}" -le 65535 ]] \
        || die "HTTP_PORT is invalid."
    printf 'http://%s:%s/health\n' "${host}" "${port}"
}

health_check() {
    local response
    response="$(curl --fail --silent --show-error --max-time "${HEALTH_TIMEOUT}" "$(health_url)")" \
        || return 1
    grep -Eq '"status"[[:space:]]*:[[:space:]]*"ok"' <<<"${response}"
}

wait_for_health() {
    local attempt
    for ((attempt = 1; attempt <= HEALTH_RETRIES; attempt++)); do
        if health_check; then
            return 0
        fi
        sleep 1
    done
    return 1
}

create_database_backup() {
    require_commands chmod chown date install realpath sqlite3
    local database backup timestamp integrity
    database="$(database_path)"
    [[ -f "${database}" && ! -L "${database}" ]] || die "Database does not exist: ${database}"
    install -d -o root -g root -m 0750 "${BACKUP_DIR}"
    timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
    backup="${BACKUP_DIR}/auth-backend-${timestamp}.db"
    local sequence=0
    while [[ -e "${backup}" ]]; do
        sequence=$((sequence + 1))
        backup="${BACKUP_DIR}/auth-backend-${timestamp}-${sequence}.db"
    done
    sqlite3 "${database}" ".backup '${backup}'"
    [[ -s "${backup}" && ! -L "${backup}" ]] || die "SQLite backup was not created correctly."
    chmod 0640 "${backup}"
    chown root:root "${backup}"
    integrity="$(sqlite3 "${backup}" "PRAGMA integrity_check;")"
    [[ "${integrity}" == "ok" ]] || die "Backup integrity check failed."
    printf '%s\n' "${backup}"
}

record_deployment() {
    require_commands chmod chown date touch
    local operation="$1" old_release="$2" new_release="$3" result="$4"
    local operator="${SUDO_USER:-${USER:-unknown}}"
    touch "${DEPLOY_LOG}"
    chown root:root "${DEPLOY_LOG}"
    chmod 0640 "${DEPLOY_LOG}"
    printf '%s operation=%s old=%s new=%s result=%s operator=%s\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${operation}" "${old_release:-none}" \
        "${new_release:-none}" "${result}" "${operator//[^A-Za-z0-9._-]/_}" >>"${DEPLOY_LOG}"
}

safe_remove_release() {
    local directory="$1"
    assert_release_path "${directory}"
    [[ -d "${directory}" && ! -L "${directory}" ]] || die "Refusing to remove invalid release: ${directory}"
    find "${directory}" -xdev -depth -delete
}

human_duration() {
    local seconds="$1"
    local days=$((seconds / 86400))
    local hours=$(((seconds % 86400) / 3600))
    local minutes=$(((seconds % 3600) / 60))
    if ((days > 0)); then
        printf '%dd %dh %dm\n' "${days}" "${hours}" "${minutes}"
    elif ((hours > 0)); then
        printf '%dh %dm\n' "${hours}" "${minutes}"
    else
        printf '%dm\n' "${minutes}"
    fi
}
