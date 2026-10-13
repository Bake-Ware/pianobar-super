'use strict';
const form = document.getElementById('login');
const error = document.getElementById('error');
const submit = document.getElementById('submit');
form.addEventListener('submit', async event => {
  event.preventDefault();
  submit.disabled = true;
  error.textContent = '';
  try {
    const response = await fetch('/api/login', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({password: document.getElementById('password').value})});
    if (response.ok) {
      location.replace('/');
      return;
    }
    const data = await response.json().catch(() => ({}));
    error.textContent = data.error || 'Could not sign in.';
  } catch (_) {
    error.textContent = 'Could not reach pianobar.';
  } finally {
    submit.disabled = false;
  }
});
