/* Vivarium shim
   ─────────────
   Spliced into every tool page right after <head> (by ToolServer on Android),
   so it runs before the tool's own scripts. The tool file on disk is never
   changed. It runs on the tool's own origin and talks to the launcher only
   through postMessage, so it is the tool's side of the glass.

   What it does, all from the outside:
   - downloads: catches <a download> with blob:/data: links (clicked in the
     page or detached) and hands the file to the launcher, which writes it to
     Documents/Vivarium. Blobs are remembered at createObjectURL time, so a
     tool that revokes its URL right after clicking still exports fine
   - haptics: navigator.vibrate goes through the launcher
   - wake lock: navigator.wakeLock.request('screen') keeps the screen on
   - links to other sites open in the real browser instead of inside the tool
   - file pickers accept any file on Android (unknown extensions get hidden)
   - save on close: when the launcher closes a tool it asks it to save first
     (pagehide), waits for a pending #dot to settle, and warns if a #banner says
     saving failed. Same convention the Den uses for its Workshop tools
   - reports how much storage the tool holds
   Frames the tool opens itself on its own origin get the same treatment. */
(function () {
  'use strict';
  if (window.__vivarium) return;
  window.__vivarium = true;

  // Only the tool's own window speaks for it. Opened on its own, or nested
  // inside another window of the tool, there is nobody to talk to.
  let parentWin = null;
  try { parentWin = window.parent !== window && window.parent === window.top ? window.parent : null; } catch (e) {}
  if (!parentWin) return;

  const ORIGIN = (location.ancestorOrigins && location.ancestorOrigins.length)
    ? location.ancestorOrigins[0] : 'https://localhost';
  const send = (msg) => { msg.vv = 1; try { parentWin.postMessage(msg, ORIGIN); } catch (e) {} };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  let isNative = false;

  /* ----- per-window patches ----- */

  function adopt(w) {
    try { if (!w || w.__vivariumAdopted) return; void w.document; } catch (e) { return; }
    w.__vivariumAdopted = true;
    trackBlobs(w);
    patchVibrate(w);
    patchDownloads(w);
    patchLinks(w);
    watchFrames(w);
    if (isNative) widenInputs(w.document);
  }

  function trackBlobs(w) {
    const map = new Map();
    w.__vivariumBlobs = map;
    const create = w.URL.createObjectURL;
    w.URL.createObjectURL = function (obj) {
      const url = create.apply(this, arguments);
      if (obj instanceof w.Blob) {
        map.set(url, obj);
        if (map.size > 48) map.delete(map.keys().next().value);
      }
      return url;
    };
  }

  function patchVibrate(w) {
    try {
      Object.defineProperty(w.navigator, 'vibrate', {
        configurable: true,
        value: (p) => {
          const ms = Array.isArray(p) ? (p[0] || 10) : (+p || 10);
          send({ t: 'haptic', ms: Math.max(1, Math.min(400, ms)) });
          return true;
        }
      });
    } catch (e) {}
  }

  function handOff(w, a) {
    const href = a.href || '';
    if (!/^(blob|data):/.test(href)) return false;
    const name = (a.getAttribute('download') || '').trim() || 'export';
    const known = w.__vivariumBlobs && w.__vivariumBlobs.get(href);
    const got = known ? Promise.resolve(known) : w.fetch(href).then((r) => r.blob());
    got.then((blob) => send({ t: 'file', name, blob }))
      .catch(() => send({ t: 'file-failed', name }));
    return true;
  }

  function patchDownloads(w) {
    w.document.addEventListener('click', (e) => {
      const a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
      if (!a) return;
      if (handOff(w, a)) { e.preventDefault(); e.stopImmediatePropagation(); }
    }, true);
    // Detached anchors never reach the document, so catch their .click() too.
    const proto = w.HTMLAnchorElement.prototype;
    const click = proto.click;
    proto.click = function () {
      if (!this.isConnected && this.hasAttribute('download') && handOff(w, this)) return;
      return click.apply(this, arguments);
    };
  }

  function external(w, raw) {
    let u;
    try { u = new URL(raw, w.location.href); } catch (e) { return null; }
    if (!/^(https?|mailto):$/.test(u.protocol)) return null;
    if (u.origin === location.origin) return null;
    return u.href;
  }

  function patchLinks(w) {
    // Bubble phase, so a tool that handles its own links still wins.
    w.document.addEventListener('click', (e) => {
      if (e.defaultPrevented) return;
      const a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
      if (!a || a.hasAttribute('download')) return;
      const url = external(w, a.getAttribute('href'));
      if (!url) return;
      e.preventDefault();
      send({ t: 'open', url });
    });
    const open = w.open;
    w.open = function (raw) {
      const url = raw != null ? external(w, String(raw)) : null;
      if (url) { send({ t: 'open', url }); return null; }
      return open.apply(w, arguments);
    };
  }

  function widenInputs(root) {
    if (!root || !root.querySelectorAll) return;
    if (root.matches && root.matches('input[type="file"]')) root.setAttribute('accept', '*/*');
    root.querySelectorAll('input[type="file"]').forEach((i) => i.setAttribute('accept', '*/*'));
  }

  function watchFrames(w) {
    const hook = (f) => {
      f.addEventListener('load', () => { try { adopt(f.contentWindow); } catch (e) {} });
      try { if (f.contentDocument && f.contentDocument.readyState !== 'loading') adopt(f.contentWindow); } catch (e) {}
    };
    const scan = (n) => {
      if (n.nodeType !== 1) return;
      if (n.tagName === 'IFRAME') hook(n);
      else if (n.querySelectorAll) n.querySelectorAll('iframe').forEach(hook);
      if (isNative) widenInputs(n);
    };
    new w.MutationObserver((muts) => {
      for (const m of muts) for (const n of m.addedNodes) scan(n);
    }).observe(w.document.documentElement, { childList: true, subtree: true });
    w.document.querySelectorAll('iframe').forEach(hook);
  }

  /* ----- wake lock ----- */

  const locks = new Set();
  function makeLock() {
    const s = new EventTarget();
    let released = false;
    s.type = 'screen';
    s.onrelease = null;
    Object.defineProperty(s, 'released', { get: () => released });
    s.release = async () => {
      if (released) return;
      released = true;
      locks.delete(s);
      if (!locks.size) send({ t: 'wake', on: false });
      const ev = new Event('release');
      s.dispatchEvent(ev);
      if (typeof s.onrelease === 'function') { try { s.onrelease(ev); } catch (e) {} }
    };
    return s;
  }
  try {
    Object.defineProperty(navigator, 'wakeLock', {
      configurable: true,
      value: {
        request: async () => {
          const s = makeLock();
          locks.add(s);
          send({ t: 'wake', on: true });
          return s;
        }
      }
    });
  } catch (e) {}

  /* ----- saving and storage ----- */

  function windows() {
    const out = [];
    const walk = (w) => {
      out.push(w);
      let frames = [];
      try { frames = w.document.querySelectorAll('iframe'); } catch (e) {}
      for (const f of frames) {
        try { const cw = f.contentWindow; void cw.document; walk(cw); } catch (e) {}
      }
    };
    walk(window);
    return out;
  }
  const failing = () => windows().some((w) => {
    try { const b = w.document.getElementById('banner'); return !!(b && !b.hidden); } catch (e) { return false; }
  });
  const pending = () => windows().some((w) => {
    try { const d = w.document.getElementById('dot'); return !!(d && d.classList.contains('pending')); } catch (e) { return false; }
  });

  async function flush() {
    for (const w of windows()) { try { w.dispatchEvent(new Event('pagehide')); } catch (e) {} }
    await sleep(60);
    const t0 = performance.now();
    while (pending() && performance.now() - t0 < 2500) await sleep(50);
  }

  async function usage() {
    let bytes = 0;
    try { const e = await navigator.storage.estimate(); bytes = e.usage || 0; } catch (e) {}
    try {
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        bytes += (k.length + (localStorage.getItem(k) || '').length) * 2;
      }
    } catch (e) {}
    return bytes;
  }
  const reportUsage = () => usage().then((bytes) => send({ t: 'usage', bytes }));

  /* ----- the line to the launcher ----- */

  window.addEventListener('message', async (e) => {
    if (e.source !== parentWin || e.origin !== ORIGIN) return;
    const m = e.data;
    if (!m || m.vv !== 1) return;
    if (m.t === 'init') {
      isNative = !!m.native;
      if (isNative) windows().forEach((w) => { try { widenInputs(w.document); } catch (err) {} });
    } else if (m.t === 'status') {
      send({ t: 'status', seq: m.seq, failing: failing() });
    } else if (m.t === 'flush') {
      await flush();
      send({ t: 'flushed', seq: m.seq, bytes: await usage() });
    }
  });

  adopt(window);
  try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {}); } catch (e) {}
  window.addEventListener('load', () => setTimeout(reportUsage, 1200), { once: true });
  send({ t: 'hello' });
})();
