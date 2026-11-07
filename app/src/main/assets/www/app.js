'use strict';
/* Tidyo UI controller. Screens are the design's markup; this file only feeds them real data. */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const num = (n) => Number(n).toLocaleString('en-US');
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* ignore */ } },
};

/* ---------- bridge ---------- */
const pending = new Map();
let seq = 0;
const listeners = {};
window.__cb = (id, ok, payload) => {
  const p = pending.get(id); pending.delete(id);
  if (!p) return;
  const data = JSON.parse(payload);
  if (ok) p.res(data); else p.rej(Object.assign(new Error(data.message || 'error'), { kind: data.kind }));
};
window.__emit = (name, payload) => { const f = listeners[name]; if (f) f(JSON.parse(payload)); };
const on = (name, f) => { listeners[name] = f; };
const call = (method, args = {}) => new Promise((res, rej) => {
  const id = ++seq; pending.set(id, { res, rej });
  window.Android.call(id, method, JSON.stringify(args));
});

/* ---------- screens ---------- */
const NAMES = ['setup', 'scan', 'home', 'plan', 'rules', 'done', 'trash', 'log'];
const BARS = { setup: ['#F1ECE1', false], scan: ['#2437E8', true], home: ['#F1ECE1', false], plan: ['#2437E8', true], rules: ['#F1ECE1', false], done: ['#FFD23F', false], trash: ['#F1ECE1', false], log: ['#F1ECE1', false] };
let current = null;
function go(name) {
  $$('.screen').forEach((s) => s.classList.remove('active'));
  const el = $('#s-' + name);
  el.classList.add('active'); // display:none -> block restarts the entry animations
  current = name;
  const [bg, light] = BARS[name];
  call('bar', { bg, light }).catch(() => {});
}
function toast(msg, ms = 2800) {
  const t = $('#toast'); t.textContent = msg; t.classList.add('on');
  clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('on'), ms);
}
function modal({ title, text, yes, no }) {
  return new Promise((resolve) => {
    $('#modal-t').textContent = title; $('#modal-p').textContent = text;
    const y = $('#modal-yes'), n = $('#modal-no');
    y.textContent = yes; n.textContent = no || ''; n.style.display = no ? '' : 'none';
    const close = (v) => { $('#modal').classList.remove('on'); y.onclick = n.onclick = null; resolve(v); };
    y.onclick = () => close(true); n.onclick = () => close(false);
    $('#modal').classList.add('on');
  });
}
function countUp(el, target, delay = 0, dur = 1200) {
  if (reduced) { el.textContent = num(target); return; }
  el.textContent = '0';
  const start = performance.now() + delay;
  const ease = (t) => 1 - Math.pow(1 - t, 3);
  (function tick(now) {
    const t = Math.min(1, Math.max(0, (now - start) / dur));
    el.textContent = num(Math.round(target * ease(t)));
    if (t < 1) requestAnimationFrame(tick);
  })(performance.now());
}
const sizeParts = (b) => (b >= 1e9 ? [(b / 1e9).toFixed(1), 'GB'] : b >= 1048576 ? [String(Math.round(b / 1048576)), 'MB'] : [String(Math.max(0, Math.round(b / 1024))), 'KB']);
const sizeText = (b) => sizeParts(b).join(' ');
const dateText = (ms) => new Date(ms).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });

/* ---------- error handling ---------- */
function explain(e) {
  switch (e.kind) {
    case 'NETWORK': return 'No internet. Tidyo needs it to reach your AI provider.';
    case 'QUOTA': return (e.message && e.message.length < 140 ? e.message + ' ' : '') + 'Wait a minute, or check the credit on your key.';
    case 'BAD_KEY': return 'Your AI provider stopped accepting the key.';
    default: return 'Something went wrong: ' + (e.message || '').slice(0, 120);
  }
}

