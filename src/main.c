/*
Copyright (c) 2008-2018
	Lars-Dominik Braun <lars@6xq.net>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

#include "config.h"

/* system includes */
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
/* fork () */
#include <unistd.h>
#include <sys/select.h>
#include <time.h>
#include <ctype.h>
/* open () */
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
/* tcset/getattr () */
#include <termios.h>
#include <pthread.h>
#include <assert.h>
#include <stdbool.h>
#include <limits.h>
#include <signal.h>
/* waitpid () */
#include <sys/types.h>
#include <sys/wait.h>

/* pandora.com library */
#include <piano.h>

#include "main.h"
#include "debug.h"
#include "terminal.h"
#include "ui.h"
#include "ui_dispatch.h"
#include "ui_readline.h"
#include "web.h"

/*	authenticate user
 */
static bool BarMainLoginUser (BarApp_t *app) {
	PianoReturn_t pRet;
	CURLcode wRet;
	PianoRequestDataLogin_t reqData;
	bool ret;

	reqData.user = app->settings.username;
	reqData.password = app->settings.password;
	reqData.step = 0;

	BarUiMsg (&app->settings, MSG_INFO, "Login... ");
	ret = BarUiPianoCall (app, PIANO_REQUEST_LOGIN, &reqData, &pRet, &wRet);
	BarUiStartEventCmd (&app->settings, "userlogin", NULL, NULL, &app->player,
			NULL, pRet, wRet);

	return ret;
}

/*	ask for username/password if none were provided in settings
 */
static bool BarMainGetLoginCredentials (BarSettings_t *settings,
		BarReadlineFds_t *input) {
	bool usernameFromConfig = true;

	if (settings->username == NULL) {
		char nameBuf[100];

		BarUiMsg (settings, MSG_QUESTION, "Email: ");
		if (BarReadlineStr (nameBuf, sizeof (nameBuf), input, BAR_RL_DEFAULT) == 0) {
			return false;
		}
		settings->username = strdup (nameBuf);
		usernameFromConfig = false;
	}

	if (settings->password == NULL) {
		char passBuf[100];

		if (usernameFromConfig) {
			BarUiMsg (settings, MSG_QUESTION, "Email: %s\n", settings->username);
		}

		if (settings->passwordCmd == NULL) {
			BarUiMsg (settings, MSG_QUESTION, "Password: ");
			if (BarReadlineStr (passBuf, sizeof (passBuf), input, BAR_RL_NOECHO) == 0) {
				puts ("");
				return false;
			}
			/* write missing newline */
			puts ("");
			settings->password = strdup (passBuf);
		} else {
			pid_t chld;
			int pipeFd[2];

			BarUiMsg (settings, MSG_INFO, "Requesting password from external helper... ");

			if (pipe (pipeFd) == -1) {
				BarUiMsg (settings, MSG_NONE, "Error: %s\n", strerror (errno));
				return false;
			}

			chld = fork ();
			if (chld == 0) {
				/* child */
				close (pipeFd[0]);
				dup2 (pipeFd[1], fileno (stdout));
				execl ("/bin/sh", "/bin/sh", "-c", settings->passwordCmd, (char *) NULL);
				BarUiMsg (settings, MSG_NONE, "Error: %s\n", strerror (errno));
				close (pipeFd[1]);
				exit (1);
			} else if (chld == -1) {
				BarUiMsg (settings, MSG_NONE, "Error: %s\n", strerror (errno));
				return false;
			} else {
				/* parent */
				int status;

				close (pipeFd[1]);
				memset (passBuf, 0, sizeof (passBuf));
				read (pipeFd[0], passBuf, sizeof (passBuf)-1);
				close (pipeFd[0]);

				/* drop trailing newlines */
				ssize_t len = strlen (passBuf)-1;
				while (len >= 0 && passBuf[len] == '\n') {
					passBuf[len] = '\0';
					--len;
				}

				waitpid (chld, &status, 0);
				if (WEXITSTATUS (status) == 0) {
					settings->password = strdup (passBuf);
					BarUiMsg (settings, MSG_NONE, "Ok.\n");
				} else {
					BarUiMsg (settings, MSG_NONE, "Error: Exit status %i.\n", WEXITSTATUS (status));
					return false;
				}
			}
		} /* end else passwordCmd */
	}

	return true;
}

/*	get station list
 */
