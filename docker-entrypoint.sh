#!/bin/sh
set -eu

: "${CORE_RPC_USERNAME:?CORE_RPC_USERNAME is required}"
: "${CORE_RPC_PASSWORD:?CORE_RPC_PASSWORD is required}"

FRIGATE_CACHE_SIZE="${FRIGATE_CACHE_SIZE:-5M}"
FRIGATE_MEMORY_LIMIT="${FRIGATE_MEMORY_LIMIT:-6GB}"
FRIGATE_DB_THREADS="${FRIGATE_DB_THREADS:-4}"
FRIGATE_COMPUTE_BACKEND="${FRIGATE_COMPUTE_BACKEND:-AUTO}"

mkdir -p /var/lib/frigate
umask 077

# Escape values for TOML double-quoted strings.
toml_escape() {
    printf '%s' "$1" | sed \
        -e 's/\\/\\\\/g' \
        -e 's/"/\\"/g'
}

RPC_USER="$(toml_escape "$CORE_RPC_USERNAME")"
RPC_PASS="$(toml_escape "$CORE_RPC_PASSWORD")"

cat > /var/lib/frigate/config.toml <<EOF
[core]
connect = true
server = "http://bitcoind:8332"
authType = "USERPASS"
auth = "${RPC_USER}:${RPC_PASS}"
zmqSequenceEndpoint = "tcp://bitcoind:28336"
rpcRequestTimeoutSeconds = 60
rpcBatchSize = 100

[index]
cacheSize = "${FRIGATE_CACHE_SIZE}"

[scan]
computeBackend = "${FRIGATE_COMPUTE_BACKEND}"
batchSize = 300000
dbThreads = ${FRIGATE_DB_THREADS}
memoryLimit = "${FRIGATE_MEMORY_LIMIT}"
metricsEnabled = false

[server]
tcp = "tcp://0.0.0.0:50001"
backendElectrumServer = "tcp://electrs:50001"
EOF

exec /opt/frigate/bin/frigate -d /var/lib/frigate "$@"
