#!/usr/bin/env python3
"""Browser lease validation and PCM enforcement over the real HTTP handler."""
import http.client
import http.server
import json
from pathlib import Path
import runpy
import socket
import struct
import threading
import time
from unittest.mock import patch

module = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'pianobar-web'))
session = module['Session']()
registry = session.clients
session.state = {'output': 'browser', 'actions': [{'id': 'act_songpause', 'enabled': True}], 'stations': []}
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), module['Handler'])
server.daemon_threads = True
server.session = session
server.authorization = b'Basic test'
threading.Thread(target=server.serve_forever, daemon=True).start()


def request(path, message=None, expected=200, headers=None):
    connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=4)
    connection.request('GET' if message is None else 'POST', path,
                       None if message is None else json.dumps(message),
                       {'Authorization': 'Basic test', 'Content-Type': 'application/json', **(headers or {})})
    response = connection.getresponse()
    data = response.read()
    connection.close()
    assert response.status == expected, (response.status, data)
    return json.loads(data)


try:
    request('/api/clients', expected=401, headers={'Authorization': 'bad'})
    request('/api/clients/register', {'name': 'bad'}, expected=403, headers={'Origin': 'https://evil.invalid'})
    request('/api/clients/register', {'name': '\n'}, expected=400)
    a = request('/api/clients/register', {'name': 'Kitchen'})['id']
    b = request('/api/clients/register', {'name': 'Desk'})['id']
    assert a != b and not any(p['enabled'] for p in request('/api/clients')['clients'])
    assert request('/api/actions')['actions'][0]['id'] == 'act_songpause'
    assert request('/api/stations') == {'stations': []}
    request('/api/clients/route', {'ids': [a]})
    assert registry.allowed(a) and not registry.allowed(b)
    for invalid in ({'ids': [b, 'missing']}, {'ids': [{}]}, {'ids': 'all'}, {'mode': 'all', 'ids': []}):
        request('/api/clients/route', invalid, expected=400)
        assert registry.allowed(a) and not registry.allowed(b)  # Atomic rejection.
    for invalid in ({'id': a, 'volume': True}, {'id': a, 'volume': -1}, {'id': a, 'volume': float('nan')},
                    {'id': a, 'enabled': 'true'}, {'id': a, 'name': 'x' * 81}):
        request('/api/clients/update', invalid, expected=400)
    page = request('/api/clients/update', {'id': b, 'volume': .25, 'name': 'Office'})
    assert page['volume'] == .25 and page['name'] == 'Office'
    page = request('/api/clients/heartbeat', {'id': a, 'status': 'blocked', 'ready': False})
    assert page['status'] == 'blocked' and page['enabled']
    request('/api/clients/heartbeat', {'id': a, 'status': 'playing', 'ready': True, 'enabled': True}, expected=400)
    request('/api/audio', expected=400)
    request('/api/clients/route', {'mode': 'all'})
    assert registry.allowed(a) and registry.allowed(b)
    c = request('/api/clients/register', {'name': 'New page'})['id']
    assert not registry.allowed(c)
    # Exercise the actual socket packet fanout and HTTP streaming gate.
    session.audio, writer = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
    threading.Thread(target=session.read_audio, daemon=True).start()
    packet = struct.pack('!5I', 4, 44100, 2, 1, 1) + b'\x01\x00\x01\x00'
    streaming = True

    def send_audio():
        while streaming:
            writer.send(packet)
            time.sleep(.05)

    threading.Thread(target=send_audio, daemon=True).start()
    for enabled in (True, False, True):
        request('/api/clients/route', {'ids': [a] if enabled else []})
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=4)
        connection.request('GET', '/api/audio', headers={'Authorization': 'Basic test', 'X-Pianobar-Client': a})
        response = connection.getresponse()
        assert response.status == 200
        size = struct.unpack('!5I', response.read(20))[0]
        assert size == (4 if enabled else 0), (enabled, size)
        if size:
            assert response.read(size) == packet[20:]
        connection.close()
    request('/api/clients/unregister', {'id': a})
    assert not registry.allowed(a)
    with patch.object(module['time'], 'monotonic', return_value=time.monotonic() + registry.lease + 1):
        assert registry.listing()['clients'] == []
        assert not registry.allowed(b)
    request('/api/clients/heartbeat', {'id': b, 'status': 'playing', 'ready': True}, expected=400)
    print('PASS: browser registration, validation, auth/origin checks, atomic routing, leases, and server PCM gating')
finally:
    streaming = False
    session.exited = True
    if hasattr(session, 'audio'):
        writer.send(bytes(20))
    server.shutdown()
    server.server_close()
