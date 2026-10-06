'use strict';
// Compact player widget: album art and transport controls for the desktop
// shell and any browser. Talks the same state/command/audio APIs as app.js.
const $ = (id) => document.getElementById(id);
const shell = typeof window.chrome?.webview?.postMessage === 'function';
function shellPost(message) {
  try { window.chrome.webview.postMessage(message); } catch (_) { }
}

// Theme (shared with the full player)
function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
}
let savedTheme;
try { savedTheme = localStorage.getItem('pianobarTheme'); } catch (_) { /* Storage can be disabled. */ }
applyTheme(['light', 'dark'].includes(savedTheme) ? savedTheme :
  (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
window.addEventListener('storage', event => {
  if (event.key === 'pianobarTheme' && ['dark', 'light'].includes(event.newValue)) applyTheme(event.newValue);
});

let snapshot = {state: {}, prompt: {}}, revision = -1;
let sending = false;
function setStatus(text) {
  $('widget-status').textContent = text;
}
function time(seconds) {
  seconds = Math.max(0, Math.floor(seconds || 0));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
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
  } catch (error) {
    setStatus(error.message);
    return false;
  } finally {
    sending = false;
    updateDisabled();
  }
}

function isActionEnabled(id) {
  const action = snapshot.state.actions?.find(a => a.id === id);
  return action?.enabled && !snapshot.pending && !snapshot.exited && !snapshot.restarting && !snapshot.prompt.active;
}
function updateDisabled() {
  for (const button of document.querySelectorAll('[data-action]')) {
    button.disabled = !isActionEnabled(button.dataset.action);
  }
}

// ── Browser client lease and audio pipeline ────────────────────────────────
let audioContext, audioController, audioGain, audioNextTime = 0, audioEpoch = null;
let browserId = null, browserRegistration, browserDesired = false, browserStatus = 'idle';
let browserVolume = 1, browserPollBusy = false, browserMutation = 0, audioGeneration = 0;
let audioBufferSeconds = .9;
const audioSources = new Set();
let autoListenPending = true; // the widget exists to listen

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
    const result = await browserRequest('register', {name: shell ? 'Desktop widget' :
      'Widget · ' + (navigator.platform || 'Browser')});
    browserId = result.id;
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
  if (browserPollBusy) return;
  browserPollBusy = true;
  const mutation = browserMutation;
  try {
    await registerBrowser();
    const page = await browserRequest('heartbeat', {id: browserId, status: browserStatus,
      ready: audioContext?.state === 'running'});
    if (mutation !== browserMutation) return;
    browserDesired = page.enabled;
    browserVolume = page.volume;
    if (autoListenPending && revision >= 0 && ['host', 'browser', 'both'].includes(snapshot.state.output) &&
        !snapshot.playerStopped && !snapshot.setupRequired &&
        !snapshot.restarting && !snapshot.exited && !snapshot.pending && !snapshot.prompt.active && !sending) {
      // Join once on page load. Subsequent remote routing remains authoritative.
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
    if ([400, 401, 403, 404].includes(error.status)) stopListening();
  } finally { browserPollBusy = false; }
}
setInterval(pollBrowser, 2000);
queueMicrotask(pollBrowser);
function clearAudioQueue() {
  for (const source of audioSources) { try { source.stop(); } catch (_) { } }
  audioSources.clear();
  audioNextTime = 0;
}
function stopListening() {
  ++audioGeneration;
  if (audioController) audioController.abort();
  audioController = null;
  clearAudioQueue();
  audioEpoch = null;
  browserStatus = 'idle';
  setStatus('');
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
  const now = audioContext.currentTime;
  if (audioNextTime > now + 8) clearAudioQueue();
  if (audioNextTime < now + .02) {
    if (audioNextTime) audioBufferSeconds = Math.min(2.5, audioBufferSeconds + .4);
    audioNextTime = now + audioBufferSeconds;
  }
  const source = audioContext.createBufferSource();
  source.buffer = buffer;
  source.connect(audioGain);
  audioSources.add(source);
  source.onended = () => {
    audioSources.delete(source);
    source.disconnect();
    if (!audioSources.size && audioController) browserStatus = 'waiting';
  };
  source.start(audioNextTime);
  audioNextTime += buffer.duration;
  browserStatus = 'playing';
}
async function startListening() {
  if (audioController) return;
  const generation = audioGeneration;
  const Context = window.AudioContext || window.webkitAudioContext;
  if (!Context) { setStatus('This browser does not support audio playback.'); return; }
  if (!audioContext || audioContext.state === 'closed') {
    audioContext = new Context({latencyHint: 'playback'});
    audioGain = audioContext.createGain();
    audioGain.gain.value = browserVolume;
    audioGain.connect(audioContext.destination);
  }
  await Promise.race([audioContext.resume(), new Promise(resolve => setTimeout(resolve, 500))]);
  if (audioContext.state !== 'running') {
    // The desktop shell allows autoplay; a plain browser needs one tap.
    browserStatus = 'blocked';
    setStatus('Tap to play');
    return;
  }
  await registerBrowser();
  if (audioController || generation !== audioGeneration) return;
  const controller = audioController = new AbortController();
  if (controller.signal.aborted) return;
  browserStatus = 'waiting';
  setStatus('Waiting for audio…');
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
  })().catch(error => { if (!controller.signal.aborted) setStatus(error.message); })
    .finally(() => { if (audioController === controller) stopListening(); });
}
document.addEventListener('pointerdown', () => {
  if (browserStatus === 'blocked' && browserDesired) startListening();
});

