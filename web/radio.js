'use strict';
// The public radio: shows what's playing and plays the shared MP3 stream.
// Nothing here can change the player.
const $ = id => document.getElementById(id);
const audio = $('audio');
let state = {}, stateAt = 0, wanted = false, retry;

try { audio.volume = Number(localStorage.getItem('pianobarRadioVolume') ?? 1); } catch (_) {}
$('volume').value = audio.volume;
$('volume').addEventListener('input', event => {
  audio.volume = Number(event.target.value);
  try { localStorage.setItem('pianobarRadioVolume', audio.volume); } catch (_) {}
});

function start() {
  clearTimeout(retry);
  $('error').textContent = '';
  // A fresh URL each time, so the browser never replays a stale buffer.
  audio.src = '/radio/stream?' + Date.now();
  audio.play().catch(error => {
    if (error.name === 'NotAllowedError') stop();
  });
  render();
}
function stop() {
  wanted = false;
  clearTimeout(retry);
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  render();
}
$('play').addEventListener('click', () => {
  if (wanted) { stop(); return; }
  wanted = true;
  start();
});
audio.addEventListener('playing', render);
audio.addEventListener('waiting', render);
audio.addEventListener('error', () => {
  if (!wanted) return;
  $('error').textContent = state.full ? 'The radio is full right now. Trying again…' : 'Lost the stream. Reconnecting…';
  retry = setTimeout(start, 3000);
});
audio.addEventListener('ended', () => { if (wanted) retry = setTimeout(start, 1000); });

function render() {
  const live = !!state.live;
  $('status').textContent = !live ? 'Off air' : state.paused ? 'Paused' :
    'Live' + (state.listeners ? ` · ${state.listeners} listening` : '');
  $('status').classList.toggle('on', live && !state.paused);
  $('title').textContent = live ? state.title || '' : 'Nothing playing right now';
  $('artist').textContent = live ? [state.artist, state.album].filter(Boolean).join(' · ') : 'Check back soon.';
  document.title = live && state.title ? `${state.title} · pianobar radio` : 'pianobar radio';
  const cover = live ? state.cover || '' : '';
  if ($('cover').dataset.src !== cover) {
    $('cover').dataset.src = cover;
    $('cover').hidden = true;
    if (cover) $('cover').src = cover; else $('cover').removeAttribute('src');
  }
  const playing = wanted && !audio.paused && audio.readyState >= 3;
  $('play').textContent = !wanted ? '▶ Listen' : playing ? '■ Stop' : 'Connecting… (stop)';
  updateProgress();
}
$('cover').addEventListener('load', () => { $('cover').hidden = false; });
$('cover').addEventListener('error', () => { $('cover').hidden = true; });

function updateProgress() {
  let elapsed = state.elapsed || 0;
  if (state.live && !state.paused) elapsed += (performance.now() - stateAt) / 1000;
  const fraction = state.live && state.duration > 0 ? Math.min(1, elapsed / state.duration) : 0;
  $('progress').style.width = (fraction * 100).toFixed(2) + '%';
}
setInterval(updateProgress, 1000);

async function poll() {
  try {
    const response = await fetch('/api/radio', {cache: 'no-store'});
    if (response.ok) {
      state = await response.json();
      stateAt = performance.now();
    }
  } catch (_) {}
  render();
  setTimeout(poll, document.hidden ? 15000 : 5000);
}
poll();
