#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "${SCRIPT_DIR}/common.sh"

require_command journalctl

follow=false
lines=100
while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--follow) follow=true; shift ;;
        -n|--lines)
            [[ $# -ge 2 ]] || die "$1 requires a line count."
            lines="$2"
            shift 2
            ;;
        *) die "Usage: $0 [-f] [-n <lines>]" ;;
    esac
done
[[ "${lines}" =~ ^[0-9]+$ && "${lines}" -ge 1 && "${lines}" -le 100000 ]] \
    || die "Line count must be between 1 and 100000."

if [[ "${follow}" == true ]]; then
    exec journalctl -u "${SERVICE_NAME}" -n "${lines}" -f
else
    exec journalctl -u "${SERVICE_NAME}" -n "${lines}" --no-pager
fi
