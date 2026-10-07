#!/usr/bin/env python3
"""Real HTTP checks for opt-in DJ generation, agent announcements and TTS."""
import contextlib
import http.server
import io
import json
import os
from pathlib import Path
import runpy
import threading
import unittest
from unittest.mock import patch
import urllib.request
import urllib.error
import wave

ROOT = Path(__file__).resolve().parents[1]
host = runpy.run_path(str(ROOT / 'pianobar-web'), run_name='dj_test_host')


class DJTests(unittest.TestCase):
    def setUp(self):
        audio = io.BytesIO()
        with wave.open(audio, 'wb') as file:
            file.setnchannels(1)
            file.setsampwidth(2)
            file.setframerate(16000)
            file.writeframes(b'\0\0' * 1600)
        self.wav = audio.getvalue()
        self.calls = []
        calls, wav = self.calls, self.wav
        class Provider(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append((self.path, None, self.headers.get('Authorization')))
                body = json.dumps({'voices': ['am_onyx', 'af_heart'], 'default': 'am_onyx'}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(body)
            def do_POST(self):
                message = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                calls.append((self.path, message, self.headers.get('Authorization')))
                if self.path == '/chat':
                    content = 'Coming at ya with Kid Cudi and Dat New New.'
                    user = message['messages'][1]['content']
                    if '\n\nCandidates:\n' in user:
                        # Playlist request: pick by artist, plus an invalid and a repeated number.
                        lines = user.split('\n\nCandidates:\n', 1)[1].split('\n')
                        number = lambda artist: next(int(line.split('\t')[0]) for line in lines if line.split('\t')[1] == artist)
                        content = 'Sure! ' + json.dumps(dict(name='Rainy Day Riot', description='Punk for grey skies.',
                            songs=[number('The Clash'), number('Kid Cudi'), 999, number('The Clash')]))
                        user = '{}'
                    context = json.loads(user)
                    if 'candidates' in context:
                        content = json.dumps(dict(id=context['candidates'][0]['id'], text='Coming at ya with the next cached song!'))
                    body = json.dumps({'message': {'content': content}}).encode()
                else:
                    body = wav
                self.send_response(200)
                self.send_header('Content-Type', 'application/json' if self.path == '/chat' else 'audio/wav')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *args):
                pass
        self.provider = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Provider)
        self.provider.daemon_threads = True
        threading.Thread(target=self.provider.serve_forever, daemon=True).start()
        url = 'http://127.0.0.1:' + str(self.provider.server_port)
        with patch.dict(os.environ, {'PIANOBAR_DJ_URL': url + '/chat', 'PIANOBAR_DJ_MODEL': 'local-model',
                                     'PIANOBAR_DJ_KEY': 'private-llm-key', 'PIANOBAR_TTS_URL': url + '/speech',
                                     'PIANOBAR_TTS_VOICE': 'radio', 'PIANOBAR_TTS_KEY': 'private-voice-key'}):
            self.session = host['Session']()
        self.session.state = dict(title='Dat New New', artist='Kid Cudi', album='', station='Cudi Radio', offline=True, output='browser')
        self.listening_client = self.session.clients.register({'name': 'Listening test browser'})['id']
        self.session.clients.update({'id': self.listening_client, 'enabled': True})
        self.session.clients.heartbeat({'id': self.listening_client, 'status': 'playing', 'ready': True})
        self.session.audio_listeners[object()] = self.listening_client
        # Record spoken lines; test_spoken_line_reaches_player covers the real pipeline.
        self.spoken = []
        self.real_say = self.session.dj.say
        def say(text, song_key=None):
            self.spoken.append(dict(text=text, songKey=song_key))
            return dict(id=len(self.spoken), status='preparing', text=text, songKey=song_key, error='')
        self.session.dj.say = say
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), host['Handler'])
        self.server.daemon_threads = True
        self.server.session = self.session
        self.server.assets = ROOT / 'web'
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = 'http://127.0.0.1:' + str(self.server.server_port)
        self.addCleanup(self.close)

    def close(self):
        for server in (self.server, self.provider):
            server.shutdown()
            server.server_close()

    def request(self, path, message=None, expected=200, headers=None):
        headers = dict({'Content-Type': 'application/json'}, **(headers or {}))
        body = json.dumps(message).encode() if message is not None else None
        try:
            with urllib.request.urlopen(urllib.request.Request(self.url + path, body, headers), timeout=5) as response:
                self.assertEqual(response.status, expected)
                data = response.read()
                return data if response.headers.get_content_type().startswith('audio/') else json.loads(data)
        except urllib.error.HTTPError as error:
            self.assertEqual(error.code, expected, error.read())
            return None

    def test_generate_context_cache_and_voice(self):
        public = self.request('/api/dj')
        self.assertTrue(public['llmReady'])
        self.assertTrue(public['voiceReady'])
        self.assertNotIn('private', json.dumps(public))
        result = self.request('/api/dj', {'style': 'A playful London punk DJ'})
        self.assertEqual(result['songKey'], public['songKey'])
        self.assertIn('Kid Cudi', result['text'])
        self.assertEqual(self.request('/api/dj', {'style': 'A playful London punk DJ'}), result)
        self.assertEqual(len(self.calls), 1)
        prompt = self.calls[0][1]['messages']
        self.assertIn('London punk', prompt[0]['content'])
        self.assertEqual(json.loads(prompt[1]['content'])['station'], 'Cudi Radio')
        self.assertEqual(self.request('/api/voice', {'text': result['text']}), self.wav)
        self.assertEqual(self.calls[-1][1], dict(text=result['text'], voice='radio'))
        self.assertEqual(self.calls[-1][2], 'Bearer private-voice-key')

    def test_no_generation_without_a_listener(self):
        import time
        page = self.session.clients.pages[self.listening_client]
        mutations = [('enabled', False), ('ready', False), ('volume', 0), ('status', 'waiting'), ('status', 'idle'), ('seen', time.monotonic() - 11)]
        for key, value in mutations:
            original = page[key]; page[key] = value
            self.request('/api/dj', {}, expected=503)
            with self.assertRaises(host['DJNotListening']): self.session.dj.choose('Mix', 'Warm DJ')
            page[key] = original
        self.session.state['paused'] = True
        self.request('/api/dj', {}, expected=503)
        self.session.state['paused'] = False
        self.session.audio_listeners.clear()
        self.request('/api/dj', {}, expected=503)
        self.assertEqual(self.calls, [])
        # Explicit Settings previews remain usable with music stopped.
        self.assertEqual(self.request('/api/voice', {'text': 'A sample', 'voice': 'radio'}), self.wav)
        # The DJ talks through the player, so host speakers count as a listener.
        for output in ('host', 'both'):
            self.session.state['output'] = output
            self.assertTrue(self.session.dj.listening())

    def test_names_are_prompt_data_and_hot_apply_invalidates_intro_cache(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'XDG_CONFIG_HOME': directory}):
            self.server.configuration = host['Configuration']()
            self.request('/api/settings', {'settings': {'dj': {'dj_name': 'DJ Rook', 'listener_name': 'Bake'}}, 'apply': False})
            self.request('/api/dj', {})
            context = json.loads(self.calls[-1][1]['messages'][1]['content'])
            self.assertEqual((context['dj_name'], context['listener_name']), ('DJ Rook', 'Bake'))
            self.request('/api/settings', {'settings': {'dj': {'dj_name': 'DJ Kaiju'}}, 'apply': False})
            self.request('/api/dj', {})
            self.assertEqual(len(self.calls), 2)
            self.assertEqual(json.loads(self.calls[-1][1]['messages'][1]['content'])['dj_name'], 'DJ Kaiju')
            self.assertFalse(self.session.restart.is_set())

    def test_background_intro_waits_and_does_not_warm_voice_after_listener_stops(self):
        import time
        from unittest.mock import Mock
        ids = [c * 64 + '.mka' for c in 'ab']
        song = dict(id=ids[1], title='Upcoming', artist='Next artist', album='')
        self.session.library = lambda: [song]
        self.session.state.update(savedId=ids[0], queuedSavedId='', elapsed=10)
        self.session.dj.on_state(self.session.state)
        self.session.dj.station['enabled'] = True
        page = self.session.clients.pages[self.listening_client]
        page['enabled'] = False
        began, release = threading.Event(), threading.Event()
        def generate(*args, **kwargs):
            began.set(); self.assertTrue(release.wait(3))
            return dict(text='Prepared intro', songKey=self.session.dj.song_key(song))
        self.session.dj.generate = Mock(side_effect=generate)
        self.session.dj.speak = Mock()
        self.session.state.update(queuedSavedId=ids[1], queuedTitle=song['title'], queuedArtist=song['artist'])
        self.session.dj.on_state(self.session.state)
        self.assertFalse(began.wait(.2))
        page['enabled'] = True; self.session.dj.intro_event.set()
        self.assertTrue(began.wait(3))
        page['enabled'] = False; release.set()
        time.sleep(.2)
        self.session.dj.speak.assert_not_called()
        self.assertIsNone(self.session.dj.plan.get('text'))
        self.session.exited = True; self.session.dj.intro_event.set()

    def test_spoken_line_reaches_player(self):
        import socket
        import time
        self.session.dj.say = self.real_say
        self.session.status, player = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.addCleanup(player.close)
        self.addCleanup(self.session.dj.close)
        key = self.request('/api/dj')['songKey']
        voice = self.request('/api/dj/announce', {'text': 'Coming at ya with some London punk!', 'songKey': key})
        self.assertEqual((voice['status'], voice['text']), ('preparing', 'Coming at ya with some London punk!'))
        player.settimeout(5)
        packet = json.loads(player.recv(4096))
        self.assertEqual((packet['type'], packet['voice'], packet['overMusic']), ('dj_voice', voice['id'], True))
        self.assertEqual(Path(packet['id']).read_bytes(), self.wav)
        self.assertEqual(self.calls[-1][1], dict(text='Coming at ya with some London punk!', voice='radio'))
        state = self.request('/api/state')
        self.assertEqual((state['djVoice']['status'], state['djVoice']['songKey']), ('on_air', key))
        self.session.dj.on_player_voice(dict(djVoice=0, djVoiceDone=voice['id']))
        self.assertEqual(self.request('/api/state')['djVoice']['status'], 'done')
        # A held line (music pauses while the DJ speaks) is the player's job too.
        self.session.dj.set_options['play_over_music'] = False
        self.request('/api/dj/announce', {'text': 'Holding the music for this one.'})
        self.assertFalse(json.loads(player.recv(4096))['overMusic'])
        self.request('/api/dj/announce', {'text': 'Stale', 'songKey': 'bad'}, expected=400)
        # A line rendered for a song that has since ended is dropped.
        self.session.dj.voice_slots.acquire()
        self.session.dj.say('About the old song', key)
        self.session.state['title'] = 'Another song'
        self.session.dj.voice_slots.release()
        deadline = time.monotonic() + 3
        while self.session.dj.voice_public()['status'] == 'preparing' and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertEqual(self.session.dj.voice_public()['status'], 'idle')
        player.settimeout(.2)
        with self.assertRaises(TimeoutError):
            player.recv(4096)

    def test_talk_toggle_stop_and_skip_silence_the_dj(self):
        import socket
        import tempfile
        from unittest.mock import Mock
        self.session.status, player = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.addCleanup(player.close)
        player.settimeout(2)
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'XDG_CONFIG_HOME': directory}):
            self.server.configuration = host['Configuration']()
            self.assertTrue(self.request('/api/dj/control', {'talk': True})['talk'])
            self.assertTrue(self.server.configuration.dj()['talk'])
            self.assertFalse(self.request('/api/dj/control', {'talk': False})['talk'])
            self.assertEqual(json.loads(player.recv(4096))['type'], 'dj_voice_stop')
            self.assertFalse(self.server.configuration.dj()['talk'])
        self.request('/api/dj/control', {'stopVoice': True})
        self.assertEqual(json.loads(player.recv(4096))['type'], 'dj_voice_stop')
        self.request('/api/dj/control', {'talk': 'yes'}, expected=400)
        self.request('/api/dj/control', {}, expected=400)
        self.session.master = Mock()
        self.session.state['actions'] = [dict(id='act_songnext', enabled=True, key='n'), dict(id='act_songpause', enabled=True, key='S')]
        with patch('os.write', return_value=1):
            self.request('/api/command', {'action': 'act_songpause'})
            self.session.pending = False
            self.request('/api/command', {'action': 'act_songnext'})
        # Pausing holds the line with the music; skipping drops it.
        self.assertEqual(json.loads(player.recv(4096))['type'], 'dj_voice_stop')
        player.settimeout(.2)
        with self.assertRaises(TimeoutError):
            player.recv(4096)

    def test_talking_dj_introduces_each_new_song_once(self):
        import time
        self.session.dj.talk = True
        self.session.state.update(elapsed=1)
        self.session.dj.on_state(self.session.state)
        time.sleep(.1)
        self.assertEqual(self.spoken, [])
        self.session.state.update(elapsed=4)
        for _ in range(3):
            self.session.dj.on_state(self.session.state)
        deadline = time.monotonic() + 3
        while not self.spoken and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertEqual(self.spoken, [dict(text='Coming at ya with Kid Cudi and Dat New New.', songKey=self.session.dj.song_key(self.session.state))])
        self.assertEqual(sum(path == '/chat' for path, _, _ in self.calls), 1)
        self.session.dj.talk = False
        self.session.state.update(title='Next song', elapsed=10)
        self.session.dj.on_state(self.session.state)
        time.sleep(.1)
        self.assertEqual(len(self.spoken), 1)

    def test_validation_authentication_origin_and_no_provider(self):
        self.request('/api/voice', {'text': 'x' * 701}, expected=400)
        self.request('/api/dj/announce', {'text': ''}, expected=400)
        self.request('/api/dj', {'url': 'http://bad.test'}, expected=400)
        self.request('/api/voice', {'text': 'Hi'}, headers={'Origin': 'https://evil.test'}, expected=403)
        self.server.authorization = b'Basic private'
        self.request('/api/dj', expected=401)
        self.server.authorization = None
        self.session.dj.llm_url = ''
        self.session.dj.tts_url = ''
        self.session.dj.local_voice = None
        self.request('/api/dj', {}, expected=503)
        self.request('/api/voice', {'text': 'Hello'}, expected=503)
        self.assertEqual(self.calls, [])

    def test_cached_selection_queues_valid_id_and_announces_on_start(self):
        from unittest.mock import Mock
        first, second = 'a' * 64 + '.mka', 'b' * 64 + '.mka'
        self.session.state.update(savedId=first, queuedSavedId='', elapsed=0)
        self.session.library = lambda: [dict(id=first, artist='Kid Cudi', title='Dat New New', album=''),
                                       dict(id=second, artist='The Clash', title='London Calling', album='London Calling')]
        self.session.status = Mock()
        self.session.dj.talk = True
        self.session.dj.on_state(self.session.state)
        result = self.request('/api/dj/pick', {'theme': 'London punk'})
        self.assertEqual(result['id'], second)
        packet = json.loads(self.session.status.send.call_args.args[0])
        self.assertEqual(packet, dict(type='queue_saved', id=second, current=first))
        self.assertEqual(self.session.state['title'], 'Dat New New')
        self.session.state.update(savedId=second, title=result['title'], artist=result['artist'], album=result['album'])
        self.session.dj.on_state(self.session.state)
        self.assertEqual(self.spoken, [dict(text=result['text'], songKey=result['songKey'])])
        self.assertIsNone(self.session.dj.station_public()['next'])

    def test_up_next_change_regenerates_and_discards_stale_intro(self):
        import time
        from unittest.mock import Mock
        first, second, third = [c * 64 + '.mka' for c in 'abc']
        library = [dict(id=first, title='Current', artist='One', album=''),
                   dict(id=second, title='Second', artist='Two', album=''),
                   dict(id=third, title='Third', artist='Three', album='')]
        self.session.library = lambda: library
        self.session.state.update(savedId=first, queuedSavedId='', elapsed=10)
        self.session.dj.on_state(self.session.state)
        self.session.dj.station['enabled'] = True
        self.session.dj.talk = True
        started, release = threading.Event(), threading.Event()
        generated = []
        def generate(message, context=None, cache=True):
            generated.append((context['id'], cache))
            if context['id'] == second:
                started.set()
                self.assertTrue(release.wait(3))
            return dict(text='Introducing ' + context['title'], songKey=self.session.dj.song_key(context))
        self.session.dj.generate = generate
        self.session.dj.speak = Mock(return_value=(self.wav, 'audio/wav'))
        self.session.state.update(queuedSavedId=second, queuedTitle='Second', queuedArtist='Two')
        self.session.dj.on_state(self.session.state)
        self.assertTrue(started.wait(3))
        self.session.state.update(queuedSavedId=third, queuedTitle='Third', queuedArtist='Three')
        self.session.dj.on_state(self.session.state)
        release.set()
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if (self.session.dj.plan or {}).get('text') == 'Introducing Third': break
            time.sleep(.01)
        self.assertEqual(self.session.dj.plan['text'], 'Introducing Third')
        self.assertEqual(generated, [(second, False), (third, False)])
        self.session.dj.speak.assert_called_once_with({'text': 'Introducing Third'})
        self.assertEqual(self.spoken, [])
        self.session.dj.on_state(self.session.state)
        self.assertEqual(len(generated), 2)
        self.session.state.update(savedId=third, title='Third', artist='Three', album='', queuedSavedId='', elapsed=0)
        self.session.dj.on_state(self.session.state)
        self.assertEqual(self.spoken[-1]['text'], 'Introducing Third')
        self.session.exited = True
        self.session.dj.intro_event.set()

    def test_intro_finishing_after_transition_announces_current_selection(self):
        import time
        from unittest.mock import Mock
        first, second = [c * 64 + '.mka' for c in 'ab']
        song = dict(id=second, title='Upcoming', artist='Next artist', album='')
        self.session.library = lambda: [song]
        self.session.state.update(savedId=first, queuedSavedId='', elapsed=10)
        self.session.dj.on_state(self.session.state)
        self.session.dj.station['enabled'] = True
        self.session.dj.talk = True
        started, release = threading.Event(), threading.Event()
        def generate(message, context=None, cache=True):
            started.set()
            self.assertTrue(release.wait(3))
            return dict(text='The prepared introduction', songKey=self.session.dj.song_key(song))
        self.session.dj.generate = generate
        self.session.dj.speak = Mock(return_value=(self.wav, 'audio/wav'))
        self.session.state.update(queuedSavedId=second, queuedTitle=song['title'], queuedArtist=song['artist'])
        self.session.dj.on_state(self.session.state)
        self.assertTrue(started.wait(3))
        self.session.state.update(savedId=second, title=song['title'], artist=song['artist'], album='', queuedSavedId='', elapsed=0)
        self.session.dj.on_state(self.session.state)
        release.set()
        deadline = time.monotonic() + 3
        while not self.spoken and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertEqual(self.spoken[-1]['text'], 'The prepared introduction')
        self.session.exited = True
        self.session.dj.intro_event.set()

    def test_voice_catalog_proxy_auth_cache_and_missing_provider(self):
        catalog = self.request('/api/dj/voices')
        self.assertEqual(catalog['voices'], ['am_onyx', 'af_heart'])
        self.assertEqual(catalog['default'], 'am_onyx')
        self.assertNotIn('private', json.dumps(catalog))
        self.assertEqual(self.calls[-1], ('/voices', None, 'Bearer private-voice-key'))
        self.assertEqual(self.request('/api/dj/voices'), catalog)
        self.assertEqual(len(self.calls), 1)
        self.request('/api/dj/voices', headers={'Origin': 'https://evil.test'}, expected=403)
        self.session.dj.tts_url = ''
        self.assertEqual(self.request('/api/dj/voices'), dict(voices=[], default='', configured=False))

    def test_prepared_voice_reused_at_playback(self):
        body = self.request('/api/voice', {'text': 'Prepared introduction'})
        self.assertEqual(self.request('/api/voice', {'text': 'Prepared introduction'}), body)
        self.assertEqual(sum(path == '/speech' for path, _, _ in self.calls), 1)

    def test_voice_preview_cache_is_separate_and_does_not_change_active_voice(self):
        for voice in ['af_heart', 'am_onyx', 'af_heart', '']:
            self.assertEqual(self.request('/api/voice', {'text': 'A voice sample', 'voice': voice}), self.wav)
        self.assertEqual([body['voice'] for path, body, _ in self.calls], ['af_heart', 'am_onyx', ''])
        self.assertEqual(self.session.dj.voice, 'radio')
        self.request('/api/voice', {'text': 'Hi', 'voice': ['bad']}, expected=400)
        self.request('/api/voice', {'text': 'Hi', 'voice': '--bad'}, expected=400)

    def test_saved_voice_applies_without_restart_or_changing_station(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'XDG_CONFIG_HOME': directory}):
            self.server.configuration = host['Configuration']()
            self.session.dj.station.update(enabled=True, next={'id': 'queued'})
            current = dict(self.session.state)
            self.request('/api/voice', {'text': 'Prepared introduction'})
            self.request('/api/settings', {'settings': {'dj': {'voice': 'af_heart'}}, 'apply': False})
            self.assertEqual(self.session.dj.voice, 'af_heart')
            self.assertFalse(self.session.restart.is_set())
            self.assertEqual(self.session.state, current)
            self.assertTrue(self.session.dj.station['enabled'])
            self.assertEqual(self.session.dj.station['next'], {'id': 'queued'})
            self.request('/api/voice', {'text': 'Prepared introduction'})
            self.assertEqual(self.calls[-1][1]['voice'], 'af_heart')
            self.assertEqual(self.server.configuration.dj()['voice'], 'af_heart')

    def test_set_selection_count_duration_and_invalid_ids(self):
        from unittest.mock import Mock
        ids = [c * 64 + '.mka' for c in 'abcd']
        self.session.state['savedId'] = ids[0]
        self.session.library = lambda: [dict(id=i, title='Song ' + str(n), artist='Artist ' + str(n), album='', duration=180) for n, i in enumerate(ids)]
        def reply(chosen):
            return json.dumps({'message': {'content': json.dumps({'ids': chosen, 'text': 'A three-song set'})}}).encode(), 'application/json'
        self.session.dj.post = Mock(return_value=reply(ids[1:3]))
        options = dict(set_mode='songs', set_songs=2, set_minutes=5, play_over_music=True)
        selected = self.session.dj.choose('A varied mix', 'Warm DJ', options)
        self.assertEqual([s['id'] for s in selected['songs']], ids[1:3])
        self.session.dj.post.return_value = reply(ids[1:])
        options['set_mode'] = 'minutes'
        selected = self.session.dj.choose('A varied mix', 'Warm DJ', options)
        self.assertEqual(sum(s['duration'] for s in selected['songs']), 360)
        for invalid in [[ids[1], ids[1]], [ids[0]], ['missing'], []]:
            self.session.dj.post.return_value = reply(invalid)
            with self.assertRaises(host['DJUnavailable']): self.session.dj.choose('Mix', 'DJ', options)

    def test_set_queue_and_one_summary_only_at_set_start(self):
        import time
        from unittest.mock import Mock
        ids = [c * 64 + '.mka' for c in 'abc']
        songs = [dict(id=i, title='Song ' + str(n), artist='Artist ' + str(n), album='', duration=180) for n, i in enumerate(ids)]
        self.session.library = lambda: songs
        self.session.status = Mock()
        self.session.state.update(savedId=ids[0], queuedSavedId='', elapsed=10)
        self.session.dj.on_state(self.session.state)
        self.session.dj.station['enabled'] = True
        self.session.dj.talk = True
        self.session.dj.generate = Mock(return_value=dict(text='Introducing this whole set', songKey=self.session.dj.song_key(songs[1])))
        self.session.dj.speak = Mock(return_value=(self.wav, 'audio/wav'))
        selection = dict(songs[1], songs=songs[1:], text='Initial summary', songKey=self.session.dj.song_key(songs[0]))
        self.session.dj.apply_selection(selection, False, self.session.dj.song_key(self.session.state))
        packet = json.loads(self.session.status.send.call_args.args[0])
        self.assertEqual(packet['ids'], ids[1:])
        self.session.state.update(queuedSavedId=ids[1], queuedSavedIds=ids[1:], queuedTitle=songs[1]['title'], queuedArtist=songs[1]['artist'])
        self.session.dj.on_state(self.session.state)
        deadline = time.monotonic() + 3
        while not self.session.dj.plan.get('text') and time.monotonic() < deadline: time.sleep(.01)
        self.assertEqual(len(self.session.dj.generate.call_args.kwargs['context']['setSongs']), 2)
        self.session.state.update(savedId=ids[1], title=songs[1]['title'], artist=songs[1]['artist'], queuedSavedId=ids[2], queuedSavedIds=[ids[2]], elapsed=0)
        self.session.dj.on_state(self.session.state)
        self.assertEqual(self.spoken[-1]['text'], 'Introducing this whole set')
        self.assertEqual(self.session.dj.active_set['position'], 1)
        self.session.state.update(savedId=ids[2], title=songs[2]['title'], artist=songs[2]['artist'], queuedSavedId='', queuedSavedIds=[], elapsed=0)
        self.session.dj.on_state(self.session.state)
        self.assertEqual(len(self.spoken), 1)
        self.assertEqual(self.session.dj.active_set['position'], 2)
        self.assertEqual(self.session.dj.generate.call_count, 1)
        self.session.exited = True
        self.session.dj.intro_event.set()

    def test_playlists_are_saved_edited_and_played_through_the_queue(self):
        import tempfile, time
        from unittest.mock import Mock
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.session.playlists.configuration.directory = Path(directory.name)
        ids = [c * 64 + '.mka' for c in 'abcd']
        songs = [dict(id=i, title='Song ' + str(n), artist='Artist ' + str(n), album='', duration=180, genres=[]) for n, i in enumerate(ids)]
        self.session.library = lambda: songs
        self.assertEqual(self.request('/api/playlists'), dict(playlists=[], run=None))
        made = self.request('/api/playlists/save', {'name': '  Late   night ', 'songs': ids[:2]})
        self.assertEqual((made['name'], made['songs'], made['by']), ('Late night', ids[:2], 'you'))
        self.request('/api/playlists/add', {'id': made['id'], 'songs': [ids[1], ids[2]]})
        edited = self.request('/api/playlists/save', {'id': made['id'], 'description': 'Quiet ones', 'songs': [ids[2], ids[0], ids[1]]})
        self.assertEqual(edited['songs'], [ids[2], ids[0], ids[1]])
        self.assertEqual(json.loads((Path(directory.name) / 'playlists.json').read_text())['playlists'][0]['description'], 'Quiet ones')
        self.assertEqual(os.stat(Path(directory.name) / 'playlists.json').st_mode & 0o777, 0o600)
        for bad in ({'name': ''}, {'name': 'x', 'songs': ['../etc/passwd']}, {'id': 'missing', 'name': 'x'}, {'name': 'x', 'extra': 1}):
            self.request('/api/playlists/save', bad, expected=400)
        # Playing starts the first song, then keeps the player's queue filled.
        self.session.status = Mock()
        self.session.state.update(savedId=ids[3], title='Song 3', artist='Artist 3', offline=True, queuedSavedIds=[])
        self.session.dj.station['enabled'] = True
        run = self.request('/api/playlists/play', {'id': made['id']})
        self.assertEqual((run['id'], run['total']), (made['id'], 3))
        self.assertFalse(self.session.dj.station['enabled'])
        packets = lambda kind: [p for p in (json.loads(c.args[0]) for c in self.session.status.send.call_args_list) if p['type'] == kind]
        self.assertEqual(packets('play_saved'), [dict(type='play_saved', id=ids[2])])
        self.session.pending = False
        self.session.state.update(savedId=ids[2], title='Song 2', artist='Artist 2')
        self.session.playlists.on_state(dict(self.session.state))
        deadline = time.monotonic() + 3
        while not packets('queue_saved') and time.monotonic() < deadline: time.sleep(.01)
        packet = packets('queue_saved')[0]
        self.assertEqual((packet['ids'], packet['current']), ([ids[0], ids[1]], ids[2]))
        self.assertEqual(self.request('/api/playlists')['run']['position'], 0)
        self.session.pending = False
        self.session.state.update(savedId=ids[0], title='Song 0', artist='Artist 0', queuedSavedIds=[ids[1]])
        self.session.playlists.on_state(dict(self.session.state))
        time.sleep(.1)
        self.assertEqual(len(packets('queue_saved')), 1)
        self.assertEqual(self.session.playlists.run_public()['position'], 1)
        # The DJ knows the song's place in the playlist.
        self.session.dj.generate({}, context=dict(title='Song 0', artist='Artist 0', offline=True), cache=False)
        sent = json.loads(self.calls[-1][1]['messages'][1]['content'])
        self.assertEqual(sent['playlist'], dict(name='Late night', description='Quiet ones', songNumber=2, totalSongs=3))
        self.assertIn('never a station', self.calls[-1][1]['messages'][0]['content'])
        self.session.dj.generate({}, context=dict(title='Song 3', artist='Artist 3', offline=True), cache=False)
        self.assertNotIn('playlist', json.loads(self.calls[-1][1]['messages'][1]['content']))
        # Anything else playing ends the run.
        self.session.state.update(savedId=ids[3], title='Song 3', artist='Artist 3', queuedSavedIds=[])
        self.session.playlists.on_state(dict(self.session.state))
        self.assertIsNone(self.request('/api/playlists')['run'])
        self.request('/api/playlists/delete', {'id': made['id']})
        self.assertEqual(self.request('/api/playlists')['playlists'], [])
        self.request('/api/playlists/delete', {'id': made['id']}, expected=400)

    def test_dj_makes_a_playlist_from_a_description(self):
        import tempfile
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.session.playlists.configuration.directory = Path(directory.name)
        clash, cudi = 'b' * 64 + '.mka', 'a' * 64 + '.mka'
        songs = [dict(id=cudi, artist='Kid Cudi', title='Dat New New', album='', duration=200, genres=['hip hop']),
                 dict(id=clash, artist='The Clash', title='London Calling', album='London Calling', duration=200, genres=['punk'])]
        songs += [dict(id=f'{n:064x}.mka', artist=f'Filler {n}', title=f'Song {n}', album='', duration=100, genres=[]) for n in range(16, 40)]
        self.session.library = lambda: songs
        made = self.request('/api/dj/playlist', {'description': 'Rainy punk afternoon', 'count': 10})
        self.assertEqual((made['name'], made['description'], made['by']), ('Rainy Day Riot', 'Punk for grey skies.', 'dj'))
        self.assertEqual(made['songs'], [clash, cudi])
        sent = self.calls[-1][1]['messages'][1]['content'].split('\n\nCandidates:\n', 1)[1].split('\n')
        self.assertEqual(sent[0].split('\t')[1], 'The Clash')  # matching songs come first
        self.assertEqual(self.request('/api/playlists')['playlists'][0]['id'], made['id'])
        for bad in ({}, {'description': 'x', 'count': 2}, {'description': 'x', 'extra': True}):
            self.request('/api/dj/playlist', bad, expected=400)

    def test_hopping_queues_each_station_once_and_announces_the_switch(self):
        import time
        from unittest.mock import Mock
        stations = [dict(id='A', name='Alpha Radio', quickMix=False), dict(id='B', name='Beta Radio', quickMix=False),
                    dict(id='C', name='Gamma Radio', quickMix=False), dict(id='Q', name='Shuffle', quickMix=True)]
        sent = []
        self.session.status = Mock()
        self.session.status.send.side_effect = lambda data: sent.append(json.loads(data))
        dj = self.session.dj
        dj.hop, dj.hop_songs = True, 2
        def play(title, station, **extra):
            self.session.pending = False
            self.session.state = dict(dict(title=title, artist='Artist', album='', station=dict((s['id'], s['name']) for s in stations)[station],
                                           stationId=station, offline=False, output='browser', elapsed=0, stations=stations), **extra)
            dj.on_state(self.session.state)
        play('One', 'A')
        self.assertEqual(sent, [])
        play('Two', 'A')
        first = sent[-1]
        self.assertEqual(first['type'], 'queue_station')
        self.assertIn(first['id'], ('B', 'C'))
        # Repeated state updates for the same song neither recount nor requeue.
        self.session.pending = False
        for _ in range(3):
            dj.on_state(dict(self.session.state, nextStationId=first['id']))
        self.assertEqual(len(sent), 1)
        self.assertEqual(dj.station_public()['hopNext'], first['id'])
        play('Three', first['id'])
        self.assertEqual((dj.hop_count, dj.hop_arrival), (1, first['id']))
        play('Four', first['id'])
        # Every other station is visited before any repeat, and never QuickMix.
        self.assertEqual(sent[-1]['id'], ({'B', 'C'} - {first['id']}).pop())
        # The player declined that request: the DJ picks again.
        self.session.pending = False
        dj.on_state(dict(self.session.state, nextStationId=None))
        self.assertEqual(len(sent), 3)
        self.assertNotIn('Q', [packet['id'] for packet in sent])
        # A listener's own station change restarts the count without an announcement.
        play('Five', 'Q')
        self.assertEqual((dj.hop_count, dj.hop_arrival), (1, None))
        # Arriving on a hopped station, a talking DJ mentions the switch.
        dj.talk = True
        play('Six', 'Q')
        target = sent[-1]['id']
        play('Seven', target, elapsed=4)
        deadline = time.monotonic() + 3
        while not self.spoken and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertEqual(len(self.spoken), 1)
        prompts = [body['messages'][0]['content'] for path, body, _ in self.calls if path == '/chat']
        self.assertIn('switched to', prompts[-1])
        self.assertTrue(json.loads([body for path, body, _ in self.calls if path == '/chat'][-1]['messages'][1]['content'])['newStation'])
        # Hopping never runs offline.
        dj.on_state(dict(self.session.state, offline=True, title='Eight'))
        self.request('/api/dj/control', {'hop': 'yes'}, expected=400)

    def test_browser_reports_playback_glitches(self):
        client = self.listening_client
        self.request('/api/clients/heartbeat', {'id': client, 'status': 'playing', 'ready': True, 'underruns': 3, 'resyncs': 1})
        page = next(p for p in self.request('/api/clients')['clients'] if p['id'] == client)
        self.assertEqual((page['underruns'], page['resyncs']), (3, 1))
        self.request('/api/clients/heartbeat', {'id': client, 'status': 'playing', 'ready': True, 'underruns': -1}, expected=400)

    def test_next_intro_is_written_and_voiced_before_its_song_starts(self):
        import time
        dj = self.session.dj
        dj.talk = True
        self.session.state.update(elapsed=0, nextTitle='London Calling', nextArtist='The Clash', nextAlbum='London Calling')
        # Nothing is prepared until this song's own intro has gone out.
        dj.on_state(self.session.state)
        time.sleep(.1)
        self.assertEqual(self.calls, [])
        dj.auto_song, dj.auto_done = dj.song_key(self.session.state), True
        dj.on_state(self.session.state)
        upcoming = dict(title='London Calling', artist='The Clash', album='London Calling')
        key = dj.song_key(upcoming)
        deadline = time.monotonic() + 3
        while key not in dj.prepared or dj.prep_busy:
            self.assertLess(time.monotonic(), deadline)
            time.sleep(.01)
        chats = [body for path, body, _ in self.calls if path == '/chat']
        self.assertEqual(len(chats), 1)
        context = json.loads(chats[0]['messages'][1]['content'])
        self.assertEqual((context['title'], context['previous']['title']), ('London Calling', 'Dat New New'))
        self.assertIn('segue', chats[0]['messages'][0]['content'])
        # The voice is rendered ahead too, not just the words.
        self.assertEqual(sum(path == '/speech' for path, _, _ in self.calls), 1)
        # Repeated state updates don't prepare it again.
        for _ in range(3):
            dj.on_state(self.session.state)
        self.assertEqual(sum(path == '/chat' for path, _, _ in self.calls), 1)
        # The moment the song starts, its intro goes on air with no new LLM call.
        self.session.state.update(upcoming, elapsed=0, nextTitle='', nextArtist='', nextAlbum='')
        dj.on_state(self.session.state)
        self.assertEqual(self.spoken, [dict(text='Coming at ya with Kid Cudi and Dat New New.', songKey=key)])
        self.assertEqual(sum(path == '/chat' for path, _, _ in self.calls), 1)
        self.assertNotIn(key, dj.prepared)

    def test_station_intros_stay_silent_unless_the_dj_talks(self):
        from unittest.mock import Mock
        first, second = 'a' * 64 + '.mka', 'b' * 64 + '.mka'
        self.session.state.update(savedId=first, queuedSavedId='', elapsed=0)
        self.session.library = lambda: [dict(id=first, artist='Kid Cudi', title='Dat New New', album=''),
                                       dict(id=second, artist='The Clash', title='London Calling', album='London Calling')]
        self.session.status = Mock()
        self.session.dj.on_state(self.session.state)
        result = self.request('/api/dj/pick', {'theme': 'London punk'})
        self.session.state.update(savedId=second, title=result['title'], artist=result['artist'], album=result['album'])
        self.session.dj.on_state(self.session.state)
        self.assertEqual(self.spoken, [])

    def test_invalid_cached_selection_cannot_play_arbitrary_path(self):
        from unittest.mock import Mock
        self.session.library = lambda: [dict(id='a' * 64 + '.mka', title='Cached', artist='Test', album='')]
        self.session.status = Mock()
        content = json.dumps(dict(id='/etc/passwd', text='Bad selection'))
        with patch.object(self.session.dj, 'post', return_value=(json.dumps({'message': {'content': content}}).encode(), 'application/json')):
            self.request('/api/dj/pick', {'play': True}, expected=503)
        self.session.status.send.assert_not_called()

    def test_local_voice_backend(self):
        import subprocess
        self.session.dj.tts_url = ''
        self.session.dj.local_voice = '/fake/espeak-ng'
        self.session.dj.voice = 'en-us'
        with patch.object(host['subprocess'], 'run', return_value=subprocess.CompletedProcess([], 0, self.wav)) as run:
            self.assertEqual(self.request('/api/voice', {'text': 'London punk, coming up!'}), self.wav)
            self.assertEqual(run.call_args.args[0], ['/fake/espeak-ng', '--stdin', '--stdout', '-v', 'en-us'])
            self.assertEqual(run.call_args.kwargs['input'], b'London punk, coming up!')
        self.assertEqual(self.calls, [])

    def test_busy_and_invalid_provider_responses(self):
        with self.session.dj.generation_slot:
            self.request('/api/dj', {}, expected=503)
        with patch.object(self.session.dj, 'post', return_value=(b'<html>broken</html>', 'text/html')):
            self.request('/api/dj', {}, expected=503)
            self.request('/api/voice', {'text': 'Hello'}, expected=503)
        self.assertEqual(self.calls, [])


if __name__ == '__main__':
    unittest.main()
