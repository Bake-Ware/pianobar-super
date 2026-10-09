#!/usr/bin/env python3
"""Compare the production PCM output against FFmpeg, without audio hardware."""
import array
import math
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

    # The DJ voice is decoded to the output format and mixed in the player, so
    # host speakers and browsers hear the same thing.
    voice = base / 'voice.wav'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'aevalsrc=0.5*sin(2*PI*300*t):s=16000:d=1',
                    '-c:a', 'pcm_s16le', str(voice)], check=True, timeout=15)
    (config / 'config').write_text('sample_rate = 44100\nvolume = 0\n')
    env = dict(os.environ, XDG_CONFIG_HOME=str(config.parent), PIANOBAR_TEST_VOICE=str(voice))
    music = array.array('h', subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(fixture),
        '-af', 'aformat=sample_fmts=s16:sample_rates=44100', '-f', 's16le', '-'], timeout=15))
    speech = array.array('h', subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(voice),
        '-af', 'aformat=sample_fmts=s16:sample_rates=44100:channel_layouts=stereo', '-f', 's16le', '-'], timeout=15))

    def play(mode, level=0, duck=-12):
        fifo = base / f'output-{mode}-{level}-{duck}'
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
                            str(fifo), mode, '0'], check=True,
                           env=dict(env, PIANOBAR_TEST_VOICE_LEVEL=str(level), PIANOBAR_TEST_VOICE_DUCK=str(duck)),
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
            time.sleep(.05)
        finally:
            stop.set()
            reader.join()
            os.close(audio_fd)
        return array.array('h', bytes(captured))

    def loudness(samples):
        report = subprocess.run(['ffmpeg', '-nostdin', '-f', 's16le', '-ar', '44100', '-ac', '2', '-i', '-',
                                 '-af', 'ebur128', '-f', 'null', '-'], input=samples.tobytes(),
                                capture_output=True, timeout=15).stderr.decode()
        return float(report.rsplit('I:', 1)[1].split()[0])

    # Held music: the line plays alone, evened out to -16 LUFS whatever its
    # source level, then the song from its first sample.
    held = play('voice-break')
    assert len(held) == len(speech) + len(music), (len(held), len(speech), len(music))
    assert held[len(speech):].tolist() == music.tolist()
    leveled = held[:len(speech)]
    assert abs(loudness(leveled) - -16) < .5, loudness(leveled)
    gain = max(abs(x) for x in leveled) / max(abs(x) for x in speech)
    assert max(abs(leveled[i] - speech[i] * gain) for i in range(len(speech))) <= 2
    # The listener's voice level moves it from there.
    louder = play('voice-break', level=4)[:len(speech)]
    assert abs(loudness(louder) - loudness(leveled) - 4) < .2, loudness(louder)
    speech = leveled
    # Over the music: same length; the music dips (here to a quarter) over 300 ms,
    # the voice starts once it is down, and the song returns bit-exact afterwards.
    mixed = play('voice-over', duck=20 * math.log10(.25))
    assert len(mixed) == len(music), (len(mixed), len(music))
    ramp = int(44100 * .3)  # Frames to move the music gain through its full range.
    dip = round(ramp * .75)  # Frames from full level down to a quarter.
    assert mixed[:2].tolist() == music[:2].tolist()
    after = (len(speech) // 2 + dip + ramp + 4) * 2
    assert mixed[after:].tolist() == music[after:].tolist()
    def mix_error(offset, start=int(44100 * .5), end=int(44100 * 1.2)):
        return max(abs(mixed[i] - max(-32768, min(32767, music[i] * .25 + speech[i - offset])))
                   for i in range(start * 2, end * 2))
    error = min(mix_error(frames * 2) for frames in range(dip - 3, dip + 4))
    assert error <= 2, error
    # Nothing is louder than the song while the music dips before the voice.
    peak = max(abs(x) for x in mixed[:(dip - 3) * 2])
    assert peak <= max(abs(x) for x in music[:(dip - 3) * 2]), peak
    # With no dip the music stays at full level and the voice starts at once.
    flat = play('voice-over', duck=0)
    assert max(abs(flat[i] - max(-32768, min(32767, music[i] + speech[i]))) for i in range(len(speech))) <= 1
    assert flat[len(speech):].tolist() == music[len(speech):].tolist()
    print('PASS: DJ voice decoded to the output format, evened out to one loudness, mixed over music dipped to a set depth or played as a break, bit-exact afterwards')