static bool BarMainGetStations (BarApp_t *app) {
	PianoReturn_t pRet;
	CURLcode wRet;
	bool ret;

	BarUiMsg (&app->settings, MSG_INFO, "Get stations... ");
	ret = BarUiPianoCall (app, PIANO_REQUEST_GET_STATIONS, NULL, &pRet, &wRet);
	BarUiStartEventCmd (&app->settings, "usergetstations", NULL, NULL, &app->player,
			app->ph.stations, pRet, wRet);
	return ret;
}

/*	get initial station from autostart setting or user input
 */
static void BarMainGetInitialStation (BarApp_t *app) {
	/* try to get autostart station */
	if (app->settings.autostartStation != NULL) {
		app->nextStation = PianoFindStationById (app->ph.stations,
				app->settings.autostartStation);
		if (app->nextStation == NULL) {
			BarUiMsg (&app->settings, MSG_ERR,
					"Error: Autostart station not found.\n");
		}
	}
	/* no autostart? ask the user */
	if (app->nextStation == NULL) {
		app->nextStation = BarUiSelectStation (app, app->ph.stations,
				"Select station: ", NULL, app->settings.autoselect);
	}
}

/*	wait for user input
 */
static void BarMainHandleUserInput (BarApp_t *app) {
	char buf[2];
	if (BarReadline (buf, sizeof (buf), NULL, &app->input,
			BAR_RL_FULLRETURN | BAR_RL_NOECHO | BAR_RL_NOINT, 1) > 0) {
		BarUiDispatch (app, buf[0], app->curStation, app->playlist, true,
				BAR_DC_GLOBAL);
		BarWebHandled ();
	}
}

/* Called only while no player thread is using the current playlist. */
static bool BarMainUseOfflinePlaylist (BarApp_t *app, PianoSong_t *songs) {
	if (!app->offline) {
		app->onlineStation = app->curStation != NULL ? app->curStation : app->nextStation;
	}
	PianoDestroyPlaylist (app->playlist);
	app->playlist = songs;
	app->offline = true;
	app->networkError = false;
	app->playerErrors = 0;
	app->offlineStation.name = "Offline library";
	app->offlineStation.id = "offline";
	app->curStation = app->nextStation = &app->offlineStation;
	BarUiMsg (&app->settings, MSG_INFO, "Offline mode: playing saved songs.\n");
	return true;
}

static bool BarMainOffline (BarApp_t *app) {
	PianoSong_t *songs = BarCacheLoad (&app->settings);
	if (songs == NULL) {
		BarUiMsg (&app->settings, MSG_ERR, "No saved songs available in %s.\n",
				app->settings.cacheDir);
		return false;
	}
	return BarMainUseOfflinePlaylist (app, songs);
}

/*	fetch new playlist
 */
static void BarMainGetPlaylist (BarApp_t *app) {
	PianoReturn_t pRet;
	CURLcode wRet;
	PianoRequestDataGetPlaylist_t reqData;
	reqData.station = app->nextStation;
	reqData.quality = app->settings.audioQuality;

	BarUiMsg (&app->settings, MSG_INFO, "Receiving new playlist... ");
	if (!BarUiPianoCall (app, PIANO_REQUEST_GET_PLAYLIST,
			&reqData, &pRet, &wRet)) {
		if (app->networkError && app->settings.offlineFallback && BarMainOffline (app)) {
			return;
		}
		app->nextStation = NULL;
	} else {
		app->playlist = reqData.retPlaylist;
		if (app->playlist == NULL) {
			BarUiMsg (&app->settings, MSG_INFO, "No tracks left.\n");
			app->nextStation = NULL;
		}
	}
	app->curStation = app->nextStation;
	BarUiStartEventCmd (&app->settings, "stationfetchplaylist",
			app->curStation, app->playlist, &app->player, app->ph.stations,
			pRet, wRet);
}

/*	start new player thread
 */
