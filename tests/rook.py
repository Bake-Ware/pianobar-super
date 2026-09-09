#!/usr/bin/env python3
"""Verify every persisted Rook template against a local HTTP API fixture."""
import http.server
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import threading

root = Path(__file__).resolve().parents[1]
calls = []


class API(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        assert self.headers['Authorization'] == 'Basic cGlhbm9iYXI6dGVzdA=='
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{"ok":true}')
        calls.append((self.path, None))

    def do_POST(self):
        assert self.headers['Authorization'] == 'Basic cGlhbm9iYXI6dGVzdA=='
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        calls.append((self.path, body))
        self.send_response(400 if body == {'action': 'reject'} else 200)
        self.end_headers()
        self.wfile.write(b'{"error":"Rejected"}' if body == {'action': 'reject'} else b'{"ok":true}')


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), API)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with tempfile.TemporaryDirectory() as temporary:
        config = Path(temporary) / 'web.json'
        config.write_text('{"password":"test"}')
        env = dict(os.environ, PIANOBAR_API_URL=f'http://127.0.0.1:{server.server_port}', PIANOBAR_WEB_CONFIG=str(config))
        manifest = json.loads((root / 'integrations/rook-caps.json').read_text())
        assert len(manifest) == len({spec['name'] for spec in manifest}), 'Duplicate capability names'
        for spec in manifest:
            params = {key: shlex.quote({'targets': 'none', 'mode': 'browser', 'id': 'example', 'json': '{}'}[key])
                      for key in spec['args']}
            cmd = spec['command'].replace('/usr/local/bin/pianobar-rook', shlex.quote(str(root / 'integrations/pianobar-rook'))).format(**params)
            result = subprocess.run(cmd, shell=True, env=env, capture_output=True, text=True)
            assert result.returncode == 0, (spec['name'], result.stdout, result.stderr)
            assert json.loads(result.stdout) == {'ok': True}
        expected = set(re.findall(r'"(act_\w+)"', (root / 'src/ui_dispatch.h').read_text()))
        assert expected == {body['action'] for _, body in calls if body and 'action' in body}
        assert len(calls) == len(manifest)
        # Exercise the exact escaping used by Rook with hostile prompt text.
        marker = Path(temporary) / 'injected'
        text = f"'; touch {marker}; echo $(touch {marker})"
        spec = next(s for s in manifest if s['name'] == 'pianobar-command')
        template = spec['command'].replace('/usr/local/bin/pianobar-rook', shlex.quote(str(root / 'integrations/pianobar-rook')))
        result = subprocess.run(template.format(json=shlex.quote(json.dumps({'text': text, 'promptId': 1}))),
                                shell=True, env=env, capture_output=True, text=True)
        assert result.returncode == 0 and not marker.exists()
        assert calls[-1] == ('/api/command', {'text': text, 'promptId': 1})
        result = subprocess.run(template.format(json=shlex.quote('{"action":"reject"}')),
                                shell=True, env=env, capture_output=True, text=True)
        assert result.returncode == 1 and json.loads(result.stdout)['status'] == 400
        assert 'Basic ' not in result.stdout
        print(f'PASS: all {len(manifest)} Rook templates, full native action coverage, JSON/shell escaping, auth and HTTP error propagation')
finally:
    server.shutdown()
    server.server_close()
