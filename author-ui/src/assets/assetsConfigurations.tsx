import './assetsConfigurations.scss';
import {
  ActionButton,
  Button,
  Checkbox,
  Item,
  NumberField,
  Picker,
  Radio,
  RadioGroup,
  Switch,
  Text,
  TextField,
} from '@adobe/react-spectrum';
import Close from '@spectrum-icons/workflow/Close';
import { useCallback, useEffect, useState } from 'react';
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import { useScreenTitle } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import type { DamClient } from './api';
import {
  loadAssetsConfig,
  saveAssetsConfig,
  validityLabel,
  type AssetsConfigName,
  type AssetsConfigurations,
  type LinkShareSettings,
  type ShareMode,
  type ValidityUnit,
} from './assetsConfig';
import { routeUrl, type AssetsConfigEditor, type AssetsRoute } from './route';
import { WatermarkSection, watermarkSummary } from './watermark';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** The cards, in AEM's order, with AEM's names. */
export const CONFIG_CARDS: Array<{ key: AssetsConfigEditor; title: string; description: string }> = [
  { key: 'uploadrestrictions', title: 'Upload Restrictions', description: 'The types of file that may be uploaded' },
  { key: 'digitalrightsmanagement', title: 'DRM Configuration', description: 'Files with a license are downloaded only after it is agreed to' },
  { key: 'watermark', title: 'System Watermarking Profile', description: 'The picture drawn on the renditions of the profiles that ask for it' },
  { key: 'assetsomnisearch', title: 'Assets Omnisearch Configuration', description: 'How many results are counted, and where a search starts' },
  { key: 'assetduplicationdetector', title: 'Asset Duplication Detector', description: 'Whether an upload is checked for the same file elsewhere in Media' },
  { key: 'linkshare', title: 'Link Share', description: 'What the Share Link dialog starts with, for how long, and who may share' },
];

/** One line saying what a setting is now, for its card. */
export function cardSummary(key: AssetsConfigEditor, c: AssetsConfigurations): string {
  switch (key) {
    case 'uploadrestrictions':
      return c.uploadRestrictions.allowedMimeTypes.length ? `Only ${c.uploadRestrictions.allowedMimeTypes.join(', ')}` : 'Any type Media accepts';
    case 'digitalrightsmanagement':
      return c.drm.enabled ? 'On: licenses are agreed to before download' : 'Off';
    case 'watermark':
      return watermarkSummary(c.watermark);
    case 'assetsomnisearch':
      return `Counts up to ${c.omnisearch.guessTotal} · ${c.omnisearch.currentFolder ? 'searches the current folder' : 'searches all of Media'}`;
    case 'assetduplicationdetector':
      return c.duplicates.enabled ? 'On' : 'Off';
    default: {
      const l = c.linkShare;
      const what = [l.includeOriginals && 'originals', l.includeRenditions && 'renditions'].filter(Boolean).join(' and ');
      const who = l.groups.length ? (l.groupsMode === 'allow' ? ` · only ${l.groups.join(', ')}` : ` · not ${l.groups.join(', ')}`) : '';
      return `${what.charAt(0).toUpperCase()}${what.slice(1)} · valid ${validityLabel(l.validityAmount, l.validityUnit)}${who}`;
    }
  }
}

