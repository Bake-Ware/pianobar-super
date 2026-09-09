#include "config.h"
#include <dirent.h>
#include <curl/curl.h>
#include <pthread.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "cache.h"
#include "ui.h"

static const char *nonNull (const char *s) { return s == NULL ? "" : s; }

static char *joinPath (const char *dir, const char *name) {
	const size_t len = strlen (dir) + strlen (name) + 2;
	char *path = malloc (len);
	if (path != NULL) {
		snprintf (path, len, "%s/%s", dir, name);
	}
	return path;
}

static bool makeDirectory (const char *path) {
	char *copy = strdup (path);
	if (copy == NULL) { return false; }
	bool ok = true;
	for (char *p = copy + 1; ; ++p) {
		if (*p == '/' || *p == '\0') {
			const char saved = *p;
			*p = '\0';
			struct stat st;
			if ((mkdir (copy, 0700) != 0 && errno != EEXIST) ||
					stat (copy, &st) != 0 || !S_ISDIR (st.st_mode)) {
				ok = false;
				break;
			}
			*p = saved;
			if (saved == '\0') { break; }
		}
	}
	free (copy);
	return ok;
}

#define ARTWORK_LIMIT (8 * 1024 * 1024)

typedef struct {
	pthread_t thread;
	bool active, done;
	char *path, *url, *proxy, *bindTo, *caBundle;
} ArtworkJob;
static ArtworkJob artworkJobs[4];
static pthread_mutex_t artworkLock = PTHREAD_MUTEX_INITIALIZER;

static void artworkKey (const PianoSong_t *song, char id[65]) {
	gcry_md_hd_t hash;
	id[0] = '\0';
	if (gcry_md_open (&hash, GCRY_MD_SHA256, 0) != 0) { return; }
	const char *fields[] = {song->artist, song->album,
		song->album == NULL || song->album[0] == '\0' ? song->title : ""};
	for (size_t i = 0; i < 3; ++i) {
		gcry_md_write (hash, nonNull (fields[i]), strlen (nonNull (fields[i])) + 1);
	}
	const unsigned char *digest = gcry_md_read (hash, GCRY_MD_SHA256);
	for (size_t i = 0; i < 32; ++i) { sprintf (id + i*2, "%02x", digest[i]); }
	gcry_md_close (hash);
}

static char *artworkPath (const BarSettings_t *settings, const char *id) {
	char name[80];
	snprintf (name, sizeof (name), "artwork/%s.img", id);
	return joinPath (settings->cacheDir, name);
}

bool BarCacheArtworkId (const BarSettings_t *settings, const PianoSong_t *song, char id[65]) {
	if (song == NULL || settings->cacheDir == NULL) { return false; }
	artworkKey (song, id);
	if (id[0] == '\0') { return false; }
	char *path = artworkPath (settings, id);
	struct stat st;
	bool exists = path != NULL && lstat (path, &st) == 0 && S_ISREG (st.st_mode) &&
		st.st_size > 0 && st.st_size <= ARTWORK_LIMIT;
	free (path);
	return exists;
}

typedef struct { FILE *file; size_t size; } ArtworkDownload;
static size_t artworkWrite (char *data, size_t size, size_t count, void *opaque) {
	ArtworkDownload *download = opaque;
	if (size != 0 && count > (ARTWORK_LIMIT - download->size) / size) { return 0; }
	const size_t length = size * count;
	const size_t written = fwrite (data, 1, length, download->file);
	download->size += written;
	return written;
}

static bool artworkImage (FILE *file) {
	unsigned char header[12];
	rewind (file);
	if (fread (header, 1, sizeof (header), file) != sizeof (header)) { return false; }
	return (header[0] == 0xff && header[1] == 0xd8 && header[2] == 0xff) ||
		memcmp (header, "\x89PNG\r\n\x1a\n", 8) == 0 ||
		(memcmp (header, "RIFF", 4) == 0 && memcmp (header + 8, "WEBP", 4) == 0) ||
		memcmp (header, "GIF87a", 6) == 0 || memcmp (header, "GIF89a", 6) == 0;
}

