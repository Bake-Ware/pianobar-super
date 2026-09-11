#!/usr/bin/env python3
"""Destructive smoke test for a disposable installer-created container only."""
import argparse
import base64
import http.client
import json
import os
from pathlib import Path
import pwd
import struct
import subprocess
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--disposable', action='store_true', required=True)
parser.parse_args()
directory = Path('/var/lib/pianobar/.config/pianobar')
# A second guard prevents this test from altering a real account's settings.
assert 'user = installer-test@example.invalid\n' in (directory / 'config').read_text()
web = json.loads((directory / 'web.json').read_text())
auth = 'Basic ' + base64.b64encode(('pianobar:' + web['password']).encode()).decode()
origin = 'http://127.0.0.1:' + str(web['port'])


def request(path, data=None, authorized=True, expected=200):
    headers = {'Content-Type': 'application/json'}
    if authorized: headers['Authorization'] = auth
    try:
        with urllib.request.urlopen(urllib.request.Request(origin + path,
                None if data is None else json.dumps(data).encode(), headers), timeout=5) as response:
            assert response.status == expected
            body = response.read()
            return json.loads(body) if response.headers.get_content_type() == 'application/json' else body
    except urllib.error.HTTPError as error:
        assert error.code == expected, error.code


def playing():
    for _ in range(100):
        value = request('/api/state')
        if (value['state'].get('title') == 'Installer audio' and
                not value.get('playerStopped') and not value.get('restarting')):
            return value
        time.sleep(.1)
    raise AssertionError('Fixture did not start')


for file in ('config', 'web.json'):
    assert (directory / file).stat().st_mode & 0o777 == 0o600
request('/', authorized=False, expected=401)
request('/api/state', authorized=False, expected=401)
assert b'brand-super' in request('/')
assert web['password'] not in json.dumps(request('/api/settings'))
cache = Path('/var/lib/pianobar/songs')
song = cache / ('d' * 64 + '.mka')
if not song.exists():
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=120',
                '-ac', '2', '-ar', '44100', '-c:a', 'aac', '-metadata', 'title=Installer audio',
                '-metadata', 'artist=Test artist', str(song)], check=True)
account = pwd.getpwnam('pianobar')
os.chown(song, account.pw_uid, account.pw_gid)
song.chmod(0o600)
request('/api/settings', {'settings': {'offline': True}, 'apply': True})
playing()
listing = request('/api/library')['songs']
assert len(listing) == 1 and listing[0]['title'] == 'Installer audio'
request('/api/download/' + song.name, authorized=False, expected=401)
download = request('/api/download/' + song.name)
probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'stream=codec_name', '-of', 'json', '-i', 'pipe:0'],
                       input=download, capture_output=True, check=True)
assert json.loads(probe.stdout)['streams'][0]['codec_name'] == 'aac'
client = request('/api/clients/register', {'name': 'Installer smoke test'})
request('/api/clients/route', {'ids': [client['id']]})
stream = http.client.HTTPConnection('127.0.0.1', web['port'], timeout=5)
stream.request('GET', '/api/audio', headers={'Authorization': auth, 'X-Pianobar-Client': client['id']})
response = stream.getresponse()
assert response.status == 200
for _ in range(10):
    size, rate, channels, _, _ = struct.unpack('!5I', response.read(20))
    if size:
        packet = response.read(size)
        assert rate == 44100 and channels == 2 and any(packet)
        break
else:
    raise AssertionError('No audible PCM frames')
stream.close()
request('/api/clients/unregister', {'id': client['id']})
subprocess.run(['systemctl', 'restart', 'pianobar'], check=True)
for _ in range(40):
    try:
        playing()
        break
    except OSError:
        time.sleep(.25)
assert len(request('/api/library')['songs']) == 1
logs = subprocess.check_output(['journalctl', '-u', 'pianobar', '--no-pager'], text=True)
assert web['password'] not in logs
for line in (directory / 'config').read_text().splitlines():
    if line.startswith('password = '): assert line[len('password = '):] not in logs
subprocess.run(['systemctl', 'is-enabled', '--quiet', 'pianobar'], check=True)
assert not list(Path('/run').glob('pianobar-install.*'))
assert not Path('/run/pianobar-credentials.json').exists()
print('PASS: installed service, authentication, private credentials/logs, offline library, AAC download, PCM playback, restart persistence and staging cleanup')
