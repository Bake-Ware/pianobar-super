'use strict';
const $ = (id) => document.getElementById(id);
function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  $('theme-toggle').textContent = theme === 'dark' ? 'Light mode' : 'Dark mode';
  $('theme-toggle').setAttribute('aria-pressed', String(theme === 'dark'));
  document.querySelector('meta[name="theme-color"]').content = theme === 'dark' ? '#101a1a' : '#f5f4ef';
}
let savedTheme;
try { savedTheme = localStorage.getItem('pianobarTheme'); } catch (_) { /* Storage can be disabled. */ }
applyTheme(['light', 'dark'].includes(savedTheme) ? savedTheme :
  (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
$('theme-toggle').addEventListener('click', () => {
  const theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  applyTheme(theme);
  try { localStorage.setItem('pianobarTheme', theme); } catch (_) { /* Keep the session preference. */ }
});
window.addEventListener('storage', event => {
  if (event.key === 'pianobarTheme' && ['dark', 'light'].includes(event.newValue)) applyTheme(event.newValue);
});
// Inside the desktop shell, offer a jump to the compact widget window.
const shell = typeof window.chrome?.webview?.postMessage === 'function';
if (shell) {
  $('widget-toggle').hidden = false;
  $('widget-toggle').addEventListener('click', () => {
    try { window.chrome.webview.postMessage({type: 'widget'}); } catch (_) { }
  });
}
const views = {player: 'Now playing', stations: 'Stations', library: 'Library', settings: 'Settings'};
let voiceSampleController = null, voiceSampleAudio = null, voiceSampleURL = null;
$('sidebar-tools-toggle').addEventListener('click', () => {
  const open = document.querySelector('.sidebar').classList.toggle('tools-open');
  $('sidebar-tools-toggle').setAttribute('aria-expanded', String(open));
  $('sidebar-tools-toggle').querySelector('span').textContent = open ? '−' : '+';
});
function showView(view, focus = false) {
  if (!views[view]) view = 'player';
  if (view !== 'settings') stopVoiceSample();
  document.body.dataset.view = view;
  document.querySelector('main').setAttribute('aria-label', views[view]);
  document.querySelectorAll('[data-views]').forEach(panel => {
    panel.hidden = !panel.dataset.views.split(' ').includes(view);
  });
  document.querySelectorAll('.nav[data-view]').forEach(link => {
    const active = link.dataset.view === view;
    link.classList.toggle('active', active);
    if (active) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });
  if (view === 'library') queueMicrotask(refreshLibrary);
  if (view === 'settings') queueMicrotask(loadSettings);
  requestAnimationFrame(followTranscript);
  if (focus) {
    document.querySelector('.nav[aria-current="page"]').focus({preventScroll: true});
    window.scrollTo(0, 0);
  }
}
document.querySelectorAll('.nav[data-view]').forEach(link => link.addEventListener('click', event => {
  event.preventDefault();
  history.pushState(null, '', '#' + link.dataset.view);
  showView(link.dataset.view, true);
}));
window.addEventListener('hashchange', () => showView(location.hash.slice(1)));
showView(location.hash.slice(1));
let snapshot = {state: {}, prompt: {}}, revision = -1, renderedActions = '', promptId = -1;
let sending = false, noticeTimer;
let savedSongs = [], libraryBusy = false, libraryRenderKey = '', libraryDirectory = '';
let stationRenderKey = '';
let audioContext, audioController, audioNextTime = 0, audioEpoch = null, desiredOutput = null;
let browserId = null, browserRegistration, browserDesired = false, browserStatus = 'idle';
let browserVolume = 1, audioGain, browserPollBusy = false, browserLeaving = false;
let browserMutation = 0, audioGeneration = 0;
let browserLastSeen = 0;
let autoListen = true;
try { autoListen = localStorage.getItem('pianobarAutoListen') !== 'false'; } catch (_) {}
let autoListenPending = autoListen;
$('setting-auto-listen').checked = autoListen;
$('setting-auto-listen').addEventListener('change', event => {
  autoListen = event.target.checked;
  autoListenPending = false;
  try {
    localStorage.setItem('pianobarAutoListen', String(autoListen));
    $('auto-listen-note').textContent = 'Saved for this browser. Applies on the next page load. Your browser may still require a click to allow sound.';
  } catch (_) {
    $('auto-listen-note').textContent = 'Browser storage is unavailable, so this preference cannot be remembered after reloading.';
  }
});
window.addEventListener('storage', event => {
  if (event.key === 'pianobarAutoListen') {
    autoListen = event.newValue !== 'false';
    $('setting-auto-listen').checked = autoListen;
    if (!autoListen) autoListenPending = false;
  }
});
let coverSource = '', coverAttempt = 0, coverRetry, coverCandidates = [], coverCandidate = 0;
let djEnabled = false, djSpeaking = false, djInfo = {}, djTrack = '', djGeneratedTrack = '';
let djAnnouncement = 0, djGeneration = 0, djController, djAudio, djObjectURL, djUtterance;
let djAirtimeToken = null, djAirtimePending = false, djAirtimeSong = null, djAirtimeReleasing = false;
let audioBufferSeconds = .9;
const audioStats = {underruns: 0, resyncs: 0};
let settingsLoaded = false, settingsBusy = false, setupShown = false;
const audioSources = new Set();
async function browserRequest(operation, data) {
  const response = await fetch('/api/clients/' + operation, {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(result.error || 'Could not contact browser controls.');
    error.status = response.status;
    throw error;
  }
  return result;
}
async function registerBrowser() {
  if (browserId) return;
  if (!browserRegistration) browserRegistration = (async () => {
    let name;
    try { name = localStorage.getItem('pianobarBrowserName'); } catch (_) {}
    const result = await browserRequest('register', {name: name || 'Browser · ' +
      (navigator.userAgent.includes('Mobile') ? 'Mobile' : navigator.platform || 'Desktop')});
    browserId = result.id;
    browserLastSeen = performance.now();
    $('browser-name').value = result.name;
  })().finally(() => { browserRegistration = null; });
  await browserRegistration;
}
async function updateBrowserAudio(enabled) {
  ++browserMutation;
  await registerBrowser();
  await browserRequest('update', {id: browserId, enabled});
  browserDesired = enabled;
}
async function pollBrowser() {
  if (browserPollBusy || browserLeaving) return;
  browserPollBusy = true;
  const mutation = browserMutation;
  try {
    await registerBrowser();
    const page = await browserRequest('heartbeat', {id: browserId, status: browserPlaybackStatus(),
      ready: audioContext?.state === 'running' || djHasAirtime()});
    browserLastSeen = performance.now();
    if (mutation !== browserMutation) return;
    browserDesired = page.enabled;
    browserVolume = page.volume;
    if (document.activeElement !== $('browser-name')) $('browser-name').value = page.name;
    if (audioGain) audioGain.gain.value = browserVolume * (djSpeaking ? .2 : 1);
    if (djAudio) djAudio.volume = browserVolume;
    if (!browserDesired || browserVolume === 0) stopDJ();
    if (autoListenPending && revision >= 0 && ['host', 'browser', 'both'].includes(snapshot.state.output) &&
        !snapshot.playerStopped && !snapshot.setupRequired &&
        !snapshot.restarting && !snapshot.exited && !snapshot.pending && !snapshot.prompt.active && !sending) {
      // Join once on page load. Subsequent remote routing or Stop listening
      // remains authoritative; a heartbeat must not turn this browser back on.
      if (snapshot.state.output === 'host' && !await command({output: 'both'})) return;
      if (!autoListenPending || mutation !== browserMutation) return;
      await updateBrowserAudio(true);
      autoListenPending = false;
    }
    if (!browserDesired) stopListening();
    else if (!audioController && !snapshot.playerStopped && !snapshot.restarting && snapshot.state.output !== 'host') {
      await startListening();
    }
  } catch (error) {
    // A single failed heartbeat must not tear down a healthy audio stream.
    // The server still enforces routing and lease expiry on every audio packet.
    if ([400, 401, 403, 404].includes(error.status) || performance.now() - browserLastSeen > 90000) {
      stopListening();
      browserId = null;
    }
  } finally { browserPollBusy = false; }
}
setInterval(pollBrowser, 2000);
queueMicrotask(pollBrowser);
$('browser-name').addEventListener('change', async event => {
  try {
    await registerBrowser();
    const page = await browserRequest('update', {id: browserId, name: event.target.value});
    try { localStorage.setItem('pianobarBrowserName', page.name); } catch (_) {}
  } catch (error) { notify(error.message); }
});
const labels = {
  act_help: 'Help & shortcuts', act_songlove: 'Love song', act_songban: 'Ban song',
  act_stationaddmusic: 'Add music', act_stationcreate: 'Create a station',
  act_stationdelete: 'Delete station', act_songexplain: 'Why this song?',
  act_stationaddbygenre: 'Explore genres', act_history: 'Song history',
  act_songinfo: 'Song information', act_addshared: 'Add shared station',
  act_songnext: 'Next song', act_songpausetoggle: 'Play / pause', act_quit: 'Quit player',
  act_stationrename: 'Rename station', act_stationchange: 'Change station',
  act_songtired: 'Rest this song', act_upcoming: 'Upcoming songs',
  act_stationselectquickmix: 'Mix stations', act_debug: 'Song details',
  act_bookmark: 'Bookmark', act_voldown: 'Volume down', act_volup: 'Volume up',
  act_managestation: 'Manage station', act_songpausetoggle2: 'Play / pause (alternate)',
  act_stationcreatefromsong: 'Station from this song', act_songplay: 'Resume',
  act_songpause: 'Pause', act_volreset: 'Reset volume', act_settings: 'Account settings',
  act_offline: 'Offline / reconnect', act_web: 'Open browser'
};
const stationActions = ['act_stationchange', 'act_stationcreate', 'act_stationaddmusic', 'act_stationaddbygenre', 'act_managestation', 'act_stationrename', 'act_addshared', 'act_stationcreatefromsong', 'act_stationselectquickmix', 'act_stationdelete'];
const libraryActions = ['act_upcoming', 'act_history', 'act_songinfo', 'act_bookmark', 'act_songexplain', 'act_offline'];
async function refreshLibrary() {
  if (libraryBusy) return;
  libraryBusy = true;
  $('refresh-library').disabled = true;
  try {
    const response = await fetch('/api/library');
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Could not read your saved songs.');
    savedSongs = result.songs;
    libraryRenderKey = '';
    renderLibrary();
  } catch (error) {
    $('library-message').textContent = error.message;
  } finally {
    libraryBusy = false;
    $('refresh-library').disabled = false;
  }
}
function renderLibrary() {
  const query = $('library-search').value.trim().toLocaleLowerCase();
  const currentId = snapshot.state.savedId || '';
  const key = JSON.stringify([savedSongs, query, currentId]);
  if (key === libraryRenderKey) return;
  libraryRenderKey = key;
  const filtered = savedSongs.filter(song =>
    [song.title, song.artist, song.album].some(value => value.toLocaleLowerCase().includes(query)));
  $('saved-count').textContent = savedSongs.length;
  $('library-message').textContent = !savedSongs.length ?
    'No saved songs yet. Songs appear here when their background downloads finish.' :
    (!filtered.length ? 'No songs match your search.' :
      `${filtered.length} ${filtered.length === 1 ? 'song' : 'songs'} · Choose Play to listen locally.`);
  const rows = filtered.map(song => {
    const row = document.createElement('div');
    row.className = 'saved-song' + (song.id === currentId ? ' current' : '');
    const info = document.createElement('div');
    info.className = 'saved-song-info';
    const title = document.createElement('p');
    title.className = 'saved-song-title';
    title.textContent = song.title || 'Untitled song';
    const detail = document.createElement('p');
    detail.className = 'saved-song-detail';
    detail.textContent = [song.artist || 'Unknown artist', song.album,
      song.id === currentId ? 'Now playing' : ''].filter(Boolean).join(' · ');
    info.append(title, detail);
    const duration = document.createElement('span');
    duration.className = 'saved-song-duration';
    duration.textContent = time(song.duration);
    const play = document.createElement('button');
    play.type = 'button';
    play.className = 'saved-play';
    play.textContent = '▶ Play';
    play.setAttribute('aria-label', 'Play ' + (song.title || 'Untitled song'));
    play.addEventListener('click', () => command({playSaved: song.id}));
    const download = document.createElement('a');
    download.className = 'saved-download';
    download.textContent = '↓ Download';
    download.href = '/api/download/' + encodeURIComponent(song.id);
    download.setAttribute('download', '');
    download.setAttribute('aria-label', 'Download ' + (song.title || 'Untitled song'));
    const actions = document.createElement('div');
    actions.className = 'saved-song-actions';
    actions.append(play, download);
    const artwork = document.createElement('span');
    artwork.className = 'saved-artwork';
    artwork.setAttribute('aria-hidden', 'true');
    artwork.textContent = '♫';
    if (/^\/api\/artwork\/[0-9a-f]{64}$/.test(song.cover || '')) {
      const image = document.createElement('img');
      image.alt = '';
      image.loading = 'lazy';
      image.decoding = 'async';
      image.src = song.cover;
      let attempts = 0;
      image.addEventListener('load', () => { image.hidden = false; });
      image.addEventListener('error', () => {
        image.hidden = true;
        if (attempts >= 3) return;
        const attempt = ++attempts;
        setTimeout(() => {
          if (image.isConnected) {
            image.hidden = false;
            image.src = song.cover + '?retry=' + attempt;
          }
        }, 1000 * 2 ** attempt);
      });
      artwork.append(image);
    }
    row.append(artwork, info, duration, actions);
    return row;
  });
  $('saved-songs').replaceChildren(...rows);
  updateDisabled();
}
$('refresh-library').addEventListener('click', refreshLibrary);
$('library-search').addEventListener('input', () => { libraryRenderKey = ''; renderLibrary(); });
setInterval(() => {
  if (document.body.dataset.view === 'library' && !document.hidden) refreshLibrary();
}, 10000);

function renderStations() {
  const state = snapshot.state;
  const query = $('station-search').value.trim().toLocaleLowerCase();
  const stations = [...(state.stations || [])].sort((a, b) =>
    Number(b.quickMix) - Number(a.quickMix) || a.name.localeCompare(b.name));
  const key = JSON.stringify([stations, query, state.stationId, state.nextStationId, state.offline]);
  if (key === stationRenderKey) return;
  stationRenderKey = key;
  const filtered = stations.filter(station => station.name.toLocaleLowerCase().includes(query));
  $('station-list-message').textContent = !stations.length
    ? (state.offline ? 'Reconnect to load your station list.' : 'Your stations will appear after connecting to Pandora.')
    : (!filtered.length ? 'No stations match your search.' : `${filtered.length} ${filtered.length === 1 ? 'station' : 'stations'}`);
  $('station-list').replaceChildren(...filtered.map(station => {
    const row = document.createElement('div');
    row.className = 'station-row';
    const info = document.createElement('div');
    const name = document.createElement('strong');
    name.textContent = station.name;
    const detail = document.createElement('small');
    const current = !state.offline && state.stationId === station.id;
    const switching = !state.offline && state.nextStationId === station.id && state.stationId !== station.id;
    detail.textContent = switching ? 'Switching…' : (current ? 'Now playing' : (station.quickMix ? 'Shuffle mix' : 'Pandora station'));
    row.classList.toggle('current', current);
    info.append(name, detail);
    const play = document.createElement('button');
    play.type = 'button';
    play.className = 'station-play';
    play.dataset.stationId = station.id;
    play.dataset.selected = String(current || switching);
    play.textContent = switching ? 'Switching…' : (current ? 'Playing' : '▶ Play');
    play.setAttribute('aria-label', 'Play station ' + station.name);
    play.addEventListener('click', () => command({selectStation: station.id}));
    row.append(info, play);
    return row;
  }));
}
$('station-search').addEventListener('input', () => { renderStations(); updateDisabled(); });

function notify(message) {
  $('notice').textContent = message;
  $('notice').hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { $('notice').hidden = true; }, 6000);
}
async function command(message) {
  if (sending) return false;
  sending = true;
  updateDisabled();
  try {
    const response = await fetch('/api/command', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(message)
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error);
    return true;
  } catch (error) { notify(error.message); return false; }
  finally { sending = false; updateDisabled(); }
}
function updateDisabled() {
  const actions = snapshot.state.actions || [];
  const stopped = snapshot.playerStopped || snapshot.setupRequired || snapshot.restarting;
  const busy = !snapshot.state.actions || snapshot.prompt.active || snapshot.pending || sending || snapshot.exited || stopped;
  $('save-settings').disabled = settingsBusy || !settingsLoaded;
  $('apply-settings').disabled = settingsBusy || !settingsLoaded || !!snapshot.state.cachePending || !!snapshot.restarting;
  $('apply-settings').textContent = snapshot.setupRequired ? 'Save & start listening' : 'Save & restart player';
  $('apply-settings').title = snapshot.state.cachePending ? 'Wait for background saves to finish.' : '';
  $('audio-output').disabled = !!busy;
  $('listen-button').disabled = !audioController && !!busy;
  document.querySelectorAll('[data-action]').forEach(button => {
    const action = actions.find(a => a.id === button.dataset.action);
    button.disabled = !action?.enabled || busy;
  });
  const stationAction = actions.find(action => action.id === 'act_stationchange');
  document.querySelectorAll('.station-play').forEach(button => {
    button.disabled = !stationAction?.enabled || snapshot.state.offline || button.dataset.selected === 'true' ||
      busy;
  });
  document.querySelectorAll('.saved-play').forEach(button => {
    button.disabled = !!busy;
  });
  document.querySelectorAll('[data-shortcut]').forEach(row => {
    const action = actions.find(a => a.id === row.dataset.shortcut);
    row.classList.toggle('unavailable', !action?.enabled || snapshot.prompt.active || snapshot.pending || sending || snapshot.exited);
  });
  document.querySelectorAll('#prompt-form button, #prompt-options button').forEach(button => {
    button.disabled = sending || !snapshot.prompt.active || snapshot.exited;
  });
}
function actionButton(id) {
  const button = document.createElement('button');
  button.type = 'button';
  button.dataset.action = id;
  button.textContent = labels[id] || id.replace(/^act_/, '');
  return button;
}
function renderActions(actions) {
  const signature = JSON.stringify(actions.map(a => [a.id, a.key]));
  if (signature === renderedActions) return;
  renderedActions = signature;
  $('shortcut-list').replaceChildren(...actions.map(action => {
    const row = document.createElement('div');
    row.className = 'shortcut';
    row.dataset.shortcut = action.id;
    const key = document.createElement('kbd');
    key.textContent = action.key === ' ' ? 'Space' : action.key;
    const label = document.createElement('span');
    label.textContent = labels[action.id] || action.label || action.id;
    row.append(key, label);
    return row;
  }));
  const available = new Set(actions.map(a => a.id));
  for (const [container, ids] of [['station-actions', stationActions], ['station-page-actions', stationActions], ['library-actions', libraryActions]]) {
    $(container).replaceChildren(...ids.filter(id => available.has(id)).map(actionButton));
  }
  const featured = new Set([...stationActions, ...libraryActions,
    ...Array.from(document.querySelectorAll('.transport [data-action], .track-actions [data-action], header [data-action]')).map(b => b.dataset.action)]);
  $('more-actions').replaceChildren(...actions.filter(a => !featured.has(a.id)).map(a => actionButton(a.id)));
}
function time(seconds) {
  seconds = Math.max(0, Math.floor(seconds || 0));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
function renderPrompt() {
  const prompt = snapshot.prompt;
  const dialog = $('prompt-dialog');
  if (!prompt.active) {
    if (dialog.open) dialog.close();
    return;
  }
  const deleting = prompt.kind === 'delete_station';
  dialog.classList.toggle('confirm-delete', deleting);
  $('delete-confirmation').hidden = !deleting;
  $('prompt-output').hidden = deleting;
  $('prompt-form').hidden = deleting;
  if (deleting) {
    $('prompt-title').textContent = 'Delete station?';
    $('delete-confirmation').textContent = `Are you sure you want to delete “${prompt.station}”?`;
    if (prompt.id !== promptId) {
      promptId = prompt.id;
      const cancel = document.createElement('button');
      cancel.type = 'button';
      cancel.textContent = 'Cancel';
      cancel.addEventListener('click', () => command({text: 'n', promptId: prompt.id}));
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'confirm-delete-button';
      remove.textContent = 'Delete station';
      remove.addEventListener('click', () => command({text: 'y', promptId: prompt.id}));
      $('prompt-options').replaceChildren(cancel, remove);
      if (!dialog.open) dialog.showModal();
      cancel.focus();
    }
    return;
  }
  const output = snapshot.output || '';
  const questionStart = output.lastIndexOf('[?]');
  const previousQuestion = output.lastIndexOf('[?]', Math.max(0, questionStart - 1));
  const context = output.slice(Math.max(previousQuestion + 3, output.length - 7000));
  $('prompt-output').textContent = context;
  $('prompt-output').scrollTop = $('prompt-output').scrollHeight;
  const lastLine = output.trim().split('\n').at(-1)?.replace(/^\[\?\]\s*/, '') || 'Your player needs an answer.';
  $('answer-label').textContent = lastLine;
  $('prompt-title').textContent = prompt.secret ? 'Welcome back.' : 'Make your selection.';
  $('answer').type = prompt.secret ? 'password' : 'text';
  $('answer').maxLength = prompt.limit || 500;
  if (prompt.id !== promptId) {
    promptId = prompt.id;
    $('answer').value = '';
    if (!dialog.open) dialog.showModal();
    $('answer').focus();
  }
  const choices = [];
  if (!prompt.secret && prompt.line) {
    for (const match of context.matchAll(/^\s*(\d+)\)\s+(.+)$/gm)) {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = `${match[1]} · ${match[2]}`;
      button.addEventListener('click', () => command({text: match[1], promptId: prompt.id}));
      choices.push(button);
    }
  }
  if (!prompt.secret && !prompt.line) {
    const mask = prompt.mask || '';
    const keys = mask ? [...new Set(mask.toLowerCase())] :
      (lastLine.includes('What to do') ? ['+', '-', 't', 'b', 'i', '?'] : []);
    for (const key of keys) {
      const button = document.createElement('button');
      button.type = 'button';
      const action = (snapshot.state.actions || []).find(a => a.key === key);
      button.textContent = mask.toLowerCase().includes('yn') ? (key === 'y' ? 'Yes' : 'No') :
        (action && !mask ? labels[action.id] : key.toUpperCase());
      button.addEventListener('click', () => command({text: key, promptId: prompt.id}));
      choices.push(button);
    }
  }
  $('prompt-options').replaceChildren(...choices);
}
function render(data) {
  const wasSetup = snapshot.setupRequired;
  const wasStopped = snapshot.playerStopped;
  snapshot = data;
  const state = data.state;
  $('connection').textContent = data.setupRequired ? 'Setup needed' : (data.restarting ? 'Restarting player…' :
    (data.exited || data.playerStopped ? 'Player stopped · check Settings' : (state.offline ? 'Listening offline' : 'Player connected')));
  $('setup-message').hidden = !data.setupRequired;
  if (data.setupRequired && !setupShown) {
    setupShown = true;
    history.replaceState(null, '', '#settings');
    showView('settings');
  }
  if (!data.setupRequired) setupShown = false;
  if (wasSetup && !data.setupRequired && !data.playerStopped && document.body.dataset.view === 'settings') {
    history.replaceState(null, '', '#player');
    showView('player');
  }
  if (data.playerStopped && !wasStopped && !data.exited && !data.restarting) {
    $('settings-status').textContent = 'Player stopped. Check your Pandora account or offline library, then restart the player.';
    history.replaceState(null, '', '#settings');
    showView('settings');
  }
  $('source').textContent = state.offline ? 'FROM YOUR OFFLINE LIBRARY' : 'YOUR PERSONAL RADIO';
  $('title').textContent = state.title || 'Something good is next.';
  $('artist').textContent = state.artist || 'Connect to your station and make yourself at home.';
  $('album').textContent = state.album || '';
  $('station').textContent = state.station || 'No station selected';
  $('stations-message').textContent = state.offline
    ? 'Reconnect to browse and manage your Pandora stations.'
    : (state.station ? `Listening to ${state.station}. Choose another station or shape your mix.` : 'Choose a station or discover something new.');
  $('current-track').textContent = state.title || 'Nothing playing';
  document.title = `pianobar ${$('station').textContent} / ${$('current-track').textContent}`;
  $('mode-button').textContent = state.offline ? '↗ Reconnect' : '↓ Go offline';
  $('play-button').textContent = state.paused ? '▶' : 'Ⅱ';
  $('play-button').setAttribute('aria-label', state.paused ? 'Resume playback' : 'Pause playback');
  $('volume').textContent = `${state.volume || 0} dB`;
  if (desiredOutput === state.output) desiredOutput = null;
  $('audio-output').value = desiredOutput || state.output || 'host';
  if (data.exited || data.playerStopped || data.restarting || (!desiredOutput && state.output === 'host')) stopListening();
  if (state.paused && !djKeepsMusic(data)) clearAudioQueue();
  $('elapsed').textContent = time(state.elapsed);
  $('duration').textContent = time(state.duration);
  $('progress').max = Math.max(1, state.duration || 0);
  $('progress').value = Math.min(state.elapsed || 0, state.duration || 0);
  const love = document.querySelector('[data-action="act_songlove"]');
  love.classList.toggle('loved', !!state.loved);
  love.setAttribute('aria-pressed', String(!!state.loved));
  const localCover = /^\/api\/artwork\/[0-9a-f]{64}$/.test(state.cachedCover || '') ? state.cachedCover : '';
  const source = data.exited ? '' : (localCover || (!state.offline && /^https?:\/\//i.test(state.cover || '') ? state.cover : ''));
  renderArtwork([source, !state.offline ? state.cover : '', state.metadata?.cover].filter(url => typeof url === 'string' && (/^https?:\/\//i.test(url) || /^\/api\/artwork\/[0-9a-f]{64}$/.test(url))));
  renderMetadata(state);
  renderDJ(data);
  const transcript = $('transcript');
  const output = data.output || 'Waiting for the player…';
  if (transcript.textContent !== output) {
    transcript.textContent = output;
    followTranscript();
  }
  $('cache-note').textContent = state.cachePending
    ? `Saving ${state.cachePending} song${state.cachePending === 1 ? '' : 's'} for offline…`
    : (state.offline ? 'Your saved songs. No connection needed.' : 'Songs save automatically when loaded.');
  renderActions(state.actions || []);
  renderStations();
  if (state.cacheDir && state.cacheDir !== libraryDirectory) {
    libraryDirectory = state.cacheDir;
    if (document.body.dataset.view === 'library') refreshLibrary();
  }
  renderLibrary();
  renderPrompt();
  updateDisabled();
}
function clearAudioQueue() {
  const retryIntro = !!djController && !djSpeaking && snapshot.djAnnouncement?.songKey === snapshot.state.songKey;
  if (djController || djSpeaking) stopDJ();
  if (retryIntro) djAnnouncement = Math.max(0, snapshot.djAnnouncement.id - 1);
  for (const source of audioSources) { try { source.stop(); } catch (_) {} }
  audioSources.clear();
  audioNextTime = 0;
}
function stopListening() {
  ++audioGeneration;
  if (audioController) audioController.abort();
  audioController = null;
  clearAudioQueue();
  // Keep the user-activated context alive so remote routing can resume playback.
  audioEpoch = null;
  browserStatus = 'idle';
  $('listen-button').textContent = 'Listen here';
  $('audio-status').textContent = '';
  stopDJ();
  if (browserId) browserRequest('heartbeat', {id: browserId, status: 'idle', ready: false}).catch(() => {});
  renderDJ(snapshot);
}
function queueAudio(data, rate, channels, little, epoch) {
  if (!audioContext || (snapshot.state.paused && !djKeepsMusic(snapshot))) return;
  if (epoch !== audioEpoch) { clearAudioQueue(); audioEpoch = epoch; }
  const frames = data.byteLength / (channels * 2);
  const buffer = audioContext.createBuffer(channels, frames, rate);
  const pcm = new DataView(data.buffer, data.byteOffset, data.byteLength);
  for (let channel = 0; channel < channels; ++channel) {
    const samples = buffer.getChannelData(channel);
    for (let frame = 0; frame < frames; ++frame) samples[frame] = pcm.getInt16((frame * channels + channel) * 2, little) / 32768;
  }
  const now = audioContext.currentTime;
  // Network reads arrive in bursts. Keep contiguous samples scheduled through
  // those bursts instead of destroying the queue whenever it exceeds a second.
  // Only resync truly stale audio (for example after a device wakes from sleep).
  if (audioNextTime > now + 8) {
    clearAudioQueue();
    ++audioStats.resyncs;
  }
  if (audioNextTime < now + .02) {
    if (audioNextTime) {
      ++audioStats.underruns;
      audioBufferSeconds = Math.min(2.5, audioBufferSeconds + .4);
    }
    audioNextTime = now + audioBufferSeconds;
  }
  const source = audioContext.createBufferSource();
  source.buffer = buffer;
  source.playbackStart = audioNextTime;
  source.playbackEnd = audioNextTime + buffer.duration;
  source.connect(audioGain);
  audioSources.add(source);
  source.onended = () => {
    audioSources.delete(source);
    source.disconnect();
    if (!audioSources.size && audioController) { browserStatus = 'waiting'; renderDJ(snapshot); }
  };
  source.start(audioNextTime);
  audioNextTime += buffer.duration;
  $('audio-status').textContent = 'Playing in this browser';
  browserStatus = 'playing';
  renderDJ(snapshot);
}
async function startListening() {
  if (audioController) return;
  const generation = audioGeneration;
  const Context = window.AudioContext || window.webkitAudioContext;
  if (!Context) throw new Error('This browser does not support audio playback.');
  if (!audioContext || audioContext.state === 'closed') {
    audioContext = new Context({latencyHint: 'playback'});
    audioGain = audioContext.createGain();
    audioGain.gain.value = browserVolume * (djSpeaking ? .2 : 1);
    audioGain.connect(audioContext.destination);
    audioContext.addEventListener('statechange', () => { if (audioContext.state !== 'running' && !djKeepsMusic(snapshot)) stopDJ(); });
  }
  // A remote request must not hang indefinitely on the browser's autoplay gate.
  await Promise.race([audioContext.resume(), new Promise(resolve => setTimeout(resolve, 500))]);
  if (audioContext.state !== 'running') {
    browserStatus = 'blocked';
    $('audio-status').textContent = 'Audio requested · click Listen here to allow playback';
    return;
  }
  await registerBrowser();
  if (audioController || browserLeaving || generation !== audioGeneration) return;
  const controller = audioController = new AbortController();
  if (controller.signal.aborted) return;
  browserStatus = 'waiting';
  $('listen-button').textContent = 'Stop listening';
  $('audio-status').textContent = 'Waiting for audio…';
  (async () => {
    const response = await fetch('/api/audio', {signal: controller.signal,
      headers: {'X-Pianobar-Client': browserId}});
    if (!response.ok) throw new Error('Could not start browser audio.');
    const reader = response.body.getReader();
    let pending = new Uint8Array(0);
    while (true) {
      const {value, done} = await reader.read();
      if (done) break;
      const joined = new Uint8Array(pending.length + value.length);
      joined.set(pending); joined.set(value, pending.length);
      pending = joined;
      let offset = 0;
      while (pending.length - offset >= 20) {
        const header = new DataView(pending.buffer, pending.byteOffset + offset, 20);
        const size = header.getUint32(0), rate = header.getUint32(4), channels = header.getUint32(8);
        if (size > 262124) throw new Error('Invalid audio frame.');
        if (pending.length - offset < size + 20) break;
        if (size) {
          if (channels < 1 || channels > 8 || rate < 8000 || rate > 192000 || size % (channels * 2)) throw new Error('Invalid audio format.');
          queueAudio(pending.subarray(offset + 20, offset + 20 + size), rate, channels, !!header.getUint32(12), header.getUint32(16));
        }
        offset += size + 20;
      }
      pending = pending.slice(offset);
    }
  })().catch(error => { if (!controller.signal.aborted) notify(error.message); })
    .finally(() => { if (audioController === controller) stopListening(); });
}
async function chooseOutput(output) {
  autoListenPending = false;
  desiredOutput = output;
  try {
    if (output === 'host') stopListening();
    else {
      // Start the AudioContext within the click gesture before any network awaits.
      const starting = startListening();
      await updateBrowserAudio(true);
      await starting;
    }
    if (output === 'host') await updateBrowserAudio(false);
    if (!await command({output})) desiredOutput = null;
  } catch (error) {
    desiredOutput = null;
    stopListening();
    notify(error.message);
  }
}
$('audio-output').addEventListener('change', event => chooseOutput(event.target.value));
$('listen-button').addEventListener('click', () => {
  if (audioController) {
    stopListening();
    updateBrowserAudio(false).catch(error => notify(error.message));
  }
  else chooseOutput(snapshot.state.output === 'browser' ? 'browser' : 'both');
});
window.addEventListener('pagehide', () => {
  browserLeaving = true;
  stopListening();
  if (audioContext) audioContext.close().catch(() => {});
  if (browserId) fetch('/api/clients/unregister', {method: 'POST', keepalive: true,
    headers: {'Content-Type': 'application/json'}, body: JSON.stringify({id: browserId})}).catch(() => {});
  browserId = null;
});
window.addEventListener('pageshow', event => {
  browserLeaving = false;
  if (event.persisted) autoListenPending = autoListen;
  pollBrowser();
});

function djHasAirtime(data = snapshot) {
  return !!(djAirtimeToken || djAirtimePending) && djAirtimeSong === data.state.songKey &&
    !!data.djAirtime?.clients.includes(browserId);
}
function djKeepsMusic(data) {
  return djHasAirtime(data) || (djAirtimeReleasing && djAirtimeSong === data.state.songKey);
}
async function releaseDJAirtime() {
  const token = djAirtimeToken, clientId = browserId;
  djAirtimeToken = null;
  djAirtimePending = false;
  if (!token) return;
  djAirtimeReleasing = true;
  try {
    const result = await djJSON('/api/dj/airtime', {operation: 'end', token, clientId});
    if (djAirtimeSong === snapshot.state.songKey && typeof result.paused === 'boolean') snapshot.state.paused = result.paused;
  } catch (_) { /* The server also releases abandoned airtime after 45 seconds. */ }
  finally {
    if (browserDesired && audioContext?.state === 'suspended') await audioContext.resume().catch(() => {});
    djAirtimeReleasing = false;
    djAirtimeSong = null;
  }
}
async function reserveDJAirtime(generation, songKey) {
  if (snapshot.djStation?.settings?.play_over_music !== false) return true;
  djAirtimePending = true;
  djAirtimeSong = songKey;
  try {
    await browserRequest('heartbeat', {id: browserId, status: 'playing', ready: true});
    const result = await djJSON('/api/dj/airtime', {operation: 'begin', songKey, clientId: browserId});
    if (generation !== djGeneration || !browserDesired || browserVolume <= 0 || songKey !== snapshot.state.songKey) {
      await djJSON('/api/dj/airtime', {operation: 'end', token: result.token, clientId: browserId});
      return false;
    }
    djAirtimeToken = result.token;
    djAirtimePending = false;
    snapshot.djAirtime = result.airtime;
    await audioContext.suspend();
    return generation === djGeneration;
  } catch (error) {
    djAirtimePending = false;
    if (!djAirtimeToken) djAirtimeSong = null;
    throw error;
  }
}
function djListening() {
  return djEnabled && browserDesired && browserVolume > 0 && !!audioController &&
    (audioContext?.state === 'running' || (audioContext?.state === 'suspended' && djHasAirtime())) &&
    (!snapshot.state.paused || djHasAirtime()) && snapshot.state.output !== 'host' &&
    !snapshot.exited && !snapshot.playerStopped && !snapshot.restarting && !snapshot.setupRequired;
}
function browserPlaybackStatus() {
  if (audioController && browserDesired && audioContext?.state === 'running' && !snapshot.state.paused) {
    const now = audioContext.currentTime;
    if ([...audioSources].some(source => source.playbackStart <= now && source.playbackEnd > now)) return 'playing';
  }
  return browserStatus === 'playing' ? 'waiting' : browserStatus;
}
function djCanSpeak() {
  if (!djListening() || voiceSampleController || voiceSampleAudio || djAirtimeReleasing) return false;
  if (djHasAirtime()) return true;
  if (browserStatus !== 'playing') return false;
  const now = audioContext.currentTime;
  return [...audioSources].some(source => source.playbackStart <= now && source.playbackEnd > now);
}
function duckDJ(speaking) {
  djSpeaking = speaking;
  if (audioGain) audioGain.gain.value = browserVolume * (speaking ? .2 : 1);
  $('dj-stop').disabled = !speaking && !djController;
}
function stopDJ() {
  ++djGeneration;
  if (djController) djController.abort();
  djController = null;
  if (djAudio) { djAudio.pause(); djAudio.removeAttribute('src'); djAudio = null; }
  if (djObjectURL) { URL.revokeObjectURL(djObjectURL); djObjectURL = null; }
  if (djUtterance) { speechSynthesis.cancel(); djUtterance = null; }
  duckDJ(false);
  releaseDJAirtime();
}
async function djJSON(path, message, signal) {
  const response = await fetch(path, message === undefined ? {signal} : {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(message), signal
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || 'The DJ could not prepare that line.');
  return data;
}
async function speakDJ(text, songKey) {
  stopDJ();
  if (!djCanSpeak() || songKey !== snapshot.state.songKey) return;
  const generation = djGeneration;
  $('dj-line').textContent = text;
  $('dj-line').hidden = false;
  $('dj-status').textContent = 'Preparing the voice…';
  if (djInfo.voiceReady) {
    const controller = djController = new AbortController();
    $('dj-stop').disabled = false;
    const timeout = setTimeout(() => controller.abort(), 35000);
    try {
      const response = await fetch('/api/voice', {method: 'POST',
        headers: {'Content-Type': 'application/json'}, body: JSON.stringify({text}), signal: controller.signal});
      if (!response.ok) { const data = await response.json(); throw new Error(data.error); }
      const blob = await response.blob();
      if (!djCanSpeak() || generation !== djGeneration || songKey !== snapshot.state.songKey) return;
      if (!await reserveDJAirtime(generation, songKey)) return;
      djObjectURL = URL.createObjectURL(blob);
      const audio = djAudio = new Audio(djObjectURL);
      audio.volume = browserVolume;
      audio.onended = () => { if (generation === djGeneration) { stopDJ(); $('dj-status').textContent = 'Listening for the next song.'; } };
      audio.onerror = () => { if (generation === djGeneration) { stopDJ(); $('dj-status').textContent = 'Could not play the DJ audio.'; } };
      duckDJ(true);
      await audio.play();
      $('dj-status').textContent = 'DJ on air';
    } catch (error) {
      if (generation === djGeneration) {
        stopDJ();
        $('dj-status').textContent = error.name === 'AbortError' ? 'Voice preparation timed out.' : error.message;
      }
    } finally {
      clearTimeout(timeout);
      if (djController === controller) djController = null;
    }
  } else if ('speechSynthesis' in window) {
    try { if (!await reserveDJAirtime(generation, songKey)) return; }
    catch (error) { stopDJ(); $('dj-status').textContent = error.message; return; }
    const utterance = djUtterance = new SpeechSynthesisUtterance(text);
    utterance.volume = browserVolume;
    const localVoice = speechSynthesis.getVoices().find(voice => voice.localService && voice.lang.startsWith('en'));
    if (localVoice) utterance.voice = localVoice;
    utterance.onend = () => {
      if (generation === djGeneration) { stopDJ(); $('dj-status').textContent = 'Listening for the next song.'; }
    };
    utterance.onerror = () => {
      if (generation === djGeneration) { stopDJ(); $('dj-status').textContent = 'Browser voice is unavailable. Configure a voice endpoint on the server.'; }
    };
    duckDJ(true);
    speechSynthesis.speak(utterance);
    $('dj-status').textContent = 'DJ on air · browser voice';
  } else $('dj-status').textContent = 'Configure a voice endpoint on the server to hear the DJ.';
}
async function introduceDJ() {
  if (!djCanSpeak() || !snapshot.state.title) return;
  stopDJ();
  const generation = djGeneration, songKey = snapshot.state.songKey;
  djGeneratedTrack = songKey;
  const controller = djController = new AbortController();
  $('dj-stop').disabled = false;
  $('dj-status').textContent = 'The DJ is writing an introduction…';
  const timeout = setTimeout(() => controller.abort(), 35000);
  try {
    await browserRequest('heartbeat', {id: browserId, status: browserPlaybackStatus(), ready: audioContext?.state === 'running'});
    const result = await djJSON('/api/dj', {style: $('dj-style').value}, controller.signal);
    if (djEnabled && generation === djGeneration && songKey === snapshot.state.songKey && result.songKey === songKey) {
      await speakDJ(result.text, result.songKey);
    }
  } catch (error) {
    if (generation === djGeneration) $('dj-status').textContent = error.name === 'AbortError' ? 'DJ preparation timed out.' : error.message;
  } finally {
    clearTimeout(timeout);
    if (djController === controller) { djController = null; $('dj-stop').disabled = !djSpeaking; }
  }
}
function renderDJ(data) {
  const state = data.state;
  const station = data.djStation || {};
  $('dj-station-enabled').checked = !!station.enabled;
  if (document.activeElement !== $('dj-theme') && !$('dj-theme').dataset.edited && station.theme) $('dj-theme').value = station.theme;
  if (document.activeElement !== $('dj-style') && !$('dj-style').dataset.edited && station.style) $('dj-style').value = station.style;
  const set = station.currentSet;
  const upcoming = station.next?.songs;
  $('dj-station-status').textContent = station.error || (station.enabled ? (station.status === 'waiting_listener' ? 'DJ is waiting for music to play in a listening browser.' :
    (upcoming ? `Next set: ${upcoming.length} songs · ${time(station.next.duration)} — ${upcoming.map(song => `${song.artist} — ${song.title}`).join('; ')}` :
      (set ? `Set ${set.position}/${set.songs.length} · ${time(set.duration)}${station.next ? ' · Up next: ' + station.next.artist + ' — ' + station.next.title : ''}` :
        (station.next ? `Up next: ${station.next.artist} — ${station.next.title}` : 'The DJ is choosing the next cached set…')))) : 'Cached-song DJ station is off.');
  if (state.songKey !== djTrack) {
    stopDJ();
    djTrack = state.songKey;
    $('dj-line').hidden = true;
  }
  if (!djEnabled) return;
  if (data.exited || data.playerStopped || data.restarting) { stopDJ(); djAnnouncement = 0; return; }
  const canSpeak = djCanSpeak();
  $('dj-introduce').disabled = !canSpeak || !djInfo.llmReady || !state.title;
  $('dj-say').disabled = !canSpeak;
  if (!canSpeak) {
    if (djSpeaking || djController) stopDJ();
    if (!djListening()) djAnnouncement = Math.max(djAnnouncement, data.djAnnouncement?.id || 0);
    $('dj-status').textContent = 'DJ is quiet until music plays in this browser.';
    return;
  }
  const announcement = data.djAnnouncement || {};
  if (announcement.id > djAnnouncement) {
    djAnnouncement = announcement.id;
    if (announcement.songKey === state.songKey) {
      djGeneratedTrack = state.songKey;
      speakDJ(announcement.text, announcement.songKey);
      return;
    }
  }
  if (!station.enabled && djInfo.llmReady && state.title && !state.paused && !data.pending && !data.prompt.active &&
      djGeneratedTrack !== state.songKey && (state.metadata?.status || state.elapsed > 3)) introduceDJ();
}
$('dj-enabled').addEventListener('change', async event => {
  djEnabled = event.target.checked;
  stopDJ();
  $('dj-say').disabled = !djEnabled;
  $('dj-introduce').disabled = true;
  djGeneratedTrack = '';
  djAnnouncement = snapshot.djAnnouncement?.id || 0;
  if (!djEnabled) { $('dj-status').textContent = 'DJ mode is off.'; return; }
  const generation = djGeneration;
  try {
    djInfo = await djJSON('/api/dj');
    if (!djEnabled || generation !== djGeneration) return;
    $('dj-status').textContent = djInfo.llmReady ? 'Listening for the next song.' :
      'Agent DJ ready. Supply a line below, or configure an LLM endpoint on the server for automatic introductions.';
    renderDJ(snapshot);
  } catch (error) { if (djEnabled) $('dj-status').textContent = error.message; }
});
for (const id of ['dj-theme', 'dj-style']) $(id).addEventListener('input', () => { $(id).dataset.edited = 'true'; });
$('dj-station-enabled').addEventListener('change', async event => {
  const enabled = event.target.checked;
  event.target.disabled = true;
  try {
    const station = await djJSON('/api/dj/control', {enabled, theme: $('dj-theme').value, style: $('dj-style').value});
    snapshot.djStation = station;
    if (enabled && !djEnabled) {
      $('dj-enabled').checked = true;
      $('dj-enabled').dispatchEvent(new Event('change'));
    }
    renderDJ(snapshot);
  } catch (error) { notify(error.message); event.target.checked = !enabled; }
  finally { event.target.disabled = false; }
});
$('dj-introduce').addEventListener('click', introduceDJ);
$('dj-stop').addEventListener('click', () => { stopDJ(); $('dj-status').textContent = 'Voice stopped.'; });
$('dj-line-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (!djCanSpeak()) return;
  try {
    await djJSON('/api/dj/announce', {text: $('dj-text').value, songKey: snapshot.state.songKey});
    $('dj-text').value = '';
  } catch (error) { $('dj-status').textContent = error.message; }
});
window.addEventListener('pagehide', stopDJ);

function renderMetadata(state) {
  const metadata = state.metadata || {};
  $('song-links').hidden = !state.title || !state.artist;
  $('youtube-search').href = 'https://www.youtube.com/results?search_query=' +
    encodeURIComponent([state.artist, state.title, 'official music video'].filter(Boolean).join(' '));
  const genres = (metadata.genres || []).join(', ');
  $('song-metadata').textContent = [genres && (metadata.genreScope === 'artist' ? `Artist genres: ${genres}` : genres), metadata.releaseDate].filter(Boolean).join(' · ');
  $('song-metadata').hidden = !$('song-metadata').textContent;
  const recordingId = metadata.recordingId || '';
  $('metadata-source').hidden = !/^[0-9a-f-]{36}$/.test(recordingId);
  if (!$('metadata-source').hidden) $('metadata-source').href = 'https://musicbrainz.org/recording/' + recordingId;
}

function followTranscript() {
  const transcript = $('transcript');
  transcript.scrollTop = transcript.scrollHeight;
}
new ResizeObserver(followTranscript).observe($('transcript'));
function renderArtwork(sources) {
  sources = [...new Set(sources)];
  if (JSON.stringify(sources) === JSON.stringify(coverCandidates)) return;
  coverCandidates = sources;
  coverCandidate = 0;
  const source = sources[0] || '';
  clearTimeout(coverRetry);
  coverSource = source;
  coverAttempt = 0;
  loadArtwork();
}
function loadArtwork() {
  $('favicon').href = '/favicon.svg';
  for (const id of ['cover', 'nav-cover']) {
    const image = $(id);
    image.hidden = true;
    if (coverSource) {
      // Bypass a failed cached response on bounded retries. Cached art itself
      // stays on this authenticated origin, including the favicon and thumbnail.
      const url = new URL(coverSource, location.href);
      if (coverAttempt) url.searchParams.set('retry', coverAttempt);
      image.src = url.pathname.startsWith('/api/artwork/') && url.origin === location.origin
        ? url.pathname + url.search : url.href;
    } else image.removeAttribute('src');
  }
  $('nav-player-icon').hidden = false;
}
$('cover').addEventListener('load', () => {
  $('cover').hidden = false;
  $('favicon').href = $('cover').getAttribute('src');
  clearTimeout(coverRetry);
});
$('cover').addEventListener('error', () => {
  $('cover').hidden = true;
  $('favicon').href = '/favicon.svg';
  clearTimeout(coverRetry);
  if (coverCandidate + 1 < coverCandidates.length) {
    coverSource = coverCandidates[++coverCandidate];
    coverAttempt = 0;
    loadArtwork();
  } else if (coverSource && coverAttempt < 3) {
    ++coverAttempt;
    coverRetry = setTimeout(loadArtwork, 1000 * 2 ** coverAttempt);
  }
});
$('nav-cover').addEventListener('load', () => {
  $('nav-cover').hidden = false;
  $('nav-player-icon').hidden = true;
});
$('nav-cover').addEventListener('error', () => {
  $('nav-cover').hidden = true;
  $('nav-player-icon').hidden = false;
});
document.addEventListener('click', event => {
  const button = event.target.closest('[data-action]');
  if (!button || button.disabled) return;
  command({action: button.dataset.action});
});
document.addEventListener('keydown', event => {
  if (event.defaultPrevented || event.isComposing || event.ctrlKey || event.altKey || event.metaKey ||
      snapshot.prompt.active || $('prompt-dialog').open) return;
  const target = event.target;
  if (target instanceof Element &&
      (target.closest('input, textarea, select, [role="textbox"]') || target.isContentEditable)) return;
  // Native dispatch uses the first matching configured key, including case and punctuation.
  const action = (snapshot.state.actions || []).find(action => action.key === event.key);
  if (!action?.enabled) return;
  event.preventDefault();
  if (event.repeat || sending || snapshot.pending || snapshot.exited) return;
  command({action: action.id});
});
$('prompt-form').addEventListener('submit', event => {
  event.preventDefault();
  const answer = $('answer').value;
  command({text: answer, promptId: snapshot.prompt.id});
  if (snapshot.prompt.secret) $('answer').value = '';
});
$('prompt-dialog').addEventListener('cancel', event => {
  event.preventDefault();
  if (snapshot.prompt.kind === 'delete_station') {
    command({text: 'n', promptId: snapshot.prompt.id});
  } else {
    notify('Submit an empty answer to return or accept the default.');
  }
});
let settingsBaseline = null;
let voiceChoicesGeneration = 0;
function renderVoiceChoices(voices, selected, defaultVoice = '') {
  const select = $('setting-dj-voice');
  const choices = [new Option(defaultVoice ? `Server default (${defaultVoice})` : 'Use the voice server’s default', '')];
  for (const voice of voices) choices.push(new Option(voice, voice));
  if (selected && !voices.includes(selected)) choices.push(new Option(`Configured voice: ${selected}`, selected));
  select.replaceChildren(...choices);
  select.value = selected;
}
async function loadVoiceChoices() {
  const generation = ++voiceChoicesGeneration;
  $('refresh-dj-voices').disabled = true;
  $('dj-voices-status').textContent = 'Loading voices from the active voice server…';
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch('/api/dj/voices', {signal: controller.signal});
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Could not load available voices.');
    if (generation !== voiceChoicesGeneration) return;
    renderVoiceChoices(data.voices, $('setting-dj-voice').value, data.default);
    $('dj-voices-status').textContent = data.configured ?
      `${data.voices.length} voices available.${data.default ? ' Server default: ' + data.default + '.' : ''} Save & restart player after changing the endpoint.` :
      'Configure a voice endpoint to choose its voices. Without one, DJ mode uses the browser’s default voice.';
  } catch (error) {
    if (generation === voiceChoicesGeneration) $('dj-voices-status').textContent = error.name === 'AbortError' ?
      'Voice list timed out. Your selected voice is preserved.' : error.message;
  } finally {
    clearTimeout(timeout);
    if (generation === voiceChoicesGeneration) $('refresh-dj-voices').disabled = false;
  }
}
$('refresh-dj-voices').addEventListener('click', loadVoiceChoices);
function stopVoiceSample() {
  if (voiceSampleController) voiceSampleController.abort();
  voiceSampleController = null;
  if (voiceSampleAudio) { voiceSampleAudio.pause(); voiceSampleAudio.removeAttribute('src'); voiceSampleAudio = null; }
  if (voiceSampleURL) URL.revokeObjectURL(voiceSampleURL);
  voiceSampleURL = null;
  $('sample-dj-voice').textContent = 'Sample voice';
}
$('sample-dj-voice').addEventListener('click', async () => {
  if (voiceSampleController || voiceSampleAudio) { stopVoiceSample(); $('voice-sample-status').textContent = 'Sample stopped.'; return; }
  stopDJ();
  const controller = voiceSampleController = new AbortController();
  const voice = $('setting-dj-voice').value;
  $('sample-dj-voice').textContent = 'Stop sample';
  $('voice-sample-status').textContent = 'Preparing the selected voice…';
  const timeout = setTimeout(() => controller.abort(), 35000);
  try {
    const response = await fetch('/api/voice', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({text: 'Coming at ya with some London punk. This is your DJ, keeping the good music coming.', voice}), signal: controller.signal});
    if (!response.ok) { const data = await response.json(); throw new Error(data.error || 'Could not prepare the voice sample.'); }
    const blob = await response.blob();
    if (voiceSampleController !== controller) return;
    voiceSampleURL = URL.createObjectURL(blob);
    const audio = voiceSampleAudio = new Audio(voiceSampleURL);
    audio.onended = () => { if (voiceSampleAudio === audio) { stopVoiceSample(); $('voice-sample-status').textContent = 'Sample finished. Save settings to use this voice immediately.'; } };
    audio.onerror = () => { if (voiceSampleAudio === audio) { stopVoiceSample(); $('voice-sample-status').textContent = 'Could not play the voice sample.'; } };
    await audio.play();
    if (voiceSampleAudio === audio) $('voice-sample-status').textContent = `Playing ${voice || 'the server default voice'}.`;
  } catch (error) {
    if (voiceSampleController === controller) { stopVoiceSample(); $('voice-sample-status').textContent = error.name === 'AbortError' ? 'Voice sample timed out. Try again.' : error.message; }
  } finally { clearTimeout(timeout); }
});
$('setting-dj-voice').addEventListener('change', () => { stopVoiceSample(); $('voice-sample-status').textContent = 'Preview this voice, or save settings to apply it immediately.'; });
window.addEventListener('pagehide', stopVoiceSample);

const djSettingsKeys = ['llm_url', 'model', 'style', 'theme', 'tts_url', 'voice', 'tts_ca', 'metadata_network', 'set_mode', 'set_songs', 'set_minutes', 'play_over_music', 'dj_name', 'listener_name'];
const settingsKeys = ['user', 'cache_dir', 'cache_songs', 'offline', 'offline_fallback', 'audio_quality', 'audio_buffer_ms'];
async function loadSettings(force = false) {
  if (settingsBusy || (settingsLoaded && !force)) return;
  settingsBusy = true;
  updateDisabled();
  try {
    const response = await fetch('/api/settings');
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Could not load settings.');
    settingsBaseline = data;
    for (const key of settingsKeys) {
      const input = $('setting-' + key);
      if (input.type === 'checkbox') input.checked = data[key];
      else input.value = data[key];
    }
    for (const key of ['listen', 'port', 'output']) $('setting-' + key).value = data.web[key];
    renderVoiceChoices([], data.dj.voice || '');
    for (const key of djSettingsKeys) {
      const input = $('setting-dj-' + key);
      if (input.type === 'checkbox') input.checked = !!data.dj[key];
      else input.value = data.dj[key] || '';
      input.disabled = data.dj.overrides.includes(key);
    }
    for (const key of ['llm_key', 'tts_key']) {
      $('setting-dj-' + key).value = '';
      $('setting-dj-' + key).disabled = data.dj.overrides.includes(key);
      $('setting-dj-clear_' + key).checked = false;
      $('setting-dj-clear_' + key).disabled = data.dj.overrides.includes(key);
    }
    $('dj-key-note').textContent = `${data.dj.llm_keySet ? 'LLM key saved.' : 'No LLM key saved.'} ${data.dj.tts_keySet ? 'Voice key saved.' : 'No voice key saved.'} Leave key fields blank to keep existing keys.`;
    $('dj-environment-note').hidden = !data.dj.overrides.length;
    $('dj-environment-note').textContent = 'Set by server environment: ' + data.dj.overrides.join(', ') + '. Edit those environment variables on the server to change these fields.';
    $('setting-password').value = '';
    $('setting-web-password').value = '';
    $('setting-clearPassword').checked = false;
    $('account-password-note').textContent = data.passwordCommand
      ? 'Your existing password command is configured. A new password replaces it.'
      : (data.passwordSet ? 'Password saved. Leave blank to keep it.' : 'Your password is stored in your private pianobar config file.');
    $('web-password-note').textContent = data.web.passwordSet
      ? 'Web password saved. Leave blank to keep it.'
      : 'Set a reusable password, or leave blank to generate one at each network-enabled launch.';
    settingsLoaded = true;
    loadVoiceChoices();
  } catch (error) { $('settings-status').textContent = error.message; }
  finally { settingsBusy = false; updateDisabled(); }
}
$('settings-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (settingsBusy || !settingsLoaded) return;
  const apply = event.submitter?.value === 'apply';
  const changes = {};
  for (const key of settingsKeys) {
    const input = $('setting-' + key);
    const value = input.type === 'checkbox' ? input.checked : (input.type === 'number' ? Number(input.value) : input.value);
    if (value !== settingsBaseline[key]) changes[key] = value;
  }
  if ($('setting-password').value) changes.password = $('setting-password').value;
  if ($('setting-clearPassword').checked) changes.clearPassword = true;
  const web = {};
  for (const key of ['listen', 'port', 'output']) {
    const value = key === 'port' ? Number($('setting-port').value) : $('setting-' + key).value;
    if (value !== settingsBaseline.web[key]) web[key] = value;
  }
  if ($('setting-web-password').value) web.password = $('setting-web-password').value;
  if (Object.keys(web).length) changes.web = web;
  const dj = {};
  for (const key of djSettingsKeys) {
    const input = $('setting-dj-' + key);
    const value = input.type === 'checkbox' ? input.checked : (input.type === 'number' ? Number(input.value) : input.value);
    if (!input.disabled && value !== settingsBaseline.dj[key]) dj[key] = value;
  }
  for (const key of ['llm_key', 'tts_key']) {
    if ($('setting-dj-' + key).value) dj[key] = $('setting-dj-' + key).value;
    if ($('setting-dj-clear_' + key).checked) dj['clear_' + key] = true;
  }
  if (Object.keys(dj).length) changes.dj = dj;
  settingsBusy = true;
  updateDisabled();
  let saved = false;
  try {
    const response = await fetch('/api/settings', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({settings: changes, apply})});
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Could not save settings.');
    saved = true;
    $('setting-password').value = '';
    $('setting-web-password').value = '';
    $('setting-dj-llm_key').value = '';
    $('setting-dj-tts_key').value = '';
    $('settings-status').textContent = apply
      ? 'Saved. Starting the player… Web address and password changes apply after restarting pianobar.'
      : 'Saved. Names, voice and intro playback are active now; set sizing applies to the next selection. Restart the player for account, listening, provider and metadata changes; restart pianobar for web access changes.';
  } catch (error) { $('settings-status').textContent = error.message; }
  finally {
    settingsBusy = false;
    updateDisabled();
    if (saved) loadSettings(true);
  }
});

async function watch() {
  while (true) {
    try {
      const response = await fetch(`/api/state?after=${revision}`);
      const data = await response.json();
      if (!response.ok) {
        notify(data.error || 'Cannot connect to the player.');
        $('connection').textContent = 'Connection rejected';
        return;
      }
      revision = data.revision;
      render(data);
      if (data.exited) return;
    } catch (error) {
      $('connection').textContent = 'Player disconnected';
      snapshot.exited = true;
      updateDisabled();
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }
}
watch();
setInterval(() => {
  if (!snapshot.exited && document.body.dataset.view === 'library') refreshLibrary();
}, 3000);
