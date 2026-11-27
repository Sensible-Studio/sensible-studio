#!/usr/bin/env node
// Takes the pictures of the documentation from a running Sensible Studio.
//
//   node docs/tools/shots.mjs [--url http://localhost:4502] [--user admin:admin] [name-filter ...]
//
// Needs Node 22 or newer and Google Chrome (or CHROME=/path/to/chrome); nothing to install.
// The pictures are described in docs/shots/*.mjs: each file exports a list of scenes, a scene is
// { name, steps } (and fresh: false to keep the tabs and settings of the scene before). A step is one of
//
//   { goto: '/studio/start.html' }        open an address and wait until the screen is quiet
//   { click: 'css selector' }             a real mouse click in the middle of the element
//   { click: { text: 'Pages' } }          ... of the smallest visible element with exactly this text (last: true takes
//                                         the last one in the document instead, which is where menus are)
//   { rightClick: ... } { hover: ... } { dblClick: ... }      the same targets ({ clickAt: [x, y] } for a point, { moveAt: [x, y] } to hover one,
//                                         { scroll: [x, y, pixels] } for the wheel there)
//   { key: 'Alt+T' }                      a key with its modifiers (Alt, Ctrl, Meta, Shift)
//   { type: 'some text' }                 text into the focused field
//   { wait: 400 } { waitFor: 'selector' } { waitFor: { text: '...' } }
//   { eval: 'javascript' }                anything else, in the page ({ log: 'javascript' } prints what it returns)
//   { mark: [target, target, ...] }       numbered badges on the elements, 1, 2, 3, ... ({ mark: [] } removes them)
//   { shot: 'file-name', clip: target?, pad: 12 }    the picture: the window, or one element with a margin
//
// A scene that fails says which step failed and the others still run; the exit code says whether all passed.
// Pictures are WebP, two device pixels per pixel, in docs/content/en/images/.

import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readdirSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DOCS = resolve(HERE, '..');
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i < 0 ? fallback : args.splice(i, 2)[1];
};
const URL_ = option('--url', process.env.STUDIO_URL || 'http://localhost:4502').replace(/\/$/, '');
const USER = option('--user', process.env.STUDIO_USER || 'admin:admin');
const OUT = resolve(option('--out', join(DOCS, 'content', 'en', 'images')));
const WIDTH = Number(option('--width', 1440));
const HEIGHT = Number(option('--height', 900));
const filters = args;

const CHROME = process.env.CHROME || [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser',
].find(existsSync);
if (!CHROME) {
  console.error('Google Chrome was not found; set CHROME to its program.');
  process.exit(2);
}

const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

// --- Chrome, spoken to over its DevTools protocol -------------------------------------------------

const profile = mkdtempSync(join(tmpdir(), 'studio-shots-'));
const chrome = spawn(CHROME, [
  '--headless=new', '--remote-debugging-port=0', '--user-data-dir=' + profile, '--no-first-run', '--no-default-browser-check',
  '--hide-scrollbars', '--force-color-profile=srgb', '--disable-gpu', `--window-size=${WIDTH},${HEIGHT}`, 'about:blank',
], { stdio: ['ignore', 'ignore', 'pipe'] });
const endpoint = await new Promise((found, failed) => {
  let seen = '';
  chrome.stderr.on('data', (chunk) => {
    seen += chunk;
    const match = seen.match(/DevTools listening on (ws:\/\/\S+)/);
    if (match) found(match[1]);
  });
  chrome.on('exit', () => failed(new Error('Chrome ended before it listened:\n' + seen)));
  setTimeout(() => failed(new Error('Chrome did not start in 20 s')), 20000);
});

