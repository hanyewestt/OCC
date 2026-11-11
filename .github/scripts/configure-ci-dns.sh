#!/usr/bin/env bash
set -euo pipefail

# The hosted runner's Azure resolver can fail for Ubuntu package mirrors even
# with Docker host networking. Set the runner's uplink DNS before Docker builds
# so both the host and BuildKit inherit working resolvers.
interface=$(ip -o -4 route show to default | awk '{print $5; exit}')
test -n "$interface"
sudo resolvectl dns "$interface" 1.1.1.1 8.8.8.8
sudo resolvectl flush-caches
getent ahostsv4 archive.ubuntu.com
getent ahostsv4 security.ubuntu.com
