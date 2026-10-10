const STYLE = `
:root { --bg:#0f1115; --panel:#181b22; --line:#2a2f3a; --text:#eef0f4; --muted:#9aa3b2; --accent:#e50914; }
* { box-sizing:border-box; }
html, body { margin:0; }
body { background:var(--bg); color:var(--text); font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
.wrap { max-width:960px; margin:0 auto; padding:20px 16px 48px; }
header { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:20px; }
h1 { font-size:22px; margin:0; }
h1 span { color:var(--accent); }
.hidden { display:none !important; }
input { width:100%; padding:12px 14px; border-radius:10px; border:1px solid var(--line); background:var(--panel); color:var(--text); font:inherit; }
input:focus { outline:2px solid var(--accent); outline-offset:1px; }
button { padding:12px 18px; border-radius:10px; border:0; background:var(--accent); color:#fff; font:inherit; font-weight:600; cursor:pointer; }
button.quiet { background:transparent; border:1px solid var(--line); color:var(--text); padding:8px 14px; font-weight:500; }
button:disabled { opacity:.6; cursor:default; }
.login { max-width:380px; margin:12vh auto 0; background:var(--panel); border:1px solid var(--line); border-radius:14px; padding:24px; }
.login h2 { margin:0 0 6px; font-size:19px; }
.login p { margin:0 0 16px; color:var(--muted); font-size:14px; }
.login form { display:grid; gap:12px; }
.login .link { justify-self:start; background:transparent; color:var(--muted); padding:0; font-size:14px; font-weight:500; text-decoration:underline; }
.note { color:var(--muted); font-size:14px; min-height:20px; }
.actions { display:flex; gap:8px; }
.field { position:relative; }
.field input { padding-right:76px; }
.field button { position:absolute; right:6px; top:50%; transform:translateY(-50%); background:transparent; color:var(--muted); padding:6px 10px; font-size:14px; font-weight:500; }
.error { color:#ff7b72; font-size:14px; min-height:20px; }
.bar { display:flex; gap:12px; align-items:center; margin-bottom:12px; }
.count { color:var(--muted); font-size:14px; white-space:nowrap; }
table { width:100%; border-collapse:collapse; background:var(--panel); border:1px solid var(--line); border-radius:12px; overflow:hidden; }
th, td { text-align:left; padding:12px 14px; border-bottom:1px solid var(--line); }
th { color:var(--muted); font-size:13px; font-weight:600; }
tr:last-child td { border-bottom:0; }
td a { color:var(--text); text-decoration:none; }
td a:hover { text-decoration:underline; }
.muted { color:var(--muted); }
.tag { font-size:12px; padding:2px 8px; border-radius:999px; border:1px solid var(--line); color:var(--muted); margin-left:8px; }
.more { margin-top:16px; text-align:center; }
.empty { padding:28px; text-align:center; color:var(--muted); }
@media (max-width:640px) {
  thead { display:none; }
  table, tbody, tr, td { display:block; width:100%; }
  table { background:transparent; border:0; }
  tr { background:var(--panel); border:1px solid var(--line); border-radius:12px; margin-bottom:10px; padding:6px 0; }
  td { border:0; padding:5px 14px; }
  td.name { font-weight:600; font-size:17px; }
  td[data-label]::before { content:attr(data-label) ": "; color:var(--muted); font-size:13px; }
  .bar { flex-wrap:wrap; }
}
`;