static void BarMainStartPlayback (BarApp_t *app, pthread_t *playerThread) {
	assert (app != NULL);
	assert (playerThread != NULL);

	const PianoSong_t * const curSong = app->playlist;
	assert (curSong != NULL);

	BarUiPrintSong (&app->settings, curSong, app->curStation->isQuickMix ?
			PianoFindStationById (app->ph.stations,
			curSong->stationId) : NULL);

	/* Remote playlists may only supply HTTP(S); local paths come from the cache. */
	if (curSong->audioUrl == NULL || (!app->offline &&
			strncmp (curSong->audioUrl, "http://", 7) != 0 &&
			strncmp (curSong->audioUrl, "https://", 8) != 0)) {
		BarUiMsg (&app->settings, MSG_ERR, "Invalid song url.\n");
	} else {
		player_t * const player = &app->player;
		BarPlayerReset (player);

		app->player.url = curSong->audioUrl;
		app->player.song = curSong;
		app->player.local = app->offline;
		app->player.gain = curSong->fileGain;
		app->player.songDuration = curSong->length;

		assert (interrupted == &app->doQuit);
		interrupted = &app->player.interrupted;

		/* throw event */
		BarUiStartEventCmd (&app->settings, "songstart",
				app->curStation, curSong, &app->player, app->ph.stations,
				PIANO_RET_OK, CURLE_OK);

		/* prevent race condition, mode must _not_ be DEAD if
		 * thread has been started */
		app->player.mode = PLAYER_WAITING;
		/* start player */
		if (pthread_create (playerThread, NULL, BarPlayerThread, &app->player) != 0) {
			app->player.mode = PLAYER_DEAD;
			interrupted = &app->doQuit;
			app->nextStation = NULL;
			BarUiMsg (&app->settings, MSG_ERR, "Cannot start player thread.\n");
		}
	}
}

/*	player is done, clean up
 */
static void BarMainPlayerCleanup (BarApp_t *app, pthread_t *playerThread) {
	void *threadRet;

	BarUiStartEventCmd (&app->settings, "songfinish", app->curStation,
			app->playlist, &app->player, app->ph.stations, PIANO_RET_OK,
			CURLE_OK);

	/* FIXME: pthread_join blocks everything if network connection
	 * is hung up e.g. */
	pthread_join (*playerThread, &threadRet);

	if (threadRet == (void *) PLAYER_RET_OK) {
		app->playerErrors = 0;
	} else if (threadRet == (void *) PLAYER_RET_SOFTFAIL) {
		++app->playerErrors;
		if (!app->offline && !app->player.doQuit && app->settings.offlineFallback) {
			app->modeRequest = 1;
		}
		if (app->playerErrors >= app->settings.maxRetry) {
			/* don't continue playback if thread reports too many error */
			app->nextStation = NULL;
		}
	} else {
		app->nextStation = NULL;
	}

	assert (interrupted == &app->player.interrupted);
	interrupted = &app->doQuit;

	app->player.mode = PLAYER_DEAD;
}

/*	print song duration
 */
static void BarMainPrintTime (BarApp_t *app) {
	unsigned int songRemaining;
	char sign[2] = {0, 0};
	player_t * const player = &app->player;

	pthread_mutex_lock (&player->lock);
	const unsigned int songDuration = player->songDuration;
	const unsigned int songPlayed = player->songPlayed;
	pthread_mutex_unlock (&player->lock);

	if (songPlayed <= songDuration) {
		songRemaining = songDuration - songPlayed;
		sign[0] = '-';
	} else {
		/* longer than expected */
		songRemaining = songPlayed - songDuration;
		sign[0] = '+';
	}

	char outstr[512], totalFormatted[16], remainingFormatted[16],
			elapsedFormatted[16];
	const char *vals[] = {totalFormatted, remainingFormatted,
			elapsedFormatted, sign};
	snprintf (totalFormatted, sizeof (totalFormatted), "%02u:%02u",
			songDuration/60, songDuration%60);
	snprintf (remainingFormatted, sizeof (remainingFormatted), "%02u:%02u",
			songRemaining/60, songRemaining%60);
	snprintf (elapsedFormatted, sizeof (elapsedFormatted), "%02u:%02u",
			songPlayed/60, songPlayed%60);
	BarUiCustomFormat (outstr, sizeof (outstr), app->settings.timeFormat,
			"tres", vals);
	BarUiMsg (&app->settings, MSG_TIME, "%s\r", outstr);
}

/*	main loop
 */