/* ---------- state ---------- */
const S = { provider: '', stats: null, plan: null, level: store.get('level', 1), mode: 'scan', names: [], t0: 0 };
const DEFAULT_RULES = [
  { id: 1, text: 'screenshots older than 30 days → trash', instruction: 'Move screenshots older than 30 days to trash.', enabled: true },
  { id: 2, text: 'sort downloads by type', instruction: "Sort files in the Download folder into folders by type (Documents, Pictures, Videos, Audio, Archives, Apps).", enabled: true },
  { id: 3, text: 'chat media after 60 days → archive', instruction: "Move chat app media (WhatsApp, Telegram) older than 60 days into an 'Archive/Chat media' folder.", enabled: false },
];
S.rules = store.get('rules', DEFAULT_RULES);
const saveRules = () => store.set('rules', S.rules);

/* ---------- setup ---------- */
function bindSetup() {
  const root = $('#s-setup'), input = $('#key'), btn = $('#btn-check'), err = $('#key-error');
  const label = $('span', btn);
  async function submit() {
    const key = input.value.trim();
    if (!key) return fail('PASTE A KEY FIRST.');
    btn.disabled = true; label.textContent = 'checking…';
    try {
      const v = await call('validateKey', { key });
      S.provider = v.provider; toast('Connected to ' + v.provider + ' · ' + v.model, 2500);
      input.value = ''; root.classList.remove('failed'); input.removeAttribute('aria-invalid');
      await startAfterKey();
    } catch (e) {
      fail({ NETWORK: 'NO INTERNET. TRY AGAIN.', QUOTA: 'KEY OK, BUT RATE-LIMITED. WAIT A MINUTE.', OTHER: 'COULDN\'T CHECK IT. TRY AGAIN.' }[e.kind] || "NOPE. THAT KEY DIDN'T WORK.");
    } finally { btn.disabled = false; label.textContent = 'check key'; }
  }
  function fail(msg) {
    err.textContent = msg; input.setAttribute('aria-invalid', 'true'); input.setAttribute('aria-describedby', 'key-error');
    root.classList.remove('failed'); void root.offsetWidth; root.classList.add('failed');
  }
  btn.onclick = submit;
  input.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') submit(); });
  $('#lnk-key').onclick = (ev) => { ev.preventDefault(); call('openUrl', { url: 'https://aistudio.google.com/apikey' }); };
}

async function waitForStorage() {
  while (!(await call('storageGranted')).granted) {
    await modal({ title: 'let me in.', text: 'Tidyo needs "all files access" to sort, rename and delete files. Tap the button, switch it on for Tidyo, then come back.', yes: 'open settings' });
    await call('requestStorage');
    await new Promise((resolve) => { on('resume', () => { delete listeners.resume; resolve(); }); });
  }
}
async function startAfterKey() { await waitForStorage(); await runScan(); }