// ── State polling and rendering ────────────────────────────────────────────
function renderArtwork() {
  const state = snapshot.state;
  const source = state.offline ? '' : (state.cachedCover || state.cover || '');
  const cover = $('widget-cover');
  if (!source) { cover.hidden = true; return; }
  const load = (url) => {
    cover.onerror = () => {
      if (!state.offline && state.cover && url.startsWith('/api/artwork/')) load(state.cover);
    };
    cover.onload = () => {
      cover.hidden = false;
      $('favicon').href = url;
    };
    cover.src = url;
  };
  load(source);
}
function render() {
  const state = snapshot.state;
  $('widget-station').textContent = state.station || 'No station selected';
  $('widget-source').textContent = state.offline ? 'FROM YOUR OFFLINE LIBRARY' : 'YOUR PERSONAL RADIO';
  $('widget-title').textContent = state.title || 'Something good is next.';
  $('widget-artist').textContent = state.artist || '';
  $('widget-play').textContent = state.paused ? '▶' : 'Ⅱ';
  $('widget-play').setAttribute('aria-label', state.paused ? 'Resume playback' : 'Pause playback');
  $('widget-love').classList.toggle('loved', !!state.loved);
  $('widget-love').setAttribute('aria-pressed', String(!!state.loved));
  $('widget-volume').textContent = `${state.volume} dB`;
  $('widget-progress').value = state.duration ? (state.elapsed || 0) / state.duration : 0;
  $('widget-progress').max = Math.max(1, state.duration || 1);
  document.title = state.title ? 'pianobar ' + [state.station, state.title].filter(Boolean).join(' / ') : 'pianobar widget';
  document.body.classList.toggle('widget-playing', !state.paused && !!state.title && !snapshot.exited);
  renderArtwork();
  updateDisabled();
  if ((snapshot.prompt.active || snapshot.state.setupRequired) && !browserDesired) {
    if (shell) shellPost({type: 'openFull'});
    else setStatus('The player needs input — open the full player.');
  }
}
async function watch() {
  while (true) {
    try {
      const response = await fetch(`/api/state?after=${revision}`);
      const data = await response.json();
      if (!response.ok) { setStatus(data.error || 'Connection rejected'); return; }
      revision = data.revision;
      snapshot = data;
      render();
      if (data.exited) { setStatus('Player stopped'); return; }
    } catch (_) {
      setStatus('Disconnected · retrying…');
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
  }
}

// ── Transport and keys ─────────────────────────────────────────────────────
document.addEventListener('click', event => {
  const button = event.target.closest('[data-action]');
  if (button && !button.disabled) command({action: button.dataset.action});
});
const keyActions = {' ': 'act_songpausetoggle', 'n': 'act_songnext', '(': 'act_voldown', ')': 'act_volup'};
document.addEventListener('keydown', event => {
  if (event.ctrlKey || event.metaKey || event.altKey || event.repeat) return;
  const id = keyActions[event.key];
  if (id && isActionEnabled(id)) command({action: id});
});

// ── Desktop shell bridge (pin = always on top, expand = full player) ──────
function setPinned(pinned) {
  document.body.dataset.pinned = String(pinned);
  $('widget-pin').setAttribute('aria-pressed', String(pinned));
}
if (shell) {
  $('widget-pin').hidden = false;
  $('widget-expand').hidden = false;
  window.chrome.webview.addEventListener('message', event => {
    const message = event.data;
    if (message && message.type === 'shellState') setPinned(!!message.topmost);
  });
  $('widget-pin').addEventListener('click', event => {
    event.stopPropagation();
    shellPost({type: 'topmost', value: document.body.dataset.pinned !== 'true'});
  });
  $('widget-expand').addEventListener('click', () => shellPost({type: 'openFull'}));
}

watch();