static void BarMainLoop (BarApp_t *app) {
	pthread_t playerThread;
	if (app->settings.offline) {
		if (!BarMainOffline (app)) { return; }
	} else {
		if (!BarMainGetLoginCredentials (&app->settings, &app->input)) { return; }
		if (!BarMainLoginUser (app) || !BarMainGetStations (app)) {
			if (!app->networkError || !app->settings.offlineFallback ||
					!BarMainOffline (app)) { return; }
		} else {
			BarMainGetInitialStation (app);
		}
	}

	player_t * const player = &app->player;
	bool started = false;
	while (!app->doQuit) {
		if (BarPlayerGetMode (player) == PLAYER_FINISHED) {
			if (player->interrupted != 0) { app->doQuit = 1; }
			BarMainPlayerCleanup (app, &playerThread);
		}
		if (BarPlayerGetMode (player) == PLAYER_DEAD) {
			if (started && app->playlist != NULL) {
				PianoSong_t *histsong = app->playlist;
				app->playlist = PianoListNextP (app->playlist);
				histsong->head.next = NULL;
				BarUiHistoryPrepend (app, histsong);
			}
			started = false;
			if (app->doQuit) { break; }
			const int request = app->modeRequest;
			app->modeRequest = 0;
			if (request == 1) {
				BarMainOffline (app);
			} else if (request == 3 && app->pendingLocalSong != NULL) {
				BarMainUseOfflinePlaylist (app, app->pendingLocalSong);
				app->pendingLocalSong = NULL;
				BarWebHandled ();
			} else if (request == 2) {
				/* Keep the local queue intact if reconnect fails. */
				app->offline = false;
				if (BarMainGetLoginCredentials (&app->settings, &app->input) &&
						BarMainLoginUser (app)) {
					/* Existing station pointers remain valid; initial offline launch
					 * has no station list yet. */
					if (app->ph.stations != NULL || BarMainGetStations (app)) {
						PianoDestroyPlaylist (app->playlist);
						app->playlist = NULL;
						app->curStation = NULL;
						app->nextStation = app->onlineStation;
						BarMainGetInitialStation (app);
					} else { app->offline = true; }
				} else { app->offline = true; }
				if (app->offline) {
					app->networkError = false;
					app->nextStation = &app->offlineStation;
					BarUiMsg (&app->settings, MSG_INFO, "Reconnect failed; staying offline.\n");
				}
			}
			if (app->playlist == NULL && app->nextStation != NULL) {
				if (app->offline) {
					app->playlist = BarCacheLoad (&app->settings);
					if (app->playlist == NULL) {
						app->nextStation = NULL;
						BarUiMsg (&app->settings, MSG_ERR, "No saved songs available.\n");
					}
				} else {
					if (app->nextStation != app->curStation) {
						BarUiPrintStation (&app->settings, app->nextStation);
					}
					BarMainGetPlaylist (app);
				}
			}
			if (app->playlist != NULL && app->nextStation != NULL) {
				BarMainStartPlayback (app, &playerThread);
				started = true;
			}
		}
		BarWebState (app);
		BarWebPoll (app);
		BarMainHandleUserInput (app);
		if (BarPlayerGetMode (player) == PLAYER_PLAYING) { BarMainPrintTime (app); }
	}
	if (BarPlayerGetMode (player) != PLAYER_DEAD) {
		pthread_join (playerThread, NULL);
		interrupted = &app->doQuit;
	}
}

sig_atomic_t *interrupted = NULL;

static void intHandler (int signal) {
	if (interrupted != NULL) {
		debugPrint(DEBUG_UI, "Received ^C\n");
		*interrupted += 1;
	}
}

static void BarMainSetupSigaction () {
	struct sigaction act = {
			.sa_handler = intHandler,
			.sa_flags = 0,
			};
	sigemptyset (&act.sa_mask);
	sigaction (SIGINT, &act, NULL);
}