/* ---------- scan / plan / apply progress (one screen, four moods) ---------- */
const MODES = {
  scan: { state: 'SCANNING', note: 'Reading file names and dates on this phone. Nothing leaves it in this step.', pause: 'pause scan' },
  plan: { state: 'THINKING', note: "Paced to fit your key's rate limit, so big folders take a few extra minutes.", pause: 'pause' },
  apply: { state: 'TIDYING', note: 'Moving files carefully. Anything deleted waits in trash for 30 days.', pause: null },
};
function setProgress(p, n, ofText, stickers) {
  const blocks = $$('#scan-blocks > div');
  const filled = Math.min(blocks.length, Math.floor(p * blocks.length));
  blocks.forEach((b, i) => { b.style.background = i < filled ? '#FFD23F' : '#2437E8'; });
  $('#scan-blocks').setAttribute('aria-label', Math.round(p * 100) + ' percent');
  $('#scan-n').textContent = num(n);
  $('#scan-of').textContent = ofText;
  if (stickers) stickers.forEach((t, i) => { $('#stk' + (i + 1)).textContent = t; });
  if (p > 0.04) {
    const left = ((performance.now() - S.t0) * (1 - p)) / p / 1000;
    $('#scan-eta').textContent = left < 60 ? 'ETA ' + Math.max(5, Math.round(left / 5) * 5) + ' SEC' : 'ETA ' + Math.ceil(left / 60) + ' MIN';
  }
}
function enterProgress(mode) {
  S.mode = mode; S.t0 = performance.now();
  const m = MODES[mode];
  $('#scan-state').textContent = m.state; $('#scan-note').textContent = m.note;
  $('#scan-eta').textContent = 'ETA …';
  const pb = $('#btn-pause'); pb.style.visibility = m.pause ? 'visible' : 'hidden'; pb.textContent = m.pause || '';
  $('#s-scan').classList.remove('paused');
  setProgress(0, 0, mode === 'scan' ? 'counting files…' : 'warming up…', ['…', '…', '…']);
  go('scan');
}
function bindProgress() {
  let paused = false;
  $('#btn-pause').onclick = async () => {
    paused = !paused; await call('pause', { on: paused });
    $('#s-scan').classList.toggle('paused', paused);
    $('#btn-pause').textContent = paused ? 'resume' : MODES[S.mode].pause;
  };
  $$('#s-scan [data-card]').forEach((c) => {
    c.addEventListener('animationiteration', () => { if (S.names.length) c.textContent = S.names[Math.floor(Math.random() * S.names.length)]; });
    c.textContent = ['IMG_20260412_0933.jpg', 'scan0007.pdf', 'Screenshot_2025-09-14.png', 'IMG_0933 (copy).jpg'][$$('#s-scan [data-card]').indexOf(c)] || c.textContent;
  });
  const remember = (n) => { if (n) { S.names.push(n); if (S.names.length > 24) S.names.shift(); } };
  on('scan', (d) => {
    remember(d.name);
    if (d.phase === 'walk') setProgress(0, d.n, 'counting files…', ['finding files', 'walking folders', 'reading names…']);
    else if (d.phase === 'read') setProgress((d.n / Math.max(1, d.total)) * 0.7, d.n, 'of ' + num(d.total) + ' files read', [num(d.total) + ' files', 'reading sizes', 'reading dates…']);
    else setProgress(0.7 + 0.3 * (d.n / Math.max(1, d.total)), S.lastTotal || 0, 'checking for twins…', ['hunting twins', 'comparing files', 'hashing…']);
    if (d.phase === 'read') S.lastTotal = d.total;
  });
  on('plan', (d) => setProgress(d.total ? d.n / d.total : 1, d.n, 'of ' + num(d.total) + ' files looked at', [num(d.n) + ' looked at', 'asking ' + (S.provider || 'AI'), d.callsLeft + ' requests left…']));
  on('apply', (d) => setProgress(d.total ? d.n / d.total : 1, d.n, 'of ' + num(d.total) + ' changes made', [num(d.n) + ' done', 'making folders', 'tidying…']));
}

async function runScan() {
  enterProgress('scan');
  try {
    S.stats = await call('scan');
    renderHome(); go('home');
  } catch (e) {
    if (e.kind === 'CANCELLED') return;
    toast(explain(e), 4500); go('home');
  }
}

