#!/usr/bin/env python3
"""Station enumeration, native switching, HTTP validation, and optional browser checks."""
import contextlib
import http.server
import json
import os
from pathlib import Path
import runpy
import socket
import subprocess
import sys
import threading
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
result = subprocess.check_output([str(ROOT / 'tests/stations-driver')], text=True)
state = json.loads(result.splitlines()[-1])
delete_prompt = state.pop('testDeletePrompt')
assert [s['id'] for s in state['stations']] == ['101', '202']
assert state['stations'][0]['quickMix']
host = runpy.run_path(str(ROOT / 'pianobar-web'))
session = host['Session']()
session.state = state
session.status, receiver = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
receiver.settimeout(3)
terminal_read, session.master = os.pipe()
os.set_blocking(terminal_read, False)
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), host['Handler'])
server.daemon_threads = True
server.session, server.assets = session, ROOT / 'web'
threading.Thread(target=server.serve_forever, daemon=True).start()
origin = f'http://localhost:{server.server_port}'

def request(message, expected=200):
    try:
        with urllib.request.urlopen(urllib.request.Request(origin + '/api/command',
                json.dumps(message).encode(), {'Content-Type': 'application/json'}), timeout=3) as response:
            assert response.status == expected
    except urllib.error.HTTPError as error:
        assert error.code == expected, (error.code, error.read())

try:
    for invalid in ['missing', '', None, 202, 'x' * 129]:
        request({'selectStation': invalid}, 400)
    session.state['offline'] = True
    request({'selectStation': '202'}, 400)
    session.state['offline'] = False
    session.prompt['active'] = True
    request({'selectStation': '202'}, 400)
    session.prompt['active'] = False
    session.state['actions'][0]['enabled'] = False
    request({'selectStation': '202'}, 400)
    session.state['actions'][0]['enabled'] = True
    request({'selectStation': '202'})
    assert json.loads(receiver.recv(1024)) == {'type': 'select_station', 'id': '202'}
    request({'selectStation': '101'}, 400)  # Pending selection is serialized.
    with session.condition:
        session.pending = False
        session.changed()
    print('PASS: native station list/selection, queue draining, paused playback, offline/disabled/invalid/busy rejection')
    if '--browser' in sys.argv:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=os.environ.get('PIANOBAR_TEST_BROWSER'))
            page = browser.new_page(viewport={'width': 1440, 'height': 1080})
            page.goto(origin + '/#stations')
            page.get_by_role('button', name='Play station Evening jazz').wait_for()
            assert page.locator('.station-row').count() == 2
            assert page.get_by_role('button', name='Play station All stations').is_disabled()
            page.locator('#station-search').fill('jazz')
            assert page.locator('.station-row').count() == 1
            page.get_by_role('button', name='Play station Evening jazz').click()
            assert json.loads(receiver.recv(1024)) == {'type': 'select_station', 'id': '202'}
            # The native driver's assertions above cover application of this message;
            # supply its completion here without connecting to Pandora or audio hardware.
            with session.condition:
                session.state = {**state, 'station': 'Evening jazz', 'stationId': '202', 'nextStationId': '202'}
                session.pending = False
                session.changed()
            page.wait_for_function("() => document.getElementById('station').textContent === 'Evening jazz'")
            assert page.get_by_role('button', name='Play station Evening jazz').is_disabled()
            assert page.locator('.station-row.current small').text_content() == 'Now playing'
            page.locator('#station-search').fill('')
            page.screenshot(path='/tmp/pianobar-web-stations.png', full_page=True)
            page.reload()
            page.get_by_role('button', name='Play station Evening jazz').wait_for()
            assert page.locator('.station-row').count() == 2
            # Confirmations use native prompt metadata; no deletion request reaches Pandora.
            for choice, answer in [('Cancel', b'n'), ('Escape', b'n'), ('Delete station', b'y')]:
                with session.condition:
                    session.prompt = {**delete_prompt, 'id': session.prompt['id'] + 1}
                    session.changed()
                page.locator('#delete-confirmation').wait_for(state='visible')
                assert page.locator('#delete-confirmation').text_content() == 'Are you sure you want to delete “Evening jazz”?'
                assert page.locator('#prompt-form').is_hidden()
                assert page.locator('#prompt-dialog button:focus').text_content() == 'Cancel'
                with contextlib.suppress(BlockingIOError):
                    assert os.read(terminal_read, 10) == b''
                if choice == 'Escape':
                    page.keyboard.press('Escape')
                else:
                    page.locator('#prompt-dialog').get_by_role('button', name=choice, exact=True).click()
                page.locator('#prompt-dialog').wait_for(state='hidden')
                assert os.read(terminal_read, 10) == answer
            # Subsequent ordinary prompts restore the normal input form.
            with session.condition:
                session.prompt = {'active': True, 'id': session.prompt['id'] + 1,
                                  'secret': False, 'line': True, 'limit': 10, 'mask': ''}
                session.changed()
            page.locator('#prompt-form').wait_for(state='visible')
            with session.condition:
                session.prompt['active'] = False
                session.changed()
            page.locator('#prompt-dialog').wait_for(state='hidden')
            with session.condition:
                session.state = {**session.state, 'offline': True}
                session.changed()
            page.wait_for_function("() => document.getElementById('stations-message').textContent.includes('Reconnect')")
            assert page.get_by_role('button', name='Play station All stations').is_disabled()
            page.set_viewport_size({'width': 390, 'height': 844})
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
            browser.close()
        print('PASS: browser station search, Play command, current station, reload, offline state, mobile layout')
finally:
    with session.condition:
        session.exited = True
        session.changed()
    server.shutdown()
    server.server_close()
    session.status.close()
    receiver.close()
    os.close(terminal_read)
    os.close(session.master)