const SCRIPT = `
(function () {
  var KEY = 'dekho_admin_token';
  var API = '/api/v1/admin';
  var LIMIT = 50;
  var state = { page: 1, q: '', loaded: 0, total: 0 };
  var $ = function (id) { return document.getElementById(id); };

  function token() { try { return sessionStorage.getItem(KEY); } catch (e) { return null; } }
  function setToken(value) {
    try { if (value) sessionStorage.setItem(KEY, value); else sessionStorage.removeItem(KEY); } catch (e) {}
    memory = value;
  }
  var memory = token();

  var PASSWORDS = ['password', 'newPassword', 'currentPassword', 'changedPassword'];
  var MIN_PASSWORD = 8;

  // view: 'login', 'forgot' (signed out) or 'users', 'change' (signed in).
  function show(view) {
    var signedIn = view === 'users' || view === 'change';
    ['login', 'forgot', 'users', 'change'].forEach(function (id) {
      $(id).classList.toggle('hidden', id !== view);
    });
    $('account').classList.toggle('hidden', !signedIn);
    PASSWORDS.forEach(function (id) { $(id).value = ''; reveal(id, false); });
    $('code').value = '';
    ['loginError', 'forgotError', 'changeError', 'forgotNote'].forEach(function (id) { $(id).textContent = ''; });
    if (view === 'login') { $('phone').focus(); }
    if (view === 'forgot') { step(false); $('forgotPhone').value = $('phone').value; $('forgotPhone').focus(); }
    if (view === 'change') { $('currentPassword').focus(); }
  }

  function reveal(id, visible) {
    var button = document.querySelector('[data-peek="' + id + '"]');
    $(id).type = visible ? 'text' : 'password';
    button.textContent = visible ? 'Hide' : 'Show';
    button.setAttribute('aria-pressed', visible ? 'true' : 'false');
  }

  // The forgot card has two steps: ask for the code, then type it with the new password.
  function step(codeSent) {
    $('forgotForm').classList.toggle('hidden', codeSent);
    $('resetForm').classList.toggle('hidden', !codeSent);
  }

  function post(path, body, auth) {
    var headers = { 'Content-Type': 'application/json' };
    if (auth) headers.Authorization = 'Bearer ' + memory;
    return fetch(API + path, { method: 'POST', headers: headers, body: JSON.stringify(body) })
      .then(function (res) {
        if (res.status === 204) return { ok: true, status: 204, body: {} };
        return res.json().then(function (data) { return { ok: res.ok, status: res.status, body: data }; });
      });
  }

  function message(result, fallback) {
    return (result.body.error && result.body.error.message) || fallback;
  }

  // Runs one form submission: disables its button, shows the failure in its error line.
  function submit(formId, errorId, run) {
    $(formId).addEventListener('submit', function (event) {
      event.preventDefault();
      var button = $(formId).querySelector('button[type="submit"]');
      button.disabled = true;
      $(errorId).textContent = '';
      Promise.resolve()
        .then(run)
        .then(function (problem) { if (problem) $(errorId).textContent = problem; })
        .catch(function () { $(errorId).textContent = 'Could not reach the server. Try again.'; })
        .then(function () { button.disabled = false; });
    });
  }

  function tooShort(id) {
    return $(id).value.trim().length < MIN_PASSWORD
      ? 'The new password needs at least ' + MIN_PASSWORD + ' characters.'
      : '';
  }

  function enter(result) {
    setToken(result.body.token);
    show('users');
    state.page = 1;
    return load(false);
  }

  function when(iso) {
    if (!iso) return '—';
    var d = new Date(iso);
    return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) +
      ', ' + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }

  function cell(row, label, text, className) {
    var td = document.createElement('td');
    if (label) td.setAttribute('data-label', label);
    if (className) td.className = className;
    td.textContent = text;
    row.appendChild(td);
    return td;
  }

  function render(items, append) {
    var body = $('rows');
    if (!append) body.textContent = '';
    items.forEach(function (user) {
      var tr = document.createElement('tr');
      var name = cell(tr, '', user.name, 'name');
      if (user.status !== 'ACTIVE') {
        var tag = document.createElement('span');
        tag.className = 'tag';
        tag.textContent = user.status.toLowerCase().replace('_', ' ');
        name.appendChild(tag);
      }
      var phone = cell(tr, 'Mobile', '');
      var link = document.createElement('a');
      link.href = 'tel:' + user.phone;
      link.textContent = user.phone;
      phone.appendChild(link);
      cell(tr, 'Joined', when(user.createdAt), 'muted');
      cell(tr, 'Last sign-in', when(user.lastLoginAt), 'muted');
      body.appendChild(tr);
    });
    $('empty').classList.toggle('hidden', state.total !== 0);
    $('table').classList.toggle('hidden', state.total === 0);
    $('count').textContent = state.total + (state.total === 1 ? ' user' : ' users');
    $('more').classList.toggle('hidden', state.loaded >= state.total);
  }

  function load(append) {
    var url = API + '/users?page=' + state.page + '&limit=' + LIMIT +
      (state.q ? '&q=' + encodeURIComponent(state.q) : '');
    $('listError').textContent = '';
    return fetch(url, { headers: { Authorization: 'Bearer ' + memory } })
      .then(function (res) {
        if (res.status === 401) { setToken(null); show('login'); $('loginError').textContent = 'Please sign in again.'; return null; }
        if (!res.ok) throw new Error('failed');
        return res.json();
      })
      .then(function (data) {
        if (!data) return;
        state.total = data.total;
        state.loaded = (append ? state.loaded : 0) + data.items.length;
        render(data.items, append);
      })
      .catch(function () { $('listError').textContent = 'Could not load users. Check your connection and try again.'; });
  }

  submit('loginForm', 'loginError', function () {
    return post('/login', { phone: $('phone').value.trim(), password: $('password').value.trim() })
      .then(function (result) {
        if (!result.ok) return message(result, 'Could not sign in.');
        return enter(result);
      });
  });

  submit('forgotForm', 'forgotError', function () {
    return post('/forgot', { phone: $('forgotPhone').value.trim() }).then(function (result) {
      if (!result.ok) return message(result, 'Could not send the code.');
      step(true);
      $('forgotNote').textContent = 'If that is the admin number, a 6-digit code is on its way to the admin email. It is valid for 10 minutes.';
      $('code').focus();
    });
  });

  submit('resetForm', 'forgotError', function () {
    var problem = tooShort('newPassword');
    if (problem) return problem;
    return post('/reset', {
      phone: $('forgotPhone').value.trim(),
      code: $('code').value.trim(),
      newPassword: $('newPassword').value.trim()
    }).then(function (result) {
      if (!result.ok) return message(result, 'Could not set the new password.');
      return enter(result);
    });
  });

  submit('changeForm', 'changeError', function () {
    var problem = tooShort('changedPassword');
    if (problem) return problem;
    return post('/password', {
      currentPassword: $('currentPassword').value.trim(),
      newPassword: $('changedPassword').value.trim()
    }, true).then(function (result) {
      if (result.status === 401 && result.body.error && result.body.error.code === 'UNAUTHENTICATED') {
        setToken(null); show('login'); $('loginError').textContent = 'Please sign in again.'; return;
      }
      if (!result.ok) return message(result, 'Could not change the password.');
      setToken(result.body.token);
      show('users');
      $('listError').textContent = '';
      return load(false);
    });
  });

  Array.prototype.forEach.call(document.querySelectorAll('[data-peek]'), function (button) {
    button.addEventListener('click', function () {
      var id = button.getAttribute('data-peek');
      reveal(id, $(id).type === 'password');
      $(id).focus();
    });
  });

  $('forgotLink').addEventListener('click', function () { show('forgot'); });
  $('backToLogin').addEventListener('click', function () { show('login'); });
  $('backToLogin2').addEventListener('click', function () { show('login'); });
  $('changeLink').addEventListener('click', function () { show('change'); });
  $('cancelChange').addEventListener('click', function () { show('users'); });

  var timer;
  $('search').addEventListener('input', function () {
    clearTimeout(timer);
    timer = setTimeout(function () {
      state.q = $('search').value.trim();
      state.page = 1;
      load(false);
    }, 300);
  });

  $('moreButton').addEventListener('click', function () { state.page += 1; load(true); });
  $('signout').addEventListener('click', function () { setToken(null); show('login'); });

  if (memory) { show('users'); load(false); } else { show('login'); }
})();
`;

