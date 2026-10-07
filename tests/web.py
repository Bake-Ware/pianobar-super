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
               PIANOBAR_METADATA_NETWORK='0', BROWSER=str(opener) + " %s", PIANOBAR_OPENED_URL=str(opened_url))
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
        assert b'<title>pianobar widget</title>' in request('/widget')
        assert b'widget.js' in request('/widget')
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
        request('/api/command', {'queueSaved': second.name})
        queued = wait_for(lambda s: s['state'].get('queuedSavedId') == second.name and not s['pending'])
        assert queued['state']['savedId'] == fixture.name
        request('/api/command', {'queueSaved': '', 'expectedSongKey': queued['state']['songKey']})
        wait_for(lambda s: not s['state'].get('queuedSavedId') and not s['pending'])
        request('/api/command', {'queueSaved': second.name, 'expectedSongKey': 'outdated'}, expected=400)
        request('/api/command', {'queueSaved': '../../config'}, expected=400)
        request('/api/command', {'queueSaved': second.name})
        wait_for(lambda s: s['state'].get('queuedSavedId') == second.name and not s['pending'])
        request('/api/command', {'action': 'act_songnext'})
        wait_for(lambda s: s['state'].get('savedId') == second.name and not s['pending'])
        request('/api/command', {'queueSaved': []}, expected=400)
        request('/api/command', {'queueSaved': [second.name, second.name]}, expected=400)
        request('/api/command', {'queueSaved': [fixture.name, second.name]})
        queued = wait_for(lambda s: s['state'].get('queuedSavedIds') == [fixture.name, second.name] and not s['pending'])
        assert queued['state']['savedId'] == second.name
        request('/api/command', {'action': 'act_songnext'})
        wait_for(lambda s: s['state'].get('savedId') == fixture.name and s['state'].get('queuedSavedIds') == [second.name] and not s['pending'])
        request('/api/command', {'playSaved': fixture.name})
        wait_for(lambda s: s['state'].get('savedId') == fixture.name and not s['pending'])
        assert request('/api/now-playing')['title'] == 'Late afternoon'
        assert 'dj' in request('/api/queue')
        print('PASS: cached next-song queue, uninterrupted current song, stale/invalid selection guards')
        print('PASS: library enumeration, selected local playback, metadata, unsafe/missing/corrupt files rejected')
        print('PASS: plain localhost access, host/origin checks, all 32 actions, custom keys, shared CLI, prompts, secret input')
        if '--browser' in sys.argv:
            from playwright.sync_api import sync_playwright
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(headless=True, executable_path=os.environ.get("PIANOBAR_TEST_BROWSER"), args=['--mute-audio'])
                page = browser.new_page(viewport={'width': 1440, 'height': 1080}, device_scale_factor=1)
                page.add_init_script("localStorage.setItem('pianobarAutoListen', 'false')")
                errors = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(origin + '/')
                page.wait_for_function("() => document.getElementById('title').textContent === 'Late afternoon'")
                # Missing native and Pandora art must fall through to recovered local art.
                import base64
                png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')
                broken_art = '/api/artwork/' + 'e' * 64
                recovered_art = '/api/artwork/' + 'f' * 64
                page.route('**' + broken_art, lambda route: route.fulfill(status=404))
                page.route('https://art.example.test/missing.jpg', lambda route: route.fulfill(status=404))
                page.route('**' + recovered_art, lambda route: route.fulfill(content_type='image/png', body=png))
                youtube_requests = []
                page.on('request', lambda req: youtube_requests.append(req.url) if 'youtube.com' in req.url else None)
                # Later server snapshots carry the same test state, so a poll can't undo it.
                art_patch = dict(cachedCover=broken_art, cover='https://art.example.test/missing.jpg', offline=False,
                                 metadata=dict(cover=recovered_art, genres=['hip hop'], genreScope='artist', releaseDate='2008'))
                def patch_art(route):
                    try:
                        response = route.fetch()
                        data = response.json()
                        data['state'].update(art_patch)
                        route.fulfill(response=response, body=json.dumps(data))
                    except Exception:
                        pass
                page.route('**/api/state*', patch_art)
                page.evaluate("""([broken, recovered]) => {
                    snapshot.state.cachedCover = broken;
                    snapshot.state.cover = 'https://art.example.test/missing.jpg';
                    snapshot.state.offline = false;
                    snapshot.state.metadata = {cover: recovered, genres: ['hip hop'], genreScope: 'artist', releaseDate: '2008'};
                    render(snapshot);
                }""", [broken_art, recovered_art])
                page.wait_for_function("""recovered => document.getElementById('cover').naturalWidth > 0 &&
                    document.getElementById('cover').getAttribute('src') === recovered""", arg=recovered_art)
                assert page.locator('#song-metadata').text_content() == 'Artist genres: hip hop · 2008'
                assert 'Local%20test%20session%20Late%20afternoon' in page.locator('#youtube-search').get_attribute('href')
                assert page.locator('iframe').count() == 0
                assert youtube_requests == []
                page.unroute('**/api/state*')
                page.evaluate("""() => {
                    snapshot.state.cachedCover = '';
                    snapshot.state.cover = '';
                    snapshot.state.metadata = {};
                    snapshot.state.offline = true;
                    render(snapshot);
                }""")
                print('PASS: artwork fallback, metadata display, YouTube link without embeds or requests')
                # The server writes, voices and mixes the DJ; the page only shows and
                # steers it. Inject DJ state into snapshots and record control requests.
                real = page.evaluate('snapshot.djStation')
                if not real.get('llmReady') and not real.get('voiceReady'):
                    assert page.locator('#dj-booth').is_hidden() and page.locator('.dj-try').is_hidden()
                dj_station = dict(real, llmReady=True, voiceReady=True, talk=False, hop=False, hopSongs=4, enabled=False,
                                  theme='A varied mix', settings=dict(real.get('settings', {}), dj_name='DJ Test'))
                dj_voice = {'id': 0, 'status': 'idle', 'text': '', 'error': ''}
                state_patch = {}
                def inject(route):
                    try:
                        response = route.fetch()
                        data = response.json()
                        data.update(djStation=dj_station, djVoice=dj_voice)
                        data['state'].update(state_patch)
                        route.fulfill(response=response, body=json.dumps(data))
                    except Exception:
                        pass  # The page navigated or the route was removed mid-poll.
                page.route('**/api/state*', inject)
                dj_posts = []
                def dj_post(route):
                    body = json.loads(route.request.post_data)
                    dj_posts.append((route.request.url.rsplit('/api/', 1)[1], body))
                    dj_station.update({k: v for k, v in body.items() if k in ('talk', 'hop', 'enabled')})
                    route.fulfill(content_type='application/json', body=json.dumps(
                        dj_station if route.request.url.endswith('/control') else dict(dj_voice, status='preparing')))
                for path in ('control', 'announce', 'introduce'):
                    page.route('**/api/dj/' + path, dj_post)
                page.evaluate('([station, voice]) => { snapshot.djStation = station; snapshot.djVoice = voice; render(snapshot); }', [dj_station, dj_voice])
                page.wait_for_function("() => !document.getElementById('dj-booth').hidden && document.getElementById('dj-name').textContent === 'DJ Test'")
                # The booth sits in the player controls, above the fold; More controls moved to the sidebar.
                assert page.locator('.player-controls #dj-booth').count() == 1
                assert page.locator('.sidebar #more-actions').count() == 1
                assert page.locator('#dj-booth').bounding_box()['y'] < 1080
                assert page.locator('#dj-state').text_content() == 'Off'
                page.locator('#dj-talk-switch').click()
                page.wait_for_function("() => document.getElementById('dj-talk').checked")
                assert dj_posts[-1] == ('dj/control', {'talk': True})
                # Hopping needs Pandora; the test server plays the offline library.
                assert page.locator('#dj-hop').is_disabled()
                dj_key = page.evaluate('snapshot.state.songKey')
                dj_voice.update(id=1, status='on_air', text='Coming at ya with some London punk!', songKey=dj_key)
                page.evaluate('([station, voice]) => { snapshot.djStation = station; snapshot.djVoice = voice; render(snapshot); }', [dj_station, dj_voice])
                page.wait_for_function("() => document.getElementById('dj-booth').dataset.state === 'on-air'")
                assert page.locator('#dj-line').text_content() == 'Coming at ya with some London punk!'
                page.locator('#dj-stop').click()
                assert ('dj/control', {'stopVoice': True}) in dj_posts
                # Lines and on-demand intros live in Settings, not the booth.
                assert page.locator('#dj-booth #dj-text, #dj-booth #dj-introduce').count() == 0
                dj_voice.update(status='done')
                page.locator('.nav[data-view="settings"]').click()
                page.locator('#settings-tab-dj').click()
                page.locator('#dj-text').fill('Another song coming your way!')
                page.locator('#dj-text').press('Enter')
                page.wait_for_function("() => document.getElementById('dj-text').value === ''")
                assert dj_posts[-1] == ('dj/announce', {'text': 'Another song coming your way!', 'songKey': dj_key})
                page.locator('#dj-introduce').click()
                page.wait_for_function("() => !document.getElementById('notice')?.hidden")
                assert dj_posts[-1] == ('dj/introduce', {})
                # Each control needs what it uses; with neither provider there is no DJ.
                dj_station.update(llmReady=False)
                page.evaluate('([station, voice]) => { snapshot.djStation = station; snapshot.djVoice = voice; render(snapshot); }', [dj_station, dj_voice])
                page.wait_for_function("() => document.getElementById('dj-introduce').hidden && !document.getElementById('dj-say').hidden")
                dj_station.update(voiceReady=False)
                page.evaluate('([station, voice]) => { snapshot.djStation = station; snapshot.djVoice = voice; render(snapshot); }', [dj_station, dj_voice])
                page.wait_for_function("() => document.querySelector('.dj-try').hidden")
                page.locator('.nav[data-view="player"]').click()
                page.wait_for_function("() => document.getElementById('dj-booth').hidden")
                dj_station.update(llmReady=True, voiceReady=True)
                page.evaluate('([station, voice]) => { snapshot.djStation = station; snapshot.djVoice = voice; render(snapshot); }', [dj_station, dj_voice])
                # Song links are right-aligned icons in the controls.
                state_patch['metadata'] = {'recordingId': '0f2ec8d4-5c4a-4f43-9d0f-0b1f8a2a1a11'}
                page.evaluate("snapshot.state.metadata = {recordingId: '0f2ec8d4-5c4a-4f43-9d0f-0b1f8a2a1a11'}; render(snapshot)")
                assert page.locator('#youtube-search svg').count() == 1 and page.locator('#metadata-source svg').count() == 1
                assert page.locator('#metadata-source').is_visible()
                assert page.evaluate("getComputedStyle(document.getElementById('song-links')).justifyContent") == 'flex-end'
                links, controls = page.locator('#song-links').bounding_box(), page.locator('.player-controls').bounding_box()
                assert page.locator('#metadata-source').bounding_box()['x'] + 40 > links['x'] + links['width'] - 40
                # The links sit in the top-right corner, and the listen toggle sits beside Next.
                assert links['y'] < controls['y'] + 70 and links['x'] + links['width'] > controls['x'] + controls['width'] - 80
                assert page.locator('.transport-main #listen-button svg').count() == 1
                assert page.locator('#listen-button').get_attribute('aria-label') == 'Listen here'
                assert page.locator('#browser-name').is_hidden() and page.locator('#audio-output-panel summary').is_visible()
                page.screenshot(path='/tmp/pianobar-dj-desktop.png')
                page.set_viewport_size({'width': 390, 'height': 844})
                assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
                page.locator('#dj-booth').scroll_into_view_if_needed()
                page.screenshot(path='/tmp/pianobar-dj-mobile.png')
                page.set_viewport_size({'width': 1440, 'height': 1080})
                page.unroute('**/api/state*')
                for path in ('control', 'announce', 'introduce'):
                    page.unroute('**/api/dj/' + path)
                print('PASS: DJ booth in the controls, server-driven state, switches, stop, Settings lines and intros, icon links')
                # Library groups by metadata in tabs; rows are reused, not rebuilt, between refreshes.
                page.locator('.nav[data-view="library"]').click()
                page.wait_for_function("() => document.querySelectorAll('#saved-songs .saved-song').length === 2")
                first_row = page.evaluate_handle("document.querySelector('#saved-songs .saved-song')")
                page.evaluate("savedSongs = JSON.parse(JSON.stringify(savedSongs)); libraryRenderKey = ''; renderLibrary()")
                assert page.evaluate("row => row === document.querySelector('#saved-songs .saved-song')", first_row)
                page.locator('#library-tab-artists').click()
                assert page.locator('.library-group').count() == 1
                assert page.locator('.library-group strong').text_content() == 'Local test session'
                assert '2 songs' in page.locator('.library-group small').text_content()
                page.locator('.library-group > summary').click()
                page.wait_for_function("() => document.querySelectorAll('.library-group-songs .saved-song').length === 2")
                page.locator('#library-tab-albums').click()
                assert page.locator('.library-group strong').all_text_contents() == ['Offline collection']
                assert page.locator('.library-group small').text_content().startswith('Local test session · 2 songs')
                def with_genres(route):
                    response = route.fetch()
                    data = response.json()
                    for index, song in enumerate(data['songs']):
                        song['genres'] = ['jazz'] if index else ['ambient', 'jazz']
                        song['cover'] = '/api/artwork/' + 'e' * 64
                    route.fulfill(response=response, body=json.dumps(data))
                page.route('**/api/library', with_genres)
                page.route('**/api/artwork/' + 'e' * 64, lambda route: route.fulfill(content_type='image/png', body=png))
                page.evaluate('refreshLibrary()')
                # A background refresh never flickers the button.
                assert page.locator('#refresh-library').is_enabled()
                page.wait_for_function("() => savedSongs.every(song => song.genres?.includes('jazz'))")
                page.locator('#library-tab-genres').click()
                assert page.locator('.library-group strong').all_text_contents() == ['ambient', 'jazz']
                page.locator('#library-search').fill('ambient')
                assert page.locator('.library-group strong').all_text_contents() == ['ambient', 'jazz']
                page.locator('#library-search').fill('')
                page.locator('#library-tab-songs').click()
                # Lazy covers load (they must not start hidden) and show once decoded.
                page.wait_for_function("() => [...document.querySelectorAll('#saved-songs .saved-artwork img')].length === 2 && [...document.querySelectorAll('#saved-songs .saved-artwork img')].every(img => img.naturalWidth > 0 && img.classList.contains('loaded'))")
                page.unroute('**/api/library')
                page.unroute('**/api/artwork/' + 'e' * 64)
                # Playlists: add from the library, reorder, rename, play and delete.
                page.locator('.nav[data-view="playlists"]').click()
                page.wait_for_function("() => playlistsLoaded")
                assert page.locator('#playlist-empty').is_visible() and page.locator('#dj-playlist-form').is_visible() == bool(page.evaluate('snapshot.djStation?.llmReady'))
                page.locator('.nav[data-view="library"]').click()
                page.locator('#saved-songs .saved-add').first.click()
                assert page.locator('#playlist-picker').is_visible()
                page.locator('#playlist-picker-name').fill('Road trip')
                page.locator('#playlist-picker-name').press('Enter')
                page.wait_for_function("() => playlists.length === 1 && playlists[0].songs.length === 1")
                assert page.locator('#playlist-picker').is_hidden()
                page.locator('#saved-songs .saved-add').nth(1).click()
                page.locator('#playlist-picker-list .playlist-card').click()
                page.wait_for_function("() => playlists[0].songs.length === 2")
                page.locator('.nav[data-view="playlists"]').click()
                assert page.locator('#playlist-list .playlist-card strong').all_text_contents() == ['Road trip']
                first_titles = page.locator('#playlist-songs .saved-song-title').all_text_contents()
                assert len(first_titles) == 2
                page.get_by_role('button', name='Move ' + first_titles[0] + ' down').click()
                page.wait_for_function("titles => [...document.querySelectorAll('#playlist-songs .saved-song-title')].map(e => e.textContent).join() === titles", arg=','.join(first_titles[::-1]))
                page.locator('#playlist-name').fill('Highway songs')
                page.locator('#playlist-name').press('Enter')
                page.wait_for_function("() => playlists[0].name === 'Highway songs'")
                page.locator('#playlist-play').click()
                page.wait_for_function("titles => snapshot.state.title === titles[0] && snapshot.playlist?.name === 'Highway songs'", arg=first_titles[::-1])
                page.wait_for_function("() => document.querySelector('#playlist-songs .playlist-song.current') && !document.getElementById('playlist-stop').hidden")
                page.wait_for_function("() => (snapshot.state.queuedSavedIds || []).length === 1")
                page.screenshot(path='/tmp/pianobar-playlists.png')
                page.set_viewport_size({'width': 390, 'height': 844})
                assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
                page.screenshot(path='/tmp/pianobar-playlists-phone.png', full_page=True)
                page.set_viewport_size({'width': 1280, 'height': 900})
                page.locator('#playlist-stop').click()
                page.wait_for_function("() => document.getElementById('playlist-stop').hidden")
                page.locator('#playlist-delete').click()
                assert page.locator('#playlist-delete').text_content() == 'Really delete?'
                page.locator('#playlist-delete').click()
                page.wait_for_function("() => playlists.length === 0 && !document.getElementById('playlist-empty').hidden")
                print('PASS: playlists from the library picker, reorder, rename, server playback with queueing, delete')
                # Stations: with one match, Enter plays it.
                station_patch = dict(stations=[dict(id='s1', name='Jazz radio', quickMix=False), dict(id='s2', name='Punk radio', quickMix=False)], offline=False)
                def with_stations(route):
                    try:
                        response = route.fetch()
                        data = response.json()
                        data['state'].update(station_patch)
                        for action in data['state'].get('actions', []):
                            if action['id'] == 'act_stationchange':
                                action['enabled'] = True
                        route.fulfill(response=response, body=json.dumps(data))
                    except Exception:
                        pass
                page.route('**/api/state*', with_stations)
                page.evaluate("""([patch]) => {
                    window.stationCommands = [];
                    window.realCommand = command;
                    command = message => { stationCommands.push(message); return Promise.resolve(true); };
                    Object.assign(snapshot.state, patch);
                    snapshot.state.actions.forEach(action => { if (action.id === 'act_stationchange') action.enabled = true; });
                    stationRenderKey = '';
                    render(snapshot);
                }""", [station_patch])
                page.locator('.nav[data-view="stations"]').click()
                page.locator('#station-search').fill('radio')
                page.locator('#station-search').press('Enter')
                assert page.evaluate('stationCommands') == []
                page.locator('#station-search').fill('punk')
                page.locator('#station-search').press('Enter')
                assert page.evaluate('stationCommands') == [{'selectStation': 's2'}]
                page.locator('#station-search').fill('')
                page.unroute('**/api/state*')
                page.evaluate("command = realCommand; snapshot.state.stations = []; snapshot.state.offline = true; stationRenderKey = ''; render(snapshot)")
                page.locator('.nav[data-view="player"]').click()
                print('PASS: library tabs by artist/album/genre with reused rows; single-match Enter plays a station')
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
                # Settings are tabs over one form: edits on every tab save together.
                page.locator('#settings-tab-listening').click()
                assert page.locator('#settings-panel-dj').is_hidden()
                page.locator('#setting-audio_quality').select_option('medium')
                page.locator('#setting-audio_buffer_ms').fill('350')
                page.locator('#settings-tab-connections').click()
                assert page.locator('#settings-panel-listening').is_hidden()
                page.locator('#setting-dj-llm_url').fill('http://127.0.0.1:1234/v1/chat/completions')
                page.locator('#setting-dj-model').fill('test-dj-model')
                page.locator('#settings-tab-dj').click()
                page.route('**/api/dj/voices', lambda route: route.fulfill(content_type='application/json', body=json.dumps({'voices': ['am_onyx', 'af_heart'], 'default': 'am_onyx', 'configured': True})))
                page.locator('#refresh-dj-voices').click()
                page.wait_for_function("() => document.querySelectorAll('#setting-dj-voice option').length === 3")
                page.locator('#setting-dj-voice').select_option('af_heart')
                import io, wave
                voice = io.BytesIO()
                with wave.open(voice, 'wb') as wav:
                    wav.setnchannels(1)
                    wav.setsampwidth(2)
                    wav.setframerate(16000)
                    wav.writeframes(b'\0\0' * 160000)
                samples = []
                def sample_route(route):
                    samples.append(route.request.post_data_json)
                    route.fulfill(content_type='audio/wav', body=voice.getvalue())
                page.route('**/api/voice', sample_route)
                page.locator('#sample-dj-voice').click()
                page.wait_for_function('() => !!voiceSampleAudio && !voiceSampleAudio.paused')
                assert samples[-1]['voice'] == 'af_heart'
                page.locator('#sample-dj-voice').click()
                assert page.evaluate('!voiceSampleAudio && !voiceSampleURL && !voiceSampleController')
                page.locator('#sample-dj-voice').click()
                page.wait_for_function('() => !!voiceSampleAudio && !voiceSampleAudio.paused')
                page.locator('#setting-dj-voice').select_option('am_onyx')
                assert page.evaluate('!voiceSampleAudio && !voiceSampleURL')
                page.locator('#setting-dj-voice').select_option('af_heart')
                page.locator('#setting-dj-theme').fill('London punk')
                page.locator('#setting-dj-dj_name').fill('DJ Rook')
                page.locator('#setting-dj-listener_name').fill('Bake')
                page.locator('#setting-dj-set_songs').fill('3')
                page.locator('#setting-dj-set_mode').select_option('minutes')
                assert page.locator('#setting-dj-set_songs').is_hidden()
                page.locator('#setting-dj-set_minutes').fill('20')
                page.locator('#setting-dj-play_over_music').uncheck()
                page.locator('#settings-tab-connections').click()
                page.locator('#setting-dj-tts_key').fill('private-browser-voice-key')
                page.get_by_role('button', name='Save', exact=True).click()
                page.wait_for_function("() => document.getElementById('settings-status').textContent.startsWith('Saved.')")
                assert 'audio_quality = medium' in (config / 'config').read_text()
                assert 'audio_buffer_ms = 350' in (config / 'config').read_text()
                assert 'act_volup = ]' in (config / 'config').read_text()
                dj_settings = json.loads((config / 'dj.json').read_text())
                assert dj_settings['model'] == 'test-dj-model' and dj_settings['theme'] == 'London punk'
                assert dj_settings['tts_key'] == 'private-browser-voice-key'
                assert dj_settings['voice'] == 'af_heart'
                assert dj_settings['set_mode'] == 'minutes' and dj_settings['set_minutes'] == 20 and dj_settings['set_songs'] == 3
                assert dj_settings['play_over_music'] is False
                assert dj_settings['dj_name'] == 'DJ Rook' and dj_settings['listener_name'] == 'Bake'
                assert request('/api/state')['djStation']['settings']['dj_name'] == 'DJ Rook'
                assert 'private-browser-voice-key' not in json.dumps(request('/api/settings'))
                page.reload()
                page.wait_for_function("() => document.getElementById('setting-audio_buffer_ms').value === '350'")
                # The last tab is remembered.
                assert page.locator('#settings-panel-connections').is_visible()
                assert page.locator('#setting-password').input_value() == ''
                assert page.locator('#setting-dj-model').input_value() == 'test-dj-model'
                assert page.locator('#setting-dj-theme').input_value() == 'London punk'
                assert page.locator('#setting-dj-tts_key').input_value() == ''
                assert page.locator('#setting-dj-voice').input_value() == 'af_heart'
                assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
                page.screenshot(path='/tmp/pianobar-settings-mobile.png', full_page=True)
                page.set_viewport_size({'width': 1440, 'height': 1080})
                page.screenshot(path='/tmp/pianobar-settings-desktop.png', full_page=True)
                assert not errors, errors
                # The compact widget renders state and drives the shared player.
                widget = browser.new_page(viewport={'width': 344, 'height': 240})
                widget_errors = []
                widget.on('pageerror', lambda error: widget_errors.append(str(error)))
                widget.add_init_script("localStorage.setItem('pianobarAutoListen', 'false')")
                widget.goto(origin + '/widget')
                widget.wait_for_function("() => document.getElementById('widget-title').textContent === 'Late afternoon'")
                assert widget.locator('#widget-pin').is_hidden()
                assert widget.locator('#widget-station').text_content() == 'Offline library'
                assert widget.locator('#widget-cover').is_hidden()
                widget.wait_for_function('() => !sending && !snapshot.pending && !autoListenPending')
                widget.keyboard.press('Space')
                widget.wait_for_function("() => document.getElementById('widget-play').getAttribute('aria-label') === 'Resume playback'")
                widget.locator('#widget-play').click()
                widget.wait_for_function("() => document.getElementById('widget-play').getAttribute('aria-label') === 'Pause playback'")
                widget.screenshot(path='/tmp/pianobar-widget.png')
                assert not widget_errors, widget_errors
                widget.close()
                print('PASS: widget page renders state, transport keys and controls')
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
