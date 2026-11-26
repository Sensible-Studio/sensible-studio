import type { CfClient, CfConf, CfLanguages, CfTranslateRequest, CfTranslateResult, CfTranslationInfo, CfTranslationProvider, CfVersion, CfFolder, CfFragment, CfModel, CfModelSummary, CfReference, CfReferences, CfSearchResult, CfValue, DamItem, ModelSaveRequest, SaveRequest } from '../api';
import { CfServiceError, CfValidationError } from '../api';
import { languageName, languageRoot, pathIn } from '../languages';
import { validate, toFormValue } from '../fields';

export interface CfFixture {
  models: CfModelSummary[];
  model: Record<string, CfModel>;
  folder: Record<string, CfFolder>;
  fragment: Record<string, CfFragment>;
}

/**
 * In-memory CfClient over the responses recorded from studio-cfm (fixtures/cf). Writes are applied to the copy,
 * with the server's validation rules, so editor tests exercise the same contract.
 */
export class FixtureCfClient implements CfClient {
  private readonly data: CfFixture;
  readonly saves: SaveRequest[] = [];
  readonly modelSaves: ModelSaveRequest[] = [];
  private nextId = 1;
  /** Translation records of language copies, by path. */
  private readonly translations = new Map<string, CfTranslationInfo & { sourceSnapshot: Record<string, unknown> }>();
  /** When set, translate() fails like a connector without credentials. */
  missingCredentials = false;
  readonly translateRequests: CfTranslateRequest[] = [];
  readonly providerPaths: (string | undefined)[] = [];
  /** Versions per fragment path, oldest first, with the fragment as it was. */
  private readonly history = new Map<string, { version: CfVersion; fragment: CfFragment }[]>();

  constructor(fixture: CfFixture) {
    this.data = structuredClone(fixture);
  }

  async models() {
    return this.data.models;
  }

  async model(path: string) {
    const m = this.data.model[path];
    if (!m) throw new Error(`Not found: ${path}`);
    return m;
  }

  async folder(path: string) {
    const f = this.data.folder[path];
    if (!f) throw new Error(`Not found: ${path}`);
    return f;
  }

  async search(root: string, query: string, model: string | null, offset = 0, limit = 100): Promise<CfSearchResult> {
    const q = query.trim().toLowerCase();
    const hits = Object.values(this.data.fragment)
      .filter((f) => f.path.startsWith(`${root}/`))
      .filter((f) => !model || f.model === model)
      .filter((f) => !q || f.title.toLowerCase().includes(q) || f.name.toLowerCase().includes(q))
      .map(({ path, name, title, description, model: m, modelTitle, modified }) => ({ path, name, title, description, model: m, modelTitle, modified }));
    return { total: hits.length, truncated: false, fragments: hits.slice(offset, offset + limit) };
  }

  async fragment(path: string) {
    const f = this.data.fragment[path];
    if (!f) throw new Error(`Not found: ${path}`);
    return structuredClone(f);
  }

  async save(request: SaveRequest) {
    this.saves.push(request);
    const f = this.data.fragment[request.path];
    const model = f.model ? this.data.model[f.model] : undefined;
    if (model && request.variation === 'master') {
      const errors = [];
      for (const [name, value] of Object.entries(request.values)) {
        const field = model.fields.find((x) => x.name === name);
        if (!field) {
          errors.push({ field: name, message: 'Not an element of the model' });
          continue;
        }
        const message = validate(field, toFormValue(field, value as CfValue));
        if (message) errors.push({ field: name, message });
      }
      if (errors.length) throw new CfValidationError(errors);
    }
    const vals = (f.values[request.variation] ??= {});
    for (const [k, v] of Object.entries(request.values)) {
      if (v === null) delete vals[k];
      else vals[k] = v;
    }
    Object.assign((f.contentTypes[request.variation] ??= {}), request.contentTypes ?? {});
    if (request.title !== undefined) f.title = request.title;
    if (request.description !== undefined) f.description = request.description;
    if (request.tags !== undefined) f.tags = request.tags;
    f.modified = new Date().toISOString();
    return structuredClone(f);
  }

