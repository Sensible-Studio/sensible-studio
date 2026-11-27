// Runs before every test file (vite.config.ts, test.setupFiles).
//
// Node 25 and newer have a global `localStorage` of their own, which is undefined-like unless Node is started with
// --localstorage-file, and it hides the one jsdom gives the window: `window.localStorage.setItem` then throws and
// every test that remembers a view or a rail fails. Give the window a plain in-memory storage in that case.
class MemoryStorage implements Storage {
  private data = new Map<string, string>();
  get length() {
    return this.data.size;
  }
  clear() {
    this.data.clear();
  }
  getItem(key: string) {
    return this.data.has(key) ? this.data.get(key)! : null;
  }
  key(index: number) {
    return [...this.data.keys()][index] ?? null;
  }
  removeItem(key: string) {
    this.data.delete(key);
  }
  setItem(key: string, value: string) {
    this.data.set(key, String(value));
  }
  [name: string]: unknown;
}

for (const name of ['localStorage', 'sessionStorage'] as const) {
  let ok = false;
  try {
    ok = typeof window !== 'undefined' && typeof window[name]?.setItem === 'function';
  } catch {
    ok = false;
  }
  if (!ok && typeof window !== 'undefined') {
    const storage = new MemoryStorage();
    Object.defineProperty(window, name, { configurable: true, value: storage });
    Object.defineProperty(globalThis, name, { configurable: true, value: storage });
  }
}
