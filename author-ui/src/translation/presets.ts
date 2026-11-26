import { DEFAULT_MODEL, SECRET_REFERENCE, type Api, type HeaderRow } from './api';

/**
 * Providers the AI connector can talk to. A preset only fills in the protocol, endpoint, key reference and extra
 * headers; everything stays editable, and "Custom" covers any other server speaking one of the protocols.
 */
export interface Preset {
  id: string;
  label: string;
  api: Api;
  /** Empty: the protocol's default endpoint. */
  endpoint: string;
  /** Shown when the endpoint is specific to the customer (e.g. an Azure resource). */
  endpointHint?: string;
  /** The endpoint must be entered. */
  requiresEndpoint?: boolean;
  /** The secret the key is referred to by; none for local servers or header authentication. */
  keyVariable?: string;
  headers?: HeaderRow[];
  models: string[];
  modelHint: string;
  /** The model may stay empty (the account's default). */
  modelOptional?: boolean;
}

export const PRESETS: Preset[] = [
  {
    id: 'anthropic',
    label: 'Anthropic (Claude)',
    api: 'anthropic',
    endpoint: '',
    keyVariable: 'ANTHROPIC_API_KEY',
    models: [DEFAULT_MODEL, 'claude-haiku-5-5', 'claude-opus-5-5', 'claude-fable-5-1'],
    modelHint: 'Sonnet suits most sites; Haiku for volume, Opus for demanding texts.',
  },
  {
    id: 'openai',
    label: 'OpenAI',
    api: 'openai',
    endpoint: 'https://api.openai.com/v1/chat/completions',
    keyVariable: 'OPENAI_API_KEY',
    models: ['gpt-4.1', 'gpt-4o', 'gpt-4o-mini'],
    modelHint: 'A chat model of your OpenAI account.',
  },
  {
    id: 'openrouter',
    label: 'OpenRouter (models of many providers)',
    api: 'openrouter',
    endpoint: '',
    keyVariable: 'OPENROUTER_API_KEY',
    models: ['anthropic/claude-sonnet-4.6', 'openai/gpt-4o', 'openai/gpt-5.2'],
    modelHint: 'Any id from openrouter.ai/models (provider/model). Empty: your OpenRouter default model.',
    modelOptional: true,
  },
  {
    id: 'gemini',
    label: 'Google Gemini',
    api: 'openai',
    endpoint: 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions',
    keyVariable: 'GEMINI_API_KEY',
    models: ['gemini-3.8-flash'],
    modelHint: 'A Gemini model; the key comes from Google AI Studio.',
  },
  {
    id: 'mistral',
    label: 'Mistral',
    api: 'openai',
    endpoint: 'https://api.mistral.ai/v1/chat/completions',
    keyVariable: 'MISTRAL_API_KEY',
    models: ['mistral-large-latest', 'mistral-small-latest'],
    modelHint: 'A Mistral chat model.',
  },
  {
    id: 'groq',
    label: 'Groq',
    api: 'openai',
    endpoint: 'https://api.groq.com/openai/v1/chat/completions',
    keyVariable: 'GROQ_API_KEY',
    models: ['llama-3.3-70b-versatile'],
    modelHint: 'A model hosted by Groq.',
  },
  {
    id: 'deepseek',
    label: 'DeepSeek',
    api: 'openai',
    endpoint: 'https://api.deepseek.com/chat/completions',
    keyVariable: 'DEEPSEEK_API_KEY',
    models: ['deepseek-flash', 'deepseek-v4-pro'],
    modelHint: 'A DeepSeek model.',
  },
  {
    id: 'azure',
    label: 'Azure OpenAI',
    api: 'openai',
    endpoint: '',
    endpointHint: 'https://<resource>.openai.azure.com/openai/v1/chat/completions',
    requiresEndpoint: true,
    headers: [{ name: 'api-key', value: '$[secret:AZURE_OPENAI_API_KEY]' }],
    models: ['gpt-4o-mini', 'gpt-4o'],
    modelHint: 'The model of your Azure deployment.',
  },
  {
    id: 'ollama',
    label: 'Ollama (local)',
    api: 'openai',
    endpoint: 'http://localhost:11434/v1/chat/completions',
    models: ['llama3.1', 'qwen2.5', 'mistral'],
    modelHint: 'A model pulled into Ollama (ollama pull …). No key needed.',
  },
  {
    id: 'lmstudio',
    label: 'LM Studio (local)',
    api: 'openai',
    endpoint: 'http://localhost:1234/v1/chat/completions',
    models: [],
    modelHint: 'The model loaded in LM Studio. No key needed.',
  },
  {
    id: 'custom',
    label: 'Custom (any OpenAI- or Anthropic-compatible server)',
    api: 'openai',
    endpoint: '',
    models: [],
    modelHint: 'The model name as the server knows it.',
  },
];

export const presetById = (id: string | undefined) => PRESETS.find((p) => p.id === id);

/** The preset of saved settings: the stored one, or the one whose protocol and endpoint match. */
export function presetOf(settings: { preset?: string; api?: Api; endpoint?: string }): Preset {
  const stored = presetById(settings.preset);
  if (stored) return stored;
  const api = settings.api ?? 'anthropic';
  const endpoint = (settings.endpoint ?? '').trim();
  if (api === 'anthropic' && !endpoint) return presetById('anthropic')!;
  if (api === 'openrouter') return presetById('openrouter')!;
  if (/\.openai\.azure\.com\//.test(endpoint)) return presetById('azure')!;
  return PRESETS.find((p) => p.api === api && p.endpoint && p.endpoint === endpoint) ?? presetById('custom')!;
}

/** Header names that carry credentials (their values must be secret references). */
export const carriesCredentials = (name: string) => /key|token|secret|password|auth/i.test(name);

const RESTRICTED = ['connection', 'content-length', 'expect', 'host', 'upgrade', 'content-type'];

/** What is wrong with a header row, checked before anything is sent (a pasted credential never leaves the browser). */
export function headerProblem(h: HeaderRow): string | null {
  const name = h.name.trim();
  const value = h.value.trim();
  if (!name && !value) return null;
  if (!/^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/.test(name)) return 'Not a header name.';
  if (RESTRICTED.includes(name.toLowerCase())) return `${name} is set by Sensible Studio itself.`;
  if (!value) return 'A value is needed.';
  if (carriesCredentials(name) && !/\$\[(secret|env):[A-Za-z_][A-Za-z0-9_.]*\]/.test(value)) {
    return `${name} carries a credential: refer to a secret, e.g. $[secret:NAME].`;
  }
  return null;
}

export const isReference = (v: string) => SECRET_REFERENCE.test(v.trim());
