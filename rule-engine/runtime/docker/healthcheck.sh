#!/usr/bin/env bash
# Healthy once the runtime answers its readiness check: database reachable and active models loaded.
# Uses bash only, so the image needs no curl or wget.
exec 3<>/dev/tcp/127.0.0.1/8080 || exit 1
printf 'GET /q/health/ready HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
read -r status <&3
exec 3<&-
case "$status" in
  *" 200 "*) exit 0 ;;
  *) exit 1 ;;
esac
