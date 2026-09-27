#!/usr/bin/env bash
# Makes localhost inside this container look like the host: each port the stack publishes is forwarded to its
# service on the compose network. The repository's scripts and the README's commands use http://localhost:<port>,
# so they run here unchanged. Keycloak still issues tokens for http://localhost:8180 (KC_HOSTNAME), which is what
# the services expect.
set -euo pipefail

forward() {  # forward <local port> <service host:port>; IPv6 is best effort (some Docker setups disable it)
  socat -lf /dev/null "TCP4-LISTEN:$1,fork,reuseaddr,bind=127.0.0.1" "TCP:$2" &
  socat -lf /dev/null "TCP6-LISTEN:$1,fork,reuseaddr,bind=[::1]" "TCP:$2" 2>/dev/null &
}

forward 8080 room-reservation-service:8080
forward 8081 bank-transfer-payment-service:8081
forward 8082 notification-service:8082
forward 9090 credit-card-payment-service:9090
forward 8083 connect:8083
forward 8180 keycloak:8080

exec "$@"