const socket = new WebSocket(endpoint);
await new Promise((open, failed) => { socket.onopen = open; socket.onerror = failed; });
let lastId = 0;
const waiting = new Map();
socket.onmessage = (event) => {
  const message = JSON.parse(event.data);
  const entry = waiting.get(message.id);
  if (!entry) return;
  waiting.delete(message.id);
  if (message.error) entry.failed(new Error(message.error.message));
  else entry.done(message.result);
};
let sessionId;
const send = (method, params = {}, session = sessionId) => new Promise((done, failed) => {
  const id = ++lastId;
  waiting.set(id, { done, failed });
  socket.send(JSON.stringify({ id, method, params, ...(session ? { sessionId: session } : {}) }));
});

const { targetId } = await send('Target.createTarget', { url: 'about:blank' }, null);
sessionId = (await send('Target.attachToTarget', { targetId, flatten: true }, null)).sessionId;
await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: HEIGHT, deviceScaleFactor: 2, mobile: false });

async function evaluate(expression) {
  const answer = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (answer.exceptionDetails) {
    throw new Error(answer.exceptionDetails.exception?.description || answer.exceptionDetails.text);
  }
  return answer.result.value;
}

/** Waits until the page has stopped changing: no pending fetches we can see, and the same size of DOM twice. */
async function quiet(limit = 8000) {
  const until = Date.now() + limit;
  let before = -1;
  let same = 0;
  while (Date.now() < until) {
    await sleep(250);
    let now;
    try {
      now = await evaluate('document.readyState === "complete" ? document.body.innerHTML.length : -1');
    } catch {
      now = -1;
    }
    same = now === before && now > 0 ? same + 1 : 0;
    before = now;
    if (same >= 2) return;
  }
}

// --- Targets --------------------------------------------------------------------------------------

const FIND = `
(target) => {
  const visible = (el) => {
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'
      && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
  };
  let found = null;
  if (typeof target === 'string') {
    found = [...document.querySelectorAll(target)].find(visible) || null;
  } else {
    const want = target.text;
    const scope = target.within ? document.querySelector(target.within) : document.body;
    if (!scope) return null;
    let best = Infinity;
    for (const el of scope.querySelectorAll(target.tag || '*')) {
      const own = (el.getAttribute('aria-label') || '').trim();
      const text = (el.textContent || '').trim().replace(/\\s+/g, ' ');
      const title = (el.getAttribute('title') || '').trim();
      const hit = target.contains ? text.includes(want) : (text === want || own === want || title === want);
      if (!hit || !visible(el)) continue;
      const r = el.getBoundingClientRect();
      const area = r.width * r.height;
      if (target.last) found = el; else if (area < best) { best = area; found = el; }
    }
    if (found && target.closest) found = found.closest(target.closest) || found;
  }
  if (!found) return null;
  if (target.nth) { /* reserved */ }
  const r = found.getBoundingClientRect();
  return { x: r.x, y: r.y, width: r.width, height: r.height };
}`;

async function box(target, tries = 24) {
  for (let i = 0; i < tries; i++) {
    const rect = await evaluate(`(${FIND})(${JSON.stringify(target)})`);
    if (rect) return rect;
    await sleep(250);
  }
  throw new Error('not on the screen: ' + JSON.stringify(target));
}

async function mouse(target, button = 'left', clicks = 1) {
  const r = await box(target);
  const x = r.x + r.width / 2;
  const y = r.y + r.height / 2;
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
  if (button === 'none') return;
  for (let n = 1; n <= clicks; n++) {
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button, clickCount: n });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button, clickCount: n });
  }
}

