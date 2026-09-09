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
const views = {player: 'Now playing', stations: 'Stations', library: 'Library', settings: 'Settings'};
function showView(view, focus = false) {
  if (!views[view]) view = 'player';
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
let failedCoverSource = '';
let settingsLoaded = false, settingsBusy = false, setupShown = false;
const audioSources = new Set();
async function browserRequest(operation, data) {
  const response = await fetch('/api/clients/' + operation, {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || 'Could not contact browser controls.');
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
    const page = await browserRequest('heartbeat', {id: browserId, status: browserStatus,
      ready: audioContext?.state === 'running'});
    if (mutation !== browserMutation) return;
    browserDesired = page.enabled;
    browserVolume = page.volume;
    if (document.activeElement !== $('browser-name')) $('browser-name').value = page.name;
    if (audioGain) audioGain.gain.value = browserVolume;
    if (!browserDesired) stopListening();
    else if (!audioController && !snapshot.playerStopped && !snapshot.restarting && snapshot.state.output !== 'host') {
      await startListening();
    }
  } catch (error) {
    // Fail silent on a lost control connection, and obtain a fresh lease next time.
    stopListening();
    browserId = null;
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
    const artwork = document.createElement('span');
    artwork.className = 'saved-artwork';
    artwork.setAttribute('aria-hidden', 'true');
    artwork.textContent = '♫';
    if (/^\/api\/artwork\/[0-9a-f]{64}$/.test(song.cover || '')) {
      const image = document.createElement('img');
      image.alt = '';
      image.loading = 'lazy';
      image.src = song.cover;
      image.addEventListener('error', () => image.remove());
      artwork.append(image);
    }
    row.append(artwork, info, duration, play);
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
  if (state.paused) clearAudioQueue();
  $('elapsed').textContent = time(state.elapsed);
  $('duration').textContent = time(state.duration);
  $('progress').max = Math.max(1, state.duration || 0);
  $('progress').value = Math.min(state.elapsed || 0, state.duration || 0);
  const love = document.querySelector('[data-action="act_songlove"]');
  love.classList.toggle('loved', !!state.loved);
  love.setAttribute('aria-pressed', String(!!state.loved));
  const localCover = /^\/api\/artwork\/[0-9a-f]{64}$/.test(state.cachedCover || '') ? state.cachedCover : '';
  const source = data.exited ? '' : (localCover || (!state.offline && /^https?:\/\//i.test(state.cover || '') ? state.cover : ''));
  const favicon = source && source !== failedCoverSource ? source : '/favicon.svg';
  if ($('favicon').getAttribute('href') !== favicon) $('favicon').href = favicon;
  for (const id of ['cover', 'nav-cover']) {
    const cover = $(id);
    if (source !== (cover.getAttribute('src') || '')) {
      cover.hidden = true;
      if (id === 'nav-cover') $('nav-player-icon').hidden = false;
      if (source) cover.src = source;
      else cover.removeAttribute('src');
    }
  }
  const transcript = $('transcript');
  transcript.textContent = data.output || 'Waiting for the player…';
  followTranscript();
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
}
function queueAudio(data, rate, channels, little, epoch) {
  if (!audioContext || snapshot.state.paused) return;
  if (epoch !== audioEpoch) { clearAudioQueue(); audioEpoch = epoch; }
  const frames = data.byteLength / (channels * 2);
  const buffer = audioContext.createBuffer(channels, frames, rate);
  const pcm = new DataView(data.buffer, data.byteOffset, data.byteLength);
  for (let channel = 0; channel < channels; ++channel) {
    const samples = buffer.getChannelData(channel);
    for (let frame = 0; frame < frames; ++frame) samples[frame] = pcm.getInt16((frame * channels + channel) * 2, little) / 32768;
  }
  if (audioNextTime > audioContext.currentTime + 1) clearAudioQueue();
  if (audioNextTime < audioContext.currentTime) audioNextTime = audioContext.currentTime + .15;
  const source = audioContext.createBufferSource();
  source.buffer = buffer;
  source.connect(audioGain);
  audioSources.add(source);
  source.onended = () => {
    audioSources.delete(source);
    if (!audioSources.size && audioController) browserStatus = 'waiting';
  };
  source.start(audioNextTime);
  audioNextTime += buffer.duration;
  $('audio-status').textContent = 'Playing in this browser';
  browserStatus = 'playing';
}
async function startListening() {
  if (audioController) return;
  const generation = audioGeneration;
  const Context = window.AudioContext || window.webkitAudioContext;
  if (!Context) throw new Error('This browser does not support audio playback.');
  if (!audioContext || audioContext.state === 'closed') {
    audioContext = new Context();
    audioGain = audioContext.createGain();
    audioGain.gain.value = browserVolume;
    audioGain.connect(audioContext.destination);
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
window.addEventListener('pageshow', () => { browserLeaving = false; pollBrowser(); });

function followTranscript() {
  const transcript = $('transcript');
  transcript.scrollTop = transcript.scrollHeight;
}
new ResizeObserver(followTranscript).observe($('transcript'));
$('cover').addEventListener('load', () => { $('cover').hidden = false; });
$('cover').addEventListener('error', () => {
  $('cover').hidden = true;
  failedCoverSource = $('cover').getAttribute('src') || '';
  $('favicon').href = '/favicon.svg';
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
    $('settings-status').textContent = apply
      ? 'Saved. Starting the player… Web address and password changes apply after restarting pianobar.'
      : 'Saved. Restart the player to apply account and listening changes; restart pianobar for web access changes.';
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
