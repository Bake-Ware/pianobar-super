#!/usr/bin/env python3
"""Authenticated LAN host and real native/browser PCM, without Pandora or speakers."""
import base64
import contextlib
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import select
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='pianobar-network-') as temporary:
    base = Path(temporary)
    config = base / 'config' / 'pianobar'
    config.mkdir(parents=True)
    cache = base / 'songs'
    (cache / 'artwork').mkdir(parents=True)
    song = cache / ('a' * 64 + '.mka')
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=60',
                    '-ac', '2', '-ar', '44100', '-c:a', 'aac', '-metadata', 'title=LAN audio',
                    '-metadata', 'artist=Test artist', '-metadata', 'album=Test album', str(song)], check=True)
    image_id = hashlib.sha256(b'Test artist\0Test album\0\0').hexdigest()
    image = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aO2kAAAAASUVORK5CYII=')
    (cache / 'artwork' / (image_id + '.img')).write_bytes(image)
    (cache / 'artwork' / ('b' * 64 + '.img')).symlink_to(song)
    (cache / 'artwork' / ('c' * 64 + '.img')).write_text('<svg>not cached raster art</svg>')
    fifo = base / 'audio'
    os.mkfifo(fifo)
    audio_fd = os.open(fifo, os.O_RDWR | os.O_NONBLOCK)
    host_bytes = [0]
    stop = threading.Event()
    def drain():
        while not stop.is_set():
            if select.select([audio_fd], [], [], .05)[0]:
                data = os.read(audio_fd, 4096)
                host_bytes[0] += len(data)
                stop.wait(len(data) / (44100 * 4))
    threading.Thread(target=drain, daemon=True).start()
    (config / 'config').write_text(f'cache_dir = {cache}\naudio_pipe = {fifo}\n')
    password_file = base / 'password'
    password_file.write_text('test-network-password\n')
    env = dict(os.environ, XDG_CONFIG_HOME=str(config.parent))
    proc = subprocess.Popen([str(ROOT / 'pianobar'), '--offline', '--port', '0', '--listen', '0.0.0.0',
                             '--password-file', str(password_file), '--output', 'browser'],
                            env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        link = proc.stdout.readline().decode()
        port = int(re.search(r'localhost:(\d+)', link).group(1))
        threading.Thread(target=lambda: proc.stdout.read(), daemon=True).start()
        origin = f'http://localhost:{port}'
        auth = 'Basic ' + base64.b64encode(b'pianobar:test-network-password').decode()
        def request(path, data=None, authorized=True, expected=200, headers=None):
            h = {'Content-Type': 'application/json', **({'Authorization': auth} if authorized else {}), **(headers or {})}
            try:
                with urllib.request.urlopen(urllib.request.Request(origin + path, None if data is None else json.dumps(data).encode(), h), timeout=5) as response:
                    assert response.status == expected
                    body = response.read()
                    return json.loads(body) if response.headers.get_content_type() == 'application/json' else body
            except urllib.error.HTTPError as error:
                assert error.code == expected, (error.code, error.read())
        def wait_for(condition):
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                state = request('/api/state')
                if condition(state): return state
                time.sleep(.05)
            raise AssertionError(state)
        state = wait_for(lambda s: s['state'].get('title') == 'LAN audio')
        assert state['state']['output'] == 'browser' and host_bytes[0] == 0
        for path in ['/', '/api/state', '/api/audio', '/api/artwork/' + image_id]:
            request(path, authorized=False, expected=401)
        request('/api/state', headers={'Authorization': 'Basic wrong'}, expected=401)
        request('/api/state', headers={'Origin': 'https://example.com'}, expected=403)
        assert request('/api/artwork/' + image_id) == image
        for invalid in ['../config', 'b' * 64, 'c' * 64]:
            request('/api/artwork/' + invalid, expected=404)
        assert request('/api/library')['songs'][0]['cover'] == '/api/artwork/' + image_id
        assert state['state']['cachedCover'] == '/api/artwork/' + image_id
        stream = http.client.HTTPConnection('localhost', port, timeout=5)
        stream.request('GET', '/api/audio', headers={'Authorization': auth})
        response = stream.getresponse()
        assert response.status == 200
        for _ in range(5):
            size, rate, channels, little, epoch = struct.unpack('!5I', response.read(20))
            if size:
                pcm = response.read(size)
                assert rate == 44100 and channels == 2 and little in (0, 1)
                assert len(pcm) == size and any(pcm)
                break
        else: raise AssertionError('No native PCM received')
        stream.close()
        request('/api/command', {'output': 'both'})
        wait_for(lambda s: s['state'].get('output') == 'both' and not s['pending'])
        deadline = time.monotonic() + 3
        while host_bytes[0] == 0 and time.monotonic() < deadline: time.sleep(.05)
        assert host_bytes[0] > 0
        request('/api/command', {'output': 'browser'})
        wait_for(lambda s: s['state'].get('output') == 'browser' and not s['pending'])
        baseline = host_bytes[0]
        # libao flushes buffered bytes on close; allow the paced FIFO reader to
        # drain them, then require a continuous quiet interval.
        deadline = time.monotonic() + 3
        quiet_since = time.monotonic()
        while time.monotonic() < deadline:
            time.sleep(.05)
            if host_bytes[0] != baseline:
                baseline = host_bytes[0]
                quiet_since = time.monotonic()
            if time.monotonic() - quiet_since >= .5:
                break
        else: raise AssertionError('Host audio continued after switching to Browser')
        print('PASS: LAN authentication, origin checks, local artwork/fallbacks, native PCM stream, Both/Browser output routing')
        if '--browser' in sys.argv:
            from playwright.sync_api import sync_playwright
            with sync_playwright() as p:
                browser = p.chromium.launch(executable_path=os.environ.get('PIANOBAR_TEST_BROWSER'), args=['--mute-audio'])
                page = browser.new_page(http_credentials={'username': 'pianobar', 'password': 'test-network-password'},
                                        viewport={'width': 1440, 'height': 1080})
                errors = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(origin + '/#library')
                page.wait_for_function("() => document.querySelector('.saved-artwork img')?.naturalWidth > 0")
                assert page.locator('#favicon').get_attribute('href') == '/api/artwork/' + image_id
                page.screenshot(path='/tmp/pianobar-web-cached-art.png', full_page=True)
                page.locator('.nav[data-view="player"]').click()
                page.get_by_role('button', name='Listen here', exact=True).click()
                page.wait_for_function("() => document.getElementById('audio-status').textContent === 'Playing in this browser'")
                assert page.evaluate('audioContext.state') == 'running'
                assert page.evaluate('audioSources.size') > 0
                wait_for(lambda s: not s['pending'])
                other = browser.new_page(http_credentials={'username': 'pianobar', 'password': 'test-network-password'})
                other.on('pageerror', lambda error: errors.append(str(error)))
                other.goto(origin)
                other.get_by_role('button', name='Listen here', exact=True).click()
                other.wait_for_function('() => audioSources.size > 0')
                page.wait_for_function('() => audioSources.size > 0')
                other.get_by_role('button', name='Stop listening', exact=True).click()
                assert other.evaluate('audioController') is None
                page.wait_for_function('() => audioSources.size > 0')
                other.close()
                wait_for(lambda s: not s['pending'])
                page.locator('#audio-output').select_option('both')
                wait_for(lambda s: s['state'].get('output') == 'both' and not s['pending'])
                page.locator('#audio-output').select_option('host')
                wait_for(lambda s: s['state'].get('output') == 'host' and not s['pending'])
                assert page.evaluate('audioController') is None
                page.locator('#audio-output').select_option('browser')
                wait_for(lambda s: s['state'].get('output') == 'browser' and not s['pending'])
                page.wait_for_function('() => audioSources.size > 0')
                page.locator('#play-button').click()
                wait_for(lambda s: s['state'].get('paused') and not s['pending'])
                page.wait_for_function('() => audioSources.size === 0')
                page.locator('#play-button').click()
                wait_for(lambda s: not s['state'].get('paused') and not s['pending'])
                page.wait_for_function('() => audioSources.size > 0')
                page.get_by_role('button', name='Stop listening', exact=True).click()
                assert page.evaluate('audioController') is None
                assert page.evaluate('audioSources.size') == 0
                assert not errors, errors
                browser.close()
            print('PASS: password login, cached library thumbnails/favicon, simultaneous browsers, output selector, pause/resume, stop/cleanup')
        wait_for(lambda s: not s['pending'])
        request('/api/command', {'action': 'act_quit'})
        proc.wait(timeout=8)
        assert proc.returncode == 0
    finally:
        if proc.poll() is None:
            proc.terminate()
            proc.wait(timeout=8)
        stop.set()
        os.close(audio_fd)
