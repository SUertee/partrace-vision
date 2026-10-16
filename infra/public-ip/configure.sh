#!/usr/bin/env bash
set -euo pipefail
umask 077
MODE=${1:-status}
PUBLIC_IP=${2:-}
API_PORT=${3:-8000}
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
CERTBOT_IMAGE=certbot/certbot:v5.4.0
CONFIG=/etc/nginx/conf.d/partrace-public-ip.conf
if [[ $EUID -ne 0 ]]; then echo "Run with sudo." >&2; exit 1; fi
if [[ $MODE != prepare && $MODE != issue && $MODE != status ]]; then echo "Usage: configure.sh prepare|issue|status PUBLIC_IPV4 [LOOPBACK_API_PORT]" >&2; exit 1; fi
[[ $API_PORT =~ ^[0-9]+$ && $API_PORT -ge 1024 && $API_PORT -le 65535 ]] || { echo "Invalid loopback API port." >&2; exit 1; }
python3 - "$PUBLIC_IP" <<'IPCHECK'
import ipaddress,sys
address=ipaddress.ip_address(sys.argv[1])
if address.version != 4 or not address.is_global:raise SystemExit('A global IPv4 address is required')
IPCHECK
if [[ -e $CONFIG ]] && ! grep -q '^# Managed by ParTrace public-ip HTTPS setup\.$' "$CONFIG"; then
    echo "Refusing to replace an unmanaged nginx configuration." >&2; exit 1
fi
render() {
    local template=$1
    local candidate previous
    candidate=$(mktemp /etc/nginx/conf.d/.partrace-candidate.XXXXXX)
    sed -e "s/__PUBLIC_IP__/$PUBLIC_IP/g" -e "s/__API_PORT__/$API_PORT/g" "$SCRIPT_DIR/$template" > "$candidate"
    chmod 644 "$candidate"
    previous=$(mktemp /etc/nginx/conf.d/.partrace-previous.XXXXXX)
    if [[ -e $CONFIG ]]; then cp -p "$CONFIG" "$previous"; else rm "$previous"; fi
    mv "$candidate" "$CONFIG"
    if ! nginx -t; then
        if [[ -e $previous ]]; then mv "$previous" "$CONFIG"; else rm -f "$CONFIG"; fi
        echo "nginx validation failed; prior managed config restored." >&2; return 1
    fi
    rm -f "$previous"
    systemctl enable nginx >/dev/null
    systemctl reload-or-restart nginx
}
if [[ $MODE == prepare ]]; then
    installed=false
    if ! command -v nginx >/dev/null; then
        apt-get update
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends nginx
        installed=true
    fi
    command -v docker >/dev/null
    if [[ $installed == true && -L /etc/nginx/sites-enabled/default ]]; then rm /etc/nginx/sites-enabled/default; fi
    install -d -m 755 /var/www/partrace-acme/.well-known/acme-challenge
    printf 'partrace-acme-ready\n' > /var/www/partrace-acme/.well-known/acme-challenge/partrace-http-check
    chmod 644 /var/www/partrace-acme/.well-known/acme-challenge/partrace-http-check
    if [[ ! -e /etc/letsencrypt/live/$PUBLIC_IP/fullchain.pem ]]; then render http.conf.in; fi
    docker pull "$CERTBOT_IMAGE"
    echo "HTTP-01 is prepared. Confirm public port 80 before issue. Business API is unchanged."
elif [[ $MODE == issue ]]; then
    test -e /var/www/partrace-acme/.well-known/acme-challenge/partrace-http-check
    install -d -m 700 /etc/letsencrypt /var/lib/letsencrypt /var/log/letsencrypt
    docker run --rm \
      -v /etc/letsencrypt:/etc/letsencrypt \
      -v /var/lib/letsencrypt:/var/lib/letsencrypt \
      -v /var/log/letsencrypt:/var/log/letsencrypt \
      -v /var/www/partrace-acme:/var/www/acme \
      "$CERTBOT_IMAGE" certonly --non-interactive --agree-tos --register-unsafely-without-email \
      --preferred-profile shortlived --webroot --webroot-path /var/www/acme \
      --ip-address "$PUBLIC_IP" --cert-name "$PUBLIC_IP" --keep-until-expiring
    openssl x509 -in "/etc/letsencrypt/live/$PUBLIC_IP/fullchain.pem" -noout -checkip "$PUBLIC_IP"
    openssl x509 -in "/etc/letsencrypt/live/$PUBLIC_IP/fullchain.pem" -noout -checkend 86400
    render https.conf.in
    install -m 700 "$SCRIPT_DIR/renew.sh" /usr/local/sbin/partrace-renew-public-ip
    install -m 644 "$SCRIPT_DIR/partrace-public-ip-renew.service" /etc/systemd/system/
    install -m 644 "$SCRIPT_DIR/partrace-public-ip-renew.timer" /etc/systemd/system/
    systemctl daemon-reload
    systemctl enable --now partrace-public-ip-renew.timer
    echo "HTTPS configured. Verify from outside EC2 and verify API Bearer permissions."
else
    systemctl is-active nginx || true
    systemctl list-timers partrace-public-ip-renew.timer --no-pager
    if [[ -e /etc/letsencrypt/live/$PUBLIC_IP/fullchain.pem ]]; then
      openssl x509 -in "/etc/letsencrypt/live/$PUBLIC_IP/fullchain.pem" -noout -dates -checkip "$PUBLIC_IP"
    fi
fi
