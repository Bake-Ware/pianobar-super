#include "config.h"
#include <fcntl.h>
#include <json.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include "ui.h"
#include "web.h"
#include "ui_dispatch.h"

static int statusFd = -1;
/* Borrowed only while the main thread waits for the deletion answer. */
static const char *deleteStationName = NULL;

void BarWebInit (void) {
	const char *value = getenv ("PIANOBAR_STATUS_FD");
	if (value == NULL) { return; }
	char *end;
	long fd = strtol (value, &end, 10);
	if (*end != '\0' || fd < 3 || fd > 1024) { return; }
	statusFd = (int) fd;
	fcntl (statusFd, F_SETFL, fcntl (statusFd, F_GETFL) | O_NONBLOCK);
	fcntl (statusFd, F_SETFD, FD_CLOEXEC);
}

static void publish (json_object *object) {
	const char *data = json_object_to_json_string_ext (object, JSON_C_TO_STRING_PLAIN);
	/* The wrapper provides a datagram socket; a slow browser cannot block audio. */
	write (statusFd, data, strlen (data));
	json_object_put (object);
}

static void string (json_object *object, const char *name, const char *value) {
	json_object_object_add (object, name, json_object_new_string (value == NULL ? "" : value));
}

static void artwork (json_object *object, const char *field,
		const BarSettings_t *settings, const PianoSong_t *song) {
	char id[65], url[80] = "";
	if (BarCacheArtworkId (settings, song, id)) { snprintf (url, sizeof (url), "/api/artwork/%s", id); }
	string (object, field, url);
}

bool BarWebOpen (void) {
	if (statusFd < 0) { return false; }
	json_object *object = json_object_new_object ();
	string (object, "type", "open_browser");
	publish (object);
	return true;
}

void BarWebHandled (void) {
	if (statusFd < 0) { return; }
	json_object *object = json_object_new_object ();
	string (object, "type", "handled");
	publish (object);
}

void BarWebDeleteConfirmation (const char *stationName) {
	deleteStationName = stationName;
}

void BarWebPrompt (bool active, bool secret, bool line, size_t limit, const char *mask) {
	if (statusFd < 0) { return; }
	json_object *object = json_object_new_object ();
	string (object, "type", "prompt");
	string (object, "mask", mask);
	if (deleteStationName != NULL) {
		string (object, "kind", "delete_station");
		string (object, "station", deleteStationName);
	}
	json_object_object_add (object, "active", json_object_new_boolean (active));
	json_object_object_add (object, "secret", json_object_new_boolean (secret));
	json_object_object_add (object, "line", json_object_new_boolean (line));
	json_object_object_add (object, "limit", json_object_new_int64 (limit));
	publish (object);
}

