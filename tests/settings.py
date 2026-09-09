#!/usr/bin/env python3
"""Private config edits, first-run setup and native restart; no Pandora access."""
import contextlib
import json
import os
from pathlib import Path
import re
import runpy
import socket
import stat
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
Configuration = runpy.run_path(str(ROOT / 'pianobar-web'))['Configuration']
with tempfile.TemporaryDirectory(prefix='pianobar-settings-') as temporary:
    base = Path(temporary)
    env = dict(os.environ, XDG_CONFIG_HOME=str(base / 'config'), XDG_DATA_HOME=str(base / 'data'))
    with patch.dict(os.environ, env):
        store = Configuration()
        assert not store.ready()
        store.directory.mkdir(parents=True)
        native = store.directory / 'config'
        native.write_text('# Personal settings\nact_volup = ]\nproxy = \nuser = old\npassword = old-secret\npassword = duplicate-secret\npassword_command = old-helper\n')
        store.save({'settings': {'user': 'listener@example.invalid', 'password': 'new-secret',
                                'audio_quality': 'medium', 'web': {'listen': '0.0.0.0', 'password': 'web-secret'}}})
        assert store.ready()
        text = native.read_text()
        assert '# Personal settings\nact_volup = ]\nproxy = \n' in text
        assert text.count('password = ') == 1 and 'old-secret' not in text and 'duplicate-secret' not in text
        assert 'password_command = \n' in text
        public = json.dumps(store.public())
        assert 'new-secret' not in public and 'web-secret' not in public
        assert store.public()['passwordSet'] and store.public()['web']['passwordSet']
        assert stat.S_IMODE(native.stat().st_mode) == 0o600
        assert stat.S_IMODE((store.directory / 'web.json').stat().st_mode) == 0o600
        store.save({'settings': {'password': '', 'cache_songs': False}})
        assert 'password = new-secret\n' in native.read_text()
        original = native.read_bytes()
        original_web = (store.directory / 'web.json').read_bytes()
        for settings in [{'password': 'secret\nuser = injected'}, {'cache_dir': '../outside'},
                         {'audio_buffer_ms': -1}, {'offline': 'yes'}, {'audio_quality': 'bad'},
                         {'web': {'port': 0}}, {'web': {'listen': 'example.com'}},
                         {'web': {'password': 'secret\x00'}}, {'password_command': 'untrusted command'}]:
            try:
                store.save({'settings': settings})
                raise AssertionError(settings)
            except ValueError:
                pass
            assert native.read_bytes() == original
            assert (store.directory / 'web.json').read_bytes() == original_web
        target = store.directory / 'other'
        native.rename(target)
        native.symlink_to(target)
        try:
            store.save({'settings': {'user': 'no@example.invalid'}})
            raise AssertionError('Symlink write allowed')
        except ValueError:
            pass
        assert target.read_bytes() == original
        native.unlink()
        target.rename(native)
        store.save({'settings': {'clearPassword': True}})
        assert not store.ready() and not store.public()['passwordSet']
        # Fresh setup below has no saved account or web defaults.
        (store.directory / 'web.json').unlink()
        cache = base / 'songs'
        cache.mkdir()
        song = cache / ('a' * 64 + '.mka')
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=duration=60',
                        '-c:a', 'aac', '-metadata', 'title=Setup song', str(song)], check=True)
        refused = socket.socket()
        refused.bind(('127.0.0.1', 0))
        native.write_text(f'cache_dir = {cache}\nact_volup = ]\nrpc_host = 127.0.0.1\n'
                          f'rpc_tls_port = {refused.getsockname()[1]}\ntimeout = 1\n')
    proc = subprocess.Popen([str(ROOT / 'pianobar'), '--port', '0', '--output', 'browser'],
                            env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = bytearray()
    try:
        link = proc.stdout.readline().decode()
        origin = re.search(r'http://localhost:\d+', link)[0]
        def drain():
            while chunk := proc.stdout.read(4096):
                output.extend(chunk)
        threading.Thread(target=drain, daemon=True).start()
        def request(path, data=None, expected=200, headers=None):
            try:
                with urllib.request.urlopen(urllib.request.Request(origin + path,
                        None if data is None else json.dumps(data).encode(),
                        {'Content-Type': 'application/json', **(headers or {})}), timeout=5) as response:
                    assert response.status == expected
                    return json.load(response)
            except urllib.error.HTTPError as error:
                assert error.code == expected, (error.code, error.read())
        def wait_for(condition):
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                state = request('/api/state')
                if condition(state):
                    return state
                time.sleep(.05)
            raise AssertionError(state)
        wait_for(lambda s: s['setupRequired'])
        assert request('/api/settings')['user'] == ''
        request('/api/settings', headers={'Origin': 'https://example.com'}, expected=403)
        request('/api/settings', {'settings': {'password': 'new-secret\n'}}, expected=400)
        request('/api/command', {'output': 'browser'}, expected=400)
        request('/api/settings', {'settings': {'user': 'setup@example.invalid', 'password': 'setup-secret'}, 'apply': True})
        wait_for(lambda s: s['state'].get('title') == 'Setup song')
        public = request('/api/settings')
        assert public['user'] == 'setup@example.invalid' and public['passwordSet']
        assert 'setup-secret' not in json.dumps(public)
        assert 'act_volup = ]' in native.read_text()
        assert stat.S_IMODE(native.stat().st_mode) == 0o600
        # Restart repeatedly while a long-poll request is open. Host stays reachable.
        for _ in range(2):
            request('/api/settings', {'settings': {'offline': True}, 'apply': True})
            wait_for(lambda s: s['state'].get('title') == 'Setup song' and not s['restarting'])
            time.sleep(.2)
        # Failed startup stays available for corrections instead of losing the UI.
        request('/api/settings', {'settings': {'cache_dir': str(base / 'empty')}, 'apply': True})
        wait_for(lambda s: s['playerStopped'] and not s['restarting'])
        assert request('/api/settings')['cache_dir'] == str(base / 'empty')
        request('/api/settings', {'settings': {'cache_dir': str(cache)}, 'apply': True})
        wait_for(lambda s: s['state'].get('title') == 'Setup song' and not s['playerStopped'] and not s['restarting'])
        request('/api/command', {'action': 'act_quit'})
        proc.wait(timeout=8)
        assert proc.returncode == 0
        assert b'setup-secret' not in output
        print('PASS: first-run setup, credential privacy, validation, config preservation/permissions, repeated native restart, failed-start recovery')
    finally:
        if proc.poll() is None:
            proc.terminate()
            proc.wait(timeout=8)
        refused.close()
