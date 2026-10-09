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

  function show(signedIn) {
    $('login').classList.toggle('hidden', signedIn);
    $('users').classList.toggle('hidden', !signedIn);
    $('signout').classList.toggle('hidden', !signedIn);
    if (!signedIn) { $('password').value = ''; $('password').focus(); }
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
        if (res.status === 401) { setToken(null); show(false); $('loginError').textContent = 'Please sign in again.'; return null; }
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

  $('loginForm').addEventListener('submit', function (event) {
    event.preventDefault();
    var button = $('loginButton');
    button.disabled = true;
    $('loginError').textContent = '';
    fetch(API + '/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: $('password').value })
    })
      .then(function (res) { return res.json().then(function (body) { return { ok: res.ok, body: body }; }); })
      .then(function (result) {
        if (!result.ok) {
          $('loginError').textContent = (result.body.error && result.body.error.message) || 'Could not sign in.';
          return;
        }
        setToken(result.body.token);
        show(true);
        state.page = 1;
        return load(false);
      })
      .catch(function () { $('loginError').textContent = 'Could not reach the server. Try again.'; })
      .then(function () { button.disabled = false; });
  });

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
  $('signout').addEventListener('click', function () { setToken(null); show(false); });

  if (memory) { show(true); load(false); } else { show(false); }
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
    <button id="signout" class="quiet hidden" type="button">Sign out</button>
  </header>

  <section id="login" class="login hidden">
    <h2>Sign in</h2>
    <p>Enter the admin password to see the users.</p>
    <form id="loginForm">
      <input id="password" type="password" autocomplete="current-password" placeholder="Admin password" required>
      <button id="loginButton" type="submit">Sign in</button>
      <div id="loginError" class="error" role="alert"></div>
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
