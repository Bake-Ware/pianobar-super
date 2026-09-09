#pragma once

#include <stdbool.h>
#include <libavformat/avformat.h>
#include <piano.h>
#include "settings.h"

/* Only finalized files are visible to the offline library. */
typedef struct {
	AVFormatContext *ctx;
	char *temporary, *path;
	int64_t firstTimestamp, endTimestamp;
	AVRational timeBase;
	double duration;
	bool failed;
	const BarSettings_t *settings;
} BarCache_t;

void BarCacheOpen (BarCache_t *, const BarSettings_t *, const PianoSong_t *, AVStream *);
void BarCacheWrite (BarCache_t *, const AVPacket *);
void BarCacheClose (BarCache_t *, bool);
/* Independent downloads survive skip/pause. Stop before settings/network teardown. */
void BarCacheQueue (const BarSettings_t *, const PianoSong_t *, const char *, AVStream *);
unsigned int BarCachePending (void);
void BarCacheDownloadsShutdown (bool cancel);
PianoSong_t *BarCacheLoad (const BarSettings_t *);

PianoSong_t *BarCacheLoadOne (const BarSettings_t *, const char *);

/* Artwork is downloaded independently of audio and shared by matching albums. */
bool BarCacheArtworkId (const BarSettings_t *, const PianoSong_t *, char id[65]);
void BarCacheArtworkWait (void);