int main (int argc, char **argv) {
	static BarApp_t app;
	/* Read-only library scans run in a separate process, away from audio. */
	if (argc == 3 && strcmp (argv[1], "--list-saved") == 0) {
		return BarWebListSaved (argv[2]);
	}
	bool offline = false, cli = false, port = false;
	for (int i = 1; i < argc; ++i) {
		if (strcmp (argv[i], "--offline") == 0) { offline = true; }
		else if (strcmp (argv[i], "--cli") == 0) { cli = true; }
		else if ((strcmp (argv[i], "--port") == 0 || strcmp (argv[i], "--listen") == 0 ||
			strcmp (argv[i], "--password-file") == 0 || strcmp (argv[i], "--output") == 0) && i + 1 < argc) {
			port = true;
			++i; /* The web host validates the port value. */
		} else if (strcmp (argv[i], "--help") == 0 || strcmp (argv[i], "-h") == 0) {
			puts ("Usage: pianobar [--offline] [--cli] [--port PORT] [--listen ADDRESS] [--output host|browser|both]\n"
					"  Web interface enabled by default on localhost:8765.\n"
					"  --offline  Play saved songs without logging in.\n"
					"  --cli      Disable the web server (terminal only).\n"
					"  --port     Choose the web server port.\n"
					"  --listen   Bind the web server to an address (e.g. 0.0.0.0 for LAN).\n"
					"  --output   Start with host, browser, or both audio outputs.\n"
					"  --password-file  Read the network access password from a file.\n"
					"  Press W in the player to open the web interface.");
			return 0;
		} else {
			fprintf (stderr, "Unknown or incomplete option: %s\n", argv[i]);
			return 1;
		}
	}
	if (cli && port) {
		fputs ("Web options cannot be used with --cli.\n", stderr);
		return 1;
	}
	/* The host starts the native child with --cli to avoid recursion. */
	if (!cli) {
		char *helper = malloc (strlen (argv[0]) + 5);
		if (helper == NULL) { return 1; }
		sprintf (helper, "%s-web", argv[0]);
		argv[0] = helper;
		execvp (helper, argv);
		perror ("Cannot start pianobar-web (requires Python 3; use --cli for terminal only)");
		free (helper);
		return 1;
	}
	srand ((unsigned int) time (NULL) ^ (unsigned int) getpid ());
	debugEnable();

	memset (&app, 0, sizeof (app));

	/* save terminal attributes, before disabling echoing */
	BarTermInit ();

	/* signals */
	signal (SIGPIPE, SIG_IGN);
	BarMainSetupSigaction ();
	interrupted = &app.doQuit;

	/* init some things */
	gcry_check_version (NULL);
	gcry_control (GCRYCTL_DISABLE_SECMEM, 0);
	gcry_control (GCRYCTL_INITIALIZATION_FINISHED, 0);
	BarPlayerInit (&app.player, &app.settings);

	BarSettingsInit (&app.settings);
	BarSettingsRead (&app.settings);
	app.settings.offline |= offline;

	PianoReturn_t pret;
	if ((pret = PianoInit (&app.ph, app.settings.partnerUser,
			app.settings.partnerPassword, app.settings.device,
			app.settings.inkey, app.settings.outkey)) != PIANO_RET_OK) {
		BarUiMsg (&app.settings, MSG_ERR, "Initialization failed:"
				" %s\n", PianoErrorToStr (pret));
		return 0;
	}

	BarUiMsg (&app.settings, MSG_NONE,
			"Welcome to " PACKAGE " (" VERSION ")! ");
	if (app.settings.keys[BAR_KS_HELP] == BAR_KS_DISABLED) {
		BarUiMsg (&app.settings, MSG_NONE, "\n");
	} else {
		BarUiMsg (&app.settings, MSG_NONE,
				"Press %c for a list of commands.\n",
				app.settings.keys[BAR_KS_HELP]);
	}

	curl_global_init (CURL_GLOBAL_DEFAULT);
	app.http = curl_easy_init ();
	assert (app.http != NULL);

	/* init fds */
	FD_ZERO(&app.input.set);
	app.input.fds[0] = STDIN_FILENO;
	FD_SET(app.input.fds[0], &app.input.set);

	/* open fifo read/write so it won't EOF if nobody writes to it */
	assert (sizeof (app.input.fds) / sizeof (*app.input.fds) >= 2);
	app.input.fds[1] = open (app.settings.fifo, O_RDWR);
	if (app.input.fds[1] != -1) {
		struct stat s;

		/* check for file type, must be fifo */
		fstat (app.input.fds[1], &s);
		if (!S_ISFIFO (s.st_mode)) {
			BarUiMsg (&app.settings, MSG_ERR, "File at %s is not a fifo\n", app.settings.fifo);
			close (app.input.fds[1]);
			app.input.fds[1] = -1;
		} else {
			FD_SET(app.input.fds[1], &app.input.set);
			BarUiMsg (&app.settings, MSG_INFO, "Control fifo at %s opened\n",
					app.settings.fifo);
		}
	}
	app.input.maxfd = app.input.fds[0] > app.input.fds[1] ? app.input.fds[0] :
			app.input.fds[1];
	++app.input.maxfd;

	BarWebInit ();
	BarWebState (&app);
	BarMainLoop (&app);

	if (app.input.fds[1] != -1) {
		close (app.input.fds[1]);
	}

	/* write statefile */
	BarSettingsWrite (app.offline ? app.onlineStation : app.curStation, &app.settings);

	BarCacheDownloadsShutdown (true);
	PianoDestroy (&app.ph);
	PianoDestroyPlaylist (app.songHistory);
	PianoDestroyPlaylist (app.playlist);
	PianoDestroyPlaylist (app.pendingLocalSong);
	curl_easy_cleanup (app.http);
	BarCacheArtworkWait ();
	curl_global_cleanup ();
	BarPlayerDestroy (&app.player);
	BarSettingsDestroy (&app.settings);

	/* restore terminal attributes, zsh doesn't need this, bash does... */
	BarTermRestore ();

	return 0;
}
