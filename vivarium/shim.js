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
   - window.vivarium and Notification: abilities the tool can ask for (read
     other sites, notifications, background wake-ups). Each one goes through
     the launcher, which asks you first and remembers the answer
   Frames the tool opens itself on its own origin get the same treatment.

   Headless: when Vivarium wakes a tool in the background it loads it alone
   in a hidden WebView that has a VivariumWake line to the native side, and
   nothing else. The shim then runs the tool's onWake handlers, saves, and
   says done. */
(function () {
  'use strict';
  if (window.__vivarium) return;
  window.__vivarium = true;

  // Only the tool's own window speaks for it. Opened on its own, or nested
  // inside another window of the tool, there is nobody to talk to.
  let parentWin = null;
  try { parentWin = window.parent !== window && window.parent === window.top ? window.parent : null; } catch (e) {}
  const line = window.parent === window && window.VivariumWake && typeof window.VivariumWake.postMessage === 'function'
    ? window.VivariumWake : null;
  if (!parentWin && !line) return;
  const HEADLESS = !parentWin;

  const ORIGIN = (location.ancestorOrigins && location.ancestorOrigins.length)
    ? location.ancestorOrigins[0] : 'https://localhost';
  const send = (msg) => {
    if (HEADLESS) return;
    msg.vv = 1;
    try { parentWin.postMessage(msg, ORIGIN); } catch (e) {}
  };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  let isNative = HEADLESS;

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

  /* ----- abilities: window.vivarium and Notification ----- */

  let perms = { sites: 'prompt', notifications: 'prompt', background: 'prompt' };
  let capSeq = 0;
  const capWaits = new Map();

  function failure(code, message) {
    const e = new Error(message || code || 'failed');
    e.name = 'VivariumError';
    e.code = code || 'failed';
    return e;
  }

  function call(op, args) {
    return new Promise((resolve, reject) => {
      const seq = ++capSeq;
      capWaits.set(seq, (r) => {
        capWaits.delete(seq);
        if (r && r.ok) resolve(r.value == null ? {} : r.value);
        else reject(failure(r && r.error, r && r.message));
      });
      const msg = { seq, op, args: args || {} };
      try {
        if (HEADLESS) line.postMessage(JSON.stringify(msg));
        else send(Object.assign({ t: 'cap' }, msg));
      } catch (e) { capWaits.delete(seq); reject(failure('failed', 'could not reach Vivarium')); }
    });
  }
  function answered(r) {
    const f = r && capWaits.get(r.seq);
    if (f) f(r);
  }
  if (HEADLESS) {
    line.addEventListener('message', (e) => {
      let r = null;
      try { r = JSON.parse(e.data); } catch (err) { return; }
      answered(r);
    });
  }

  function webUrl(raw) {
    let u;
    try { u = new URL(String(raw)); } catch (e) { throw failure('bad-url', 'needs a full http(s) address'); }
    if (u.protocol !== 'https:' && u.protocol !== 'http:') throw failure('bad-url', 'only http and https');
    return u.href;
  }

  async function asDataUrl(src) {
    if (!src) return '';
    src = String(src);
    if (/^data:image\//i.test(src)) return src;
    if (!/^blob:/i.test(src)) return '';
    try {
      const b = (window.__vivariumBlobs && window.__vivariumBlobs.get(src)) || await (await fetch(src)).blob();
      return await new Promise((res) => {
        const r = new FileReader();
        r.onload = () => res(String(r.result));
        r.onerror = () => res('');
        r.readAsDataURL(b);
      });
    } catch (e) { return ''; }
  }

  const wakeFns = [];
  const clickFns = [];
  const unclaimed = [];

  async function runWake(ctx) {
    const t0 = Date.now();
    while (!wakeFns.length && Date.now() - t0 < 8000) await sleep(100);
    const jobs = wakeFns.slice().map((fn) => {
      try { return Promise.resolve(fn(ctx)); } catch (e) { return Promise.reject(e); }
    });
    await Promise.race([Promise.allSettled(jobs), sleep(75000)]);
  }

  const api = {
    version: 1,
    headless: HEADLESS,
    fetch(url, o) {
      o = o || {};
      const dataUrl = o.as === 'dataurl';
      return Promise.resolve().then(() => call('fetch', {
        url: webUrl(url),
        as: dataUrl || o.as === 'base64' ? 'base64' : 'text',
        headers: o.headers || null,
        maxBytes: o.maxBytes, timeout: o.timeout
      })).then((r) => {
        if (dataUrl && r.base64 != null) r.dataUrl = 'data:' + (String(r.type || '').split(';')[0] || 'application/octet-stream') + ';base64,' + r.base64;
        return r;
      });
    },
    render(url, o) {
      o = o || {};
      return Promise.resolve().then(() => call('render', {
        url: webUrl(url), settle: o.settle, waitFor: o.waitFor || '', timeout: o.timeout
      }));
    },
    network() { return call('network'); },
    permission(name) { return perms[name] || 'prompt'; },
    request(name, o) {
      o = o || {};
      const args = { name: String(name) };
      if (o.host) args.host = String(o.host);
      if (o.minutes) args.minutes = +o.minutes;
      return call('request', args).then((r) => {
        if (r.perms) perms = Object.assign({}, perms, r.perms);
        else if (r.state && name !== 'sites') perms[name] = r.state;
        return r.state;
      });
    },
    wake: Object.freeze({
      every(minutes) { return call('wake-every', { minutes: Math.round(+minutes || 60) }); },
      stop() { return call('wake-stop'); },
      get() { return call('wake-get'); }
    }),
    onWake(fn) { if (typeof fn === 'function') wakeFns.push(fn); },
    onNotificationClick(fn) {
      if (typeof fn !== 'function') return;
      clickFns.push(fn);
      while (unclaimed.length) { const c = unclaimed.shift(); try { fn(c); } catch (e) {} }
    }
  };
  try { Object.defineProperty(window, 'vivarium', { value: Object.freeze(api), configurable: false }); } catch (e) {}

  /* Notification, the standard one, delivered by Android. Extras that only
     mean something here: url (a tap opens it in the browser) and actions
     [{ action, title, url? }] (up to two buttons). */
  const live = new Map();
  const fire = (n, type) => {
    const ev = new Event(type);
    try { n.dispatchEvent(ev); } catch (e) {}
    const h = n['on' + type];
    if (typeof h === 'function') { try { h.call(n, ev); } catch (e) {} }
  };
  const webPerm = () => perms.notifications === 'prompt' ? 'default' : perms.notifications;
  class VivariumNotification extends EventTarget {
    constructor(title, options) {
      super();
      if (title === undefined) throw new TypeError('Notification needs a title');
      options = options || {};
      this.title = String(title);
      this.body = options.body == null ? '' : String(options.body);
      this.tag = options.tag == null ? '' : String(options.tag);
      this.data = options.data === undefined ? null : options.data;
      this.icon = options.icon || '';
      this.silent = !!options.silent;
      this.onclick = this.onshow = this.onerror = this.onclose = null;
      const n = this;
      (async () => {
        if (perms.notifications !== 'granted') throw failure('not-allowed');
        let data = '';
        try { data = n.data == null ? '' : JSON.stringify(n.data); } catch (e) {}
        const actions = Array.isArray(options.actions) ? options.actions.slice(0, 2).map((a) => ({
          action: String(a && a.action || ''), title: String(a && a.title || ''), url: a && a.url ? String(a.url) : ''
        })) : [];
        await call('notify', {
          title: n.title, body: n.body, tag: n.tag, data, silent: n.silent, actions,
          url: options.url ? String(options.url) : '',
          icon: await asDataUrl(options.icon || options.image)
        });
        if (n.tag) live.set(n.tag, n);
        fire(n, 'show');
      })().catch(() => fire(n, 'error'));
    }
    close() {
      if (this.tag) { call('notify-close', { tag: this.tag }).catch(() => {}); live.delete(this.tag); }
      fire(this, 'close');
    }
    static get permission() { return webPerm(); }
    static get maxActions() { return 2; }
    static requestPermission(cb) {
      return api.request('notifications').catch(() => perms.notifications).then(() => {
        const st = webPerm();
        if (typeof cb === 'function') { try { cb(st); } catch (e) {} }
        return st;
      });
    }
  }
  try { Object.defineProperty(window, 'Notification', { value: VivariumNotification, configurable: true, writable: true }); } catch (e) {}

  // Alone in the background there is no init message; ask straight away.
  const infoReady = HEADLESS ? call('info').then((i) => {
    if (i.perms) perms = Object.assign({}, perms, i.perms);
    return i;
  }).catch(() => ({})) : null;

  function clicked(m) {
    let data = null;
    try { data = m.data ? JSON.parse(m.data) : null; } catch (e) { data = m.data; }
    const c = { tag: m.tag || '', action: m.action || '', data };
    const n = c.tag && live.get(c.tag);
    if (n) fire(n, 'click');
    if (clickFns.length) clickFns.forEach((fn) => { try { fn(c); } catch (e) {} });
    else unclaimed.push(c);
  }

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
      if (m.perms) perms = Object.assign({}, perms, m.perms);
      if (isNative) windows().forEach((w) => { try { widenInputs(w.document); } catch (err) {} });
    } else if (m.t === 'cap') {
      answered(m);
    } else if (m.t === 'perms') {
      if (m.perms) perms = Object.assign({}, perms, m.perms);
    } else if (m.t === 'wake') {
      await runWake({ reason: m.reason || 'schedule', network: m.network || null, headless: false });
      send({ t: 'woke' });
    } else if (m.t === 'nclick') {
      clicked(m);
    } else if (m.t === 'status') {
      send({ t: 'status', seq: m.seq, failing: failing() });
    } else if (m.t === 'flush') {
      await flush();
      send({ t: 'flushed', seq: m.seq, bytes: await usage() });
    }
  });

  adopt(window);
  try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {}); } catch (e) {}

  if (HEADLESS) {
    const go = async () => {
      await sleep(300);
      const info = await infoReady;
      try { await runWake({ reason: info.reason || 'schedule', network: info.network || null, headless: true }); } catch (e) {}
      try { await flush(); } catch (e) {}
      try { line.postMessage(JSON.stringify({ op: 'done' })); } catch (e) {}
    };
    if (document.readyState === 'complete') go();
    else window.addEventListener('load', go, { once: true });
    return;
  }

  window.addEventListener('load', () => setTimeout(reportUsage, 1200), { once: true });
  send({ t: 'hello' });
})();
