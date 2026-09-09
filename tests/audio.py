#!/usr/bin/env python3
"""Compare the production PCM output against FFmpeg, without audio hardware."""
import os
from pathlib import Path
import select
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='pianobar-audio-tests-') as temporary:
    base = Path(temporary)
    config = base / 'config' / 'pianobar'
    config.mkdir(parents=True)
    fixture = base / 'fixture.mka'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
        'aevalsrc=0.8*sin(2*PI*997*t)|0.3*sin(2*PI*431*t):s=44100:d=3',
        '-c:a', 'pcm_f32le', str(fixture)], check=True, timeout=15)
    for rate, volume in [(44100, 0), (48000, -6)]:
        (config / 'config').write_text(f'sample_rate = {rate}\nvolume = {volume}\n')
        env = dict(os.environ, XDG_CONFIG_HOME=str(config.parent))
        fifo = base / f'output-{rate}'
        os.mkfifo(fifo)
        audio_fd = os.open(fifo, os.O_RDWR | os.O_NONBLOCK)
        stop = threading.Event()
        captured = bytearray()
        def drain():
            while not stop.is_set():
                if select.select([audio_fd], [], [], .01)[0]:
                    captured.extend(os.read(audio_fd, 65536))
        reader = threading.Thread(target=drain)
        reader.start()
        try:
            subprocess.run([str(ROOT / 'tests/player-driver'), str(fixture), str(base / 'empty'),
                            str(fifo), 'local', '0'], env=env, check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
            # Empty the pipe after the producer exits.
            time.sleep(.05)
        finally:
            stop.set()
            reader.join()
            os.close(audio_fd)
        expected = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(fixture),
            '-af', f'volume={volume}dB,aformat=sample_fmts=s16:sample_rates={rate}',
            '-f', 's16le', '-'], timeout=15)
        assert captured == expected, (rate, len(captured), len(expected))
    print('PASS: stereo PCM matches FFmpeg exactly at 44.1/48 kHz, including gain and resampler drain')
