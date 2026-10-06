#!/usr/bin/env python3
"""Metadata provider, background cache and artwork serving regression checks."""
import copy
from pathlib import Path
import runpy
import tempfile
import time
import unittest
from unittest.mock import Mock
from urllib.error import HTTPError

host = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'pianobar-web'), run_name='test_host')
Session, Metadata = host['Session'], host['Metadata']
RECORDING = '11111111-1111-1111-1111-111111111111'
ARTIST = '22222222-2222-2222-2222-222222222222'
RELEASE = '33333333-3333-3333-3333-333333333333'
PNG = b'\x89PNG\r\n\x1a\n' + b'fixture'


class MetadataTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.song = dict(title='Dat New New', artist='Kid Cudi', album='Test album',
                         cacheDir=self.temp.name, offline=False)
        self.session = Session()
        self.session.state = self.song
        self.metadata = self.session.metadata
        self.recording = dict(id=RECORDING, title='Dat New “New”', score=100,
            **{'artist-credit': [dict(artist=dict(id=ARTIST, name='Kid Cudi'))]},
            releases=[dict(id=RELEASE, title='Test album', date='2008-09-01')])
        self.details = dict(self.recording, genres=[dict(name='hip hop', count=5)])
        self.metadata.mb = Mock(side_effect=self.mb)
        self.metadata.request = Mock(return_value=PNG)

    def mb(self, path, **params):
        if path == 'recording':
            return {'recordings': [copy.deepcopy(self.recording)]}
        if path.startswith('artist/'):
            return {'genres': [dict(name='rap', count=4)]}
        return copy.deepcopy(self.details)

    def wait(self, song=None):
        song = song or self.song
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            result = self.metadata.current(song)
            if result:
                return result
            time.sleep(.01)
        self.fail('Background metadata did not finish')

    def test_matched_artwork_served_and_preserves_native_art(self):
        native = Path(self.temp.name) / 'artwork' / (Metadata.art_key(self.song) + '.img')
        native.parent.mkdir()
        native.write_bytes(b'broken existing artwork')
        result = self.metadata.lookup(self.song)
        self.assertEqual(result['genres'], ['hip hop'])
        self.assertEqual(result['releaseDate'], '2008-09-01')
        self.assertEqual(native.read_bytes(), b'broken existing artwork')
        self.assertEqual(self.session.artwork(result['cover'].rsplit('/', 1)[-1]), (PNG, 'image/png'))

    def test_native_art_key_compatible(self):
        import hashlib
        expected = hashlib.sha256(b'Kid Cudi\0Test album\0\0').hexdigest()
        self.assertEqual(Metadata.art_key(self.song), expected)
        self.song['album'] = ''
        self.assertEqual(Metadata.art_key(self.song), hashlib.sha256(b'Kid Cudi\0\0Dat New New\0').hexdigest())

    def test_wrong_artist_and_versions_rejected(self):
        self.recording['artist-credit'][0]['artist']['name'] = 'Someone else'
        self.assertEqual(self.metadata.lookup(self.song)['status'], 'not-found')
        self.recording['artist-credit'][0]['artist']['name'] = 'Kid Cudi'
        self.recording['title'] = 'Dat New New (Live)'
        self.assertEqual(self.metadata.lookup(self.song)['status'], 'not-found')
        self.metadata.request.assert_not_called()

    def test_clean_explicit_labels_match_without_accepting_live_versions(self):
        self.recording['title'] = 'Dat New “New” (dirty)'
        self.assertEqual(self.metadata.lookup(self.song)['status'], 'matched')
        self.recording['title'] = 'Dat New “New” (instrumental)'
        self.assertEqual(self.metadata.lookup(self.song)['status'], 'not-found')

    def test_wrong_album_not_used_for_artwork(self):
        self.details['releases'][0]['title'] = 'Unrelated compilation'
        result = self.metadata.lookup(self.song)
        self.assertNotIn('cover', result)
        self.metadata.request.assert_not_called()

    def test_artist_genres_labeled_and_missing_cover_handled(self):
        self.details['genres'] = []
        self.metadata.request.side_effect = HTTPError('https://coverartarchive.org', 404, '', {}, None)
        result = self.metadata.lookup(self.song)
        self.assertEqual(result['genres'], ['rap'])
        self.assertEqual(result['genreScope'], 'artist')
        self.assertEqual(result['artStatus'], 'not-found')

    def test_background_cache_reused_offline_and_reconnect_fetches(self):
        self.metadata.schedule(self.song)
        result = self.wait()
        self.assertEqual(result['status'], 'matched')
        self.metadata.mb.reset_mock()
        self.metadata.schedule(self.song)
        self.metadata.mb.assert_not_called()
        offline = dict(self.song, offline=True)
        self.metadata.schedule(offline)
        self.assertEqual(self.wait(offline)['cover'], result['cover'])
        self.metadata.mb.assert_not_called()
        # Another offline-only song must not suppress a lookup after reconnect.
        self.metadata.network = False
        unknown = dict(self.song, title='New song', offline=True)
        self.metadata.schedule(unknown)
        self.assertEqual(self.wait(unknown)['status'], 'offline')
        self.metadata.network = True
        self.metadata.schedule(dict(unknown, offline=False))
        self.assertEqual(self.wait(dict(unknown, offline=False))['status'], 'not-found')
        self.metadata.mb.assert_called()

    def test_provider_error_does_not_block_playback_or_spin(self):
        self.metadata.mb.side_effect = HTTPError('https://musicbrainz.org', 503, '', {}, None)
        self.metadata.schedule(self.song)
        self.assertEqual(self.wait()['status'], 'unavailable')
        self.assertEqual(self.session.state['title'], 'Dat New New')
        self.metadata.schedule(self.song)
        self.assertEqual(self.metadata.mb.call_count, 1)
        self.assertGreater(self.session.revision, 0)

    def test_cached_metadata_survives_restart(self):
        self.metadata.schedule(self.song)
        result = self.wait()
        second = Session()
        second.state = dict(self.song, offline=True)
        second.metadata.network = False
        second.metadata.mb = Mock(side_effect=AssertionError('Offline network request'))
        second.metadata.schedule(second.state)
        deadline = time.monotonic() + 3
        while not second.metadata.current(second.state) and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertEqual(second.metadata.current(second.state)['cover'], result['cover'])
        second.metadata.mb.assert_not_called()


if __name__ == '__main__':
    unittest.main()
