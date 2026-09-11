#!/usr/bin/env python3
"""Authenticated exports preserve encoded audio, metadata, and cached artwork."""
import base64
import hashlib
import http.client
import http.server
import json
from pathlib import Path
import runpy
import subprocess
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
module = runpy.run_path(str(ROOT / 'pianobar-web'))
with tempfile.TemporaryDirectory(prefix='pianobar-download-tests-') as temporary:
    base = Path(temporary)
    (base / 'artwork').mkdir()
    artist, title, album = 'Test artist', 'Road trip / café', 'Offline album'
    art_id = hashlib.sha256((artist + '\0' + album + '\0\0').encode()).hexdigest()
    (base / 'artwork' / (art_id + '.img')).write_bytes(base64.b64decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aO2kAAAAASUVORK5CYII='))
    for letter, encoder in (('a', 'aac'), ('b', 'libmp3lame')):
        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2',
            '-c:a', encoder, '-metadata', 'artist=' + artist, '-metadata', 'title=' + title,
            '-metadata', 'album=' + album, str(base / (letter * 64 + '.mka'))], check=True)
    session = module['Session']()
    session.binary = ROOT / 'pianobar'
    session.state = {'cacheDir': str(base)}
    (base / ('c' * 64 + '.mka')).symlink_to(base / ('a' * 64 + '.mka'))
    (base / ('d' * 64 + '.mka')).write_text('not audio')
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), module['Handler'])
    server.daemon_threads = True
    server.session, server.authorization = session, b'Basic test'
    threading.Thread(target=server.serve_forever, daemon=True).start()
    def request(song_id, extra=None, status=200):
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=15)
        connection.request('GET', '/api/download/' + song_id, headers={'Authorization': 'Basic test', **(extra or {})})
        response = connection.getresponse()
        body, headers = response.read(), dict(response.getheaders())
        connection.close()
        assert response.status == status, (response.status, body)
        return headers, body
    try:
        request('a' * 64 + '.mka', {'Authorization': 'bad'}, 401)
        request('a' * 64 + '.mka', {'Origin': 'https://evil.invalid'}, 403)
        request('a' * 64 + '.mka', {'Sec-Fetch-Site': 'cross-site'}, 403)
        for invalid in ('../../config', 'missing', 'c' * 64 + '.mka', 'd' * 64 + '.mka', 'e' * 64 + '.mka'):
            request(invalid, status=404)
        for letter, extension, codec in (('a', 'm4a', 'aac'), ('b', 'mp3', 'mp3')):
            source = base / (letter * 64 + '.mka')
            original = hashlib.sha256(source.read_bytes()).hexdigest()
            headers, data = request(source.name)
            assert int(headers['Content-Length']) == len(data)
            assert headers['Content-Type'] == ('audio/mp4' if codec == 'aac' else 'audio/mpeg')
            assert 'filename*=UTF-8' in headers['Content-Disposition'] and 'caf%C3%A9' in headers['Content-Disposition']
            assert headers['Cache-Control'] == 'private, no-store'
            target = base / ('export.' + extension)
            target.write_bytes(data)
            probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(target)]))
            assert probe['streams'][0]['codec_name'] == codec
            assert probe['format']['tags']['title'] == title
            assert probe['format']['tags']['artist'] == artist
            assert any(s.get('disposition', {}).get('attached_pic') for s in probe['streams'])
            # Encoded packets survive container conversion without re-encoding.
            def hashes(path):
                result = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'a:0',
                    '-show_packets', '-show_entries', 'packet=data_hash', '-show_data_hash', 'sha256', '-of', 'json', str(path)]))
                return [p['data_hash'] for p in result['packets']]
            assert hashes(source) == hashes(target)
            assert hashlib.sha256(source.read_bytes()).hexdigest() == original
        session.download_slots.acquire(); session.download_slots.acquire()
        request('a' * 64 + '.mka', status=503)
        session.download_slots.release(); session.download_slots.release()
        print('PASS: authenticated AAC/MP3 downloads, packet preservation, metadata/artwork, filenames, invalid files, bounded concurrency')
    finally:
        server.shutdown(); server.server_close()