/* ---------- home ---------- */
function renderHome() {
  const s = S.stats;
  $('#h-mess').textContent = s.mess;
  $('#h-dup-t').textContent = s.dupPhotos ? 'Duplicate photos' : 'Duplicate files';
  const [dv, du] = sizeParts(s.dupBytes);
  $('#h-dup-v').textContent = dv; $('#h-dup-u').textContent = ' ' + du;
  $('#h-dup-s').textContent = s.dupCount ? num(s.dupCount) + ' FILES · SAFE TO DELETE' : 'NO TWINS FOUND';
  const [sv, su] = sizeParts(s.shotBytes); $('#h-ss-v').textContent = sv; $('#h-ss-u').textContent = su;
  const [cv, cu] = sizeParts(s.chatBytes); $('#h-chat-v').textContent = cv; $('#h-chat-u').textContent = cu;
  $('#h-uns').textContent = num(s.unsorted); $('#h-bad').textContent = num(s.badNames);
  setLevel(S.level);
}
function setLevel(i) {
  S.level = i; store.set('level', i);
  $('#hl').style.transform = 'translateX(' + i * 100 + '%)';
  $$('#s-home [data-lv]').forEach((b) => b.setAttribute('aria-pressed', String(Number(b.dataset.lv) === i)));
}
function bindHome() {
  $$('#s-home [data-lv]').forEach((b) => { b.onclick = () => setLevel(Number(b.dataset.lv)); });
  $('#btn-plan').onclick = makePlan;
  $('#btn-settings').onclick = async () => {
    try { const st = await call('status'); $('#set-p').textContent = 'Connected to ' + st.provider + ' (' + st.model + '). Your key is stored encrypted on this phone.'; } catch (e) { /* ignore */ }
    $('#settings').classList.add('on');
  };
  $('#set-close').onclick = () => $('#settings').classList.remove('on');
  $('#set-key').onclick = async () => { $('#settings').classList.remove('on'); await call('clearKey'); go('setup'); };
  const q = $('#q');
  const doSearch = async () => {
    const text = q.value.trim(); if (!text) return;
    try {
      const r = await call('search', { q: text });
      const list = $('#sr-list'); list.textContent = '';
      if (!r.items.length) list.innerHTML = '<div class="empty">nothing matched. try fewer words.</div>';
      r.items.forEach((f) => {
        const d = document.createElement('div'); d.className = 'item';
        d.innerHTML = '<div class="t">' + esc(f.name) + '<small>' + esc(f.path) + ' · ' + sizeText(f.size) + ' · ' + dateText(f.mtime) + '</small></div><button type="button">open</button>';
        $('button', d).onclick = () => call('open', { path: f.abs });
        list.appendChild(d);
      });
      $('#search').classList.add('on');
    } catch (e) { toast(explain(e)); }
  };
  q.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') { q.blur(); doSearch(); } });
  $('.pulse', $('#s-home')).onclick = () => q.focus();
  $('#sr-close').onclick = () => $('#search').classList.remove('on');
}

