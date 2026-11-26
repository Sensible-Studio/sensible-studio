import {
  ConfigValidationError,
  SECRET_REFERENCE,
  type ConfigDraft,
  type FieldError,
  type KeyStatus,
  type TestResult,
  type TranslationConfig,
  type TranslationConfigClient,
  type TranslationConfigs,
} from '../api';

/** In-memory configurations, checked like the server; `secrets` are the names set on the "instance". */
export class MemoryTranslationConfigs implements TranslationConfigClient {
  confs = ['/conf/global', '/conf/wknd-shared'];
  items: TranslationConfig[] = [];
  secrets = new Set<string>();
  environment = new Set<string>();
  tests: (ConfigDraft | string)[] = [];
  saves: ConfigDraft[] = [];

  async configs(): Promise<TranslationConfigs> {
    return {
      confs: [...this.confs],
      connectors: [
        { name: 'studio-ai', label: 'AI (Claude, OpenRouter, OpenAI-compatible or local model)' },
        { name: 'studio-pseudo', label: 'Pseudo translation (testing)' },
      ],
      configs: this.items.map((c) => ({ ...structuredClone(c), key: this.keyStatus(c.connector, c.settings) })),
    };
  }

  async save(target: { conf: string } | { path: string }, draft: ConfigDraft): Promise<TranslationConfig> {
    this.saves.push(structuredClone(draft));
    const errors: FieldError[] = [];
    if (!draft.title.trim()) errors.push({ field: 'title', message: 'A title is required.' });
    const key = draft.settings.apiKey?.trim();
    if (key && !SECRET_REFERENCE.test(key)) errors.push({ field: 'settings.apiKey', message: 'Keys are not stored in the repository.' });
    (draft.settings.headers ?? []).forEach((h, i) => {
      const [name, ...rest] = h.split(':');
      if (/key|token|secret|password|auth/i.test(name) && !/\$\[(secret|env):/.test(rest.join(':'))) {
        errors.push({ field: `settings.headers.${i}`, message: `${name.trim()} carries a credential: refer to a secret.` });
      }
    });
    const existing = 'path' in target ? this.items.find((c) => c.path === target.path) : undefined;
    const conf = 'path' in target ? existing?.conf ?? '' : target.conf;
    const name = existing?.name ?? draft.name ?? draft.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
    const path = `${conf}/settings/cloudconfigs/translation/${name}`;
    if (!existing && this.items.some((c) => c.path === path)) errors.push({ field: 'name', message: `A configuration named ${name} exists already in ${conf}.` });
    if (errors.length) throw new ConfigValidationError(errors);
    const { apiKeyValue, ...rest } = draft.settings;
    // like the server: a typed key is stored encrypted and never answered
    if (apiKeyValue) rest.apiKey = `$[enc:v1:${btoa(apiKeyValue.split('').reverse().join('')).replace(/[+/=]/g, '_')}]`;
    const settings = Object.fromEntries(Object.entries(rest).filter(([, v]) => v !== undefined && v !== '' && !(Array.isArray(v) && !v.length)));
    const saved: TranslationConfig = {
      path,
      conf,
      name,
      title: draft.title.trim(),
      connector: draft.connector,
      settings,
      key: this.keyStatus(draft.connector, settings),
      lastModified: Date.now(),
      lastModifiedBy: 'admin',
    };
    this.items = [...this.items.filter((c) => c.path !== path), saved];
    return structuredClone(saved);
  }

  async delete(path: string) {
    this.items = this.items.filter((c) => c.path !== path);
  }

  async test(target: { path: string } | { config: ConfigDraft }): Promise<TestResult> {
    const draft = 'path' in target ? this.items.find((c) => c.path === target.path) : target.config;
    this.tests.push('path' in target ? target.path : structuredClone(target.config));
    if (!draft) return { ok: false, error: 'No configuration', code: 'NO_ENGINE' };
    const key = this.keyStatus(draft.connector, draft.settings);
    if (key.status === 'missing') {
      return { ok: false, ms: 3, code: 'MISSING_CREDENTIALS', error: `The secret ${draft.settings.apiKey ?? key.name} has no value on this instance` };
    }
    return { ok: true, ms: 840, label: `Claude (${draft.settings.model ?? 'claude-sonnet-5-5'})`, sample: 'Bonjour ! Cette phrase vérifie la connexion de traduction.' };
  }

  async currentUser() {
    return 'admin';
  }

  keyStatus(connector: string, settings: ConfigDraft['settings']): KeyStatus {
    if (connector !== 'studio-ai') return { status: 'notNeeded' };
    const headerRefs = (settings.headers ?? []).flatMap((h) => [...h.matchAll(/\$\[(secret|env):([A-Za-z_][A-Za-z0-9_.]*)\]/g)]);
    const missingRef = headerRefs.find((m) => !((m[1] === 'secret' && this.secrets.has(m[2])) || this.environment.has(m[2])));
    if (missingRef) return { status: 'missing', name: missingRef[2], header: true };
    if (!settings.apiKey?.trim() && (settings.headers ?? []).some((h) => /^(authorization|api-key|x-api-key|x-goog-api-key)\s*:/i.test(h))) {
      return { status: 'found', name: headerRefs[0]?.[2], header: true };
    }
    const api = settings.api ?? 'anthropic';
    const local = api === 'openai' && /^https?:\/\/(localhost|127\.0\.0\.1)/.test(settings.endpoint ?? '');
    const ref = settings.apiKey?.trim();
    if (settings.apiKeyValue || (ref && /^\$\[enc:v1:/.test(ref))) return { status: 'found', encrypted: true };
    if (!ref) {
      const name = api === 'openai' ? 'OPENAI_API_KEY' : api === 'openrouter' ? 'OPENROUTER_API_KEY' : 'ANTHROPIC_API_KEY';
      return { status: this.environment.has(name) ? 'found' : local ? 'notNeeded' : 'missing', name, default: true };
    }
    const m = ref.match(SECRET_REFERENCE);
    if (!m) return { status: 'plain' };
    const found = (m[1] === 'secret' && this.secrets.has(m[2])) || this.environment.has(m[2]);
    return { status: found ? 'found' : local ? 'notNeeded' : 'missing', name: m[2] };
  }
}
