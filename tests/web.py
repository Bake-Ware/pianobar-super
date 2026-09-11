#!/usr/bin/env python3
"""Integration checks for the real HTTP host, native player, and optional browser."""
import contextlib
import json
import os
from pathlib import Path
import re
import select
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

with tempfile.TemporaryDirectory(prefix="pianobar-web-tests-") as temporary:
    base = Path(temporary)
    config = base / "config" / "pianobar"
    config.mkdir(parents=True)
    cache = base / "songs"
    cache.mkdir()
    fixture = cache / ("a" * 64 + ".mka")
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "sine=frequency=330:duration=60", "-c:a", "aac",
                    "-metadata", "title=Late afternoon", "-metadata", "artist=Local test session",
                    "-metadata", "album=Offline collection", str(fixture)], check=True, timeout=30)
    fifo = base / "audio"
    os.mkfifo(fifo)
    audio_fd = os.open(fifo, os.O_RDWR | os.O_NONBLOCK)
    stop = threading.Event()
    def drain():
        while not stop.is_set():
            if select.select([audio_fd], [], [], .05)[0]:
                with contextlib.suppress(OSError):
                    os.read(audio_fd, 8192)
                time.sleep(.04)
    threading.Thread(target=drain, daemon=True).start()
    refused = socket.socket()
    refused.bind(("127.0.0.1", 0))
    (config / "config").write_text(
        f"cache_dir = {cache}\naudio_pipe = {fifo}\ntimeout = 1\n"
        f"rpc_host = 127.0.0.1\nrpc_tls_port = {refused.getsockname()[1]}\n"
        "act_volup = ]\n")
    # Record the browser shortcut target without opening a real browser.
    opened_url = base / "opened-url"
    opener = base / "browser-opener"
    opener.write_text("#!/usr/bin/env python3\nimport os, sys\nfrom pathlib import Path\n"
                      "Path(os.environ['PIANOBAR_OPENED_URL']).write_text(sys.argv[1])\n")
    opener.chmod(0o755)
    env = dict(os.environ, XDG_CONFIG_HOME=str(config.parent),
               BROWSER=str(opener) + " %s", PIANOBAR_OPENED_URL=str(opened_url))
    proc = subprocess.Popen([str(ROOT / "pianobar"), "--offline", "--port", "0"],
                            env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = bytearray()
    try:
        link_line = proc.stdout.readline().decode()
        match = re.search(r"http://localhost:(\d+)/\s*$", link_line)
        assert match, link_line
        port = match.group(1)
        origin = f"http://localhost:{port}"
        def consume():
            while chunk := proc.stdout.read(4096):
                output.extend(chunk)
        threading.Thread(target=consume, daemon=True).start()
        def request(path, data=None, headers=None, expected=200):
            headers = {"Content-Type": "application/json", **(headers or {})}
            payload = None if data is None else json.dumps(data).encode()
            try:
                with urllib.request.urlopen(urllib.request.Request(origin + path, payload, headers), timeout=5) as response:
                    assert response.status == expected
                    body = response.read()
                    return json.loads(body) if path.startswith('/api/') else body
            except urllib.error.HTTPError as error:
                assert error.code == expected, (error.code, error.read())
                return json.loads(error.read())
        def wait_for(condition):
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                snapshot = request('/api/state')
                if condition(snapshot):
                    return snapshot
                time.sleep(.05)
            raise AssertionError(snapshot)
        state = wait_for(lambda s: s['state'].get('title') == 'Late afternoon')
        assert len(state['state']['actions']) == 32
        assert '2026.09.08-dev' in state['output']
        proc.stdin.write(b'W')
        proc.stdin.flush()
        deadline = time.monotonic() + 5
        while not opened_url.exists() and time.monotonic() < deadline:
            time.sleep(.05)
        assert opened_url.read_text() == origin + '/'
        assert next(a for a in state['state']['actions'] if a['id'] == 'act_volup')['key'] == ']'
        assert b'<title>pianobar</title>' in request('/')
        request('/api/state', headers={'Sec-Fetch-Site': 'cross-site'}, expected=403)
        request('/api/state', headers={'Origin': 'https://example.com'}, expected=403)
        request('/api/state', headers={'Host': 'evil.invalid'}, expected=403)
        request('/api/command', {'action': 'act_volup'}, headers={'Origin': 'https://example.com'}, expected=403)
        request('/api/command', {'action': 'act_volup'}, headers={'Content-Type': 'text/plain'}, expected=415)
        request('/api/command', {'action': 'act_songlove'}, expected=400)
        request('/api/command', {'action': 'act_volup'})
        wait_for(lambda s: s['state'].get('volume') == 1 and not s['pending'])
        # The physical terminal and browser control the same player.
        proc.stdin.write(b'(')
        proc.stdin.flush()
        wait_for(lambda s: s['state'].get('volume') == 0 and not s['pending'])
        request('/api/command', {'action': 'act_songpausetoggle'})
        wait_for(lambda s: s['state'].get('paused') and not s['pending'])
        request('/api/command', {'action': 'act_offline'})
        prompt = wait_for(lambda s: s['prompt'].get('active'))['prompt']
        assert not prompt['secret']
        request('/api/command', {'action': 'act_volup'}, expected=400)
        request('/api/command', {'text': 'test@example.invalid', 'promptId': prompt['id']})
        prompt = wait_for(lambda s: s['prompt'].get('active') and s['prompt'].get('secret'))['prompt']
        request('/api/command', {'text': 'obsolete', 'promptId': prompt['id'] - 1}, expected=400)
        password = 'private-fixture-password'
        request('/api/command', {'text': password, 'promptId': prompt['id']})
        result = wait_for(lambda s: 'Reconnect failed; staying offline.' in s['output'] and not s['pending'])
        assert password not in result['output']
        assert password not in json.dumps(result['state'])
        second = cache / ('b' * 64 + '.mka')
        subprocess.run(['ffmpeg', '-v', 'error', '-i', str(fixture), '-c', 'copy',
                        '-metadata', 'title=Blue hour', str(second)], check=True, timeout=10)
        # Partial files, corrupt entries, and symlinks must not become playable.
        (cache / '.partial-fixture').write_bytes(b'incomplete')
        (cache / ('c' * 64 + '.mka')).write_bytes(b'corrupt')
        (cache / ('d' * 64 + '.mka')).symlink_to(fixture)
        request('/api/library', headers={'Sec-Fetch-Site': 'cross-site'}, expected=403)
        songs = request('/api/library')['songs']
        assert [song['title'] for song in songs] == ['Blue hour', 'Late afternoon'], songs
        for invalid in ['../../config', 'http://example.com/song', 'e' * 64 + '.mka', 'c' * 64 + '.mka', 'd' * 64 + '.mka']:
            request('/api/command', {'playSaved': invalid}, expected=400)
        request('/api/command', {'playSaved': second.name})
        selected = wait_for(lambda s: s['state'].get('savedId') == second.name and not s['pending'])
        assert selected['state']['offline'] and selected['state']['title'] == 'Blue hour'
        assert not selected['state']['paused']
        request('/api/command', {'playSaved': fixture.name})
        wait_for(lambda s: s['state'].get('savedId') == fixture.name and not s['pending'])
        print('PASS: library enumeration, selected local playback, metadata, unsafe/missing/corrupt files rejected')
        print('PASS: plain localhost access, host/origin checks, all 32 actions, custom keys, shared CLI, prompts, secret input')
        if '--browser' in sys.argv:
            from playwright.sync_api import sync_playwright
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(headless=True, executable_path=os.environ.get("PIANOBAR_TEST_BROWSER"))
                page = browser.new_page(viewport={'width': 1440, 'height': 1080}, device_scale_factor=1)
                page.add_init_script("localStorage.setItem('pianobarAutoListen', 'false')")
                errors = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(origin + '/')
                page.wait_for_function("() => document.getElementById('title').textContent === 'Late afternoon'")
                assert page.locator('#shortcut-list').is_hidden()
                page.locator('#shortcuts-title').click()
                assert page.locator('#shortcut-list').is_visible()
                page.reload()
                page.wait_for_function("() => document.getElementById('title').textContent === 'Late afternoon'")
                assert page.locator('#shortcut-list').is_hidden()
                # Exercise every configured command in an isolated tab without executing
                # destructive/online actions; real playback shortcuts are checked below.
                keys_page = browser.new_page()
                keys_page.add_init_script("localStorage.setItem('pianobarAutoListen', 'false')")
                keys_page.goto(origin)
                keys_page.wait_for_function("() => document.querySelectorAll('[data-shortcut]').length === 32")
                assert keys_page.locator('[data-shortcut="act_volup"] kbd').text_content() == ']'
                assert keys_page.locator('[data-shortcut="act_songpausetoggle2"] kbd').text_content() == 'Space'
                keys_page.evaluate("""() => {
                    render = () => {};
                    snapshot.pending = false;
                    snapshot.exited = false;
                    snapshot.prompt = {};
                    snapshot.state.actions.forEach(a => a.enabled = true);
                    window.receivedKeys = [];
                    command = message => { receivedKeys.push(message.action); };
                    updateDisabled();
                }""")
                for action in state['state']['actions']:
                    keys_page.keyboard.press('Space' if action['key'] == ' ' else action['key'])
                assert keys_page.evaluate('receivedKeys') == [a['id'] for a in state['state']['actions']]
                keys_page.evaluate('receivedKeys = []')
                keys_page.locator('.nav[data-view="library"]').click()
                keys_page.locator('#library-search').focus()
                keys_page.keyboard.type('qnpPS[]')
                assert keys_page.locator('#library-search').input_value() == 'qnpPS[]'
                assert keys_page.evaluate('receivedKeys') == []
                keys_page.evaluate("""() => {
                    document.activeElement.blur();
                    for (const flags of [{ctrlKey: true}, {metaKey: true}, {altKey: true},
                                         {repeat: true}, {isComposing: true}]) {
                        document.dispatchEvent(new KeyboardEvent('keydown', {key: 'q', bubbles: true, ...flags}));
                    }
                    snapshot.pending = true;
                    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'q'}));
                    snapshot.pending = false;
                    snapshot.prompt.active = true;
                    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'q'}));
                    snapshot.prompt.active = false;
                    snapshot.state.actions.find(a => a.id === 'act_quit').enabled = false;
                    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'q'}));
                    const editable = document.createElement('div');
                    editable.contentEditable = 'true';
                    document.body.append(editable);
                    editable.focus();
                    editable.dispatchEvent(new KeyboardEvent('keydown', {key: 'p', bubbles: true}));
                }""")
                assert keys_page.evaluate('receivedKeys') == []
                keys_page.close()
                # Configured volume and case-sensitive playback keys reach the native player.
                page.keyboard.press(']')
                page.wait_for_function("() => document.getElementById('volume').textContent === '1 dB'")
                page.keyboard.press('(')
                page.wait_for_function("() => document.getElementById('volume').textContent === '0 dB'")
                page.keyboard.press('Space')
                page.wait_for_function("() => document.getElementById('play-button').getAttribute('aria-label') === 'Resume playback'")
                page.keyboard.press('P')
                page.wait_for_function("() => document.getElementById('play-button').getAttribute('aria-label') === 'Pause playback'")
                print('PASS: all configured web hotkeys, legend, case/Space, native volume/playback, text fields, modifiers, repeat and prompt guards')
                page.get_by_role('button', name='Increase volume', exact=True).click()
                page.wait_for_function("() => document.getElementById('volume').textContent === '1 dB'")
                page.get_by_role('button', name='Pause playback', exact=True).click()
                page.wait_for_function("() => document.getElementById('play-button').getAttribute('aria-label') === 'Resume playback'")
                # Fresh visits and reloads work without a token; theme and routes persist.
                page.get_by_role('button', name='Dark mode', exact=True).click()
                assert page.evaluate("localStorage.getItem('pianobarTheme')") == 'dark'
                page.reload()
                page.wait_for_function("() => document.documentElement.dataset.theme === 'dark'")
                page.wait_for_function("() => document.getElementById('title').textContent === 'Late afternoon'")
                page.locator('.nav[data-view="stations"]').click()
                assert page.locator('#player').is_hidden()
                assert page.locator('main #stations-page').is_visible()
                assert page.locator('#station-page-actions button').count() == 10
                assert page.locator('.sidebar #station-actions').is_hidden()
                page.reload()
                page.wait_for_function("() => document.getElementById('station-page-actions').children.length === 10")
                assert page.locator('main #stations-page').is_visible()
                assert page.locator('#activity').is_hidden()
                assert page.locator('#library-actions').is_hidden()
                assert page.locator('.nav[data-view="stations"]').get_attribute('aria-current') == 'page'
                page.locator('.nav[data-view="library"]').click()
                assert page.locator('#library-controls').is_hidden()
                assert page.locator('#activity').is_hidden()
                assert page.locator('#saved-library').is_visible()
                page.get_by_role('button', name='Play Blue hour', exact=True).wait_for()
                page.locator('#library-search').fill('blue')
                assert page.locator('.saved-song').count() == 1
                page.get_by_role('button', name='Play Blue hour', exact=True).click()
                page.wait_for_function("() => document.getElementById('title').textContent === 'Blue hour'")
                assert page.title() == 'pianobar Offline library / Blue hour'
                page.locator('#library-search').fill('')
                assert page.locator('.saved-song').count() == 2
                page.screenshot(path='/tmp/pianobar-web-library.png', full_page=True)
                page.get_by_role('button', name='Play Late afternoon', exact=True).click()
                page.wait_for_function("() => document.getElementById('title').textContent === 'Late afternoon'")
                page.reload()
                page.wait_for_function("() => document.getElementById('title').textContent === 'Late afternoon'")
                assert page.locator('#saved-library').is_visible()
                assert page.evaluate("sessionStorage.getItem('pianobarToken')") is None
                page.go_back()
                page.wait_for_function("() => document.body.dataset.view === 'stations'")
                page.locator('.nav[data-view="player"]').click()
                for group in ['station-controls', 'library-controls']:
                    if page.locator(f'#{group}').get_attribute('open') is not None:
                        page.locator(f'#{group} summary').click()
                    assert page.locator(f'#{group} .action-grid').is_hidden()
                assert page.locator('#activity').is_visible()
                assert page.evaluate("getComputedStyle(document.getElementById('transcript')).fontSize") == '12px'
                assert page.locator('.activity-sidebar').count() == 0
                assert page.evaluate("getComputedStyle(document.getElementById('artwork')).transform") == 'none'
                for selector in ['.player-content', '#artwork', '.control-card', '.theme-toggle']:
                    assert page.locator(selector).first.evaluate('(el) => getComputedStyle(el).borderRadius') == '0px'
                assert 'Late afternoon' in page.locator('#transcript').text_content()
                # The background cannot be scrolled manually, so it always follows live output.
                assert page.locator('#transcript').evaluate('(el) => el.scrollHeight - el.scrollTop - el.clientHeight < 2')
                artwork = page.locator('#artwork').bounding_box()
                controls = page.locator('.player-content').bounding_box()
                assert artwork['x'] > controls['x'] + controls['width']
                activity = page.locator('#activity').bounding_box()
                assert activity['x'] > controls['x'] and activity['y'] > controls['y']
                assert activity['x'] + activity['width'] < controls['x'] + controls['width']
                assert activity['y'] + activity['height'] < controls['y'] + controls['height']
                assert page.locator('main > footer').evaluate('(el) => getComputedStyle(el).position') == 'fixed'
                page.screenshot(path='/tmp/pianobar-web-desktop.png', full_page=True)
                page.get_by_role('button', name='Light mode', exact=True).click()
                page.reload()
                page.wait_for_function("() => document.documentElement.dataset.theme === 'light'")
                page.wait_for_function("() => document.getElementById('title').textContent === 'Late afternoon'")
                page.screenshot(path='/tmp/pianobar-web-light.png', full_page=True)
                page.set_viewport_size({'width': 390, 'height': 844})
                page.screenshot(path='/tmp/pianobar-web-mobile.png', full_page=True)
                assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
                assert page.locator('#activity').is_visible()
                page.locator('.nav[data-view="library"]').click()
                assert page.locator('#saved-library').is_visible()
                assert page.locator('#player').is_hidden()
                page.locator('.nav[data-view="settings"]').click()
                page.wait_for_function("() => document.getElementById('save-settings').disabled === false")
                assert page.locator('#activity').is_hidden()
                page.locator('#setting-audio_quality').select_option('medium')
                page.locator('#setting-audio_buffer_ms').fill('350')
                page.get_by_role('button', name='Save settings', exact=True).click()
                page.wait_for_function("() => document.getElementById('settings-status').textContent.startsWith('Saved.')")
                assert 'audio_quality = medium' in (config / 'config').read_text()
                assert 'audio_buffer_ms = 350' in (config / 'config').read_text()
                assert 'act_volup = ]' in (config / 'config').read_text()
                page.reload()
                page.wait_for_function("() => document.getElementById('setting-audio_buffer_ms').value === '350'")
                assert page.locator('#setting-password').input_value() == ''
                assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
                page.screenshot(path='/tmp/pianobar-settings-mobile.png', full_page=True)
                page.set_viewport_size({'width': 1440, 'height': 1080})
                page.screenshot(path='/tmp/pianobar-settings-desktop.png', full_page=True)
                assert not errors, errors
                # A fresh session persists credentials through browser setup.
                login_proc = subprocess.Popen([str(ROOT / "pianobar"), "--port", "0"],
                    env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                try:
                    login_link = login_proc.stdout.readline().decode().strip().split(' ', 2)[-1]
                    login_output = bytearray()
                    def consume_login():
                        while chunk := login_proc.stdout.read(4096):
                            login_output.extend(chunk)
                    threading.Thread(target=consume_login, daemon=True).start()
                    page.goto(login_link)
                    page.locator('#settings-page').wait_for(state='visible')
                    page.locator('#setting-user').fill('browser@example.invalid')
                    page.locator('#setting-password').fill('browser-private-password')
                    page.get_by_role('button', name='Save & start listening', exact=True).click()
                    page.wait_for_function("() => document.getElementById('station').textContent === 'Offline library'")
                    page.locator('#prompt-dialog').wait_for(state='hidden')
                    page.locator('summary').filter(has_text='More controls').click()
                    page.get_by_role('button', name='Quit player', exact=True).click()
                    login_proc.wait(timeout=8)
                    assert login_proc.returncode == 0
                    assert b'browser-private-password' not in login_output
                finally:
                    if login_proc.poll() is None:
                        login_proc.terminate()
                        login_proc.wait(timeout=8)
                assert not errors, errors
                browser.close()
            print('PASS: themes/persistence, navigation/reload/back, terminal backdrop, browser controls and login')
        wait_for(lambda s: not s['pending'])
        request('/api/command', {'action': 'act_quit'})
        proc.wait(timeout=8)
        assert proc.returncode == 0
        print('PASS: browser quit shuts down player and HTTP host')
    finally:
        if proc.poll() is None:
            proc.terminate()
            proc.wait(timeout=8)
        stop.set()
        os.close(audio_fd)
        refused.close()
