#!/usr/bin/env bash
# Run on the Proxmox host. Creates only a new, unprivileged Debian 12 container.
set +x
set -Eeuo pipefail
umask 077
credentials_file=''
source_dir=''
while (($#)); do
    case "$1" in
        --credentials-file) credentials_file=${2:?Missing file}; shift 2 ;;
        --source) source_dir=${2:?Missing source directory}; shift 2 ;;
        -h|--help)
            echo 'Usage: bash scripts/proxmox-lxc.sh [--credentials-file /private/credentials.json] [--source /source]'
            echo 'Options via environment: CT_ID, STORAGE, TEMPLATE_STORAGE, BRIDGE, DISK_GB, MEMORY_MB, CORES, CT_HOSTNAME.'
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done
[[ $EUID == 0 ]] || { echo 'Run as root on the Proxmox host.' >&2; exit 1; }
for tool in pct pvesh pvesm pveam python3 curl flock; do command -v "$tool" >/dev/null || { echo "Missing $tool; run on Proxmox VE." >&2; exit 1; }; done
exec 9>/run/lock/pianobar-lxc-install.lock
flock -n 9 || { echo 'Another LXC installer is running.' >&2; exit 1; }
CT_ID=${CT_ID:-$(pvesh get /cluster/nextid)}
STORAGE=${STORAGE:-local-lvm}
TEMPLATE_STORAGE=${TEMPLATE_STORAGE:-local}
BRIDGE=${BRIDGE:-vmbr0}
DISK_GB=${DISK_GB:-100}
MEMORY_MB=${MEMORY_MB:-2048}
CORES=${CORES:-2}
CT_HOSTNAME=${CT_HOSTNAME:-pianobar-super}
for value in "$CT_ID" "$DISK_GB" "$MEMORY_MB" "$CORES"; do [[ $value =~ ^[1-9][0-9]*$ ]] || { echo 'Numeric options must be positive integers.' >&2; exit 1; }; done
for value in "$STORAGE" "$TEMPLATE_STORAGE" "$BRIDGE" "$CT_HOSTNAME"; do [[ $value =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]] || { echo 'Invalid storage, bridge or hostname.' >&2; exit 1; }; done
((CT_ID >= 100 && DISK_GB >= 8 && MEMORY_MB >= 512)) || { echo 'Minimums: CT_ID=100, DISK_GB=8, MEMORY_MB=512.' >&2; exit 1; }
pvesh get /cluster/nextid --vmid "$CT_ID" >/dev/null
[[ -d /sys/class/net/$BRIDGE/bridge ]] || { echo "Bridge $BRIDGE not found." >&2; exit 1; }
pvesm status --storage "$STORAGE" --content rootdir | awk 'NR>1 && $3=="active" {ok=1} END {exit !ok}' || { echo 'Choose active container storage with STORAGE=...' >&2; exit 1; }
stage=$(mktemp -d /run/pianobar-lxc.XXXXXX)
created=0
success=0
marker="pianobar-installer-$(cat /proc/sys/kernel/random/uuid)"
cleanup() {
    status=$?
    trap - EXIT
    rm -rf -- "$stage"
    if ((created && !success)); then
        # Never destroy a container unless this run created and still owns it.
        if pct config "$CT_ID" | grep -Fq "$marker"; then
            echo "Installation failed; removing this run's new container $CT_ID." >&2
            pct stop "$CT_ID" >/dev/null 2>&1 || true
            pct destroy "$CT_ID" --purge 1 || echo "Cleanup failed: inspect container $CT_ID." >&2
        fi
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [[ -z $source_dir ]]; then
    script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
    if [[ -f $script_dir/install.sh ]]; then
        source_dir=$script_dir
    else
        curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
            https://github.com/Bake-Ware/pianobar-super/archive/refs/heads/main.tar.gz -o "$stage/source.tar.gz"
        mkdir "$stage/source"
        tar -xzf "$stage/source.tar.gz" --strip-components=1 -C "$stage/source"
        source_dir=$stage/source
    fi
fi
source_dir=$(realpath "$source_dir")
credential_args=(--output "$stage/credentials.json")
[[ -z $credentials_file ]] || credential_args+=(--input "$credentials_file")
python3 "$source_dir/scripts/install-credentials.py" "${credential_args[@]}"
# Transfer just the install/build inputs, never .git, keys, local configuration or music.
tar -czf "$stage/build.tar.gz" -C "$source_dir" install.sh Makefile pianobar-web src web contrib deployment scripts
template=$(pveam list "$TEMPLATE_STORAGE" | awk '/debian-12-standard_.*_amd64.tar/ {print $1}' | sort -V | tail -1)
if [[ -z $template ]]; then
    pveam update
    name=$(pveam available --section system | awk '/debian-12-standard_.*_amd64.tar/ {print $2}' | sort -V | tail -1)
    [[ -n $name ]] || { echo 'No Debian 12 template available.' >&2; exit 1; }
    pveam download "$TEMPLATE_STORAGE" "$name"
    template="$TEMPLATE_STORAGE:vztmpl/$name"
fi
echo "Creating LXC $CT_ID: $DISK_GB GB, $MEMORY_MB MB RAM, $CORES cores, DHCP on $BRIDGE."
(umask 022; pct create "$CT_ID" "$template" --hostname "$CT_HOSTNAME" --description "$marker" \
    --unprivileged 1 --cores "$CORES" --memory "$MEMORY_MB" --swap 512 \
    --rootfs "$STORAGE:$DISK_GB" --net0 "name=eth0,bridge=$BRIDGE,ip=dhcp,type=veth" --onboot 1)
created=1
pct start "$CT_ID"
ready=0
for ((attempt=0; attempt<45; attempt++)); do
    if pct exec "$CT_ID" -- sh -c 'ip -4 route get 1.1.1.1 >/dev/null 2>&1 && getent hosts deb.debian.org >/dev/null' 2>/dev/null; then ready=1; break; fi
    sleep 2
done
((ready)) || { echo 'Container DHCP/DNS did not become ready.' >&2; exit 1; }
pct push "$CT_ID" "$stage/build.tar.gz" /run/pianobar-build.tar.gz --perms 0600
pct push "$CT_ID" "$stage/credentials.json" /run/pianobar-credentials.json --perms 0600
rm -f "$stage/credentials.json"
pct exec "$CT_ID" -- bash -c '
    set -e
    trap '\''rm -rf /run/pianobar-source /run/pianobar-build.tar.gz /run/pianobar-credentials.json'\'' EXIT
    mkdir -m 0700 /run/pianobar-source
    tar -xzf /run/pianobar-build.tar.gz -C /run/pianobar-source
    # Minimal LXC templates need Python for credential handling.
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y --no-install-recommends python3 ca-certificates
    bash /run/pianobar-source/install.sh --source /run/pianobar-source --credentials-file /run/pianobar-credentials.json
'
success=1
address=$(pct exec "$CT_ID" -- hostname -I | awk '{print $1}')
echo "Ready: http://$address:8765/ (web username: pianobar). LXC ID: $CT_ID"
