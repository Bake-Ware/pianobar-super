#!/usr/bin/env python3
"""Whole-file delivery: the server downloads the current and next songs, serves
them by track id with Range support, evicts old ones, and serves DJ lines."""
import contextlib
import http.client
import http.server
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
CONFIG = tempfile.TemporaryDirectory()
os.environ['XDG_CONFIG_HOME'] = CONFIG.name
module = runpy.run_path(str(ROOT / 'pianobar-web'))
Tracks = module['Tracks']

with tempfile.TemporaryDirectory(prefix='pianobar-track-tests-') as temporary:
    base = Path(temporary)
    songs = base / 'songs'
    songs.mkdir()
    fixture = base / 'fixture.mp3'
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=3',
                    '-c:a', 'libmp3lame', str(fixture)], check=True, timeout=30)
    audio = fixture.read_bytes()
    saved = 'a' * 64
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1',
                    '-c:a', 'aac', str(songs / (saved + '.mka'))], check=True, timeout=30)

    # A stand-in for Pandora's audio servers.
    fetched, gate = [], threading.Event()
    gate.set()
    class Origin(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            fetched.append(self.path)
            gate.wait(10)
            if self.path.startswith('/missing'):
                self.send_error(404)
                return
            body = b'<html>expired</html>' if self.path.startswith('/html') else audio
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            with contextlib.suppress(BrokenPipeError, ConnectionResetError):
                self.wfile.write(body[:len(body) // 2] if self.path.startswith('/short') else body)
    origin = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Origin)
    origin.daemon_threads = True
    threading.Thread(target=origin.serve_forever, daemon=True).start()
    url = f'http://127.0.0.1:{origin.server_port}'

    session = module['Session']()
    session.binary = ROOT / 'pianobar'
    session.state = {'cacheDir': str(songs)}
    tracks = session.tracks
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), module['Handler'])
    server.daemon_threads = True
    server.session, server.authorization = session, b'Basic test'
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def request(path, extra=None, status=200):
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=40)
        connection.request('GET', path, headers={'Authorization': 'Basic test', **(extra or {})})
        response = connection.getresponse()
        body, headers = response.read(), dict(response.getheaders())
        connection.close()
        assert response.status == status, (path, response.status, body)
        return headers, body

    def state(current, upcoming=None, offline=False, **extra):
        """A player state as the native player sends it, private fields included."""
        message = dict(type='state', offline=offline, cacheDir=str(songs), title='Song', **extra)
        if current:
            message.update(_audioUrl=f'{url}/{current}?token=secret', _trackToken='token-' + current)
        if upcoming:
            message.update(_nextAudioUrl=f'{url}/{upcoming}?token=secret', _nextTrackToken='token-' + upcoming)
        return message

    def feed(message):
        private = {key: message.pop(key) for key in [key for key in message if key.startswith('_')]}
        message['trackId'], message['nextTrackId'] = tracks.update(message, private)
        with session.condition:
            session.state = message
        return message['trackId'], message['nextTrackId']

    def wait_ready(*ids):
        deadline = time.monotonic() + 15
        while not all(tracks.ready(i) for i in ids):
            assert time.monotonic() < deadline, ids
            time.sleep(.05)

    try:
        first, second = feed(state('one', 'two'))
        assert first and second and first != second and len(first) == 64
        assert 'secret' not in first + second and 'token' not in json.dumps(session.state)
        # Ids don't depend on the expiring URL, only on the song's place in the playlist.
        again = dict(_audioUrl=url + '/one?token=renewed', _trackToken='token-one')
        assert tracks.update({'cacheDir': str(songs)}, again)[0] == first
        wait_ready(first, second)
        snapshot = session.snapshot(-1)
        assert snapshot['delivery'] == 'files'
        assert snapshot['state']['trackReady'] and snapshot['state']['nextTrackReady']
        assert not any(key.startswith('_') for key in snapshot['state']), snapshot['state'].keys()
        assert '/one?' not in json.dumps(snapshot)
        # One download per song, however many state updates mention it.
        for _ in range(5):
            feed(state('one', 'two'))
        assert sorted(fetched) == ['/one?token=secret', '/two?token=secret'], fetched

        headers, body = request('/api/tracks/' + first)
        assert body == audio and headers['Content-Type'] == 'audio/mpeg' and headers['Accept-Ranges'] == 'bytes'
        headers, body = request('/api/tracks/' + first, {'Range': 'bytes=100-199'}, 206)
        assert body == audio[100:200] and headers['Content-Range'] == f'bytes 100-199/{len(audio)}'
        headers, body = request('/api/tracks/' + first, {'Range': 'bytes=1000-'}, 206)
        assert body == audio[1000:]
        headers, body = request('/api/tracks/' + first, {'Range': 'bytes=-50'}, 206)
        assert body == audio[-50:]
        headers, _ = request('/api/tracks/' + first, {'Range': f'bytes={len(audio)}-'}, 416)
        assert headers['Content-Range'] == f'bytes */{len(audio)}'
        _, body = request('/api/tracks/' + first, {'Range': 'bytes=0-1,5-9'})
        assert body == audio  # Multiple ranges: the whole file.
        request('/api/tracks/' + first, {'Authorization': 'Basic wrong'}, 401)
        for invalid in ('../../etc/passwd', 'b' * 64, first.upper(), first + '.mp3'):
            request('/api/tracks/' + invalid, status=404)
        # Saved songs (the offline library) are served from their files.
        _, body = request('/api/tracks/' + saved)
        assert body == (songs / (saved + '.mka')).read_bytes()
        assert feed(dict(type='state', offline=True, cacheDir=str(songs), savedId=saved + '.mka',
                         queuedSavedId='c' * 64 + '.mka')) == (saved, 'c' * 64)
        assert session.snapshot(-1)['state']['trackReady']
        print('PASS: current and next songs download once, private URLs stay private, Range requests, saved songs')

        # A request for a song still downloading waits for it.
        gate.clear()
        third = feed(state('three', 'four'))[0]
        assert not tracks.ready(third)
        result = {}
        waiter = threading.Thread(target=lambda: result.update(body=request('/api/tracks/' + third)[1]))
        waiter.start()
        time.sleep(.3)
        gate.set()
        waiter.join(20)
        assert result['body'] == audio
        # Failures: Pandora's error pages, truncated downloads, and missing files are never served.
        for name in ('html', 'short', 'missing'):
            broken = feed(state(name))[0]
            deadline = time.monotonic() + 10
            while tracks.entries[broken]['status'] == 'downloading':
                assert time.monotonic() < deadline
                time.sleep(.05)
            assert tracks.entries[broken]['status'] == 'failed', name
            request('/api/tracks/' + broken, status=503)  # It will be tried again.
        print('PASS: waiting for a download in progress; HTML, truncated, and missing audio are rejected')

        # Eviction: only the wanted songs and a few recent ones stay on disk.
        for number in range(12):
            feed(state(f'song{number}', f'song{number + 1}'))
        wait_ready(session.state['trackId'], session.state['nextTrackId'])
        deadline = time.monotonic() + 10
        while any(e['status'] == 'downloading' for e in tracks.entries.values()):
            assert time.monotonic() < deadline
            time.sleep(.05)
        feed(state('song11', 'song12'))
        files = list(tracks.directory.glob('*.audio'))
        assert len(tracks.entries) <= Tracks.keep + 2 and len(files) <= Tracks.keep + 2, (len(tracks.entries), len(files))
        assert tracks.ready(session.state['trackId']) and tracks.ready(session.state['nextTrackId'])
        request('/api/tracks/' + first, status=404)
        print('PASS: old downloads are evicted; the current and next songs are kept')

        # DJ lines are served as whole files once they're on air.
        dj = session.dj
        dj.voice_dir = tempfile.mkdtemp(dir=base)
        (Path(dj.voice_dir) / 'line-7.mp3').write_bytes(audio)
        headers, body = request('/api/dj/line/7')
        assert body == audio and headers['Content-Type'] == 'audio/mpeg'
        _, body = request('/api/dj/line/7', {'Range': 'bytes=0-9'}, 206)
        assert body == audio[:10]
        for invalid in ('8', '0', '-1', '../7', '7.mp3'):
            request('/api/dj/line/' + invalid, status=404)
        print('PASS: DJ lines are served as files')
    finally:
        server.shutdown()
        origin.shutdown()