  async create(parent: string, title: string, model: string, name?: string): Promise<string> {
    const n = name || title.toLowerCase().replace(/[^a-z0-9]+/g, '-');
    const path = `${parent}/${n}`;
    const m = this.data.model[model];
    this.data.fragment[path] = {
      path,
      name: n,
      title,
      description: '',
      model,
      modelTitle: m?.title ?? null,
      modified: new Date().toISOString(),
      variations: [{ name: 'master', title: 'Master' }],
      values: { master: {} },
      contentTypes: { master: {} },
      tags: [],
    };
    return path;
  }

  async remove(path: string) {
    delete this.data.fragment[path];
  }

  async createVariation(path: string, title: string, name?: string, description?: string) {
    const f = this.data.fragment[path];
    const n = name || title.toLowerCase().replace(/[^a-z0-9]+/g, '-');
    f.variations.push({ name: n, title, description });
    f.values[n] = structuredClone(f.values.master ?? {});
    f.contentTypes[n] = structuredClone(f.contentTypes.master ?? {});
    return structuredClone(f);
  }

  async deleteVariation(path: string, name: string) {
    const f = this.data.fragment[path];
    f.variations = f.variations.filter((v) => v.name !== name);
    delete f.values[name];
    return structuredClone(f);
  }

  async languages(path: string): Promise<CfLanguages> {
    const lr = languageRoot(path);
    if (!lr) return { path, languageRoot: null };
    const site = lr.root.slice(0, lr.root.lastIndexOf('/'));
    const codes = new Set<string>([lr.language]);
    for (const p of Object.keys(this.data.fragment)) {
      const other = p.startsWith(`${site}/`) ? languageRoot(p) : null;
      if (other) codes.add(other.language);
    }
    const languages = [...codes].map((code) => ({ code, name: languageName(code) ?? code, root: `${site}/${code}` }));
    const copies: CfTranslationInfo[] = [];
    for (const code of codes) {
      if (code === lr.language) continue;
      const copy = pathIn(path, code)!;
      if (this.data.fragment[copy]) copies.push({ ...this.info(copy), language: code });
    }
    const out: CfLanguages = { path, languageRoot: lr.root, language: lr.language, languages, copies };
    if (this.translations.has(path)) out.translation = this.info(path);
    return out;
  }

  private info(path: string): CfTranslationInfo {
    const t = this.translations.get(path);
    if (!t) return { path, status: null };
    const { sourceSnapshot, ...rest } = t;
    const source = this.data.fragment[t.sourcePath!];
    const outdated = source
      ? Object.entries(source.values.master ?? {})
          .filter(([k, v]) => JSON.stringify(v) !== JSON.stringify(sourceSnapshot[k]))
          .map(([k]) => `master/${k}`)
      : [];
    return { ...structuredClone(rest), outdated };
  }

  async translationProviders(path?: string): Promise<CfTranslationProvider[]> {
    this.providerPaths.push(path);
    return [
      {
        name: 'studio-ai',
        label: 'Claude (claude-sonnet-5-5)',
        available: !this.missingCredentials,
        default: true,
        problem: this.missingCredentials ? 'No API key' : undefined,
        code: this.missingCredentials ? 'MISSING_CREDENTIALS' : undefined,
      },
      { name: 'studio-pseudo', label: 'Pseudo translation (testing)', available: true, default: false },
    ];
  }