/* Commands identify a saved file or an existing station, never a remote URL. */
void BarWebPoll (BarApp_t *app) {
	if (statusFd < 0) { return; }
	char buffer[1024];
	ssize_t len = recv (statusFd, buffer, sizeof (buffer) - 1, MSG_DONTWAIT | MSG_TRUNC);
	if (len >= (ssize_t) sizeof (buffer)) { BarWebHandled (); return; }
	if (len <= 0) { return; }
	buffer[len] = '\0';
	bool accepted = false, ack = true;
	json_object *message = json_tokener_parse (buffer), *type, *id;
	if (message != NULL && json_object_object_get_ex (message, "type", &type) &&
			json_object_is_type (type, json_type_string) &&
			json_object_object_get_ex (message, "id", &id) &&
			json_object_is_type (id, json_type_string) &&
			(size_t) json_object_get_string_len (id) == strlen (json_object_get_string (id))) {
		if (strcmp (json_object_get_string (type), "output") == 0 && app->player.audioFd >= 0) {
			const char *output = json_object_get_string (id);
			unsigned int mode = strcmp (output, "host") == 0 ? 1 : strcmp (output, "browser") == 0 ? 2 : strcmp (output, "both") == 0 ? 3 : 0;
			if (mode != 0) {
				pthread_mutex_lock (&app->player.lock);
				app->player.outputMode = mode;
				pthread_mutex_unlock (&app->player.lock);
				BarWebState (app);
			}
		} else if (strcmp (json_object_get_string (type), "dj_voice") == 0) {
			/* The web server owns the clip file; nothing awaits a reply. */
			json_object *voice, *overMusic;
			ack = false;
			if (json_object_object_get_ex (message, "voice", &voice) &&
					json_object_is_type (voice, json_type_int) &&
					json_object_object_get_ex (message, "overMusic", &overMusic) &&
					json_object_is_type (overMusic, json_type_boolean)) {
				BarPlayerLoadVoice (&app->player, json_object_get_string (id),
						json_object_get_int (voice), json_object_get_boolean (overMusic));
				BarWebState (app);
			}
		} else if (strcmp (json_object_get_string (type), "dj_voice_stop") == 0) {
			ack = false;
			BarPlayerStopVoice (&app->player);
			BarWebState (app);
		} else if (strcmp (json_object_get_string (type), "queue_station") == 0) {
			/* Switch after the current song instead of skipping it. */
			PianoStation_t *station = PianoFindStationById (app->ph.stations,
					json_object_get_string (id));
			if (!app->offline && !app->doQuit && app->modeRequest == 0 && station != NULL) {
				app->nextStation = station;
				if (app->playlist != NULL) {
					PianoDestroyPlaylist (PianoListNextP (app->playlist));
					app->playlist->head.next = NULL;
				}
				BarWebState (app);
			} else {
				BarUiMsg (&app->settings, MSG_ERR, "That station is unavailable.\n");
			}
		} else if (strcmp (json_object_get_string (type), "select_station") == 0) {
			PianoStation_t *station = PianoFindStationById (app->ph.stations,
					json_object_get_string (id));
			if (!app->offline && !app->doQuit && app->modeRequest == 0 &&
					app->settings.keys[BAR_KS_SELECTSTATION] != BAR_KS_DISABLED && station != NULL) {
				BarUiSwitchStation (app, station);
				BarWebState (app);
			} else {
				BarUiMsg (&app->settings, MSG_ERR, "That station is unavailable.\n");
			}
		} else if (strcmp (json_object_get_string (type), "queue_saved") == 0) {
			json_object *current;
			const char *name = app->playlist == NULL ? NULL : strrchr (app->playlist->audioUrl, '/');
			const bool matches = json_object_object_get_ex (message, "current", &current) &&
				json_object_is_type (current, json_type_string) && name != NULL &&
				strcmp (name + 1, json_object_get_string (current)) == 0;
			if (app->offline && app->playlist != NULL && app->modeRequest == 0 && matches) {
				const char *savedId = json_object_get_string (id);
				json_object *ids = NULL;
				const bool multiple = json_object_object_get_ex (message, "ids", &ids);
				const size_t count = multiple && json_object_is_type (ids, json_type_array) ? json_object_array_length (ids) : 0;
				PianoSong_t *songs = NULL, *tail = NULL;
				bool valid = !multiple || (count > 0 && count <= 30);
				for (size_t i = 0; valid && i < (multiple ? count : (savedId[0] == '\0' ? 0 : 1)); ++i) {
					json_object *item = multiple ? json_object_array_get_idx (ids, i) : id;
					if (!json_object_is_type (item, json_type_string) ||
							(size_t) json_object_get_string_len (item) != strlen (json_object_get_string (item))) { valid = false; break; }
					PianoSong_t *song = BarCacheLoadOne (&app->settings, json_object_get_string (item));
					if (song == NULL) { valid = false; break; }
					if (tail == NULL) { songs = song; } else { tail->head.next = &song->head; }
					tail = song;
				}
				if (valid) {
					PianoDestroyPlaylist (PianoListNextP (app->playlist));
					app->playlist->head.next = songs == NULL ? NULL : &songs->head;
					BarWebState (app);
				} else { PianoDestroyPlaylist (songs); }
			}
		} else if (strcmp (json_object_get_string (type), "play_saved") == 0) {
			PianoSong_t *song = BarCacheLoadOne (&app->settings, json_object_get_string (id));
			if (song == NULL) {
				BarUiMsg (&app->settings, MSG_ERR, "Saved song is missing or unreadable.\n");
			} else {
				PianoDestroyPlaylist (app->pendingLocalSong);
				app->pendingLocalSong = song;
				app->modeRequest = 3;
				accepted = true;
				BarUiActSkipSong (app, app->curStation, app->playlist, BAR_DC_GLOBAL);
			}
		}
	}
	if (message != NULL) { json_object_put (message); }
	if (!accepted && ack) { BarWebHandled (); }
}

int BarWebListSaved (const char *directory) {
	BarSettings_t settings = {.cacheDir = (char *) directory};
	gcry_check_version (NULL);
	av_log_set_level (AV_LOG_QUIET);
	PianoSong_t *songs = BarCacheLoad (&settings);
	json_object *list = json_object_new_array ();
	for (PianoSong_t *song = songs; song != NULL; song = PianoListNextP (song)) {
		json_object *item = json_object_new_object ();
		const char *name = strrchr (song->audioUrl, '/');
		string (item, "id", name == NULL ? song->audioUrl : name + 1);
		string (item, "title", song->title);
		string (item, "artist", song->artist);
		string (item, "album", song->album);
		artwork (item, "cover", &settings, song);
		json_object_object_add (item, "duration", json_object_new_int64 (song->length));
		json_object_array_add (list, item);
	}
	puts (json_object_to_json_string_ext (list, JSON_C_TO_STRING_PLAIN));
	json_object_put (list);
	PianoDestroyPlaylist (songs);
	return 0;
}

