/**
 * Translation cloud configurations (`/bin/studio/translation`): where content is translated, with which model, and
 * which secret holds the API key. Keys themselves never pass through here: a configuration holds a reference
 * (`$[secret:NAME]`) and the server only says whether it resolves on the instance.
 */

/** Anthropic's Messages API, an OpenAI-compatible chat API (OpenAI, Ollama, vLLM …) or OpenRouter. */
export type Api = 'anthropic' | 'openai' | 'openrouter';

export interface TranslationSettings {
  api?: Api;
  endpoint?: string;
  model?: string;
  /** A secret reference: `$[secret:NAME]` or `$[env:NAME]`, or the stored `$[enc:v1:…]`. Empty: the default environment variable. */
  apiKey?: string;
  /** A key typed in the console: sent once, stored encrypted with the instance's master key, never answered. */
  apiKeyValue?: string;
  maxTokens?: number;
  maxChars?: number;
  timeoutSeconds?: number;
  instructions?: string;
  doNotTranslate?: string[];
  /** The provider preset the settings were made from (openai, mistral, azure …); informative. */
  preset?: string;
  /** Extra HTTP headers, `Name: value`; credentials only as secret references. */
  headers?: string[];
}

export interface HeaderRow {
  name: string;
  value: string;
}

export interface KeyStatus {
  /** found: the key resolves; missing: the secret has no value here; plain: a literal key (refused); notNeeded. */
  status: 'found' | 'missing' | 'plain' | 'notNeeded';
  /** The secret's or environment variable's name. */
  name?: string;
  /** No reference: the API's default environment variable. */
  default?: boolean;
  /** The credential is sent in an extra header (e.g. Azure's api-key). */
  header?: boolean;
  /** The key was typed in the console and is stored encrypted. */
  encrypted?: boolean;
  /** Encrypted with another master key (another instance): it must be typed again. */
  unreadable?: boolean;
}

export interface TranslationConfig {
  path: string;
  conf: string;
  name: string;
  title: string;
  connector: string;
  settings: TranslationSettings;
  key: KeyStatus;
  lastModified?: number;
  lastModifiedBy?: string;
}

export interface TranslationConnector {
  name: string;
  label: string;
}

export interface TranslationConfigs {
  confs: string[];
  connectors: TranslationConnector[];
  configs: TranslationConfig[];
}

export interface ConfigDraft {
  title: string;
  name?: string;
  connector: string;
  settings: TranslationSettings;
}

export interface TestResult {
  ok: boolean;
  ms?: number;
  label?: string;
  sample?: string;
  error?: string;
  code?: string;
}

export interface FieldError {
  field: string;
  message: string;
}

export class ConfigValidationError extends Error {
  constructor(readonly errors: FieldError[]) {
    super(errors.map((e) => e.message).join(' '));
  }
}

export interface TranslationConfigClient {
  configs(): Promise<TranslationConfigs>;
  /** Creates (`conf` + draft) or updates (`path` + draft). 422 → ConfigValidationError. */
  save(target: { conf: string } | { path: string }, draft: ConfigDraft): Promise<TranslationConfig>;
  delete(path: string): Promise<void>;
  /** Translates a test sentence with a saved configuration or with unsaved settings. */
  test(target: { path: string } | { config: ConfigDraft }): Promise<TestResult>;
  currentUser(): Promise<string>;
}

/** Models offered in the editor (any other name can be typed). */
export const MODELS: Record<Api, { id: string; label: string }[]> = {
  anthropic: [
    { id: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5 (default: quality and speed)' },
    { id: 'claude-haiku-5-5', label: 'Claude Haiku 5.5 (fastest, lowest cost)' },
    { id: 'claude-opus-5-5', label: 'Claude Opus 5.5 (most capable)' },
    { id: 'claude-fable-5-1', label: 'Claude Fable 5.1' },
  ],
  openai: [
    { id: 'gpt-4.1', label: 'gpt-4.1 (OpenAI)' },
    { id: 'llama3.1', label: 'llama3.1 (Ollama)' },
    { id: 'qwen2.5', label: 'qwen2.5 (Ollama)' },
  ],
  // any id from openrouter.ai/models; empty: the account's default model
  openrouter: [
    { id: 'anthropic/claude-sonnet-4.6', label: 'Anthropic Claude Sonnet' },
    { id: 'openai/gpt-4o', label: 'OpenAI GPT-4o' },
    { id: 'openai/gpt-5.2', label: 'OpenAI GPT-5.2' },
  ],
};

export const DEFAULT_MODEL = 'claude-sonnet-5-5';
export const DEFAULT_KEY_VARIABLE = { anthropic: 'ANTHROPIC_API_KEY', openai: 'OPENAI_API_KEY', openrouter: 'OPENROUTER_API_KEY' } as const;
export const API_PROTOCOLS: Record<Api, string> = {
  anthropic: 'Anthropic Messages API',
  openai: 'OpenAI chat completions',
  openrouter: 'OpenRouter (OpenAI format)',
};
export const API_LABELS: Record<Api, string> = { anthropic: 'Claude', openai: 'OpenAI-compatible', openrouter: 'OpenRouter' };
export const SECRET_REFERENCE = /^\$\[(secret|env):([A-Za-z_][A-Za-z0-9_.]*)\]$/;
/** A key typed in the console, as it is stored (encrypted). */
export const ENCRYPTED_KEY = /^\$\[enc:v1:[A-Za-z0-9_-]+\]$/;
/** The OSGi factory configuration where secrets are added. */
export const SECRETS_CONSOLE = '/system/console/configMgr/io.sensible.studio.translation.impl.SecretEntry';

export const translationConsoleUrl = (conf?: string | null) => `/cloudservices/translation.html${conf ?? ''}`;

const API = '/bin/studio/translation.json';

export class HttpTranslationConfigClient implements TranslationConfigClient {
  constructor(private readonly base = '') {}

  async configs() {
    const res = await fetch(`${this.base}${API}?op=configs`, { credentials: 'same-origin' });
    return read<TranslationConfigs>(res);
  }

  async save(target: { conf: string } | { path: string }, draft: ConfigDraft) {
    return this.post<TranslationConfig>({ op: 'save', ...target, ...draft });
  }

  async delete(path: string) {
    await this.post({ op: 'delete', path });
  }

  async test(target: { path: string } | { config: ConfigDraft }) {
    return this.post<TestResult>({ op: 'test', ...target });
  }

  async currentUser() {
    const res = await fetch(`${this.base}/system/sling/info.sessionInfo.json`, { credentials: 'same-origin' });
    if (!res.ok) return 'anonymous';
    const info = (await res.json()) as { userID?: string };
    return info.userID ?? 'anonymous';
  }

  private async post<T = unknown>(body: Record<string, unknown>): Promise<T> {
    const res = await fetch(`${this.base}${API}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
    });
    return read<T>(res);
  }
}

async function read<T>(res: Response): Promise<T> {
  const text = await res.text();
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* not JSON */
  }
  if (res.status === 422 && json && typeof json === 'object' && 'errors' in json) {
    throw new ConfigValidationError((json as { errors: FieldError[] }).errors);
  }
  if (!res.ok) {
    throw new Error(json && typeof json === 'object' && 'error' in json ? String((json as { error: unknown }).error) : `HTTP ${res.status}`);
  }
  return json as T;
}
