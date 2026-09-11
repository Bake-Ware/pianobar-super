#!/usr/bin/env bash
# Debian 12 server installer. Passwords never appear in arguments or logs.
set +x
set -Eeuo pipefail
umask 077

usage() {
    cat <<'EOF'
Usage: sudo bash install.sh [--credentials-file /private/credentials.json] [--source /path/to/source]
Installs the service on Debian 12. Prompts once on a fresh install; existing
configuration and music are preserved on updates. See README.md for automation.
EOF
}
credentials_file=''
source_dir=''
while (($#)); do
    case "$1" in
        --credentials-file) credentials_file=${2:?Missing file}; shift 2 ;;
        --source) source_dir=${2:?Missing source directory}; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
[[ $EUID == 0 ]] || { echo 'Run this installer as root (sudo bash install.sh).' >&2; exit 1; }
. /etc/os-release
[[ $ID == debian && $VERSION_ID == 12 ]] || { echo 'This installer requires Debian 12 (FFmpeg 5).' >&2; exit 1; }
export DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 LC_ALL=C.UTF-8
if ! command -v python3 >/dev/null || ! command -v curl >/dev/null; then
    apt-get update
    apt-get install -y --no-install-recommends python3 curl ca-certificates
fi
exec 9>/run/lock/pianobar-install.lock
flock -n 9 || { echo 'Another installer is running.' >&2; exit 1; }
stage=$(mktemp -d /run/pianobar-install.XXXXXX)
trap 'rm -rf -- "$stage"' EXIT
trap 'echo "Installation failed; inspect the error above. Existing music and credentials are retained." >&2' ERR
if [[ -z $source_dir ]]; then
    script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
    if [[ -f $script_dir/Makefile && -f $script_dir/scripts/install-credentials.py ]]; then
        source_dir=$script_dir
    else
        command -v curl >/dev/null || { echo 'Install curl first: apt-get install -y curl ca-certificates' >&2; exit 1; }
        curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
            https://github.com/Bake-Ware/pianobar-super/archive/refs/heads/main.tar.gz -o "$stage/source.tar.gz"
        mkdir "$stage/source"
        tar -xzf "$stage/source.tar.gz" --strip-components=1 -C "$stage/source"
        source_dir=$stage/source
    fi
fi
source_dir=$(realpath "$source_dir")
[[ -f $source_dir/scripts/install-credentials.py && -f $source_dir/Makefile ]] || { echo 'Incomplete source checkout.' >&2; exit 1; }
config_dir=/var/lib/pianobar/.config/pianobar
fresh=1
if [[ -f $config_dir/config && -f $config_dir/web.json ]]; then
    fresh=0
    [[ -z $credentials_file ]] || { echo 'Existing configuration found; change credentials in Settings.' >&2; exit 1; }
    echo 'Updating installation; retaining existing configuration and music.'
elif [[ -e $config_dir/config || -e $config_dir/web.json ]]; then
    echo 'Partial existing configuration found. Complete or move it before installing.' >&2
    exit 1
else
    credential_args=(--output "$stage/credentials.json")
    [[ -z $credentials_file ]] || credential_args+=(--input "$credentials_file")
    python3 "$source_dir/scripts/install-credentials.py" "${credential_args[@]}"
fi
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends build-essential pkg-config ca-certificates python3 \
    libao-dev libcurl4-openssl-dev libgcrypt20-dev libjson-c-dev \
    libavcodec-dev libavformat-dev libavutil-dev libavfilter-dev ffmpeg
# Build an isolated copy; never copy runtime data or an existing build into it.
mkdir "$stage/build"
cp -a "$source_dir/Makefile" "$source_dir/pianobar-web" "$source_dir/src" \
    "$source_dir/web" "$source_dir/contrib" "$stage/build/"
make -C "$stage/build" clean
make -C "$stage/build" -j2
id pianobar >/dev/null 2>&1 || useradd --system --user-group --home-dir /var/lib/pianobar --create-home --shell /usr/sbin/nologin pianobar
install -d -o pianobar -g pianobar -m 0700 /var/lib/pianobar "$config_dir" /var/lib/pianobar/songs
if ((fresh)); then
    python3 - "$stage/credentials.json" "$config_dir" <<'PY'
import json, os, pwd, sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text())
directory = Path(sys.argv[2])
account = pwd.getpwnam('pianobar')
files = {
    'config': 'user = ' + data['user'] + '\npassword = ' + data['password'] +
        '\ncache_songs = 1\ncache_dir = /var/lib/pianobar/songs\noffline_fallback = 1\n',
    'web.json': json.dumps(dict(listen='0.0.0.0', port=8765, output='browser', password=data['web_password'])) + '\n',
}
for name, content in files.items():
    fd = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(content)
        os.fchown(stream.fileno(), account.pw_uid, account.pw_gid)
PY
fi
make -C "$stage/build" install
install -m 0644 "$source_dir/deployment/pianobar.service" /etc/systemd/system/pianobar.service
systemctl daemon-reload
systemctl enable pianobar
systemctl restart pianobar
python3 - <<'PY'
import base64, json, time, urllib.request
from pathlib import Path
web = json.loads(Path('/var/lib/pianobar/.config/pianobar/web.json').read_text())
host = web.get('listen', '127.0.0.1')
if host == '0.0.0.0': host = '127.0.0.1'
if host == '::': host = '::1'
if ':' in host: host = '[' + host + ']'
url = 'http://' + host + ':' + str(web.get('port', 8765)) + '/api/state'
headers = {}
if web.get('password'):
    headers['Authorization'] = 'Basic ' + base64.b64encode(('pianobar:' + web['password']).encode()).decode()
for _ in range(30):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=2) as response:
            assert response.status == 200
        break
    except OSError:
        time.sleep(1)
else:
    raise SystemExit('Web health check failed. Run: systemctl status pianobar')
print('Installed. Open http://<server-ip>:' + str(web.get('port', 8765)) + '/ and sign in as pianobar.')
print('Select a station to begin. Settings can update your account and playback preferences.')
print('HTTP is for trusted networks; use an HTTPS reverse proxy for Internet access.')
PY
