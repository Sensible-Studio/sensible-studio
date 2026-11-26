import { describe, expect, it } from 'vitest';
import { headerProblem, presetOf, PRESETS } from './presets';

describe('provider presets', () => {
  it('recognise saved settings', () => {
    expect(presetOf({ api: 'anthropic' }).id).toBe('anthropic');
    expect(presetOf({ api: 'openrouter' }).id).toBe('openrouter');
    expect(presetOf({ api: 'openai', endpoint: 'https://api.mistral.ai/v1/chat/completions' }).id).toBe('mistral');
    expect(presetOf({ api: 'openai', endpoint: 'https://acme.openai.azure.com/openai/v1/chat/completions' }).id).toBe('azure');
    expect(presetOf({ api: 'openai', endpoint: 'https://llm.example.com/v1/chat/completions' }).id).toBe('custom');
    expect(presetOf({ preset: 'groq', api: 'openai' }).id).toBe('groq');
  });

  it('use OpenAI-compatible endpoints except Anthropic and OpenRouter', () => {
    for (const p of PRESETS) {
      if (p.endpoint) expect(p.endpoint).toMatch(/^https?:\/\/.+\/chat\/completions$/);
    }
  });

  it('check header rows', () => {
    expect(headerProblem({ name: '', value: '' })).toBeNull();
    expect(headerProblem({ name: 'api-key', value: '$[secret:AZ]' })).toBeNull();
    expect(headerProblem({ name: 'Authorization', value: 'Bearer $[env:TOKEN]' })).toBeNull();
    expect(headerProblem({ name: 'X-Team', value: 'content' })).toBeNull();
    expect(headerProblem({ name: 'api-key', value: 'literal' })).toMatch(/credential/);
    expect(headerProblem({ name: 'Host', value: 'x' })).toMatch(/set by Sensible Studio/);
    expect(headerProblem({ name: 'bad name', value: 'x' })).toMatch(/Not a header name/);
  });
});
