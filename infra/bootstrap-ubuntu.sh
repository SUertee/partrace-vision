#!/usr/bin/env bash
# Provision missing host tools; retain existing services and project data.
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run with sudo bash infra/bootstrap-ubuntu.sh" >&2
  exit 1
fi

source /etc/os-release
if [[ ${ID} != ubuntu || ${VERSION_ID} != 24.04 ]]; then
  echo "This bootstrap is verified for Ubuntu 24.04 only." >&2
  exit 1
fi

python3 - <<'PY'
from pathlib import Path
import os, shutil, subprocess

memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
if int(memory['MemTotal'].split()[0]) < 2 * 1024 * 1024 and int(memory['SwapTotal'].split()[0]) == 0:
    swap = Path('/var/lib/partrace-vision/swapfile')
    if swap.exists():
        raise SystemExit('An inactive project swapfile already exists; inspect it before provisioning.')
    if shutil.disk_usage('/var/lib').free < 4 * 1024**3:
        raise SystemExit('At least 4 GiB free disk is required before adding swap.')
    swap.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['fallocate', '-l', '2G', str(swap)], check=True)
    os.chmod(swap, 0o600)
    subprocess.run(['mkswap', str(swap)], check=True)
    subprocess.run(['swapon', str(swap)], check=True)
    entry = f'{swap} none swap sw 0 0'
    fstab = Path('/etc/fstab')
    content = fstab.read_text()
    if not any(line.split()[:1] == [str(swap)] for line in content.splitlines()):
        with fstab.open('a') as stream:
            stream.write(('' if content.endswith('\n') else '\n') + entry + '\n')
    print('Added persistent 2 GiB project swap.')
PY

if ! command -v docker >/dev/null 2>&1; then
  for partrace_package in docker.io podman-docker containerd runc; do
    if dpkg-query -W -f='${Status}' "${partrace_package}" 2>/dev/null | grep -q 'install ok installed'; then
      echo "Existing ${partrace_package} must be reviewed before installing Docker CE." >&2
      exit 1
    fi
  done
  export DEBIAN_FRONTEND=noninteractive
  export NEEDRESTART_MODE=l
  apt-get -o DPkg::Lock::Timeout=180 update
  apt-get -o DPkg::Lock::Timeout=180 install -y --no-install-recommends ca-certificates curl
  install -m 0755 -d /etc/apt/keyrings
  if [[ ! -f /etc/apt/keyrings/docker.asc ]]; then
    curl -fsSL --retry 3 https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod 0644 /etc/apt/keyrings/docker.asc
  fi
  if [[ ! -f /etc/apt/sources.list.d/docker.sources ]]; then
    partrace_arch=$(dpkg --print-architecture)
    cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-${VERSION_CODENAME}}
Components: stable
Architectures: ${partrace_arch}
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  fi
  apt-get -o DPkg::Lock::Timeout=180 update
  apt-get -o DPkg::Lock::Timeout=180 install -y --no-install-recommends docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

systemctl enable --now docker
docker compose version
docker version --format '{{.Server.Version}}'

if [[ ! -e /srv/partrace-vision ]]; then
  install -d -m 0750 -o ubuntu -g ubuntu /srv/partrace-vision
fi
if ! sudo -u ubuntu test -w /srv/partrace-vision; then
  echo "ubuntu needs write access to the existing /srv/partrace-vision directory." >&2
  exit 1
fi
echo "Host prerequisites ready."
