#!/usr/bin/env python3
"""Authenticated TTS route shares the existing voice provider without an LLM call."""
import asyncio
import io
from pathlib import Path
import runpy
import unittest
import wave

try:
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
except ImportError:
    print('SKIP: voice adapter tests need fastapi and httpx')
    raise SystemExit(0)

install = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'integrations/voice-tts.py'))['install']


class VoiceTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        calls = self.calls
        class Provider:
            voices = ['am_onyx', 'af_heart']
            default_voice = 'am_onyx'
            async def synthesize(self, text, voice):
                calls.append((text, voice))
                return b'\0\0' * 2400, 24000
        self.app = FastAPI()
        self.app.state.provider = Provider()
        install(self.app, lambda key: key == 'test-voice-key')
        self.client = TestClient(self.app)
        self.headers = {'Authorization': 'Bearer test-voice-key'}

    def test_wav_and_default_voice(self):
        response = self.client.post('/api/voice', json={'text': 'London punk, coming up!'}, headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers['content-type'], 'audio/wav')
        with wave.open(io.BytesIO(response.content), 'rb') as audio:
            self.assertEqual(audio.getframerate(), 24000)
            self.assertEqual(audio.getnframes(), 2400)
        self.assertEqual(self.calls, [('London punk, coming up!', 'am_onyx')])

    def test_authorization_and_input(self):
        self.assertEqual(self.client.post('/api/voice', json={'text': 'Hello'}).status_code, 401)
        for message in ({'text': 'x' * 701}, {'text': ''}, {'text': 'Hello', 'voice': 'bad'}, {'text': 'Hello', 'url': 'bad'}):
            self.assertEqual(self.client.post('/api/voice', json=message, headers=self.headers).status_code, 400)
        self.assertEqual(self.calls, [])

    def test_provider_failure_is_bounded(self):
        async def failed(*args):
            raise RuntimeError('Internal provider failure with private details')
        self.app.state.provider.synthesize = failed
        response = self.client.post('/api/voice', json={'text': 'Hello'}, headers=self.headers)
        self.assertEqual(response.status_code, 503)
        self.assertNotIn('private', response.text)


if __name__ == '__main__':
    unittest.main()
