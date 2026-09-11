#!/usr/bin/env python3
"""Capture the real UI with fictional music and generated artwork; no live server."""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--browser', help='Optional Chromium executable')
parser.add_argument('--output', type=Path, default=ROOT / 'docs/screenshots')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)

# Original vector artwork and fictional metadata; nothing comes from an account.
art = '''<svg xmlns="http://www.w3.org/2000/svg" width="900" height="900" viewBox="0 0 900 900">
<defs><linearGradient id="sky" x2="0" y2="1"><stop stop-color="#16273e"/><stop offset="1" stop-color="#724e63"/></linearGradient>
<linearGradient id="sun" x2="0" y2="1"><stop stop-color="#ffe3aa"/><stop offset="1" stop-color="#ec7967"/></linearGradient></defs>
<path fill="url(#sky)" d="M0 0h900v900H0z"/><circle cx="450" cy="355" r="205" fill="url(#sun)"/>
<path fill="#263b46" d="M0 590L240 365l175 200 190-165 295 210v290H0z"/>
<path fill="#172d35" d="M0 740l230-230 270 250 210-185 190 135v190H0z"/>
<path stroke="#b68b88" stroke-width="2" d="M0 800h900M0 830h900M0 870h900M450 690L50 900m400-210L250 900m200-210v210m0-210l200 210m-200-210l400 210"/>
<text x="55" y="90" fill="#fff1d7" font-family="sans-serif" font-size="26" letter-spacing="9">THE NIGHT SIGNAL</text>
<text x="55" y="140" fill="#fff1d7" font-family="sans-serif" font-size="16" letter-spacing="5">AFTER THE SUN</text></svg>'''
tracks = [('Midnight Drive', 'The Night Signal', 'After the Sun', 247),
          ('Electric Coast', 'Glass Harbor', 'Open Water', 213),
          ('Slow Motion', 'Velvet Satellites', 'Evening Light', 284),
          ('Somewhere Warm', 'Sunday Arcade', 'Soft Focus', 198),
          ('Last Light', 'The Night Signal', 'After the Sun', 235)]
songs = [dict(id=f'{i+1:064x}.mka', title=t, artist=a, album=b, duration=d, cover='/api/artwork/'+'a'*64)
         for i, (t, a, b, d) in enumerate(tracks)]
action_ids = re.findall(r'"(act_\w+)"', (ROOT / 'src/ui_dispatch.h').read_text())
actions = [dict(id=a, label=a.removeprefix('act_').replace('_', ' ').title(), key='', enabled=True) for a in action_ids]
state = dict(revision=1, state=dict(title=tracks[0][0], artist=tracks[0][1], album=tracks[0][2],
             station='Late Night Radio', elapsed=83, duration=247, volume=0, output='browser',
             paused=False, offline=False, loved=True, cachePending=0, cacheDir='/var/lib/pianobar/songs',
             cachedCover='/api/artwork/'+'a'*64, savedId=songs[0]['id'], actions=actions,
             stations=[dict(id=str(i), name=name, selected=i == 0) for i, name in enumerate(
                 ['Late Night Radio', 'Sunday Morning', 'Instrumental Focus', 'Coastal Sounds'])]),
             prompt=dict(active=False), output='Welcome to pianobar SUPER\nStation: Late Night Radio\nNow playing: Midnight Drive — The Night Signal\nSaved for offline listening.\nBrowser audio ready.',
             exited=False, playerStopped=False, setupRequired=False, pending=False)

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True, executable_path=args.browser,
                                         args=['--mute-audio', '--disable-dev-shm-usage'])
    try:
        for name, width, height, theme, view in [
            ('player-dark', 1440, 1050, 'dark', 'player'),
            ('library-light', 1440, 1050, 'light', 'library'),
            ('player-mobile', 390, 1150, 'dark', 'player')]:
            page = browser.new_page(viewport=dict(width=width, height=height), device_scale_factor=1)
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.add_init_script('localStorage.setItem("pianobarAutoListen", "false");'
                                 + 'localStorage.setItem("pianobarTheme", ' + json.dumps(theme) + ');')

            def route(request):
                path = urlsplit(request.request.url).path
                if path.startswith('/api/artwork/'):
                    request.fulfill(body=art, content_type='image/svg+xml')
                elif path == '/api/state':
                    request.fulfill(json=state)
                elif path == '/api/library':
                    request.fulfill(json=dict(songs=songs))
                elif path in ('/api/clients/register', '/api/clients/heartbeat', '/api/clients/update'):
                    request.fulfill(json=dict(id='demo', name='This browser', enabled=False, volume=1))
                elif path.startswith('/api/clients'):
                    request.fulfill(json=dict(clients=[]))
                elif path.startswith('/api/'):
                    request.fulfill(json={})
                else:
                    file = ROOT / 'web' / ('index.html' if path == '/' else path.lstrip('/'))
                    request.fulfill(path=str(file)) if file.is_file() else request.fulfill(status=404)

            page.route('**/*', route)
            page.goto('http://pianobar.example/' + ('#library' if view == 'library' else ''))
            page.wait_for_function('document.querySelector("#title").textContent === "Midnight Drive"')
            page.wait_for_function('document.querySelector("#cover").complete')
            if view == 'library': page.wait_for_selector('.saved-song')
            page.wait_for_timeout(500)
            assert not errors, errors
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Horizontal overflow'
            page.screenshot(path=str(args.output / (name + '.png')), full_page=True, animations='disabled')
            page.close()
            print('Captured ' + name)
    finally:
        browser.close()
