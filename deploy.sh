#!/usr/bin/env bash
# deploy.sh — build and push the federation plugin to all Openfire servers.
#
# Each SERVERS entry has the form:  ssh-target:docker-container
#   ssh-target       = [user@]hostname   (anything valid for ssh/scp)
#   docker-container = name or ID of the running Openfire container
#
# Password file:
#   Create a file containing just the SSH password (one line, no trailing newline).
#   Point SSH_PASS_FILE at it.  The file must be chmod 600.
#   If SSH_PASS_FILE is empty or the file doesn't exist, sshpass is skipped and
#   you'll be prompted for passwords normally (or keys are used).
#
# The script:
#   1. Builds the plugin JAR with Maven
#   2. For every server: SCP the JAR, docker cp it into the container,
#      remove the old extracted plugin dir so Openfire hot-reloads it.
#
# Usage:
#   chmod +x deploy.sh
#   echo 'mypassword' > .ssh_pass && chmod 600 .ssh_pass
#   ./deploy.sh            # build + deploy all servers
#   ./deploy.sh --no-build # skip mvn, just redeploy the existing JAR

set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────────────
SERVERS=(
     "deploy-user@192.0.2.1:xmpp"
     "deploy-user@192.0.2.2:xmpp"
     "deploy-user@192.0.2.3:xmpp"
     "deploy-user@192.0.2.4:xmpp"
     "deploy-user@192.0.2.5:xmpp"
     "deploy-user@192.0.2.6:xmpp"
     "deploy-user@192.0.2.7:xmpp"
)

# Path to a file containing the SSH password (chmod 600).
# Leave empty to use SSH keys / interactive prompt instead.
SSH_PASS_FILE="${SSH_PASS_FILE:-.ssh_pass}"

OPENFIRE_PLUGIN_DIR="/var/lib/openfire/plugins"   # path INSIDE the container
PLUGIN_NAME="federation"
# The assembly plugin outputs target/federation.jar (the proper plugin archive).
# This is distinct from target/federation-1.2.0.jar (the classes-only JAR).
JAR="target/${PLUGIN_NAME}.jar"
# Seconds to wait after removing the old plugin before copying the new one.
# Openfire's plugin watcher polls every ~4 s; 6 s gives it time to unload cleanly.
RELOAD_WAIT=6
# ───────────────────────────────────────────────────────────────────────────────

if [[ ${#SERVERS[@]} -eq 0 ]]; then
    echo "ERROR: No servers configured. Edit the SERVERS array in deploy.sh."
    exit 1
fi

# Build the sshpass prefix if the password file exists and is non-empty
SSH_PREFIX=""
if [[ -n "$SSH_PASS_FILE" && -f "$SSH_PASS_FILE" ]]; then
    if ! command -v sshpass &>/dev/null; then
        echo "ERROR: SSH_PASS_FILE is set but 'sshpass' is not installed."
        echo "       Install it with:  sudo apt install -y sshpass"
        exit 1
    fi
    SSH_PREFIX="sshpass -f $(printf '%q' "$SSH_PASS_FILE")"
    echo "==> Using password file: $SSH_PASS_FILE"
else
    echo "==> No password file found — using SSH keys / interactive auth"
fi

# Build unless caller passed --no-build
if [[ "${1:-}" != "--no-build" ]]; then
    echo "==> Building plugin..."
    mvn package -DskipTests -q
fi

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: Plugin archive not found at ${JAR}. Run mvn package first."
    exit 1
fi
echo "==> Using JAR: $JAR"

SSH_OPTS=(-o StrictHostKeyChecking=no)

for entry in "${SERVERS[@]}"; do
    SSH_TARGET="${entry%%:*}"
    CONTAINER="${entry##*:}"

    echo ""
    echo "──> Deploying to ${SSH_TARGET} (container: ${CONTAINER})"

    # Step 1: remove old plugin files so Openfire's watcher unloads the plugin.
    $SSH_PREFIX ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
        set -e
        docker exec '$CONTAINER' bash -c \
            'rm -rf \"${OPENFIRE_PLUGIN_DIR}/${PLUGIN_NAME}\" \
                    \"${OPENFIRE_PLUGIN_DIR}/${PLUGIN_NAME}.jar\"'
        echo '    Old plugin removed.'
    "

    # Give Openfire's plugin watcher time to detect the removal and unload the plugin
    # before the new JAR lands (watcher polls every ~4 s).
    echo "    Waiting ${RELOAD_WAIT}s for Openfire to unload…"
    sleep "$RELOAD_WAIT"

    # Step 2: push the new JAR — Openfire will pick it up and hot-load it.
    $SSH_PREFIX scp -q "${SSH_OPTS[@]}" \
        "$JAR" "${SSH_TARGET}:/tmp/${PLUGIN_NAME}.jar"

    $SSH_PREFIX ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
        set -e
        docker cp '/tmp/${PLUGIN_NAME}.jar' \
                  '${CONTAINER}:${OPENFIRE_PLUGIN_DIR}/${PLUGIN_NAME}.jar'
        rm -f '/tmp/${PLUGIN_NAME}.jar'
        echo '    New JAR deployed — Openfire is loading it.'
    "
done

echo ""
echo "==> Done. All servers updated."