/* ---------- plan ---------- */
async function makePlan() {
  enterProgress('plan');
  try {
    const rules = S.rules.filter((r) => r.enabled).map((r) => r.instruction);
    S.plan = await call('plan', { rules });
  } catch (e) {
    if (e.kind === 'CANCELLED') { go('home'); return; }
    go('home');
    if (e.kind === 'BAD_KEY') { await modal({ title: 'key trouble.', text: explain(e), yes: 'enter a new key' }); await call('clearKey'); go('setup'); }
    else toast(explain(e), 5000);
    return;
  }
  if (!S.plan.items.length) { toast(S.plan.partial ? explain({ kind: 'QUOTA', message: S.plan.partial }) : "nothing to tidy. you're clean.", 5000); go('home'); return; }
  if (S.plan.partial) toast('Stopped early (' + String(S.plan.partial).slice(0, 80) + '). Here is what I found so far.', 6000);
  const hasDelete = S.plan.items.some((i) => i.kind === 'DELETE');
  if (S.level === 2 || (S.level === 1 && !hasDelete)) return applyPlan(S.plan.items.map((i) => i.id));
  renderPlan(); go('plan');
}
const GROUPS = ['MOVE', 'RENAME', 'DELETE'];
function groupHtml(kind, items) {
  let lines = '';
  if (kind === 'MOVE') {
    const by = {}; items.forEach((i) => { by[i.toFolder] = (by[i.toFolder] || 0) + 1; });
    const top = Object.entries(by).sort((a, b) => b[1] - a[1]);
    lines = top.slice(0, 2).map(([f, n]) => num(n) + ' file' + (n > 1 ? 's' : '') + ' → ' + esc(f)).join('<br>');
    if (top.length > 2) lines += '<br>and ' + (top.length - 2) + ' more folders';
  } else if (kind === 'RENAME') {
    lines = items.slice(0, 2).map((i) => '<span style="text-decoration: line-through">' + esc(i.from.split('/').pop()) + '</span><br>→ ' + esc(i.newName)).join('<br>');
  } else {
    const bytes = items.reduce((a, i) => a + i.size, 0);
    lines = 'dupes + junk · ' + esc(sizeText(bytes)) + '<br>sits in trash 30 days first';
  }
  return '<div class="grp" data-kind="' + kind + '" style="display: flex; flex-direction: column; gap: 4px">' +
    '<label style="display: flex; align-items: center; gap: 10px; min-height: 40px; font-size: 15px; font-weight: 700">' +
    '<input type="checkbox" checked="checked" style="width: 22px; height: 22px; accent-color: #2437E8; margin: 0">' +
    '<span>' + kind + '</span><span style="flex: 1; border-bottom: 2px dotted #0E0E0E; margin-bottom: 4px"></span>' +
    '<span style="font-family: \'Syne\', sans-serif; font-size: 22px; font-weight: 800">' + num(items.length) + '</span></label>' +
    '<div style="padding-left: 32px; font-size: 12px; line-height: 1.5; color: #4A463E">' + lines + '</div></div>';
}
function selectedIds() {
  const ids = [];
  $$('#plan-groups .grp').forEach((g) => { if ($('input', g).checked) S.plan.items.filter((i) => i.kind === g.dataset.kind).forEach((i) => ids.push(i.id)); });
  return ids;
}
function renderPlan() {
  const p = S.plan;
  $('#plan-hdr').textContent = 'PLAN #' + String(p.planId).padStart(4, '0') + ' · NOT APPLIED YET';
  $('#plan-date').textContent = new Date().toLocaleDateString('en-US', { month: 'short', day: '2-digit', year: 'numeric' }).replace(',', '').toUpperCase() + ' · ' + num(p.filesRead) + ' FILES READ';
  $('#plan-groups').innerHTML = GROUPS.map((k) => { const it = p.items.filter((i) => i.kind === k); return it.length ? groupHtml(k, it) : ''; }).join('');
  const total = selectedIds().length;
  $('#plan-n').textContent = num(total);
  countUp($('#plan-total'), total, 2000, 1400);
  $$('#plan-groups input').forEach((c) => { c.onchange = () => { const n = selectedIds().length; $('#plan-n').textContent = num(n); $('#plan-total').textContent = num(n); $('#btn-apply').disabled = n === 0; }; });
  $('#btn-apply').disabled = false;
}
function bindPlan() {
  $('#btn-back').onclick = () => go('home');
  $('#btn-apply').onclick = () => applyPlan(selectedIds());
}

/* ---------- apply + done ---------- */
async function applyPlan(ids) {
  if (!ids.length) return;
  enterProgress('apply');
  try {
    const r = await call('apply', { ids });
    S.result = r; renderDone(r); go('done');
  } catch (e) { go('home'); toast(explain(e), 5000); }
}
function renderDone(r) {
  $('#done-stamp').textContent = num(r.done) + ' / ' + num(r.attempted) + ' DONE · ' + r.seconds + ' SEC';
  $$('#s-done [data-fly]').forEach((el, i) => { const n = r.examples[i]; el.style.display = n ? '' : 'none'; if (n) el.textContent = n.replace(/\.[^.]+$/, '').slice(0, 14); });
  $$('#s-done [data-row]').forEach((el, i) => {
    const t = r.top[i]; el.style.display = t ? 'flex' : 'none';
    if (t) { const sp = $$('span', el); sp[0].textContent = t.name; sp[1].textContent = t.count; }
  });
  const mb = Math.round(r.freed / 1048576);
  countUp($('#done-a'), mb, 1200, 1600); countUp($('#done-b'), r.newFolders, 1300, 1600); countUp($('#done-c'), r.deleted, 1400, 1600);
  $('#done-until').textContent = r.until;
  const labels = $$('#s-done .pop span:last-child'); if (labels[0]) labels[0].textContent = 'MB CLEARED';
  $('#btn-undo').disabled = false;
}
function bindDone() {
  $('#btn-done').onclick = runScan;
  $('#btn-undo').onclick = async () => {
    $('#btn-undo').disabled = true;
    try { const r = await call('undo', { id: S.result.sessionId }); toast('Put ' + num(r.n) + ' files back.'); await runScan(); }
    catch (e) { toast(explain(e)); }
  };
}

