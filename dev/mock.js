// Browser-only stand-in for the Kotlin bridge, used for visual testing (not shipped in the APK).
(() => {
  let hasKey = !!localStorage.getItem('mockKey');
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const emit = (n, d) => window.__emit(n, JSON.stringify(d));
  const items = [];
  for (let i = 0; i < 340; i++) items.push({ id: i, kind: 'MOVE', from: 'Download/invoice_' + i + '.pdf', toFolder: i % 3 ? 'Documents/Invoices' : 'Pictures/Istanbul', newName: null, reason: 'x', size: 120000 });
  for (let i = 340; i < 460; i++) items.push({ id: i, kind: 'RENAME', from: 'DCIM/Camera/IMG_20260412_0933.jpg', toFolder: null, newName: 'Cafe terrace, Apr 12.jpg', reason: 'x', size: 900000 });
  for (let i = 460; i < 545; i++) items.push({ id: i, kind: 'DELETE', from: 'Download/dupe' + i + '.jpg', toFolder: null, newName: null, reason: 'x', size: 7900000 });
  const sessions = [{ id: 1, time: Date.now() - 86400000, moved: 340, renamed: 120, deleted: 85, undone: false, active: 545, inTrash: 85, trashBytes: 671088640 }];
  const handlers = {
    init: async () => ({ hasKey, storage: true }),
    validateKey: async (a) => { await sleep(700); if (a.key.startsWith('AIza')) { hasKey = true; localStorage.setItem('mockKey', '1'); return { ok: true, provider: 'OpenAI', model: 'gpt-4o-mini' }; } const e = new Error('bad'); e.kind = 'BAD_KEY'; throw e; },
    status: async () => ({ provider: 'OpenAI', model: 'gpt-4o-mini', vision: true }),
    clearKey: async () => { hasKey = false; localStorage.removeItem('mockKey'); return { ok: true }; },
    storageGranted: async () => ({ granted: true }),
    scan: async () => {
      for (let n = 0; n <= 1284; n += 214) { emit('scan', { phase: 'walk', n, name: 'IMG_' + n + '.jpg' }); await sleep(250); }
      for (let n = 0; n <= 1284; n += 214) { emit('scan', { phase: 'read', n, total: 1284, name: 'scan' + n + '.pdf' }); await sleep(250); }
      emit('scan', { phase: 'dupes', n: 40, total: 100 }); await sleep(300);
      return { total: 1284, mess: 73, dupCount: 41, dupBytes: 293601280, dupPhotos: true, shotCount: 28, shotBytes: 199229440, chatCount: 90, chatBytes: 178257920, unsorted: 340, badNames: 120 };
    },
    pause: async (a) => ({ paused: a.on }), cancel: async () => ({}),
    plan: async () => { for (let n = 0; n <= 545; n += 109) { emit('plan', { n, total: 545, callsLeft: 5 - n / 109 }); await sleep(350); } return { planId: 412, items, deleteBytes: 1, filesRead: 1284 }; },
    apply: async (a) => { for (let n = 0; n <= 545; n += 109) { emit('apply', { n, total: 545 }); await sleep(250); }
      return { sessionId: 1, done: 545, attempted: 545, seconds: 38, moved: 340, renamed: 120, deleted: 85, skipped: 0, freed: 671088640, newFolders: 9, top: [{ name: 'Invoices', count: 42 }, { name: 'Istanbul', count: 96 }, { name: 'Bills', count: 17 }, { name: 'Contracts', count: 9 }], examples: ['IMG_0933.jpg', 'scan0007.pdf', 'Screenshot_14.png', 'IMG_0933 (1).jpg', 'new (2).txt'], until: 'Dec 7' }; },
    undo: async () => ({ n: 545 }), restoreTrash: async () => ({ n: 85 }), sessions: async () => ({ items: sessions }),
    trashInfo: async () => ({ count: 85, bytes: 671088640, until: Date.now() + 29 * 86400000 }), emptyTrash: async () => ({}),
    parseRule: async (a) => { await sleep(600); return { ok: true, instruction: a.text, chips: [{ t: 'text', v: 'when a' }, { t: 'chip', v: 'PDF' }, { t: 'text', v: 'mentions' }, { t: 'chip', v: 'electricity' }, { t: 'text', v: ', move it to' }, { t: 'chip', v: 'Bills' }, { t: 'text', v: 'and name it' }, { t: 'chip', v: 'by month' }] }; },
    search: async () => ({ items: [{ path: 'Documents/Contracts/Rent contract 2026.pdf', name: 'Rent contract 2026.pdf', size: 340000, mtime: Date.now(), abs: '/x' }] }),
    open: async () => ({}), openUrl: async () => ({}), bar: async () => ({}), exit: async () => ({}),
  };
  window.Android = { call(id, method, json) {
    Promise.resolve().then(() => (handlers[method] || (async () => { throw new Error('no ' + method); }))(JSON.parse(json)))
      .then((r) => window.__cb(id, true, JSON.stringify(r)))
      .catch((e) => window.__cb(id, false, JSON.stringify({ kind: e.kind || 'OTHER', message: e.message })));
  } };
})();
