#!/usr/bin/env bash
# pull-logs.sh — pull the last N lines of the Openfire log from each xmpp server.
#
# Mirrors deploy.sh: same SERVERS list, same SSH/sshpass auth, same docker container.
# For each server it runs `tail` inside the Openfire container and writes the output
# to logs/server<N> (N = position in the SERVERS list, 1-based), and echoes it.
#
# Usage:
#   ./pull-logs.sh                 # last 50 lines from all servers
#   LOG_LINES=200 ./pull-logs.sh   # last 200 lines from all servers
#   ./pull-logs.sh 3               # only server 3
#   ./pull-logs.sh 3 7             # only servers 3 and 7
#   GREP=federation ./pull-logs.sh # only matching lines (last N of the matches)
#
# NB: the count var is LOG_LINES, not LINES — bash reserves LINES for the
#     terminal height, so using it would silently cap output to your window size.
#
# Auth: uses .ssh_pass via sshpass if present (chmod 600), else SSH keys / prompt.

set -euo pipefail

# ── Configuration (keep in sync with deploy.sh) ────────────────────────────────
SERVERS=(
     "deploy-user@192.0.2.1:xmpp"
     "deploy-user@192.0.2.2:xmpp"
     "deploy-user@192.0.2.3:xmpp"
     "deploy-user@192.0.2.4:xmpp"
     "deploy-user@192.0.2.5:xmpp"
     "deploy-user@192.0.2.6:xmpp"
     "deploy-user@192.0.2.7:xmpp"
)

SSH_PASS_FILE="${SSH_PASS_FILE:-.ssh_pass}"
LOG_FILE="${LOG_FILE:-/var/log/openfire/openfire.log}"   # path INSIDE the container
LOG_LINES="${LOG_LINES:-50}"    # how many trailing lines to pull (NOT 'LINES' — bash owns that)
GREP="${GREP:-}"                # optional case-insensitive filter applied before tail
OUT_DIR="${OUT_DIR:-logs}"      # where the per-server files are written (gitignored)
# ───────────────────────────────────────────────────────────────────────────────

# Optional positional args select a subset of servers by 1-based index.
SELECT=("$@")

SSH_PREFIX=""
if [[ -n "$SSH_PASS_FILE" && -f "$SSH_PASS_FILE" ]]; then
    if ! command -v sshpass &>/dev/null; then
        echo "ERROR: SSH_PASS_FILE is set but 'sshpass' is not installed (sudo apt install -y sshpass)." >&2
        exit 1
    fi
    SSH_PREFIX="sshpass -f $(printf '%q' "$SSH_PASS_FILE")"
else
    echo "==> No password file found — using SSH keys / interactive auth"
fi

SSH_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=10)
mkdir -p "$OUT_DIR"

# Build the remote command: optional grep, then tail, then strip ANSI colour codes
# so the saved files read cleanly (Openfire colourises levels in the log).
STRIP_ANSI="sed 's/\x1b\[[0-9;]*m//g'"
if [[ -n "$GREP" ]]; then
    REMOTE_CMD="grep -i -- $(printf '%q' "$GREP") $(printf '%q' "$LOG_FILE") 2>/dev/null | tail -n $(printf '%q' "$LOG_LINES") | $STRIP_ANSI"
else
    REMOTE_CMD="tail -n $(printf '%q' "$LOG_LINES") $(printf '%q' "$LOG_FILE") | $STRIP_ANSI"
fi

wanted() {  # is server index $1 in the SELECT list? (empty SELECT = all)
    [[ ${#SELECT[@]} -eq 0 ]] && return 0
    local n="$1" s
    for s in "${SELECT[@]}"; do [[ "$s" == "$n" ]] && return 0; done
    return 1
}

rc=0
for i in "${!SERVERS[@]}"; do
    n=$((i + 1))
    wanted "$n" || continue

    entry="${SERVERS[$i]}"
    SSH_TARGET="${entry%%:*}"
    CONTAINER="${entry##*:}"
    OUT="${OUT_DIR}/server${n}"

    echo ""
    echo "──> server${n}  (${SSH_TARGET}, container ${CONTAINER}) — last ${LOG_LINES}${GREP:+ matching '$GREP'} line(s)"

    if $SSH_PREFIX ssh "${SSH_OPTS[@]}" "$SSH_TARGET" \
            "docker exec '$CONTAINER' sh -c $(printf '%q' "$REMOTE_CMD")" >"$OUT" 2>"${OUT}.err"; then
        cat "$OUT"
        rm -f "${OUT}.err"
        echo "    (saved to ${OUT})"
    else
        echo "    FAILED — $(tr '\n' ' ' <"${OUT}.err")" >&2
        rc=1
    fi
done

echo ""
echo "==> Done."
exit "$rc"