/* ---------- rules ---------- */
const CHIP_STYLE = ['background: #FFD23F', 'background: #FFFFFF', 'background: #FF4D2E', 'background: #0E0E0E; color: #FFD23F'];
function renderRules() {
  const list = $('#rule-list'); list.textContent = '';
  S.rules.forEach((r, i) => {
    const row = document.createElement('div'); row.className = 'rule-row';
    row.style.cssText = 'animation-delay: ' + (0.1 + i * 0.1) + 's; display: flex; align-items: center; gap: 8px; border: 3px solid #0E0E0E; padding: 4px 8px 4px 12px; min-height: 54px; background: ' + (r.enabled ? '#FFFFFF' : '#E4DFD2');
    row.innerHTML = '<div style="flex: 1; font-size: 15px; font-weight: 700; line-height: 1.25;' + (r.enabled ? '' : ' color: #4A463E') + '">' + esc(r.text) + '</div>' +
      '<label style="display: flex; align-items: center; justify-content: center; min-width: 44px; min-height: 44px"><span style="position: absolute; left: -9999px">Enabled</span><input type="checkbox" role="switch" ' + (r.enabled ? 'checked="checked" ' : '') + 'style="width: 26px; height: 26px; accent-color: #0E0E0E; margin: 0"></label>' +
      '<button type="button" aria-label="Delete rule" style="width: 40px; min-height: 44px; border: 3px solid #0E0E0E; border-radius: 0; background: #FFFFFF; color: #0E0E0E; font-weight: 700; font-size: 16px; cursor: pointer">×</button>';
    $('input', row).onchange = (ev) => { r.enabled = ev.target.checked; saveRules(); renderRules(); };
    $('button', row).onclick = () => { S.rules = S.rules.filter((x) => x !== r); saveRules(); renderRules(); };
    list.appendChild(row);
  });
  if (!S.rules.length) list.innerHTML = '<div class="empty">no rules yet. teach it one above.</div>';
}
function showHeard(chips) {
  const box = $('#heard-txt'); box.textContent = '';
  let c = 0;
  chips.forEach((p) => {
    if (p.t === 'chip') {
      const s = document.createElement('span'); s.className = 'chip'; s.textContent = p.v;
      s.style.cssText = CHIP_STYLE[c % 4] + '; animation-delay: ' + (0.35 + c * 0.15) + 's'; c++;
      box.appendChild(s);
    } else box.appendChild(document.createTextNode(/^[,.;:!?]/.test(p.v) ? p.v + ' ' : ' ' + p.v + ' '));
  });
  const h = $('#heard'); h.classList.remove('on'); void h.offsetWidth; h.classList.add('on');
}
function bindRules() {
  const btn = $('#btn-teach'), input = $('#rule-in');
  btn.onclick = async () => {
    const text = input.value.trim(); if (!text) return;
    btn.disabled = true; btn.textContent = 'listening…';
    try {
      const r = await call('parseRule', { text });
      if (!r.ok || !r.instruction) { toast("That doesn't look like a cleanup rule. Try: pdfs about rent go to Bills."); return; }
      S.rules.unshift({ id: Date.now(), text, instruction: r.instruction, enabled: true }); saveRules();
      showHeard(Array.isArray(r.chips) ? r.chips.slice(0, 14) : []);
      input.value = ''; renderRules();
    } catch (e) { toast(explain(e), 4500); }
    finally { btn.disabled = false; btn.textContent = 'teach it'; }
  };
}