  /** Translates like the pseudo connector: "[fr] " before translatable texts. */
  async translate(request: CfTranslateRequest): Promise<CfTranslateResult[]> {
    this.translateRequests.push(structuredClone(request));
    if (this.missingCredentials && request.provider !== 'studio-pseudo') {
      throw new CfServiceError('No API key: set it in the OSGi configuration "Sensible Studio AI Translation"', 'MISSING_CREDENTIALS');
    }
    const out: CfTranslateResult[] = [];
    for (const language of request.languages) {
      for (const source of request.paths) {
        const f = this.data.fragment[source];
        const lr = languageRoot(source);
        if (!f || !lr || lr.language === language) continue;
        const target = pathIn(source, language)!;
        const created = !this.data.fragment[target];
        const model = f.model ? this.data.model[f.model] : undefined;
        const copy = structuredClone(f);
        copy.path = target;
        copy.title = `[${language}] ${f.title}`;
        let translated = 1;
        for (const field of model?.fields ?? []) {
          const v = copy.values.master?.[field.name];
          if (field.translatable && typeof v === 'string' && v) {
            copy.values.master[field.name] = v.startsWith('<') ? v.replace(/>([^<\s][^<]*)</, `>[${language}] $1<`) : `[${language}] ${v}`;
            translated++;
          }
        }
        this.data.fragment[target] = copy;
        this.translations.set(target, {
          path: target, sourcePath: source, sourceLanguage: lr.language, targetLanguage: language, status: 'TRANSLATED',
          provider: request.provider ?? 'studio-ai', attribution: 'Translated by test', translatedAt: new Date().toISOString(),
          translatedBy: 'admin', failed: [], sourceSnapshot: structuredClone(f.values.master ?? {}),
        });
        out.push({ source, path: target, language, created, translated, unchanged: 0, failed: [], status: 'TRANSLATED', attribution: 'Translated by test' });
      }
    }
    return out;
  }

  async approveTranslation(path: string) {
    const t = this.translations.get(path);
    if (!t) throw new Error(`Not a language version: ${path}`);
    t.status = 'APPROVED';
    t.approvedAt = new Date().toISOString();
    t.approvedBy = 'admin';
    return this.languages(path);
  }

  async versions(path: string) {
    return [...(this.history.get(path) ?? [])].reverse().map((h) => structuredClone(h.version));
  }

  async versionContent(path: string, version: string) {
    const h = (this.history.get(path) ?? []).find((x) => x.version.name === version);
    if (!h) throw new Error(`No version ${version}`);
    return { ...structuredClone(h.fragment), version: structuredClone(h.version) };
  }

  async createVersion(path: string, label?: string, comment?: string) {
    const list = this.history.get(path) ?? [];
    if (label && list.some((h) => h.version.labels.includes(label))) {
      throw new CfValidationError([{ field: 'label', message: `Another version is labelled "${label}".` }]);
    }
    const f = this.data.fragment[path];
    const version: CfVersion = {
      name: `1.${list.length}`,
      created: new Date(Date.now() + list.length).toISOString(),
      createdBy: 'admin',
      labels: label ? [label] : [],
      comment: comment || null,
      title: f.title,
    };
    list.push({ version, fragment: structuredClone(f) });
    this.history.set(path, list);
    return structuredClone(version);
  }

  async restoreVersion(path: string, version: string) {
    const h = (this.history.get(path) ?? []).find((x) => x.version.name === version);
    if (!h) throw new Error(`No version ${version}`);
    const saved = await this.createVersion(path, undefined, `Before restoring ${h.version.labels[0] ?? `version ${version}`}`);
    this.data.fragment[path] = structuredClone(h.fragment);
    return { saved, fragment: structuredClone(h.fragment) };
  }

  async confs(): Promise<CfConf[]> {
    const confs = new Map<string, number>();
    for (const m of Object.values(this.data.model)) {
      const conf = m.conf ?? m.path.slice(0, m.path.indexOf('/settings/'));
      confs.set(conf, (confs.get(conf) ?? 0) + 1);
    }
    return [...confs].map(([path, models]) => ({ path, title: path.slice(path.lastIndexOf('/') + 1), models }));
  }

  async createModel(conf: string, title: string, name?: string, description?: string) {
    const n = name || title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
    const path = `${conf}/settings/dam/cfm/models/${n}`;
    if (this.data.model[path]) throw new CfValidationError([{ field: 'name', message: `A model named ${n} exists already.` }]);
    const model: CfModel = { path, title, description: description ?? '', conf, enabled: true, fields: [] };
    this.data.model[path] = model;
    this.data.models.push({ path, title, description: model.description, conf, enabled: true });
    return path;
  }

