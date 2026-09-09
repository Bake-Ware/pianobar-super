#include "config.h"
#include <assert.h>
#include <json.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include "main.h"
#include "web.h"
#include "ui_dispatch.h"

sig_atomic_t *interrupted;

static json_object *receive (int fd) {
	char buffer[65536];
	ssize_t len = recv (fd, buffer, sizeof (buffer) - 1, 0);
	assert (len > 0);
	buffer[len] = '\0';
	json_object *object = json_tokener_parse (buffer);
	assert (object != NULL);
	return object;
}

static void selectStation (BarApp_t *app, int fd, const char *id) {
	char message[256];
	int len = snprintf (message, sizeof (message), "{\"type\":\"select_station\",\"id\":\"%s\"}", id);
	assert (send (fd, message, len, 0) == len);
	BarWebPoll (app);
	json_object *reply;
	do {
		reply = receive (fd);
		const bool handled = strcmp (json_object_get_string (json_object_object_get (reply, "type")), "handled") == 0;
		json_object_put (reply);
		if (handled) { break; }
	} while (true);
}

int main (void) {
	BarApp_t app = {0};
	BarSettingsInit (&app.settings);
	app.settings.keys[BAR_KS_SELECTSTATION] = 's';
	BarPlayerInit (&app.player, &app.settings);
	PianoStation_t second = {.id = "202", .name = "Evening jazz"};
	PianoStation_t first = {.id = "101", .name = "All stations", .isQuickMix = true};
	first.head.next = &second.head;
	app.ph.stations = app.curStation = app.nextStation = &first;
	app.playlist = calloc (1, sizeof (*app.playlist));
	PianoSong_t *queued = calloc (1, sizeof (*queued));
	app.playlist->head.next = &queued->head;
	app.player.doPause = true;
	int pair[2];
	assert (socketpair (AF_UNIX, SOCK_DGRAM, 0, pair) == 0);
	struct timeval timeout = {.tv_sec = 2};
	setsockopt (pair[1], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof (timeout));
	char fd[16];
	snprintf (fd, sizeof (fd), "%d", pair[0]);
	setenv ("PIANOBAR_STATUS_FD", fd, 1);
	BarWebInit ();
	BarWebState (&app);
	json_object *state = receive (pair[1]);
	assert (json_object_array_length (json_object_object_get (state, "stations")) == 2);
	int answers[2];
	assert (pipe (answers) == 0);
	FD_ZERO (&app.input.set);
	FD_SET (answers[0], &app.input.set);
	app.input.fds[0] = answers[0];
	app.input.fds[1] = -1;
	app.input.maxfd = answers[0] + 1;
	for (const char *answer = "n\n"; *answer != '\0'; ++answer) {
		assert (write (answers[1], answer, 1) == 1);
		BarUiActDeleteStation (&app, &second, app.playlist, BAR_DC_GLOBAL | BAR_DC_STATION);
		json_object *prompt = receive (pair[1]);
		assert (strcmp (json_object_get_string (json_object_object_get (prompt, "kind")), "delete_station") == 0);
		assert (strcmp (json_object_get_string (json_object_object_get (prompt, "station")), second.name) == 0);
		json_object_object_add (state, "testDeletePrompt", prompt);
		json_object *closed = receive (pair[1]);
		assert (!json_object_get_boolean (json_object_object_get (closed, "active")));
		json_object_put (closed);
		assert (app.nextStation == &first && app.ph.stations->head.next == &second.head);
		assert (!app.player.doQuit && app.playlist->head.next == &queued->head);
	}
	close (answers[0]); close (answers[1]);
	BarWebPrompt (true, false, true, 10, NULL);
	json_object *generic = receive (pair[1]);
	assert (json_object_object_get (generic, "kind") == NULL);
	json_object_put (generic);
	selectStation (&app, pair[1], "missing");
	assert (app.nextStation == &first && !app.player.doQuit);
	app.offline = true;
	selectStation (&app, pair[1], "202");
	assert (app.nextStation == &first && !app.player.doQuit);
	app.offline = false;
	app.settings.keys[BAR_KS_SELECTSTATION] = BAR_KS_DISABLED;
	selectStation (&app, pair[1], "202");
	assert (app.nextStation == &first && !app.player.doQuit);
	app.settings.keys[BAR_KS_SELECTSTATION] = 's';
	selectStation (&app, pair[1], "202");
	assert (app.nextStation == &second && app.curStation == &first);
	assert (app.playlist != NULL && app.playlist->head.next == NULL);
	assert (app.player.doQuit && !app.player.doPause);
	/* Python/browser tests use the actual native station-state representation. */
	puts (json_object_to_json_string_ext (state, JSON_C_TO_STRING_PLAIN));
	json_object_put (state);
	PianoDestroyPlaylist (app.playlist);
	BarPlayerDestroy (&app.player);
	BarSettingsDestroy (&app.settings);
	close (pair[0]); close (pair[1]);
	return 0;
}