/** The whole admin page: one self-contained document, allowed to run only through the nonce. */
export function renderAdminPage(nonce: string): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Dekho Admin</title>
<style nonce="${nonce}">${STYLE}</style>
</head>
<body>
<div class="wrap">
  <header>
    <h1><span>Dekho</span> Admin</h1>
    <div id="account" class="actions hidden">
      <button id="changeLink" class="quiet" type="button">Change password</button>
      <button id="signout" class="quiet" type="button">Sign out</button>
    </div>
  </header>

  <section id="login" class="login hidden">
    <h2>Sign in</h2>
    <p>Enter the admin mobile number and password.</p>
    <form id="loginForm">
      <input id="phone" type="tel" inputmode="tel" autocomplete="username" placeholder="Mobile number" required>
      <div class="field">
        <input id="password" type="password" autocomplete="current-password" placeholder="Password" required>
        <button type="button" data-peek="password" aria-pressed="false">Show</button>
      </div>
      <button id="loginButton" type="submit">Sign in</button>
      <button id="forgotLink" class="link" type="button">Forgot password?</button>
      <div id="loginError" class="error" role="alert"></div>
    </form>
  </section>

  <section id="forgot" class="login hidden">
    <h2>Forgot password</h2>
    <p>A code is sent to the admin email. Enter it here with a new password.</p>
    <form id="forgotForm">
      <input id="forgotPhone" type="tel" inputmode="tel" autocomplete="username" placeholder="Mobile number" required>
      <button type="submit">Send code</button>
      <button id="backToLogin" class="link" type="button">Back to sign in</button>
    </form>
    <form id="resetForm" class="hidden">
      <div id="forgotNote" class="note"></div>
      <input id="code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="6-digit code" required>
      <div class="field">
        <input id="newPassword" type="password" autocomplete="new-password" placeholder="New password" required>
        <button type="button" data-peek="newPassword" aria-pressed="false">Show</button>
      </div>
      <button type="submit">Set new password</button>
      <button id="backToLogin2" class="link" type="button">Back to sign in</button>
    </form>
    <div id="forgotError" class="error" role="alert"></div>
  </section>

  <section id="change" class="login hidden">
    <h2>Change password</h2>
    <p>Other signed-in admin sessions are signed out when the password changes.</p>
    <form id="changeForm">
      <div class="field">
        <input id="currentPassword" type="password" autocomplete="current-password" placeholder="Current password" required>
        <button type="button" data-peek="currentPassword" aria-pressed="false">Show</button>
      </div>
      <div class="field">
        <input id="changedPassword" type="password" autocomplete="new-password" placeholder="New password" required>
        <button type="button" data-peek="changedPassword" aria-pressed="false">Show</button>
      </div>
      <button type="submit">Change password</button>
      <button id="cancelChange" class="link" type="button">Cancel</button>
      <div id="changeError" class="error" role="alert"></div>
    </form>
  </section>

  <section id="users" class="hidden">
    <div class="bar">
      <input id="search" type="search" placeholder="Search by name or mobile number" autocomplete="off">
      <span id="count" class="count"></span>
    </div>
    <div id="listError" class="error" role="alert"></div>
    <table id="table">
      <thead><tr><th>Name</th><th>Mobile</th><th>Joined</th><th>Last sign-in</th></tr></thead>
      <tbody id="rows"></tbody>
    </table>
    <div id="empty" class="empty hidden">No users found.</div>
    <div id="more" class="more hidden"><button id="moreButton" class="quiet" type="button">Show more</button></div>
  </section>
</div>
<script nonce="${nonce}">${SCRIPT}</script>
</body>
</html>`;
}
