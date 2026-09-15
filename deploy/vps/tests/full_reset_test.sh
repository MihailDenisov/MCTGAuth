#!/usr/bin/env bash
set -Eeuo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
DEPLOY_DIR="$(cd -- "${TEST_DIR}/.." && pwd -P)"
ROOT_DIR="$(cd -- "${DEPLOY_DIR}/../.." && pwd -P)"
source "${DEPLOY_DIR}/reset-database.sh"

require_commands grep id jq mkdir mktemp rm sqlite3
temporary="$(mktemp -d)"
trap 'rm -rf -- "${temporary}"' EXIT
database="${temporary}/reset.db"
cache="${temporary}/server/mctgauth/accounts.json"
mkdir -p -- "$(dirname -- "${cache}")"

sqlite3 "${database}" <"${ROOT_DIR}/auth-backend/src/main/resources/db/migration/V001__initial.sql"
sqlite3 "${database}" <"${ROOT_DIR}/auth-backend/src/main/resources/db/migration/V002__backend_phase_2.sql"
sqlite3 "${database}" <"${ROOT_DIR}/auth-backend/src/main/resources/db/migration/V003__user_locale.sql"
sqlite3 "${database}" <"${ROOT_DIR}/auth-backend/src/main/resources/db/migration/V004__telegram_workflow_fixes.sql"
sqlite3 "${database}" <<'SQL'
PRAGMA foreign_keys = ON;
CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, filename TEXT NOT NULL, applied_at TEXT NOT NULL);
INSERT INTO schema_migrations VALUES
 (1,'V001__initial.sql','2026-09-15T00:00:00Z'),
 (2,'V002__backend_phase_2.sql','2026-09-15T00:00:00Z'),
 (3,'V003__user_locale.sql','2026-09-15T00:00:00Z'),
 (4,'V004__telegram_workflow_fixes.sql','2026-09-15T00:00:00Z');
INSERT INTO moderators VALUES(1,'admin',1,'2026-09-15T00:00:00Z');
INSERT INTO moderator_permissions VALUES(1,'ACCOUNT_DISABLE'),(1,'ACCOUNT_ENABLE');
INSERT INTO users(telegram_id,username_snapshot,account_status,created_at,updated_at,locale)
 VALUES(100,'test','ACTIVE','2026-09-15T00:00:00Z','2026-09-15T00:00:00Z','en');
INSERT INTO tickets(id,type,status,creator_telegram_id,creator_username_snapshot,created_at,updated_at,assigned_moderator_telegram_id)
 VALUES
 (1,'REGISTRATION','IN_PROGRESS',100,'test','2026-09-15T00:00:00Z','2026-09-15T00:00:00Z',1),
 (2,'PROBLEM','IN_PROGRESS',100,'test','2026-09-15T00:00:00Z','2026-09-15T00:00:00Z',1),
 (3,'VIOLATION','OPEN',100,'test','2026-09-15T00:00:00Z','2026-09-15T00:00:00Z',NULL),
 (4,'ADD_MINECRAFT_ACCOUNT','OPEN',100,'test','2026-09-15T00:00:00Z','2026-09-15T00:00:00Z',NULL);
INSERT INTO registration_ticket_data VALUES(1,'PlayerA','playera',0,25,'test');
INSERT INTO ticket_decisions VALUES(1,'APPROVED',1,'admin','ok','2026-09-15T00:00:00Z');
INSERT INTO minecraft_accounts(id,owner_telegram_id,nickname,normalized_nickname,licensed,enabled,creation_method,
 creator_moderator_id,credential_verifier,credential_version,created_at,updated_at)
 VALUES(1,100,'PlayerA','playera',0,1,'REGISTRATION_TICKET',1,'old-verifier-must-disappear',1,
 '2026-09-15T00:00:00Z','2026-09-15T00:00:00Z');
UPDATE tickets SET affected_account_id=1 WHERE id IN (2,3,4);
INSERT INTO problem_ticket_data VALUES(2,'problem');
INSERT INTO violation_ticket_data VALUES(3,NULL,NULL,'violation');
INSERT INTO add_account_ticket_data VALUES(4,'PlayerB','playerb',0);
INSERT INTO ticket_messages(ticket_id,sender_type,sender_telegram_id,message_text,created_at)
 VALUES(2,'USER',100,'message','2026-09-15T00:00:00Z');
INSERT INTO ticket_attachments(ticket_id,telegram_file_id,telegram_file_unique_id,file_type,created_at)
 VALUES(2,'file','unique','DOCUMENT','2026-09-15T00:00:00Z');
INSERT INTO password_changes(account_id,requested_by_telegram_id,credential_version,created_at)
 VALUES(1,100,1,'2026-09-15T00:00:00Z');
INSERT INTO audit_log(event_type,ticket_id,account_id,actor_telegram_id,occurred_at)
 VALUES('TEST',2,1,1,'2026-09-15T00:00:00Z');
INSERT INTO sync_events VALUES('00000000-0000-4000-8000-000000000001','ACCOUNT_CREATED',1,'{}','2026-09-15T00:00:00Z',NULL,0);
INSERT INTO remote_commands(public_id,server_id,command_type,schema_version,payload_json,status,created_at,expires_at,created_by_telegram_id)
 VALUES('00000000-0000-4000-8000-000000000002','server','AUTH_STATUS',1,'{}','PENDING','2026-09-15T00:00:00Z','2026-09-15T01:00:00Z',1);
INSERT INTO moderator_pending_actions VALUES(1,'REPLY_TO_TICKET',2,'2026-09-15T00:00:00Z','2026-09-15T01:00:00Z');
SQL

printf '%s\n' '{"schemaVersion":1,"accounts":{"playera":{"credentialVerifier":"old-verifier-must-disappear"}},"processedEvents":[{"id":"old"}],"processedRemoteCommands":[]}' >"${cache}"
cp -- "${cache}" "${cache}.bak"
cp -- "${cache}" "${cache}.tmp"

before="$(sqlite3 "${database}" 'SELECT COUNT(*) FROM users;')"
dry_output="$(print_counts "${database}" "${cache}")"
[[ "${before}" == "1" && "$(sqlite3 "${database}" 'SELECT COUNT(*) FROM users;')" == "1" ]]
grep -q 'NO CHANGES MADE' <<<"${dry_output}"

reset_database_contents "${database}"
clear_minecraft_cache "${cache}" "$(id -un)" "$(id -gn)"

for table in "${RESET_TABLES[@]}"; do
    [[ "$(sqlite3 "${database}" "SELECT COUNT(*) FROM ${table};")" == "0" ]]
done
[[ "$(sqlite3 "${database}" 'SELECT COUNT(*) FROM schema_migrations;')" == "4" ]]
[[ "$(sqlite3 "${database}" 'SELECT COUNT(*) FROM moderators;')" == "1" ]]
[[ "$(sqlite3 "${database}" 'SELECT COUNT(*) FROM moderator_permissions;')" == "2" ]]
[[ "$(jq -r '.accounts | length' "${cache}")" == "0" ]]
! grep -q 'old-verifier-must-disappear' "${cache}"
! grep -a -q 'old-verifier-must-disappear' "${database}"
[[ ! -e "${cache}.bak" && ! -e "${cache}.tmp" ]]
[[ -z "$(sqlite3 "${database}" 'PRAGMA foreign_key_check;')" ]]
[[ "$(sqlite3 "${database}" 'PRAGMA integrity_check;')" == "ok" ]]

reset_database_contents "${database}"
printf 'Full reset fixture tests passed.\n'