static void *artworkDownload (void *opaque) {
	ArtworkJob *job = opaque;
	char *temporary = malloc (strlen (job->path) + 20);
	if (temporary != NULL) {
		sprintf (temporary, "%s.partial-XXXXXX", job->path);
		int fd = mkstemp (temporary);
		FILE *file = fd >= 0 ? fdopen (fd, "w+b") : NULL;
		if (file == NULL && fd >= 0) { close (fd); }
		CURL *http = file != NULL ? curl_easy_init () : NULL;
		if (http != NULL) {
			ArtworkDownload download = {.file = file};
			curl_easy_setopt (http, CURLOPT_URL, job->url);
			curl_easy_setopt (http, CURLOPT_WRITEFUNCTION, artworkWrite);
			curl_easy_setopt (http, CURLOPT_WRITEDATA, &download);
			curl_easy_setopt (http, CURLOPT_NOSIGNAL, 1L);
			curl_easy_setopt (http, CURLOPT_CONNECTTIMEOUT, 3L);
			curl_easy_setopt (http, CURLOPT_TIMEOUT, 5L);
			curl_easy_setopt (http, CURLOPT_FAILONERROR, 1L);
			curl_easy_setopt (http, CURLOPT_FOLLOWLOCATION, 1L);
			curl_easy_setopt (http, CURLOPT_MAXREDIRS, 3L);
#if LIBCURL_VERSION_NUM >= 0x075500
			curl_easy_setopt (http, CURLOPT_PROTOCOLS_STR, "http,https");
			curl_easy_setopt (http, CURLOPT_REDIR_PROTOCOLS_STR, "http,https");
#else
			curl_easy_setopt (http, CURLOPT_PROTOCOLS, CURLPROTO_HTTP | CURLPROTO_HTTPS);
			curl_easy_setopt (http, CURLOPT_REDIR_PROTOCOLS, CURLPROTO_HTTP | CURLPROTO_HTTPS);
#endif
			if (job->proxy != NULL) { curl_easy_setopt (http, CURLOPT_PROXY, job->proxy); }
			if (job->bindTo != NULL) { curl_easy_setopt (http, CURLOPT_INTERFACE, job->bindTo); }
			if (job->caBundle != NULL) { curl_easy_setopt (http, CURLOPT_CAINFO, job->caBundle); }
			if (curl_easy_perform (http) == CURLE_OK && fflush (file) == 0 && artworkImage (file)) {
				/* Only complete images are published, without replacing another writer. */
				link (temporary, job->path);
			}
			curl_easy_cleanup (http);
		}
		if (file != NULL) { fclose (file); }
		unlink (temporary);
		free (temporary);
	}
	pthread_mutex_lock (&artworkLock);
	job->done = true;
	pthread_mutex_unlock (&artworkLock);
	return NULL;
}

static void artworkRelease (ArtworkJob *job) {
	if (job->active) { pthread_join (job->thread, NULL); }
	free (job->path); free (job->url); free (job->proxy); free (job->bindTo); free (job->caBundle);
	memset (job, 0, sizeof (*job));
}

/* Called by the single decoder thread; jobs own copies of all their inputs. */
static void artworkQueue (const BarSettings_t *settings, const PianoSong_t *song) {
	if (song->coverArt == NULL || (strncmp (song->coverArt, "http://", 7) != 0 &&
			strncmp (song->coverArt, "https://", 8) != 0)) { return; }
	char id[65];
	if (BarCacheArtworkId (settings, song, id) || id[0] == '\0') { return; }
	char *path = artworkPath (settings, id);
	if (path == NULL) { return; }
	ArtworkJob *available = NULL;
	pthread_mutex_lock (&artworkLock);
	for (size_t i = 0; i < 4; ++i) {
		ArtworkJob *job = &artworkJobs[i];
		if (job->active && !job->done && strcmp (job->path, path) == 0) {
			pthread_mutex_unlock (&artworkLock); free (path); return;
		}
		if ((!job->active || job->done) && available == NULL) { available = job; }
	}
	pthread_mutex_unlock (&artworkLock);
	if (available == NULL) { free (path); return; }
	char *directory = joinPath (settings->cacheDir, "artwork");
	bool ready = directory != NULL && makeDirectory (directory);
	free (directory);
	if (!ready) { free (path); return; }
	artworkRelease (available);
	available->path = path;
	available->url = strdup (song->coverArt);
	available->proxy = settings->proxy == NULL ? NULL : strdup (settings->proxy);
	available->bindTo = settings->bindTo == NULL ? NULL : strdup (settings->bindTo);
	available->caBundle = settings->caBundle == NULL ? NULL : strdup (settings->caBundle);
	if (available->url == NULL || pthread_create (&available->thread, NULL, artworkDownload, available) != 0) {
		artworkRelease (available); return;
	}
	available->active = true;
}

