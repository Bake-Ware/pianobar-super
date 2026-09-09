#pragma once

#include "main.h"

void BarWebInit (void);
void BarWebState (const BarApp_t *);
void BarWebHandled (void);
void BarWebPrompt (bool active, bool secret, bool line, size_t limit, const char *mask);
void BarWebDeleteConfirmation (const char *stationName);

bool BarWebOpen (void);

void BarWebPoll (BarApp_t *);
int BarWebListSaved (const char *);
