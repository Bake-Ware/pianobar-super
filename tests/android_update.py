#!/usr/bin/env python3
"""The Android updater's view of the published APK: version, sha256 and auth."""
import base64
import hashlib
import http.server
import json
import os
from pathlib import Path
import runpy
import shutil
import struct
import subprocess
import tempfile
import threading
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
module = runpy.run_path(str(ROOT / 'pianobar-web'))
android_manifest, android_apk_info, Handler = module['android_manifest'], module['android_apk_info'], module['Handler']


def axml(package, code, name, utf8):
    """A minimal compiled manifest, laid out the way aapt2 writes one."""
    strings = ['versionCode', 'versionName', 'package', 'manifest', package, name]
    if utf8:
        encoded = [bytes([len(s), len(s.encode())]) + s.encode() + b'\0' for s in strings]
    else:
        encoded = [struct.pack('<H', len(s)) + s.encode('utf-16-le') + b'\0\0' for s in strings]
    offsets, body = [], b''
    for item in encoded:
        offsets.append(len(body))
        body += item
    body += b'\0' * (-len(body) % 4)
    start = 28 + 4 * len(strings)
    pool = struct.pack('<HHIIIIII', 1, 28, start + len(body), len(strings), 0, 0x100 if utf8 else 0, start, 0)
    pool += b''.join(struct.pack('<I', o) for o in offsets) + body
    ids = struct.pack('<HHI', 0x0180, 8, 16) + struct.pack('<II', 0x0101021b, 0x0101021c)
    none = 0xffffffff
    attributes = [(none, 0, none, 0x10, code), (none, 1, 5, 0x03, 5), (none, 2, 4, 0x03, 4)]
    attrs = b''.join(struct.pack('<IIIHBBI', ns, key, raw, 8, 0, kind, value) for ns, key, raw, kind, value in attributes)
    ext = struct.pack('<IIHHHHHH', none, 3, 20, 20, len(attributes), 0, 0, 0)
    element = struct.pack('<HHIII', 0x0102, 16, 16 + len(ext) + len(attrs), 1, none) + ext + attrs
    chunks = pool + ids + element
    return struct.pack('<HHI', 3, 8, 8 + len(chunks)) + chunks


for utf8 in (True, False):
    parsed = android_manifest(axml('org.example.app', 41, 'v41 ✓', utf8))
    assert parsed == {'package': 'org.example.app', 'versionCode': 41, 'versionName': 'v41 ✓'}, parsed
for broken in (b'', b'\x03\0\x08\0\xff\xff\0\0', b'<manifest versionCode="1"/>'):
    try:
        android_manifest(broken)
        raise AssertionError('accepted a broken manifest')
    except (ValueError, struct.error):
        pass

# A real build, when one is around, must agree with the SDK's own reader.
built = ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'
aapt2 = next(iter(sorted(Path(os.environ.get('ANDROID_HOME', '/nonexistent')).glob('build-tools/*/aapt2'))), None)
if built.is_file() and aapt2:
    badging = subprocess.run([str(aapt2), 'dump', 'badging', str(built)], capture_output=True, text=True, check=True).stdout
    info = android_apk_info(built)
    assert f"name='{info['package']}' versionCode='{info['versionCode']}' versionName='{info['versionName']}'" in badging, (info, badging[:200])
    assert info['sha256'] == hashlib.sha256(built.read_bytes()).hexdigest()

with tempfile.TemporaryDirectory(prefix='pianobar-android-') as temporary:
    assets = Path(temporary)
    apk = assets / 'pianobar.apk'

    def publish(code, name):
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('AndroidManifest.xml', axml('org.pianobarsuper.app', code, name, True))
            archive.writestr('classes.dex', os.urandom(64))
        os.utime(apk, ns=(code * 10**9, code * 10**9))

    class Configuration:
        def public(self):
            return {}

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads, server.network, server.bind_host = True, False, '127.0.0.1'
    server.authorization = b'Basic ' + base64.b64encode(b'pianobar:fixture-password')
    server.assets, server.configuration, server.session = assets, Configuration(), None
    threading.Thread(target=server.serve_forever, daemon=True).start()
    host = f'127.0.0.1:{server.server_port}'

    def get(path, expected=200, auth=True):
        request = urllib.request.Request(f'http://{host}{path}')
        if auth:
            request.add_header('Authorization', server.authorization.decode())
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read()
        assert status == expected, (path, status, body[:200])
        return json.loads(body) if body.startswith(b'{') else body

    try:
        get('/api/android/version', 404)
        assert get('/api/settings')['androidApp'] is None
        publish(6, '2026.10.11')
        get('/api/android/version', 401, auth=False)
        version = get('/api/android/version')
        assert version == {'package': 'org.pianobarsuper.app', 'versionCode': 6, 'versionName': '2026.10.11',
                           'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'size': apk.stat().st_size,
                           'updated': 6, 'url': '/pianobar.apk'}, version
        assert get('/pianobar.apk') == apk.read_bytes()
        assert get('/api/settings')['androidApp']['versionCode'] == 6
        # Publishing a new file is picked up without a restart.
        publish(7, '2026.10.12')
        assert get('/api/android/version')['versionCode'] == 7
        assert get('/api/android/version')['sha256'] == hashlib.sha256(apk.read_bytes()).hexdigest()
        # Something that is not an APK, or a symlink, is never offered as an update.
        apk.write_bytes(b'not a zip')
        get('/api/android/version', 404)
        target = assets / 'elsewhere.apk'
        apk.unlink()
        publish(8, 'linked')
        shutil.move(apk, target)
        apk.symlink_to(target)
        get('/api/android/version', 404)
    finally:
        server.shutdown()
        server.server_close()
print('android update: ok')