  /** Mirrors the server's checks (names, labels, options); new fields get an id. */
  async saveModel(request: ModelSaveRequest) {
    this.modelSaves.push(structuredClone(request));
    const errors: { field: string; message: string }[] = [];
    const names = new Set<string>();
    request.fields.forEach((f, i) => {
      if (!f.label?.trim()) errors.push({ field: `fields.${i}.label`, message: 'A label is required.' });
      if (f.kind === 'tab') return;
      if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(f.name ?? '')) errors.push({ field: `fields.${i}.name`, message: "Use letters, digits and '_', starting with a letter or '_'." });
      else if (names.has(f.name)) errors.push({ field: `fields.${i}.name`, message: `Another element is named ${f.name}.` });
      names.add(f.name);
      if (f.kind === 'enumeration' && !f.options?.length) errors.push({ field: `fields.${i}.options`, message: 'Add at least one option.' });
    });
    if (!request.title.trim()) errors.push({ field: 'title', message: 'A title is required.' });
    if (errors.length) throw new CfValidationError(errors);
    const model = this.data.model[request.path];
    const saved: CfModel = {
      ...model,
      title: request.title,
      description: request.description,
      fields: request.fields.map((f) => ({ ...structuredClone(f), id: f.id ?? `new${this.nextId++}`, name: f.kind === 'tab' ? f.id ?? `tab${this.nextId++}` : f.name })),
    };
    this.data.model[request.path] = saved;
    return structuredClone(saved);
  }

  async setModelStatus(path: string, enabled: boolean) {
    this.data.model[path] = { ...this.data.model[path], enabled };
    const summary = this.data.models.find((m) => m.path === path);
    if (summary) summary.enabled = enabled;
    return structuredClone(this.data.model[path]);
  }

  async deleteModel(path: string, force = false) {
    const used = (await this.modelUsage(path)).fragments;
    if (used.length && !force) throw new CfValidationError([{ field: 'model', message: `Content Fragments use this model, e.g. ${used[0]}.` }]);
    delete this.data.model[path];
    this.data.models = this.data.models.filter((m) => m.path !== path);
  }

  async modelUsage(path: string) {
    return { fragments: Object.values(this.data.fragment).filter((f) => f.model === path).map((f) => f.path), truncated: false };
  }

  /** References between the fixture's records and files, from the model's reference elements (pages are not in the fixture). */
  async references(path: string): Promise<CfReferences> {
    const refFields = (f: CfFragment) =>
      (f.model ? this.data.model[f.model]?.fields ?? [] : []).filter((x) => x.kind === 'content-reference' || x.kind === 'fragment-reference');
    const targets = (f: CfFragment) => {
      const out = new Map<string, string[]>();
      for (const field of refFields(f)) {
        for (const values of Object.values(f.values)) {
          const v = values[field.name];
          for (const t of (Array.isArray(v) ? v : [v]).filter((x): x is string => typeof x === 'string' && x.startsWith('/'))) {
            out.set(t, [...new Set([...(out.get(t) ?? []), field.name])]);
          }
        }
      }
      return out;
    };
    const self = this.data.fragment[path];
    if (!self) throw new Error(`Not found: ${path}`);
    const describe = (p: string): CfReference => {
      const f = this.data.fragment[p];
      return f ? { path: p, title: f.title, type: 'fragment', model: f.modelTitle ?? undefined } : { path: p, title: p.slice(p.lastIndexOf('/') + 1), type: 'asset' };
    };
    const references = [...targets(self)].map(([p, elements]) => ({ ...describe(p), elements }));
    const usedIn = Object.values(this.data.fragment)
      .filter((f) => f.path !== path && targets(f).has(path))
      .map((f) => ({ ...describe(f.path), via: [{ node: 'jcr:content/data/master', property: targets(f).get(path)![0], resourceType: null }] }));
    return { path, usedIn, references, truncated: false };
  }

  async listDam(path: string): Promise<DamItem[]> {
    const folder = this.data.folder[path];
    if (!folder) return [];
    return [
      ...folder.folders.map((f) => ({ name: f.name, path: f.path, title: f.title, kind: 'folder' as const })),
      ...folder.fragments.map((f) => ({ name: f.name, path: f.path, title: f.title, kind: 'fragment' as const })),
    ];
  }

  thumbnailUrl(assetPath: string) {
    return `data:image/svg+xml,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><title>${assetPath}</title></svg>`)}`;
  }

  async currentUser() {
    return 'admin';
  }
}