/* Join only after playback has stopped, before global curl cleanup. */
void BarCacheArtworkWait (void) {
	for (size_t i = 0; i < 4; ++i) { artworkRelease (&artworkJobs[i]); }
}

static char *songPath (const BarSettings_t *settings, const PianoSong_t *song, AVStream *stream) {
	/* Hash metadata and encoding, never expiring URLs or account tokens. */
	gcry_md_hd_t hash;
	if (gcry_md_open (&hash, GCRY_MD_SHA256, 0) != 0) { return NULL; }
	const char *fields[] = {song->artist, song->album, song->title};
	for (size_t i = 0; i < sizeof (fields) / sizeof (*fields); ++i) {
		gcry_md_write (hash, nonNull (fields[i]), strlen (nonNull (fields[i])) + 1);
	}
	char encoding[64];
	snprintf (encoding, sizeof (encoding), "%d:%d", stream->codecpar->codec_id,
			settings->audioQuality);
	gcry_md_write (hash, encoding, strlen (encoding));
	const unsigned char *digest = gcry_md_read (hash, GCRY_MD_SHA256);
	char name[69];
	for (size_t i = 0; i < 32; ++i) { sprintf (name + i*2, "%02x", digest[i]); }
	strcpy (name + 64, ".mka");
	gcry_md_close (hash);
	return joinPath (settings->cacheDir, name);
}

void BarCacheOpen (BarCache_t *cache, const BarSettings_t *settings,
		const PianoSong_t *song, AVStream *stream) {
	memset (cache, 0, sizeof (*cache));
	cache->firstTimestamp = AV_NOPTS_VALUE;
	cache->settings = settings;
	if (!settings->cacheSongs || settings->cacheDir == NULL ||
			settings->cacheDir[0] == '\0' || song == NULL) { return; }

	cache->path = songPath (settings, song, stream);
	if (cache->path == NULL) { return; }
	if (access (cache->path, R_OK) == 0) {
		BarCacheClose (cache, false);
		return;
	}
	if (!makeDirectory (settings->cacheDir)) { goto error; }
	cache->temporary = joinPath (settings->cacheDir, ".partial-XXXXXX");
	if (cache->temporary == NULL) { goto error; }
	int fd = mkstemp (cache->temporary);
	if (fd < 0) { goto error; }
	close (fd);
	if (avformat_alloc_output_context2 (&cache->ctx, NULL, "matroska",
			cache->temporary) < 0 || cache->ctx == NULL) { goto error; }
	AVStream *out = avformat_new_stream (cache->ctx, NULL);
	if (out == NULL || avcodec_parameters_copy (out->codecpar, stream->codecpar) < 0) {
		goto error;
	}
	out->codecpar->codec_tag = 0;
	out->time_base = stream->time_base;
	cache->timeBase = stream->time_base;
	cache->duration = stream->duration > 0 ?
			stream->duration * av_q2d (stream->time_base) : song->length;
	av_dict_set (&cache->ctx->metadata, "title", nonNull (song->title), 0);
	av_dict_set (&cache->ctx->metadata, "artist", nonNull (song->artist), 0);
	av_dict_set (&cache->ctx->metadata, "album", nonNull (song->album), 0);
	char gain[64];
	snprintf (gain, sizeof (gain), "%f", song->fileGain);
	av_dict_set (&cache->ctx->metadata, "pianobar_gain", gain, 0);
	if (avio_open (&cache->ctx->pb, cache->temporary, AVIO_FLAG_WRITE) < 0 ||
			avformat_write_header (cache->ctx, NULL) < 0) { goto error; }
	return;
error:
	BarUiMsg (settings, MSG_ERR, "Cannot save song in %s; continuing playback.\n",
			settings->cacheDir);
	BarCacheClose (cache, false);
}

void BarCacheWrite (BarCache_t *cache, const AVPacket *packet) {
	if (cache->ctx == NULL || cache->failed) { return; }
	AVPacket *copy = av_packet_clone (packet);
	if (copy == NULL) { cache->failed = true; return; }
	const int64_t timestamp = packet->pts == AV_NOPTS_VALUE ? packet->dts : packet->pts;
	if (timestamp != AV_NOPTS_VALUE) {
		if (cache->firstTimestamp == AV_NOPTS_VALUE) { cache->firstTimestamp = timestamp; }
		cache->endTimestamp = timestamp + packet->duration;
	}
	av_packet_rescale_ts (copy, cache->timeBase, cache->ctx->streams[0]->time_base);
	copy->stream_index = 0;
	copy->pos = -1;
	if (av_interleaved_write_frame (cache->ctx, copy) < 0) {
		cache->failed = true;
		BarUiMsg (cache->settings, MSG_ERR, "Cannot write saved song; continuing playback.\n");
	}
	av_packet_free (&copy);
}