/* ---------- trash + log ---------- */
async function renderTrash() {
  const [info, ss] = await Promise.all([call('trashInfo'), call('sessions')]);
  const [v, u] = sizeParts(info.bytes);
  $('#trash-n').textContent = num(info.count); $('#trash-u').textContent = 'FILES · ' + v + ' ' + u;
  $('#trash-until').textContent = info.until ? 'OLDEST GOES FOR GOOD ON ' + dateText(info.until).toUpperCase() : 'NOTHING WAITING.';
  const list = $('#trash-list'); list.textContent = '';
  const rows = ss.items.filter((s) => s.inTrash > 0);
  if (!rows.length) list.innerHTML = '<div class="empty">trash is empty.</div>';
  rows.forEach((s) => {
    const d = document.createElement('div'); d.className = 'item';
    d.innerHTML = '<div class="t">' + num(s.inTrash) + ' files · ' + esc(sizeText(s.trashBytes)) + '<small>cleanup of ' + esc(dateText(s.time)) + '</small></div><button type="button">restore</button>';
    $('button', d).onclick = async () => { const r = await call('restoreTrash', { id: s.id }); toast('Restored ' + num(r.n) + ' files.'); renderTrash(); };
    list.appendChild(d);
  });
  $('#btn-empty').disabled = info.count === 0;
}
async function renderLog() {
  const ss = await call('sessions');
  const list = $('#log-list'); list.textContent = '';
  if (!ss.items.length) list.innerHTML = '<div class="empty">no cleanups yet.</div>';
  ss.items.forEach((s) => {
    const d = document.createElement('div'); d.className = 'item';
    const parts = [s.moved && s.moved + ' moved', s.renamed && s.renamed + ' renamed', s.deleted && s.deleted + ' to trash'].filter(Boolean).join(' · ');
    const can = !s.undone && s.active > 0;
    d.innerHTML = '<div class="t">' + esc(dateText(s.time)) + ' · ' + num(s.moved + s.renamed + s.deleted) + ' changes<small>' + esc(parts) + '</small></div><button type="button"' + (can ? '' : ' disabled') + '>' + (can ? 'undo' : 'undone') + '</button>';
    if (can) $('button', d).onclick = async () => { const r = await call('undo', { id: s.id }); toast('Put ' + num(r.n) + ' files back.'); renderLog(); };
    list.appendChild(d);
  });
}
function bindTrash() {
  $('#btn-empty').onclick = async () => {
    if (await modal({ title: 'really?', text: 'This deletes everything in trash for good. There is no way back.', yes: 'empty it', no: 'keep' })) { await call('emptyTrash'); renderTrash(); }
  };
}

/* ---------- navigation ---------- */
function nav(to) {
  if (to === 'mess') { if (S.stats) renderHome(); go('home'); }
  else if (to === 'rules') { renderRules(); go('rules'); }
  else if (to === 'trash') { go('trash'); renderTrash().catch((e) => toast(explain(e))); }
  else if (to === 'log') { go('log'); renderLog().catch((e) => toast(explain(e))); }
}
window.__back = () => {
  const open = $$('.overlay.on');
  if (open.length) { open.forEach((o) => { if (o.id !== 'modal') o.classList.remove('on'); }); return true; }
  if (['plan', 'rules', 'trash', 'log', 'done'].includes(current)) { nav('mess'); return true; }
  if (current === 'scan') return true;
  return false;
};

/* ---------- boot ---------- */
async function boot() {
  for (const n of NAMES) {
    const sec = document.createElement('section'); sec.className = 'screen'; sec.id = 's-' + n;
    sec.innerHTML = await (await fetch('screens/' + n + '.html')).text();
    $('#app').appendChild(sec);
  }
  $$('[data-nav]').forEach((b) => { b.onclick = () => nav(b.dataset.nav); });
  bindSetup(); bindProgress(); bindHome(); bindPlan(); bindRules(); bindDone(); bindTrash();
  renderRules();
  const st = await call('init');
  S.provider = st.provider || '';
  if (!st.hasKey) go('setup'); else { await waitForStorage(); await runScan(); }
}
boot().catch((e) => { document.body.textContent = 'Tidyo failed to start: ' + e.message; });
