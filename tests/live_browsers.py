#!/usr/bin/env python3
"""Open two test receivers for live REST/Rook acceptance checks (no speaker output).

Writes IDs and playback observations to --status-file. Route these IDs through
Rook, then inspect that file. Touch --stop-file to close both pages. Requires
Playwright/Chromium. Never writes account credentials to the status file.
"""
import argparse
import json
import os
from pathlib import Path
import time

from playwright.sync_api import sync_playwright

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--origin', default='http://127.0.0.1:8765')
parser.add_argument('--status-file', type=Path, required=True)
parser.add_argument('--stop-file', type=Path, required=True)
args = parser.parse_args()
config = Path(os.environ.get('PIANOBAR_WEB_CONFIG', '/var/lib/pianobar/.config/pianobar/web.json'))
password = json.loads(config.read_text()).get('password') if config.exists() else None
credentials = {'username': 'pianobar', 'password': password} if password else None
errors = []
with sync_playwright() as p:
    browser = p.chromium.launch(args=['--mute-audio'])
    try:
        pages = []
        for name in ('Rook acceptance A', 'Rook acceptance B'):
            page = browser.new_page(http_credentials=credentials)
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(args.origin)
            page.wait_for_function('() => browserId !== null && snapshot.state.output')
            page.locator('#browser-name').fill(name)
            page.locator('#browser-name').press('Tab')
            page.get_by_role('button', name='Listen here', exact=True).click()
            page.wait_for_function('() => audioContext?.state === "running"')
            pages.append(page)
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline and not args.stop_file.exists():
            rows = [page.evaluate('''() => ({id: browserId, sources: audioSources.size,
                active: !!audioController, ready: audioContext?.state === 'running',
                gain: audioGain?.gain.value, status: browserStatus})''') for page in pages]
            temporary = args.status_file.with_suffix('.tmp')
            temporary.write_text(json.dumps({'time': time.time(), 'pages': rows, 'errors': errors}))
            temporary.replace(args.status_file)
            pages[0].wait_for_timeout(300)
        for page in pages:
            page.goto('about:blank')
        pages[0].wait_for_timeout(500)
    finally:
        browser.close()