void BarWebState (const BarApp_t *app) {
	if (statusFd < 0) { return; }
	json_object *object = json_object_new_object ();
	string (object, "type", "state");
	json_object_object_add (object, "offline", json_object_new_boolean (app->offline));
	json_object_object_add (object, "volume", json_object_new_int (app->settings.volume));
	player_t *player = (player_t *) &app->player;
	pthread_mutex_lock (&player->lock);
	string (object, "output", player->outputMode == 2 ? "browser" : player->outputMode == 3 ? "both" : "host");
	json_object_object_add (object, "paused", json_object_new_boolean (player->doPause));
	json_object_object_add (object, "mode", json_object_new_int (player->mode));
	json_object_object_add (object, "elapsed", json_object_new_int64 (player->songPlayed));
	json_object_object_add (object, "duration", json_object_new_int64 (player->songDuration));
	json_object_object_add (object, "djVoice", json_object_new_int64 (player->voice != NULL ? player->voiceId : 0));
	json_object_object_add (object, "djVoiceDone", json_object_new_int64 (player->voiceDoneId));
	pthread_mutex_unlock (&player->lock);
	string (object, "station", app->curStation == NULL ? NULL : app->curStation->name);
	string (object, "stationId", app->curStation == NULL ? NULL : app->curStation->id);
	string (object, "nextStationId", app->nextStation == NULL ? NULL : app->nextStation->id);
	json_object *stations = json_object_new_array ();
	for (const PianoStation_t *station = app->ph.stations; station != NULL;
			station = PianoListNextP (station)) {
		json_object *item = json_object_new_object ();
		string (item, "id", station->id);
		string (item, "name", station->name);
		json_object_object_add (item, "quickMix", json_object_new_boolean (station->isQuickMix));
		json_object_array_add (stations, item);
	}
	json_object_object_add (object, "stations", stations);
	string (object, "cacheDir", app->settings.cacheDir);
	json_object_object_add (object, "cachePending", json_object_new_int (BarCachePending ()));
	const PianoSong_t *song = app->playlist;
	const char *savedId = app->offline && song != NULL ? strrchr (song->audioUrl, '/') : NULL;
	string (object, "savedId", savedId == NULL ? NULL : savedId + 1);
	const PianoSong_t *queued = app->offline && song != NULL ? PianoListNextP (song) : NULL;
	const char *queuedId = queued == NULL ? NULL : strrchr (queued->audioUrl, '/');
	string (object, "queuedSavedId", queuedId == NULL ? NULL : queuedId + 1);
	string (object, "queuedTitle", queued == NULL ? NULL : queued->title);
	string (object, "queuedArtist", queued == NULL ? NULL : queued->artist);
	json_object *queuedIds = json_object_new_array ();
	for (const PianoSong_t *item = queued; item != NULL && json_object_array_length (queuedIds) < 30; item = PianoListNextP (item)) {
		const char *name = strrchr (item->audioUrl, '/');
		if (name != NULL) { json_object_array_add (queuedIds, json_object_new_string (name + 1)); }
	}
	json_object_object_add (object, "queuedSavedIds", queuedIds);
	/* The next song in the playlist, online or offline, so the DJ can write
	 * and voice its introduction before it starts. */
	const PianoSong_t *upcoming = song == NULL ? NULL : PianoListNextP (song);
	string (object, "nextTitle", upcoming == NULL ? NULL : upcoming->title);
	string (object, "nextArtist", upcoming == NULL ? NULL : upcoming->artist);
	string (object, "nextAlbum", upcoming == NULL ? NULL : upcoming->album);
	string (object, "title", song == NULL ? NULL : song->title);
	string (object, "artist", song == NULL ? NULL : song->artist);
	string (object, "album", song == NULL ? NULL : song->album);
	string (object, "cover", song == NULL ? NULL : song->coverArt);
	artwork (object, "cachedCover", &app->settings, song);
	json_object_object_add (object, "loved", json_object_new_boolean (song != NULL && song->rating == PIANO_RATE_LOVE));
	json_object *actions = json_object_new_array ();
	BarUiDispatchContext_t context = BAR_DC_GLOBAL;
	if (app->curStation != NULL) { context |= BAR_DC_STATION; }
	if (song != NULL) { context |= BAR_DC_SONG; }
	for (size_t i = 0; i < BAR_KS_COUNT; ++i) {
		if (app->settings.keys[i] == BAR_KS_DISABLED) { continue; }
		json_object *action = json_object_new_object ();
		string (action, "id", dispatchActions[i].configKey);
		string (action, "label", dispatchActions[i].helpText);
		char key[] = {app->settings.keys[i], '\0'};
		string (action, "key", key);
		json_object_object_add (action, "enabled", json_object_new_boolean (
				(context & dispatchActions[i].context) == dispatchActions[i].context &&
				(!app->offline || BarUiOfflineAction (i))));
		json_object_array_add (actions, action);
	}
	json_object_object_add (object, "actions", actions);
	publish (object);
}