const KEYS = {
  Enter: [13, 'Enter', '\r'], Escape: [27, 'Escape'], Tab: [9, 'Tab'], Backspace: [8, 'Backspace'], Delete: [46, 'Delete'],
  ArrowLeft: [37, 'ArrowLeft'], ArrowUp: [38, 'ArrowUp'], ArrowRight: [39, 'ArrowRight'], ArrowDown: [40, 'ArrowDown'],
  '\\': [220, 'Backslash'], '/': [191, 'Slash'], ',': [188, 'Comma'], '.': [190, 'Period'], ' ': [32, 'Space'], F2: [113, 'F2'],
};
async function key(combo) {
  const parts = combo.split('+');
  const name = parts.pop() || '+';
  const modifiers = parts.reduce((bits, m) => bits | { Alt: 1, Ctrl: 2, Meta: 4, Shift: 8 }[m], 0);
  let code, vk, text;
  if (KEYS[name]) {
    [vk, code, text] = KEYS[name];
  } else if (/^[a-z]$/i.test(name)) {
    vk = name.toUpperCase().charCodeAt(0);
    code = 'Key' + name.toUpperCase();
    text = modifiers & 7 ? undefined : name;
  } else if (/^[0-9]$/.test(name)) {
    vk = name.charCodeAt(0);
    code = 'Digit' + name;
    text = modifiers & 7 ? undefined : name;
  } else {
    throw new Error('unknown key: ' + combo);
  }
  const keyName = name.length === 1 && !(modifiers & 8) ? name.toLowerCase() : name;
  const event = { key: keyName, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers };
  await send('Input.dispatchKeyEvent', { type: text ? 'keyDown' : 'rawKeyDown', ...event, ...(text ? { text } : {}) });
  await send('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
}

const MARK = `
(rects) => {
  document.getElementById('__shots')?.remove();
  if (!rects.length) return;
  const layer = document.createElement('div');
  layer.id = '__shots';
  layer.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647';
  rects.forEach((r, i) => {
    const frame = document.createElement('div');
    frame.style.cssText = 'position:absolute;box-sizing:border-box;border:2px solid #e5484d;border-radius:6px;'
      + 'left:' + (r.x - 3) + 'px;top:' + (r.y - 3) + 'px;width:' + (r.width + 6) + 'px;height:' + (r.height + 6) + 'px';
    const badge = document.createElement('div');
    badge.textContent = String(r.n ?? i + 1);
    const bx = Math.max(2, Math.min(innerWidth - 24, r.x - 11));
    const by = Math.max(2, Math.min(innerHeight - 24, r.y + r.height - 9));
    badge.style.cssText = 'position:absolute;width:22px;height:22px;border-radius:11px;background:#e5484d;color:#fff;'
      + 'font:600 12px/22px -apple-system,Segoe UI,sans-serif;text-align:center;box-shadow:0 0 0 2px #fff;'
      + 'left:' + bx + 'px;top:' + by + 'px';
    layer.append(frame, badge);
  });
  document.body.append(layer);
}`;

// --- Steps ----------------------------------------------------------------------------------------

async function run(step) {
  if (step.goto !== undefined) {
    await send('Page.navigate', { url: step.goto.startsWith('http') ? step.goto : URL_ + step.goto });
    await quiet();
  } else if (step.click !== undefined) {
    await mouse(step.click);
    await quiet(step.settle ?? 4000);
  } else if (step.moveAt !== undefined) {
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: step.moveAt[0], y: step.moveAt[1] });
    await sleep(step.settle ?? 500);
  } else if (step.clickAt !== undefined) {
    const [x, y] = step.clickAt;
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: step.button || 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: step.button || 'left', clickCount: 1 });
    await quiet(step.settle ?? 3000);
  } else if (step.scroll !== undefined) {
    const [x, y, deltaY] = step.scroll;
    await send('Input.dispatchMouseEvent', { type: 'mouseWheel', x, y, deltaX: 0, deltaY });
    await sleep(step.settle ?? 700);
  } else if (step.dblClick !== undefined) {
    await mouse(step.dblClick, 'left', 2);
    await quiet(step.settle ?? 4000);
  } else if (step.rightClick !== undefined) {
    await mouse(step.rightClick, 'right');
    await sleep(350);
  } else if (step.hover !== undefined) {
    await mouse(step.hover, 'none');
    await sleep(step.settle ?? 700);
  } else if (step.key !== undefined) {
    await key(step.key);
    await sleep(step.settle ?? 450);
  } else if (step.type !== undefined) {
    await send('Input.insertText', { text: step.type });
    await sleep(step.settle ?? 600);
  } else if (step.wait !== undefined) {
    await sleep(step.wait);
  } else if (step.waitFor !== undefined) {
    await box(step.waitFor, 60);
  } else if (step.eval !== undefined) {
    await evaluate(step.eval);
    await sleep(step.settle ?? 300);
  } else if (step.log !== undefined) {
    console.log(await evaluate(step.log));
  } else if (step.mark !== undefined) {
    const rects = [];
    for (const target of step.mark) rects.push(await box(target));
    await evaluate(`(${MARK})(${JSON.stringify(rects)})`);
  } else if (step.shot !== undefined) {
    let clip;
    // the pointer out of the way, so that no tooltip or hover is in the picture
    if (!step.keepPointer) await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 1, y: HEIGHT - 1 });
    await sleep(250);
    if (step.clip) {
      const r = await box(step.clip);
      const pad = step.pad ?? 12;
      const x = Math.max(0, r.x - pad);
      const y = Math.max(0, r.y - pad);
      clip = { x, y, width: Math.min(WIDTH - x, r.width + 2 * pad), height: Math.min(HEIGHT - y, r.height + 2 * pad), scale: 1 };
    }
    const { data } = await send('Page.captureScreenshot', { format: 'webp', quality: 88, ...(clip ? { clip } : {}) });
    const file = join(OUT, step.shot + '.webp');
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, Buffer.from(data, 'base64'));
    taken.push(step.shot);
  } else {
    throw new Error('unknown step: ' + JSON.stringify(step));
  }
}

