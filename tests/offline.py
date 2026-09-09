#!/usr/bin/env python3
"""Local fixtures only: no Pandora account or audio device required."""
import base64
import contextlib
import http.server
import json
import os
from pathlib import Path
import select
import socket
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


def run(*args, **kwargs):
    return subprocess.run(args, check=True, timeout=30, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, **kwargs)


with tempfile.TemporaryDirectory(prefix="pianobar-tests-") as temporary:
    base = Path(temporary)
    config = base / "config" / "pianobar"
    config.mkdir(parents=True)
    env = dict(os.environ, XDG_CONFIG_HOME=str(config.parent), XDG_DATA_HOME=str(base / "data"))
    fifo = base / "audio"
    os.mkfifo(fifo)
    audio_fd = os.open(fifo, os.O_RDWR | os.O_NONBLOCK)
    stop = threading.Event()

    def drain():
        while not stop.is_set():
            if select.select([audio_fd], [], [], 0.05)[0]:
                os.read(audio_fd, 65536)

    threading.Thread(target=drain, daemon=True).start()
    for codec, extension in [("aac", "m4a"), ("libmp3lame", "mp3")]:
        args = ["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=8", "-c:a", codec]
        if extension == "m4a":
            args += ["-movflags", "+faststart"]
        run(*args, str(base / ("fixture." + extension)))

    artwork = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aO2kAAAAASUVORK5CYII=')
    requests = []
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            requests.append(self.path)
            if self.path == '/cover.png':
                self.send_response(200)
                self.send_header('Content-Length', str(len(artwork)))
                self.end_headers()
                self.wfile.write(artwork)
                return
            file = base / ("fixture.mp3" if self.path.endswith("mp3") else "fixture.m4a")
            data = file.read_bytes()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            if self.path.startswith("/truncated"):
                self.wfile.write(data[:len(data) // 2])
                return
            if self.path.startswith("/slow"):
                with contextlib.suppress(BrokenPipeError, ConnectionResetError):
                    for i in range(0, len(data), 1024):
                        self.wfile.write(data[i:i+1024])
                        self.wfile.flush()
                        time.sleep(0.02)
            else:
                self.wfile.write(data)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{server.server_port}"
    cache = base / "songs"

    def driver(source, directory=cache, mode="remote", status=0):
        return run(str(ROOT / "tests/player-driver"), source, str(directory), str(fifo),
                   mode, str(status), env=env)

    env["PIANOBAR_TEST_ARTWORK"] = url + "/cover.png"
    driver(url + "/fixture.m4a")
    first = list(cache.glob("*.mka"))
    assert len(first) == 1, first
    images = list((cache / 'artwork').glob('*.img'))
    assert len(images) == 1 and images[0].read_bytes() == artwork
    art_time = images[0].stat().st_mtime_ns
    original = first[0].stat()
    requests_before = requests.count('/fixture.m4a')
    driver(url + "/fixture.m4a")
    assert requests.count('/fixture.m4a') == requests_before + 1  # Playback only; no duplicate save request.
    assert first[0].stat().st_mtime_ns == original.st_mtime_ns
    driver(url + "/fixture.mp3")
    assert images[0].stat().st_mtime_ns == art_time
    assert len(list((cache / 'artwork').glob('*.img'))) == 1
    del env['PIANOBAR_TEST_ARTWORK']
    saved = list(cache.glob("*.mka"))
    assert len(saved) == 2
    codecs = set()
    for file in saved:
        info = json.loads(run("ffprobe", "-v", "error", "-show_streams", "-show_format",
                              "-of", "json", str(file)).stdout)
        codecs.add(info["streams"][0]["codec_name"])
        assert float(info["format"]["duration"]) >= 7.5
        driver(str(file), mode="local")
    assert codecs == {"aac", "mp3"}
    listed = json.loads(run(str(ROOT / 'pianobar'), '--list-saved', str(cache)).stdout)
    assert all(song['cover'] == '/api/artwork/' + images[0].stem for song in listed)
    env['PIANOBAR_TEST_ARTWORK'] = url + '/invalid-image'
    driver(url + '/fixture.m4a', base / 'bad-art')
    assert not list((base / 'bad-art' / 'artwork').glob('*.img'))
    del env['PIANOBAR_TEST_ARTWORK']
    assert not list(cache.glob(".partial-*"))
    print("PASS: AAC/MP3 round trip, metadata, deduplication, local decoding")

    for scenario, status in [("truncated", 2), ("slow", 0)]:
        directory = base / scenario
        driver(url + f"/{scenario}.m4a", directory,
               "cancel" if scenario == "slow" else "remote", status)
        assert len(list(directory.glob("*.mka"))) == (1 if scenario == 'slow' else 0)
        assert not list(directory.glob(".partial-*"))
    shutdown = base / 'shutdown'
    driver(url + '/slow.m4a', shutdown, 'shutdown')
    assert not list(shutdown.glob('*.mka'))
    assert not list(shutdown.glob('.partial-*'))
    driver(url + '/slow.m4a', base / 'paused', 'paused')
    requests_before = requests.count('/slow.m4a')
    queued = base / 'queued'
    driver(url + '/slow.m4a', queued, 'queue')
    assert requests.count('/slow.m4a') == requests_before + 7  # One probe + six unique downloads.
    assert len(list(queued.glob('*.mka'))) == 6
    assert not list(queued.glob('.partial-*'))
    print('PASS: saves complete while paused; rapid load queue owns metadata and deduplicates in-flight downloads')
    blocked = base / "not-a-directory"
    blocked.write_text("occupied")
    driver(url + "/fixture.m4a", blocked / "songs")
    print("PASS: skipped songs finish saving; truncated/shutdown downloads excluded; cache failure preserves playback")

    def config_text(extra=""):
        (config / "config").write_text(f"cache_dir = {cache}\naudio_pipe = {fifo}\n" + extra)

    def session(arguments, expected, commands=b"q", extra="", wait_for=None):
        config_text(extra)
        proc = subprocess.Popen([str(ROOT / "pianobar"), "--cli", *arguments], env=env,
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT)
        output = b""
        deadline = time.monotonic() + 10
        try:
            while expected not in output and time.monotonic() < deadline:
                if select.select([proc.stdout], [], [], .1)[0]:
                    chunk = os.read(proc.stdout.fileno(), 65536)
                    if not chunk:
                        break
                    output += chunk
            assert expected in output, output.decode(errors="replace")
            assert b"Password:" not in output, output
            if commands:
                proc.stdin.write(commands)
                proc.stdin.flush()
            if wait_for:
                while wait_for not in output and time.monotonic() < deadline:
                    if select.select([proc.stdout], [], [], .1)[0]:
                        output += os.read(proc.stdout.fileno(), 65536)
                assert wait_for in output, output.decode(errors="replace")
                proc.stdin.write(b"q")
                proc.stdin.flush()
            tail, _ = proc.communicate(timeout=10)
            assert proc.returncode == 0, tail
            return output + tail
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait()

    offline_output = session(["--offline"], b"Offline mode", b"pi+ n q")
    assert b"Email:" not in offline_output
    assert b"Browser interface:" not in offline_output
    session(["--offline"], b"Offline mode", b"Wq")
    assert b"unavailable offline" in offline_output
    state = (config / "state").read_text()
    assert "autostart_station = offline" not in state
    session([], b"Offline mode", extra="offline = 1\n")
    # Refuse a local API connection to trigger startup/reconnect fallback.
    with socket.socket() as unused:
        unused.bind(("127.0.0.1", 0))
        port = unused.getsockname()[1]
        credentials = ("user = test\npassword = test\nrpc_host = 127.0.0.1\n"
                       f"rpc_tls_port = {port}\ntimeout = 1\n")
        session([], b"Offline mode", extra=credentials)
        session(["--offline"], b"Offline mode", b"O", credentials,
                b"Reconnect failed; staying offline.")
    print("PASS: offline startup/config, controls, startup fallback, failed reconnect, state")
    config_text(f"cache_dir = {base / 'empty'}\n")
    empty = run(str(ROOT / "pianobar"), "--cli", "--offline", env=env)
    assert b"No saved songs" in empty.stdout
    assert b"Email:" not in empty.stdout
    server.shutdown()
    stop.set()
    os.close(audio_fd)
    print("PASS: empty offline library exits without credentials")
