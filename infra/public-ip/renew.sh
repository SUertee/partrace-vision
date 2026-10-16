#!/usr/bin/env bash
set -euo pipefail
umask 077
# certonly/renew does not install IP certificates into nginx; reload after success.
docker run --rm \
  -v /etc/letsencrypt:/etc/letsencrypt \
  -v /var/lib/letsencrypt:/var/lib/letsencrypt \
  -v /var/log/letsencrypt:/var/log/letsencrypt \
  -v /var/www/partrace-acme:/var/www/acme \
  certbot/certbot:v5.4.0 renew --non-interactive --quiet
nginx -t
systemctl reload nginx