/** A list of words added one at a time (MIME types, groups), each removable. */
function Chips({
  label,
  values,
  onChange,
  placeholder,
  check,
  suggestions = [],
  isDisabled,
}: {
  label: string;
  values: string[];
  onChange(v: string[]): void;
  placeholder: string;
  check?(v: string): string | null;
  suggestions?: string[];
  isDisabled?: boolean;
}) {
  const [text, setText] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const add = (raw: string) => {
    const v = raw.trim();
    if (!v) return;
    const p = check?.(v) ?? null;
    setProblem(p);
    if (p) return;
    if (!values.includes(v)) onChange([...values, v]);
    setText('');
  };
  return (
    <div className="studio-assetsconfig__chips">
      <div className="studio-assetsconfig__chips-add">
        <TextField
          label={label}
          value={text}
          onChange={(v) => {
            setText(v);
            setProblem(null);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              add(text);
            } else {
              e.continuePropagation();
            }
          }}
          placeholder={placeholder}
          validationState={problem ? 'invalid' : undefined}
          errorMessage={problem ?? undefined}
          isDisabled={isDisabled}
          width="size-3600"
        />
        <Button variant="secondary" onPress={() => add(text)} isDisabled={isDisabled || !text.trim()}>
          Add
        </Button>
      </div>
      {suggestions.filter((s) => !values.includes(s)).length > 0 && !isDisabled && (
        <div className="studio-assetsconfig__suggest">
          <span className="studio-assets__muted">Add:</span>
          {suggestions
            .filter((s) => !values.includes(s))
            .map((s) => (
              <ActionButton key={s} isQuiet onPress={() => add(s)}>
                {s}
              </ActionButton>
            ))}
        </div>
      )}
      <ul className="studio-assetsconfig__chip-list" aria-label={label}>
        {values.map((v) => (
          <li key={v}>
            <span>{v}</span>
            {!isDisabled && (
              <ActionButton isQuiet aria-label={`Remove ${v}`} onPress={() => onChange(values.filter((x) => x !== v))}>
                <Close size="S" />
              </ActionButton>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

const MIME = /^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}\/(\*|[a-z0-9][a-z0-9!#$&^_.+-]{0,126})$/;

function ModePicker({ label, value, onChange, isDisabled }: { label: string; value: ShareMode; onChange(m: ShareMode): void; isDisabled?: boolean }) {
  return (
    <Picker label={label} selectedKey={value} onSelectionChange={(k) => onChange(k as ShareMode)} isDisabled={isDisabled} width="size-2000">
      <Item key="editable">Editable</Item>
      <Item key="readonly">Read-only</Item>
      <Item key="hidden">Hidden</Item>
    </Picker>
  );
}

/** The form of one setting; `draft` is what Save sends. */
function Editor({
  name,
  config,
  draft,
  setDraft,
  readOnly,
  client,
  onWatermark,
}: {
  name: AssetsConfigEditor;
  config: AssetsConfigurations;
  draft: AssetsConfigurations;
  setDraft(c: AssetsConfigurations): void;
  readOnly: boolean;
  client: DamClient;
  onWatermark(c: AssetsConfigurations): void;
}) {
  switch (name) {
    case 'uploadrestrictions':
      return (
        <>
          <p className="studio-assetsconfig__intro">
            The MIME types that may be uploaded to Media, by people and in archives that are extracted. None listed: any type Media accepts. A family
            with <code>/*</code>, as <code>image/*</code>.
          </p>
          <Chips
            label="Allowed MIME Types"
            values={draft.uploadRestrictions.allowedMimeTypes}
            onChange={(v) => setDraft({ ...draft, uploadRestrictions: { allowedMimeTypes: v } })}
            placeholder="image/png"
            check={(v) => (MIME.test(v.toLowerCase()) ? null : 'A MIME type, as image/png, or image/* for all images')}
            suggestions={['image/*', 'video/*', 'application/pdf']}
            isDisabled={readOnly}
          />
        </>
      );
    case 'digitalrightsmanagement':
      return (
        <>
          <p className="studio-assetsconfig__intro">
            A file has a license when its metadata has <code>adobe_dam:restrictions</code> (the license&apos;s text) or{' '}
            <code>xmpRights:WebStatement</code> (the address of a page with it), as in AEM. With DRM on, Download shows the license of each such file
            and leaves out the ones not agreed to; other files download as before. Share Link is not affected: whoever shares decides.
          </p>
          <Switch isSelected={draft.drm.enabled} onChange={(on) => setDraft({ ...draft, drm: { ...draft.drm, enabled: on } })} isDisabled={readOnly}>
            Enable DRM
          </Switch>
          {draft.drm.enabled && (
            <p className="studio-assets__muted" role="note">
              {config.watermark?.ready
                ? 'A licensed picture is shown large with the system watermark on it.'
                : 'No watermark is set: licensed pictures are previewed as they are. Set one in System Watermarking Profile to mark their previews.'}
            </p>
          )}
          <TextField
            label="DRM Usage Terms URL"
            description="The general terms, linked next to each license (a page of the site, or an https address)"
            value={draft.drm.usageTermsUrl}
            onChange={(v) => setDraft({ ...draft, drm: { ...draft.drm, usageTermsUrl: v } })}
            isDisabled={readOnly}
            width="size-6000"
            maxWidth="100%"
          />
        </>
      );
    case 'watermark':
      return (
        <>
          <p className="studio-assetsconfig__intro">The same setting as on the Processing Profiles page.</p>
          <WatermarkSection client={client} watermark={config.watermark} canEdit={!readOnly} onSaved={(w) => onWatermark({ ...config, watermark: w })} />
        </>
      );
    case 'assetsomnisearch':
      return (
        <>
          <RadioGroup
            label="Result count limit"
            description="How many results are counted; past it the count says, for example, 1000+. Every result can still be scrolled to."
            value={String(draft.omnisearch.guessTotal)}
            onChange={(v) => setDraft({ ...draft, omnisearch: { ...draft.omnisearch, guessTotal: Number(v) as 1000 | 500 | 200 } })}
            isDisabled={readOnly}
          >
            <Radio value="1000">1000</Radio>
            <Radio value="500">500</Radio>
            <Radio value="200">200</Radio>
          </RadioGroup>
          <RadioGroup
            label="Search scope"
            description="Where a search starts; the Filters rail can still switch it"
            value={draft.omnisearch.currentFolder ? 'folder' : 'all'}
            onChange={(v) => setDraft({ ...draft, omnisearch: { ...draft.omnisearch, currentFolder: v === 'folder' } })}
            isDisabled={readOnly}
          >
            <Radio value="folder">Search only in current folder</Radio>
            <Radio value="all">Search across all folders</Radio>
          </RadioGroup>
        </>
      );
    case 'assetduplicationdetector':
      return (
        <>
          <p className="studio-assetsconfig__intro">
            Before a file is uploaded, Media looks for the same file anywhere in Media and asks whether to upload it anyway. Files over 512 MB are not
            checked (the browser computes the checksum).
          </p>
          <Switch isSelected={draft.duplicates.enabled} onChange={(on) => setDraft({ ...draft, duplicates: { ...draft.duplicates, enabled: on } })} isDisabled={readOnly}>
            Enabled
          </Switch>
          <TextField label="Detect Metadata Field" value={draft.duplicates.field} isReadOnly description="The file's SHA-1, kept for every file" width="size-3600" />
        </>
      );
    default: {
      const l = draft.linkShare;
      const set = (patch: Partial<LinkShareSettings>) => setDraft({ ...draft, linkShare: { ...l, ...patch } });
      const none = !l.includeOriginals && !l.includeRenditions;
      return (
        <>
          <h3 className="studio-assetsconfig__sub">Initial settings</h3>
          <p className="studio-assetsconfig__intro">
            What the Share Link dialog starts with. Either originals or renditions must be included, or both. Read-only and hidden choices are kept as
            set here, also when the dialog is bypassed.
          </p>
          <div className="studio-assetsconfig__row">
            <Checkbox isSelected={l.includeOriginals} onChange={(v) => set({ includeOriginals: v })} isDisabled={readOnly}>
              Include Originals
            </Checkbox>
            <ModePicker label="Originals in the dialog" value={l.originalsMode} onChange={(m) => set({ originalsMode: m })} isDisabled={readOnly} />
          </div>
          <div className="studio-assetsconfig__row">
            <Checkbox isSelected={l.includeRenditions} onChange={(v) => set({ includeRenditions: v })} isDisabled={readOnly}>
              Include Renditions
            </Checkbox>
            <ModePicker label="Renditions in the dialog" value={l.renditionsMode} onChange={(m) => set({ renditionsMode: m })} isDisabled={readOnly} />
          </div>
          {none && <p className="studio-assets__dialog-error">Include the originals, the renditions, or both.</p>}
          <h3 className="studio-assetsconfig__sub">Expiration date</h3>
          <div className="studio-assetsconfig__row">
            <NumberField
              label="Validity period"
              value={l.validityAmount}
              minValue={1}
              maxValue={l.validityUnit === 'hours' ? 17520 : l.validityUnit === 'days' ? 730 : l.validityUnit === 'months' ? 24 : 2}
              onChange={(v) => set({ validityAmount: Number.isFinite(v) ? Math.round(v) : 1 })}
              isDisabled={readOnly}
              width="size-1600"
            />
            <Picker label="Unit" selectedKey={l.validityUnit} onSelectionChange={(k) => set({ validityUnit: k as ValidityUnit })} isDisabled={readOnly} width="size-1600">
              <Item key="hours">hours</Item>
              <Item key="days">days</Item>
              <Item key="months">months</Item>
              <Item key="years">years</Item>
            </Picker>
          </div>
          <p className="studio-assets__muted">The dialog starts with it; a link can be valid for at most the longer of 90 days and this.</p>
          <h3 className="studio-assetsconfig__sub">Link Share button in the action bar</h3>
          <p className="studio-assetsconfig__intro">
            Without groups, everyone who can see the files may share them. Administrators always may.
          </p>
          <RadioGroup
            label="Groups"
            orientation="horizontal"
            value={l.groupsMode}
            onChange={(v) => set({ groupsMode: v as 'allow' | 'deny' })}
            isDisabled={readOnly}
          >
            <Radio value="allow">Allow only these groups</Radio>
            <Radio value="deny">Deny these groups</Radio>
          </RadioGroup>
          <Chips label="Select Groups" values={l.groups} onChange={(g) => set({ groups: g })} placeholder="group id" isDisabled={readOnly} />
        </>
      );
    }
  }
}

const SERVER_NAME: Record<AssetsConfigEditor, AssetsConfigName | null> = {
  uploadrestrictions: 'uploadrestrictions',
  digitalrightsmanagement: 'drm',
  watermark: null,
  assetsomnisearch: 'omnisearch',
  assetduplicationdetector: 'duplicates',
  linkshare: 'linkshare',
};

function payload(name: AssetsConfigName, c: AssetsConfigurations): object {
  switch (name) {
    case 'uploadrestrictions':
      return c.uploadRestrictions;
    case 'drm':
      return c.drm;
    case 'omnisearch':
      return c.omnisearch;
    case 'duplicates':
      return { enabled: c.duplicates.enabled };
    default:
      return c.linkShare;
  }
}

/**
 * AEM's Tools › Assets › Assets Configurations: the six settings as cards, and each one's form at AEM's address
 * (`…/assetsconfigurations/editors/<name>.html`). Administrators save; others see the values.
 */
export function AssetsConfigurationsPage({
  client,
  navigate,
  editor,
}: {
  client: DamClient;
  navigate(route: AssetsRoute): void;
  editor?: AssetsConfigEditor;
}) {
  const card = CONFIG_CARDS.find((c) => c.key === editor);
  useScreenTitle(card?.title ?? 'Assets Configurations', `${card?.title ?? 'Assets Configurations'} | Sensible Studio Media`);
  const [config, setConfig] = useState<AssetsConfigurations | null>(null);
  const [draft, setDraft] = useState<AssetsConfigurations | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  const load = useCallback(() => {
    setError(null);
    loadAssetsConfig(true).then(
      (c) => {
        setConfig(c);
        setDraft(c);
      },
      (e: unknown) => setError(message(e)),
    );
  }, []);
  useEffect(load, [load]);
  useEffect(() => {
    setSaveError(null);
    setSaved(false);
    if (config) setDraft(config);
  }, [editor]); // eslint-disable-line react-hooks/exhaustive-deps

  const admin = config?.admin === true;
  const serverName = editor ? SERVER_NAME[editor] : null;
  const dirty = !!(config && draft && serverName && JSON.stringify(payload(serverName, config)) !== JSON.stringify(payload(serverName, draft)));
  const invalid = !!(draft && editor === 'linkshare' && !draft.linkShare.includeOriginals && !draft.linkShare.includeRenditions);

  const save = async () => {
    if (!draft || !serverName) return;
    setBusy(true);
    setSaveError(null);
    try {
      const c = await saveAssetsConfig(serverName, payload(serverName, draft));
      setConfig(c);
      setDraft(c);
      setSaved(true);
    } catch (e) {
      setSaveError(message(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="studio-assets__profiles studio-assetsconfig">
      <ToolBar
        url={routeUrl({ kind: 'assetsconfigurations', editor })}
        primary={
          editor && serverName && admin ? (
            <Button variant="accent" onPress={() => void save()} isDisabled={busy || !dirty || invalid}>
              Save
            </Button>
          ) : undefined
        }
        end={
          editor ? (
            <ActionButton isQuiet onPress={() => navigate({ kind: 'assetsconfigurations' })}>
              <Text>All settings</Text>
            </ActionButton>
          ) : undefined
        }
      />
      {error ? (
        <ErrorState title="Assets Configurations could not be loaded">{error}</ErrorState>
      ) : !config || !draft ? (
        <LoadingState label="Loading the settings" />
      ) : editor && card ? (
        <form
          className="studio-assetsconfig__form"
          aria-label={card.title}
          onSubmit={(e) => {
            e.preventDefault();
            if (dirty && !invalid) void save();
          }}
        >
          <h2 className="studio-assetsconfig__title">{card.title}</h2>
          {!admin && <p className="studio-assets__muted">Only an administrator changes these settings.</p>}
          <Editor
            name={editor}
            config={config}
            draft={draft}
            setDraft={(c) => {
              setDraft(c);
              setSaved(false);
            }}
            readOnly={!admin}
            client={client}
            onWatermark={(c) => {
              setConfig(c);
              setDraft(c);
            }}
          />
          {saveError && <p className="studio-assets__dialog-error">{saveError}</p>}
          {saved && !dirty && (
            <p className="studio-assets__muted" role="status">
              Saved.
            </p>
          )}
        </form>
      ) : (
        <ul className="studio-assetsconfig__cards" aria-label="Assets Configurations">
          {CONFIG_CARDS.map((c) => (
            <li key={c.key}>
              <a
                href={routeUrl({ kind: 'assetsconfigurations', editor: c.key })}
                onClick={(e) => {
                  e.preventDefault();
                  navigate({ kind: 'assetsconfigurations', editor: c.key });
                }}
              >
                <strong>{c.title}</strong>
                <span className="studio-assets__muted">{c.description}</span>
                <span className="studio-assetsconfig__now">{cardSummary(c.key, config)}</span>
              </a>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