// --- Sign in, then every scene --------------------------------------------------------------------

const taken = [];
const failures = [];
try {
  const [name, password] = [USER.slice(0, USER.indexOf(':')), USER.slice(USER.indexOf(':') + 1)];
  await run({ goto: '/libs/granite/core/content/login.html' });
  await evaluate(`(() => {
    document.getElementById('username').value = ${JSON.stringify(name)};
    document.getElementById('password').value = ${JSON.stringify(password)};
    HTMLFormElement.prototype.submit.call(document.getElementById('login'));
  })()`);
  await sleep(1500);
  await quiet();

  const folder = join(DOCS, 'shots');
  for (const file of readdirSync(folder).filter((f) => f.endsWith('.mjs')).sort()) {
    const scenes = (await import(pathToFileURL(join(folder, file)).href)).default;
    for (const scene of scenes) {
      const label = file.replace(/\.mjs$/, '') + '/' + scene.name;
      if (filters.length && !filters.some((f) => label.includes(f))) continue;
      let at = 0;
      try {
        await evaluate(`(${MARK})([])`).catch(() => {});
        // every scene starts as a first visit: no tabs, no recent places, the default theme
        if (scene.fresh !== false) await evaluate('localStorage.clear(); sessionStorage.clear()').catch(() => {});
        for (const step of scene.steps) {
          at++;
          await run(step);
        }
        console.log('ok    ' + label);
      } catch (error) {
        failures.push(label);
        console.log(`FAIL  ${label}: step ${at} ${JSON.stringify(scene.steps[at - 1])}: ${error.message.split('\n')[0]}`);
        if (process.env.SHOTS_DEBUG) {
          const { data } = await send('Page.captureScreenshot', { format: 'webp', quality: 60 });
          writeFileSync(join(tmpdir(), 'shots-fail-' + scene.name + '.webp'), Buffer.from(data, 'base64'));
        }
      }
    }
  }
} finally {
  socket.close();
  chrome.kill();
  await sleep(300);
  rmSync(profile, { recursive: true, force: true });
}
console.log(`${taken.length} pictures in ${OUT}${failures.length ? ', ' + failures.length + ' scenes failed' : ''}`);
process.exit(failures.length ? 1 : 0);
