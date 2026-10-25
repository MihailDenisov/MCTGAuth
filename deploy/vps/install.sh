#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_root
require_commands awk basename chmod chown getent install realpath sqlite3 systemctl touch useradd
require_java21

if [[ $# -gt 1 ]]; then
    die "Usage: $0 [installDist-directory-or-tar.gz]"
fi

if ! getent passwd minecraft-auth >/dev/null; then
    useradd --system --home-dir "${DATA_DIR}" --shell /usr/sbin/nologin minecraft-auth
    log_info "Created system user minecraft-auth."
fi

install -d -o root -g root -m 0755 "${APP_ROOT}" "${RELEASES_DIR}" "${APP_ROOT}/shared"
install -d -o minecraft-auth -g minecraft-auth -m 0750 "${DATA_DIR}"
install -d -o root -g root -m 0750 "${BACKUP_DIR}"
install -d -o root -g root -m 0755 "${TOOLS_DIR}"

for script in "${SCRIPT_DIR}"/*.sh; do
    install -o root -g root -m 0755 "${script}" "${TOOLS_DIR}/$(basename -- "${script}")"
done
install -o root -g root -m 0644 "${SCRIPT_DIR}/minecraft-auth.service" "${SERVICE_FILE}"
if [[ ! -e "${TELEGRAM_FEATURE_CONFIG_FILE}" ]]; then
    install -o root -g minecraft-auth -m 0640 "${SCRIPT_DIR}/telegram-features.example.yaml" \
        "${TELEGRAM_FEATURE_CONFIG_FILE}"
fi

if [[ ! -e "${ENV_FILE}" ]]; then
    touch "${ENV_FILE}"
    chown root:root "${ENV_FILE}"
    chmod 0600 "${ENV_FILE}"
    printf '%s\n' \
        'TELEGRAM_BOT_TOKEN=replace_me' \
        'TELEGRAM_BOT_USERNAME=ExampleBot' \
        'TELEGRAM_ADMIN_CHAT_ID=-1000000000000' \
        'MODERATOR_TELEGRAM_IDS=123456789' \
        "AUTH_DATABASE_PATH=${DATA_DIR}/auth-backend.db" \
        'HOW_TO_PLAY_URL=https://example.invalid/minecraft' \
        "TELEGRAM_FEATURE_CONFIG_PATH=${TELEGRAM_FEATURE_CONFIG_FILE}" \
        'HTTP_BIND_ADDRESS=127.0.0.1' \
        'HTTP_PORT=8080' \
        'AUTH_API_TOKEN=replace_with_at_least_32_random_characters' \
        'DEVICE_FINGERPRINT_SECRET=replace_with_a_separate_random_secret_of_at_least_32_characters' \
        'SERVER_ID=survival-main' \
        'ADMIN_LOCALE=en' \
        'DEFAULT_USER_LOCALE=en' \
        'MINECRAFT_SERVICE_NAME=minecraft.service' \
        'MINECRAFT_SERVICE_USER=minecraft' \
        'MINECRAFT_SERVICE_GROUP=minecraft' \
        'MINECRAFT_GAME_DIR=/srv/minecraft' >"${ENV_FILE}"
    log_warn "Created ${ENV_FILE} with placeholders. Replace them before starting the service."
else
    log_info "Preserved existing ${ENV_FILE}."
fi

database="$(database_path)"
if [[ ! -e "${database}" ]]; then
    sqlite3 "${database}" 'PRAGMA user_version;'
    chown minecraft-auth:minecraft-auth "${database}"
    chmod 0640 "${database}"
fi

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"
log_info "Installed management tools in ${TOOLS_DIR} and loaded ${SERVICE_NAME}."

if [[ $# -gt 0 ]]; then
    "${TOOLS_DIR}/update.sh" "$1"
else
    log_info "Upload an installDist directory or deployment archive, then run:"
    log_info "sudo ${TOOLS_DIR}/update.sh /home/authbot/uploads/<package>"
fi
