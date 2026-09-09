#include "config.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "main.h"
#include "cache.h"

sig_atomic_t *interrupted;

/* Exercise the production decoder/cache with a FIFO in place of audio hardware. */
int main (int argc, char **argv) {
	assert (argc == 6);
	BarSettings_t settings;
	BarSettingsInit (&settings);
	BarSettingsRead (&settings);
	free (settings.cacheDir);
	settings.cacheDir = strdup (argv[2]);
	free (settings.audioPipe);
	settings.audioPipe = strdup (argv[3]);
	settings.timeout = 1;
	settings.bufferSecs = 1;
	gcry_check_version (NULL);
	curl_global_init (CURL_GLOBAL_DEFAULT);
	player_t player = {0};
	BarPlayerInit (&player, &settings);
	PianoSong_t song = {.title = "Title / ../ ", .artist = "Test artist",
		.album = "Test album", .coverArt = getenv ("PIANOBAR_TEST_ARTWORK"), .fileGain = -2.5};
	player.url = argv[1];
	player.song = &song;
	player.local = strcmp (argv[4], "local") == 0;
	player.mode = PLAYER_WAITING;
	if (strcmp (argv[4], "queue") == 0) {
		AVFormatContext *input = NULL;
		assert (avformat_open_input (&input, argv[1], NULL, NULL) == 0);
		assert (avformat_find_stream_info (input, NULL) >= 0);
		int stream = av_find_best_stream (input, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
		assert (stream >= 0);
		/* Simulate quickly loading six distinct songs, then loading each again.
		 * All caller metadata is freed before any download finishes. */
		for (int i = 0; i < 6; ++i) {
			char title[32];
			snprintf (title, sizeof (title), "Queued song %d", i);
			song.title = strdup (title);
			BarCacheQueue (&settings, &song, argv[1], input->streams[stream]);
			BarCacheQueue (&settings, &song, argv[1], input->streams[stream]);
			free (song.title);
		}
		assert (BarCachePending () == 6);
		avformat_close_input (&input);
		BarCacheDownloadsShutdown (false);
		assert (BarCachePending () == 0);
		PianoSong_t *songs = BarCacheLoad (&settings);
		int count = 0;
		for (PianoSong_t *s = songs; s != NULL; s = PianoListNextP (s)) {
			assert (strncmp (s->title, "Queued song ", 12) == 0);
			++count;
		}
		assert (count == 6);
		PianoDestroyPlaylist (songs);
		BarCacheArtworkWait ();
		curl_global_cleanup ();
		BarPlayerDestroy (&player);
		BarSettingsDestroy (&settings);
		return 0;
	}
	pthread_t thread;
	if (strcmp (argv[4], "paused") == 0) { player.doPause = true; }
	assert (pthread_create (&thread, NULL, BarPlayerThread, &player) == 0);
	if (strcmp (argv[4], "cancel") == 0 || strcmp (argv[4], "shutdown") == 0 ||
			strcmp (argv[4], "paused") == 0) {
		/* A loaded song has entered playback and queued its independent save. */
		for (int i = 0; i < 500 && BarPlayerGetMode (&player) == PLAYER_WAITING; ++i) {
			struct timespec poll = {.tv_nsec = 10000000};
			nanosleep (&poll, NULL);
		}
		if (strcmp (argv[4], "paused") == 0) {
			assert (BarPlayerGetMode (&player) == PLAYER_PLAYING);
			for (int i = 0; i < 1000 && BarCachePending () > 0; ++i) {
				struct timespec poll = {.tv_nsec = 10000000};
				nanosleep (&poll, NULL);
			}
			PianoSong_t *saved = BarCacheLoad (&settings);
			assert (saved != NULL);
			PianoDestroyPlaylist (saved);
		}
		struct timespec delay = {.tv_nsec = 100000000};
		nanosleep (&delay, NULL);
		pthread_mutex_lock (&player.lock);
		player.doQuit = true;
		player.doPause = false;
		pthread_cond_broadcast (&player.cond);
		pthread_mutex_unlock (&player.lock);
		pthread_mutex_lock (&player.aoplayLock);
		pthread_cond_broadcast (&player.aoplayCond);
		pthread_mutex_unlock (&player.aoplayLock);
	}
	void *ret;
	assert (pthread_join (thread, &ret) == 0);
	assert ((uintptr_t) ret == (uintptr_t) atoi (argv[5]));
	BarCacheDownloadsShutdown (strcmp (argv[4], "shutdown") == 0);
	BarCacheArtworkWait ();
	curl_global_cleanup ();
	BarPlayerDestroy (&player);
	PianoSong_t *songs = BarCacheLoad (&settings);
	for (PianoSong_t *s = songs; s != NULL; s = PianoListNextP (s)) {
		assert (strcmp (s->title, song.title) == 0);
		assert (strcmp (s->artist, song.artist) == 0);
		assert (strcmp (s->album, song.album) == 0);
		assert (s->fileGain == song.fileGain);
	}
	PianoDestroyPlaylist (songs);
	BarSettingsDestroy (&settings);
	return 0;
}
