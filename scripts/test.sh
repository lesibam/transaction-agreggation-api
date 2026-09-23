#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Rancher Desktop: host connects via macOS socket; containers must mount the
# daemon-side path. Ryuk cannot use the macOS socket through virtiofs.
export DOCKER_HOST="${DOCKER_HOST:-unix://${HOME}/.rd/docker.sock}"
export DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}"
export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKET_SOCKET_OVERRIDE:-/var/run/docker.sock}"

exec mvn "$@"