void BarCacheClose (BarCache_t *cache, bool complete) {
	if (cache->ctx != NULL) {
		/* Some demuxers report EOF on truncated streams. Require the advertised
		 * duration as well as a clean EOF, allowing codec padding/rounding. */
		const double received = cache->firstTimestamp == AV_NOPTS_VALUE ? 0 :
				((double) cache->endTimestamp - cache->firstTimestamp) * av_q2d (cache->timeBase);
		complete = complete && !cache->failed && cache->firstTimestamp != AV_NOPTS_VALUE &&
				(cache->duration <= 0 || received + 1.0 >= cache->duration);
		if (complete && av_write_trailer (cache->ctx) < 0) { complete = false; }
		if (cache->ctx->pb != NULL) {
			avio_flush (cache->ctx->pb);
			if (cache->ctx->pb->error < 0) { complete = false; }
			if (avio_closep (&cache->ctx->pb) < 0) { complete = false; }
		}
		avformat_free_context (cache->ctx);
	} else { complete = false; }
	if (complete) {
		/* link publishes without overwriting a completed concurrent download. */
		if (link (cache->temporary, cache->path) != 0 && errno != EEXIST) {
			fprintf (stderr, "Cannot publish cached song: %s\n", strerror (errno));
		}
	}
	if (cache->temporary != NULL) { unlink (cache->temporary); }
	free (cache->temporary);
	free (cache->path);
	memset (cache, 0, sizeof (*cache));
}

/* The queue owns its metadata and settings: skipped songs may already be freed.
 * Two workers bound bandwidth/connections; waiting jobs hold no audio in RAM. */
#define DOWNLOAD_WORKERS 2
#define DOWNLOAD_LIMIT 64

typedef struct SongDownload {
	struct SongDownload *next;
	bool active;
	char *path, *url;
	PianoSong_t song;
	BarSettings_t settings;
} SongDownload;
static SongDownload *downloads;
static pthread_t downloadThreads[DOWNLOAD_WORKERS];
static unsigned int downloadThreadsCount;
static bool downloadStopping;
static pthread_mutex_t downloadLock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t downloadCond = PTHREAD_COND_INITIALIZER;

unsigned int BarCachePending (void) {
	unsigned int count = 0;
	pthread_mutex_lock (&downloadLock);
	for (SongDownload *job = downloads; job != NULL; job = job->next) { ++count; }
	pthread_mutex_unlock (&downloadLock);
	return count;
}

static int downloadInterrupt (void *opaque) {
	(void) opaque;
	pthread_mutex_lock (&downloadLock);
	const bool stop = downloadStopping;
	pthread_mutex_unlock (&downloadLock);
	return stop;
}

static void downloadFree (SongDownload *job) {
	free (job->path); free (job->url);
	free (job->song.title); free (job->song.artist); free (job->song.album);
	free (job->settings.cacheDir); free (job->settings.proxy); free (job->settings.caBundle);
	free (job);
}

