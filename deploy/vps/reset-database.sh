#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

RESET_CONFIRMATION="RESET ALL AUTH DATA"
RESET_DATABASE_COMMITTED=false
RESET_TABLES=(
    moderator_pending_actions ticket_decisions ticket_messages ticket_attachments
    problem_ticket_data violation_ticket_data add_account_ticket_data password_changes
    telemetry_events player_sessions server_status audit_log remote_commands registration_ticket_data
    tickets minecraft_accounts sync_events users
)

validate_service_name() {
    [[ "$1" =~ ^[A-Za-z0-9_.@-]+$ ]] || die "MINECRAFT_SERVICE_NAME is invalid."
}

minecraft_cache_path() {
    local game_dir normalized
    game_dir="$(config_value MINECRAFT_GAME_DIR "")"
    [[ -n "${game_dir}" && "${game_dir}" == /* ]] \
        || die "MINECRAFT_GAME_DIR must be an absolute path in ${ENV_FILE}."
    normalized="$(realpath -m -- "${game_dir}")"
    [[ "${normalized}" != "/" ]] || die "MINECRAFT_GAME_DIR cannot be the filesystem root."
    [[ ! -L "${normalized}/mctgauth" ]] || die "Minecraft auth-cache directory must not be a symbolic link."
    printf '%s/mctgauth/accounts.json\n' "${normalized}"
}

print_counts() {
    local database="$1" cache="$2" table count
    printf 'Would delete:\n\n'
    for table in "${RESET_TABLES[@]}"; do
        count="$(sqlite3 "${database}" "SELECT COUNT(*) FROM ${table};")"
        printf '%s: %s\n' "${table}" "${count}"
    done
    if [[ -f "${cache}" && ! -L "${cache}" ]]; then
        count="$(jq -er '.accounts | length' "${cache}")" \
            || die "Minecraft cache is not valid JSON: ${cache}"
    else
        count=0
    fi
    printf '\nMinecraft cached accounts: %s\n' "${count}"
    printf '\nPreserved:\n'
    printf 'schema_migrations\nmoderators\nmoderator_permissions\nSQLite ID sequences\n'
    printf '\nNO CHANGES MADE\n'
}

backup_minecraft_cache() {
    local cache="$1" backup="$2" cache_dir name files=()
    cache_dir="$(dirname -- "${cache}")"
    for name in accounts.json accounts.json.bak accounts.json.tmp telemetry-events.json telemetry-events.json.tmp; do
        [[ ! -L "${cache_dir}/${name}" ]] || die "Minecraft cache files must not be symbolic links."
        if [[ -f "${cache_dir}/${name}" && ! -L "${cache_dir}/${name}" ]]; then
            files+=("${name}")
        fi
    done
    if ((${#files[@]} == 0)); then
        tar -czf "${backup}" --files-from /dev/null
    else
        tar -C "${cache_dir}" -czf "${backup}" -- "${files[@]}"
    fi
    [[ -s "${backup}" && ! -L "${backup}" ]] || die "Minecraft cache backup failed."
    chmod 0600 "${backup}"
    chown root:root "${backup}"
}

reset_database_contents() {
    local database="$1" table remaining foreign_key_errors integrity
    sqlite3 -bail "${database}" <<'SQL'
.bail on
PRAGMA foreign_keys = ON;
PRAGMA secure_delete = ON;
BEGIN IMMEDIATE;
DELETE FROM moderator_pending_actions;
DELETE FROM ticket_decisions;
DELETE FROM ticket_messages;
DELETE FROM ticket_attachments;
DELETE FROM problem_ticket_data;
DELETE FROM violation_ticket_data;
DELETE FROM add_account_ticket_data;
DELETE FROM password_changes;
DELETE FROM telemetry_events;
DELETE FROM player_sessions;
DELETE FROM server_status;
DELETE FROM audit_log;
DELETE FROM remote_commands;
DELETE FROM registration_ticket_data;
DELETE FROM tickets;
DELETE FROM minecraft_accounts;
DELETE FROM sync_events;
DELETE FROM users;
COMMIT;
SQL
    RESET_DATABASE_COMMITTED=true
    sqlite3 -bail "${database}" 'VACUUM; PRAGMA wal_checkpoint(TRUNCATE);'
    foreign_key_errors="$(sqlite3 "${database}" 'PRAGMA foreign_key_check;')"
    [[ -z "${foreign_key_errors}" ]] || die "Database foreign-key check failed after reset."
    integrity="$(sqlite3 "${database}" 'PRAGMA integrity_check;')"
    [[ "${integrity}" == "ok" ]] || die "Database integrity check failed after reset."
    for table in "${RESET_TABLES[@]}"; do
        remaining="$(sqlite3 "${database}" "SELECT COUNT(*) FROM ${table};")"
        [[ "${remaining}" == "0" ]] || die "Reset verification failed for table ${table}."
    done
}

clear_minecraft_cache() {
    local cache="$1" owner="$2" group="$3" cache_dir temporary
    cache_dir="$(dirname -- "${cache}")"
    install -d -o "${owner}" -g "${group}" -m 0750 "${cache_dir}"
    temporary="$(mktemp "${cache_dir}/.accounts-reset.XXXXXX")"
    chmod 0600 "${temporary}"
    chown "${owner}:${group}" "${temporary}"
    printf '%s\n' '{"schemaVersion":1,"accounts":{},"processedEvents":[],"processedRemoteCommands":[]}' \
        >"${temporary}"
    mv -f -- "${temporary}" "${cache}"
    rm -f -- "${cache}.bak" "${cache}.tmp" "${cache_dir}/telemetry-events.json" \
        "${cache_dir}/telemetry-events.json.tmp"
    [[ "$(jq -er '.accounts | length' "${cache}")" == "0" ]] \
        || die "Minecraft cache reset verification failed."
}

main() {
    require_root
    require_commands chmod chown date dirname flock install jq mktemp mv realpath rm sqlite3 systemctl tar
    acquire_management_lock

    local dry_run=false yes=false argument
    for argument in "$@"; do
        case "${argument}" in
            --dry-run) dry_run=true ;;
            --yes) yes=true ;;
            *) die "Usage: $0 [--dry-run] [--yes]" ;;
        esac
    done
    [[ "${dry_run}" != true || "${yes}" != true ]] || die "--dry-run and --yes cannot be combined."

    local database cache minecraft_service minecraft_user minecraft_group
    database="$(database_path)"
    [[ -f "${database}" && ! -L "${database}" ]] || die "Database does not exist: ${database}"
    cache="$(minecraft_cache_path)"
    minecraft_service="$(config_value MINECRAFT_SERVICE_NAME "minecraft.service")"
    minecraft_user="$(config_value MINECRAFT_SERVICE_USER "minecraft")"
    minecraft_group="$(config_value MINECRAFT_SERVICE_GROUP "minecraft")"
    validate_service_name "${minecraft_service}"
    [[ "${minecraft_user}" =~ ^[A-Za-z_][A-Za-z0-9_-]*$ ]] || die "MINECRAFT_SERVICE_USER is invalid."
    [[ "${minecraft_group}" =~ ^[A-Za-z_][A-Za-z0-9_-]*$ ]] || die "MINECRAFT_SERVICE_GROUP is invalid."

    if [[ "${dry_run}" == true ]]; then
        print_counts "${database}" "${cache}"
        return
    fi

    log_warn "WARNING: FULL AUTH DATABASE RESET"
    log_warn "This permanently deletes every Telegram user, registration, Minecraft account,"
    log_warn "nickname, verifier, ticket/message, sync event, remote command, and audit record."
    log_warn "All players must register again and every previously issued password becomes invalid."
    if [[ "${yes}" != true ]]; then
        printf 'Type exactly: %s\n' "${RESET_CONFIRMATION}"
        read -r answer
        [[ "${answer}" == "${RESET_CONFIRMATION}" ]] || die "Reset cancelled."
    fi

    local backend_was_active=false minecraft_was_active=false database_backup cache_backup timestamp
    local reset_finished=false
    cleanup_failed_reset() {
        local status=$?
        if [[ "${reset_finished}" != true && "${RESET_DATABASE_COMMITTED}" != true ]]; then
            [[ "${backend_was_active}" == true ]] && systemctl start "${SERVICE_NAME}" >/dev/null 2>&1 || true
            [[ "${minecraft_was_active}" == true ]] && systemctl start "${minecraft_service}" >/dev/null 2>&1 || true
        elif [[ "${reset_finished}" != true ]]; then
            systemctl stop "${minecraft_service}" >/dev/null 2>&1 || true
            systemctl stop "${SERVICE_NAME}" >/dev/null 2>&1 || true
            log_error "Reset did not finish after backend data changed; services remain stopped for coordinated recovery."
        fi
        return "${status}"
    }
    trap cleanup_failed_reset EXIT
    systemctl is-active --quiet "${SERVICE_NAME}" && backend_was_active=true
    systemctl is-active --quiet "${minecraft_service}" && minecraft_was_active=true
    systemctl stop "${minecraft_service}"
    systemctl stop "${SERVICE_NAME}"

    database_backup="$(create_database_backup)"
    chmod 0600 "${database_backup}"
    timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
    cache_backup="${BACKUP_DIR}/mctgauth-cache-${timestamp}.tar.gz"
    local cache_sequence=0
    while [[ -e "${cache_backup}" ]]; do
        cache_sequence=$((cache_sequence + 1))
        cache_backup="${BACKUP_DIR}/mctgauth-cache-${timestamp}-${cache_sequence}.tar.gz"
    done
    backup_minecraft_cache "${cache}" "${cache_backup}"
    log_info "Backend database backup: ${database_backup}"
    log_info "Minecraft cache backup: ${cache_backup}"

    reset_database_contents "${database}"
    chown minecraft-auth:minecraft-auth "${database}"
    chmod 0640 "${database}"
    local sqlite_sidecar
    for sqlite_sidecar in "${database}-wal" "${database}-shm"; do
        if [[ -f "${sqlite_sidecar}" && ! -L "${sqlite_sidecar}" ]]; then
            chown minecraft-auth:minecraft-auth "${sqlite_sidecar}"
            chmod 0640 "${sqlite_sidecar}"
        fi
    done
    clear_minecraft_cache "${cache}" "${minecraft_user}" "${minecraft_group}"

    if [[ "${backend_was_active}" == true ]]; then
        systemctl start "${SERVICE_NAME}"
        wait_for_health || die "Backend restarted but failed its health check."
    fi
    if [[ "${minecraft_was_active}" == true ]]; then
        systemctl start "${minecraft_service}"
    fi
    record_deployment full_reset "$(current_release 2>/dev/null || printf none)" \
        "schema-$(schema_version)" success
    reset_finished=true
    trap - EXIT
    log_info "Full authentication reset completed. All prior credentials are invalid."
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
