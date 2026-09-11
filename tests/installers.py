#!/usr/bin/env python3
"""Credential boundary tests: private files, hostile input, and hidden TTY prompts."""
import json
import os
from pathlib import Path
import pty
import select
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
helper = ROOT / 'scripts/install-credentials.py'
with tempfile.TemporaryDirectory() as temporary:
    base = Path(temporary)
    source, output = base / 'input.json', base / 'output.json'
    data = dict(user='listener@example.invalid', password='literal-$(false)-`false`-password',
                web_password='test-only-web-password')
    source.write_text(json.dumps(data))
    source.chmod(0o600)

    def run(path=source):
        return subprocess.run([sys.executable, str(helper), '--input', str(path), '--output', str(output)],
                              capture_output=True, text=True)

    result = run()
    assert result.returncode == 0
    assert json.loads(output.read_text()) == data
    assert output.stat().st_mode & 0o777 == 0o600
    assert data['password'] not in result.stdout + result.stderr
    output.unlink()
    source.chmod(0o644)
    assert run().returncode != 0 and not output.exists()
    source.chmod(0o600)
    link = base / 'link'
    link.symlink_to(source)
    assert run(link).returncode != 0
    for changes in ({'password': 'one\nuser = injected'}, {'web_password': 'short'},
                    {'user': ''}, {'password': ' trailing '}, {'extra': 'value'}, {'user': 123}):
        source.write_text(json.dumps(dict(data, **changes)))
        result = run()
        assert result.returncode != 0 and not output.exists()
        assert data['password'] not in result.stdout + result.stderr

    pid, fd = pty.fork()
    if pid == 0:
        os.execv(sys.executable, [sys.executable, str(helper), '--output', str(output)])
    transcript = bytearray()
    prompts = [(b'Pandora email:', data['user']), (b'Pandora password:', data['password']),
               (b'Web login password (username: pianobar):', data['web_password']),
               (b'Confirm web login password:', data['web_password'])]
    deadline = time.monotonic() + 10
    try:
        while time.monotonic() < deadline:
            if select.select([fd], [], [], .1)[0]:
                try:
                    chunk = os.read(fd, 4096)
                except OSError:
                    break
                if not chunk: break
                transcript.extend(chunk)
                if prompts and prompts[0][0] in transcript:
                    _, value = prompts.pop(0)
                    os.write(fd, (value + '\n').encode())
        assert not prompts, ('Interactive prompts did not complete', transcript.decode(errors='replace'))
        assert os.waitpid(pid, 0)[1] == 0
        assert json.loads(output.read_text()) == data
        assert data['password'].encode() not in transcript and data['web_password'].encode() not in transcript
    finally:
        os.close(fd)
        try: os.kill(pid, 15)
        except ProcessLookupError: pass
print('PASS: private credentials, literal shell characters, rejected injection/symlinks, hidden interactive passwords')