static void downloadSong (SongDownload *job) {
	AVFormatContext *input = avformat_alloc_context ();
	if (input == NULL) { return; }
	input->interrupt_callback.callback = downloadInterrupt;
	AVDictionary *options = NULL;
	char timeout[32];
	snprintf (timeout, sizeof (timeout), "%llu", (unsigned long long) job->settings.timeout * 1000000);
	av_dict_set (&options, "rw_timeout", timeout, 0);
	av_dict_set (&options, "protocol_whitelist", "http,https,tcp,tls,crypto", 0);
	if (job->settings.proxy != NULL) { av_dict_set (&options, "http_proxy", job->settings.proxy, 0); }
	if (job->settings.caBundle != NULL) { av_dict_set (&options, "ca_file", job->settings.caBundle, 0); }
	int ret = avformat_open_input (&input, job->url, NULL, &options);
	av_dict_free (&options);
	BarCache_t cache = {0};
	AVPacket *packet = NULL;
	bool complete = false;
	if (ret < 0 || avformat_find_stream_info (input, NULL) < 0) { goto done; }
	const int stream = av_find_best_stream (input, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
	if (stream < 0) { goto done; }
	BarCacheOpen (&cache, &job->settings, &job->song, input->streams[stream]);
	if (cache.ctx == NULL) { goto done; }
	packet = av_packet_alloc ();
	if (packet == NULL) { goto done; }
	while (!downloadInterrupt (NULL) && (ret = av_read_frame (input, packet)) >= 0) {
		if (packet->stream_index == stream) { BarCacheWrite (&cache, packet); }
		av_packet_unref (packet);
		if (cache.failed) { break; }
	}
	complete = ret == AVERROR_EOF && !downloadInterrupt (NULL) &&
			(input->pb == NULL || input->pb->error >= 0);
 done:
	BarCacheClose (&cache, complete);
	av_packet_free (&packet);
	avformat_close_input (&input);
	if (!downloadInterrupt (NULL)) {
		const bool saved = access (job->path, R_OK) == 0;
		BarUiMsg (&job->settings, saved ? MSG_INFO : MSG_ERR,
				saved ? "Saved for offline: %s\n" : "Could not save for offline: %s\n",
				job->song.title);
	}
}

static void *downloadWorker (void *unused) {
	(void) unused;
	pthread_mutex_lock (&downloadLock);
	while (!downloadStopping) {
		SongDownload *job = downloads;
		while (job != NULL && job->active) { job = job->next; }
		if (job == NULL) { pthread_cond_wait (&downloadCond, &downloadLock); continue; }
		job->active = true;
		pthread_mutex_unlock (&downloadLock);
		downloadSong (job);
		pthread_mutex_lock (&downloadLock);
		SongDownload **position = &downloads;
		while (*position != job) { position = &(*position)->next; }
		*position = job->next;
		downloadFree (job);
		pthread_cond_broadcast (&downloadCond);
	}
	pthread_mutex_unlock (&downloadLock);
	return NULL;
}

/* Called by the single playback decoder as soon as a remote stream is loaded. */
void BarCacheQueue (const BarSettings_t *settings, const PianoSong_t *song,
		const char *url, AVStream *stream) {
	if (!settings->cacheSongs || settings->cacheDir == NULL || settings->cacheDir[0] == '\0' ||
			song == NULL || url == NULL || (strncmp (url, "http://", 7) != 0 &&
			strncmp (url, "https://", 8) != 0)) { return; }
	artworkQueue (settings, song);
	char *path = songPath (settings, song, stream);
	if (path == NULL) { return; }
	if (access (path, R_OK) == 0) { free (path); return; }
	pthread_mutex_lock (&downloadLock);
	unsigned int count = 0;
	SongDownload **tail = &downloads;
	for (; *tail != NULL; tail = &(*tail)->next) {
		if (strcmp ((*tail)->path, path) == 0) { goto skip; }
		++count;
	}
	if (downloadStopping) { goto skip; }
	if (count >= DOWNLOAD_LIMIT) {
		BarUiMsg (settings, MSG_ERR, "Save queue is full; wait for downloads before skipping more songs.\n");
		goto skip;
	}
	while (downloadThreadsCount < DOWNLOAD_WORKERS) {
		if (pthread_create (&downloadThreads[downloadThreadsCount], NULL, downloadWorker, NULL) != 0) { break; }
		++downloadThreadsCount;
	}
	if (downloadThreadsCount == 0) { goto skip; }
	SongDownload *job = calloc (1, sizeof (*job));
	if (job == NULL) { goto skip; }
	job->path = path;
	job->url = strdup (url);
	job->song.title = strdup (nonNull (song->title));
	job->song.artist = strdup (nonNull (song->artist));
	job->song.album = strdup (nonNull (song->album));
	job->song.length = song->length;
	job->song.fileGain = song->fileGain;
	job->settings.cacheSongs = true;
	job->settings.cacheDir = strdup (settings->cacheDir);
	job->settings.audioQuality = settings->audioQuality;
	job->settings.timeout = settings->timeout;
	job->settings.proxy = settings->proxy == NULL ? NULL : strdup (settings->proxy);
	job->settings.caBundle = settings->caBundle == NULL ? NULL : strdup (settings->caBundle);
	if (job->url == NULL || job->song.title == NULL || job->song.artist == NULL ||
			job->song.album == NULL || job->settings.cacheDir == NULL ||
			(settings->proxy != NULL && job->settings.proxy == NULL) ||
			(settings->caBundle != NULL && job->settings.caBundle == NULL)) {
		downloadFree (job);
		pthread_mutex_unlock (&downloadLock);
		return;
	}
	*tail = job;
	pthread_cond_broadcast (&downloadCond);
	pthread_mutex_unlock (&downloadLock);
	return;
 skip:
	pthread_mutex_unlock (&downloadLock);
	free (path);
}

/* After the playback thread is joined. Tests may drain; app exit cancels promptly. */
void BarCacheDownloadsShutdown (bool cancel) {
	pthread_mutex_lock (&downloadLock);
	while (!cancel && downloads != NULL) { pthread_cond_wait (&downloadCond, &downloadLock); }
	downloadStopping = true;
	pthread_cond_broadcast (&downloadCond);
	pthread_mutex_unlock (&downloadLock);
	for (unsigned int i = 0; i < downloadThreadsCount; ++i) { pthread_join (downloadThreads[i], NULL); }
	pthread_mutex_lock (&downloadLock);
	while (downloads != NULL) {
		SongDownload *job = downloads;
		downloads = job->next;
		downloadFree (job);
	}
	downloadThreadsCount = 0;
	downloadStopping = false;
	pthread_mutex_unlock (&downloadLock);
}

static char *metadata (AVFormatContext *ctx, const char *key) {
	AVDictionaryEntry *entry = av_dict_get (ctx->metadata, key, NULL, 0);
	return strdup (entry == NULL ? "" : entry->value);
}

PianoSong_t *BarCacheLoadOne (const BarSettings_t *settings, const char *name) {
	if (settings->cacheDir == NULL || name == NULL || strlen (name) != 68 ||
			strspn (name, "0123456789abcdef") != 64 || strcmp (name + 64, ".mka") != 0) {
		return NULL;
	}
	char *path = joinPath (settings->cacheDir, name);
	struct stat st;
	if (path == NULL) { return NULL; }
	if (lstat (path, &st) != 0 || !S_ISREG (st.st_mode) || st.st_size == 0) {
		free (path); return NULL;
	}
	AVFormatContext *ctx = NULL;
	AVDictionary *options = NULL;
	av_dict_set (&options, "protocol_whitelist", "file", 0);
	int ret = avformat_open_input (&ctx, path, av_find_input_format ("matroska"), &options);
	av_dict_free (&options);
	if (ret < 0 || avformat_find_stream_info (ctx, NULL) < 0 ||
			av_find_best_stream (ctx, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0) < 0) {
		avformat_close_input (&ctx); free (path); return NULL;
	}
	PianoSong_t *song = calloc (1, sizeof (*song));
	if (song != NULL) {
		song->audioUrl = path;
		song->title = metadata (ctx, "title");
		song->artist = metadata (ctx, "artist");
		song->album = metadata (ctx, "album");
		song->stationId = strdup ("offline");
		char *gain = metadata (ctx, "pianobar_gain");
		song->fileGain = gain == NULL ? 0 : strtof (gain, NULL);
		if (!isfinite (song->fileGain)) { song->fileGain = 0; }
		free (gain);
		song->length = ctx->duration > 0 ? ctx->duration / AV_TIME_BASE : 0;
	} else { free (path); }
	avformat_close_input (&ctx);
	return song;
}

PianoSong_t *BarCacheLoad (const BarSettings_t *settings) {
	if (settings->cacheDir == NULL) { return NULL; }
	DIR *dir = opendir (settings->cacheDir);
	if (dir == NULL) { return NULL; }
	PianoSong_t **songs = NULL;
	size_t count = 0;
	struct dirent *entry;
	while ((entry = readdir (dir)) != NULL) {
		PianoSong_t *song = BarCacheLoadOne (settings, entry->d_name);
		if (song == NULL) { continue; }
		PianoSong_t **grown = realloc (songs, (count + 1) * sizeof (*songs));
		if (grown == NULL) { PianoDestroyPlaylist (song); break; }
		songs = grown;
		songs[count++] = song;
	}
	closedir (dir);
	/* Shuffle each pass through the library. */
	for (size_t i = count; i > 1; --i) {
		const size_t j = (size_t) rand () % i;
		PianoSong_t *tmp = songs[i-1]; songs[i-1] = songs[j]; songs[j] = tmp;
	}
	for (size_t i = 1; i < count; ++i) { songs[i-1]->head.next = &songs[i]->head; }
	PianoSong_t *result = count > 0 ? songs[0] : NULL;
	free (songs);
	return result;
}
