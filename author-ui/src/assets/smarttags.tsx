import {
  ActionButton,
  Button,
  Checkbox,
  Heading,
  NumberField,
  ProgressBar,
  ProgressCircle,
  Switch,
  TextField,
} from "@adobe/react-spectrum";
import Add from "@spectrum-icons/workflow/Add";
import Close from "@spectrum-icons/workflow/Close";
import { useCallback, useEffect, useState } from "react";
import {
  DAM_ROOT,
  type DamClient,
  type SmartTags,
  type SmartTagsSettings,
  type SmartTagsStatus,
} from "./api";
import { formatBytes } from "./format";
import type { AssetsRoute } from "./route";
import { AssetTagsField } from "./tagsField";
import { SmartTagTrainingSection } from "./smartTagTraining";
import { useScreenTitle } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { routeUrl } from './route';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** 0.81 → "81%". */
export function percent(v?: number | null): string {
  return typeof v === "number" && Number.isFinite(v)
    ? `${Math.round(v * 100)}%`
    : "";
}

/**
 * Tools > Assets > Smart Tags: the image model (installed once, from Hugging Face), which tags
 * it may choose from, and tagging what is already in the DAM.
 */
export function SmartTagsPage({
  client,
  navigate: _navigate,
}: {
  client: DamClient;
  navigate(route: AssetsRoute): void;
}) {
  const [status, setStatus] = useState<SmartTagsStatus | null>(null);
  const [settings, setSettings] = useState<SmartTagsSettings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [folder, setFolder] = useState(DAM_ROOT);
  const [coloring, setColoring] = useState(false);
  const [colorAgain, setColorAgain] = useState(false);
  const [learned, setLearned] = useState(0);

  /** Color tags for the files of the folder: 2000 a call, until none is left. */
  const findColors = async () => {
    setColoring(true);
    let tagged = 0;
    let skipped = 0;
    try {
      const root = folder.trim().replace(/\/+$/, "") || DAM_ROOT;
      for (let round = 0; round < 50; round++) {
        const r = await client.colorTag([root], colorAgain && round === 0);
        tagged += r.tagged;
        skipped = r.skipped;
        if (!r.more) break;
      }
      setNotice(
        tagged === 0
          ? "Every picture there has its colors already"
          : `Colors found for ${tagged} file${tagged === 1 ? "" : "s"}${skipped ? ` (${skipped} without a picture)` : ""}`,
      );
    } catch (e) {
      setError(message(e));
    } finally {
      setColoring(false);
    }
  };

  const load = useCallback(async () => {
    try {
      const s = await client.smartTagsStatus();
      setStatus(s);
      setSettings((cur) => cur ?? s.settings);
      setError(null);
    } catch (e) {
      setError(message(e));
    }
  }, [client]);

  useScreenTitle('Auto tags', "Auto tags | Sensible Studio Media");
  useEffect(() => {
    void load();
  }, [load]);

  // Follow the download.
  const downloading =
    status?.install.state === "downloading" ||
    status?.searchInstall?.state === "downloading";
  useEffect(() => {
    if (!downloading) return undefined;
    const t = window.setInterval(() => void load(), 1500);
    return () => window.clearInterval(t);
  }, [downloading, load]);

  if (!status || !settings) {
    return error ? (
      <div className="studio-assets__profiles">
        <p className="studio-assets__dialog-error">{error}</p>
      </div>
    ) : (
      <div className="studio-assets__loading">
        <ProgressCircle isIndeterminate aria-label="Loading" />
      </div>
    );
  }

  const model = status.model;
  const install = status.install;
  const installed = model.installed === true;
  const searchModel = status.searchModel;
  const searchInstall = status.searchInstall;
  const set = <K extends keyof SmartTagsSettings>(
    k: K,
    v: SmartTagsSettings[K],
  ) => setSettings({ ...settings, [k]: v });

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      setSettings(await client.saveSmartTagsSettings(settings));
      setNotice("Settings saved");
    } catch (e) {
      setError(message(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="studio-assets__profiles studio-assets__smarttags">
      <ToolBar
        url={routeUrl({ kind: "smarttags" })}
        primary={
          status.admin && (
            <Button
              variant="accent"
              onPress={() => void save()}
              isDisabled={saving}
            >
              Save
            </Button>
          )
        }
      />
      {error && <p className="studio-assets__dialog-error">{error}</p>}

      <section
        className="studio-assets__smarttags-section"
        aria-labelledby="studio-st-model"
      >
        <Heading level={2} id="studio-st-model">
          Image model
        </Heading>
        <p>
          <strong>{model.model ?? "siglip-base-patch16-224"}</strong> (
          {model.license ?? "Apache-2.0"}, Google) recognises what is in a
          picture and compares it with the titles of your tags. It runs inside
          Sensible Studio, on this computer: no image leaves it.
        </p>
        {model.error ? (
          <p className="studio-assets__dialog-error">
            The image service does not answer: {model.error}
          </p>
        ) : installed ? (
          <p className="studio-assets__smarttags-ok">
            Installed{model.loaded ? " and loaded" : ""}.
          </p>
        ) : downloading ? (
          <ProgressBar
            label={`Downloading ${install.file ?? ""}`}
            value={install.total ? (100 * install.bytes) / install.total : 0}
            valueLabel={`${formatBytes(install.bytes)} of ${formatBytes(install.total)}`}
            width="100%"
            maxWidth="size-6000"
          />
        ) : (
          <>
            <p>
              Not installed. The model is downloaded once from Hugging Face (
              {model.repository ?? "google/siglip-base-patch16-224"}, about 815
              MB) and checked against its pinned SHA-256 before it is used.
            </p>
            {install.state === "failed" && (
              <p className="studio-assets__dialog-error">
                The last download failed: {install.error}
              </p>
            )}
            {status.admin ? (
              <div>
                <Button
                  variant="primary"
                  onPress={async () => {
                    try {
                      await client.installSmartTagsModel();
                      void load();
                    } catch (e) {
                      setError(message(e));
                    }
                  }}
                >
                  Download and install
                </Button>
              </div>
            ) : (
              <p className="studio-assets__muted">Only admin can install it.</p>
            )}
          </>
        )}
      </section>

      {searchModel && (
        <section
          className="studio-assets__smarttags-section"
          aria-labelledby="studio-st-search"
        >
          <Heading level={2} id="studio-st-search">
            Search in any language
          </Heading>
          <p>
            <strong>{searchModel.model ?? "siglip-base-patch16-256-multilingual"}</strong>{" "}
            ({searchModel.license ?? "Apache-2.0"}, Google) finds pictures by a
            description in about a hundred languages (“un cort roșu pe munte”,
            “ein rotes Zelt”), without a translation service. Optional: without
            it, descriptions are read in English.
          </p>
          {searchModel.error ? (
            <p className="studio-assets__dialog-error">
              The image service does not answer: {searchModel.error}
            </p>
          ) : searchModel.installed ? (
            <p className="studio-assets__smarttags-ok">
              Installed{searchModel.loaded ? " and loaded" : ""}. Pictures
              processed from now on are looked at by it too; for those already
              in Media, use “Look at them now” after a search.
            </p>
          ) : searchInstall?.state === "downloading" ? (
            <ProgressBar
              label={`Downloading ${searchInstall.file ?? ""}`}
              value={searchInstall.total ? (100 * searchInstall.bytes) / searchInstall.total : 0}
              valueLabel={`${formatBytes(searchInstall.bytes)} of ${formatBytes(searchInstall.total)}`}
              width="100%"
              maxWidth="size-6000"
            />
          ) : (
            <>
              <p>
                Not installed. Downloaded once from Hugging Face (
                {searchModel.repository ?? "google/siglip-base-patch16-256-multilingual"},
                about 1.5 GB) and checked against its pinned SHA-256. It needs
                about 1.5 GB more memory while it is in use.
              </p>
              {searchInstall?.state === "failed" && (
                <p className="studio-assets__dialog-error">
                  The last download failed: {searchInstall.error}
                </p>
              )}
              {status.admin ? (
                <div>
                  <Button
                    variant="secondary"
                    onPress={async () => {
                      try {
                        await client.installSearchModel();
                        void load();
                      } catch (e) {
                        setError(message(e));
                      }
                    }}
                  >
                    Download and install
                  </Button>
                </div>
              ) : (
                <p className="studio-assets__muted">Only admin can install it.</p>
              )}
            </>
          )}
        </section>
      )}

      <section
        className="studio-assets__smarttags-section"
        aria-labelledby="studio-st-settings"
      >
        <Heading level={2} id="studio-st-settings">
          Settings
        </Heading>
        <Switch
          isSelected={settings.enabled}
          onChange={(v) => set("enabled", v)}
          isDisabled={!status.admin}
        >
          Tag images when they are uploaded or processed
        </Switch>
        <AssetTagsField
          label="Tags to choose from"
          description="The model picks from the tags below these (e.g. Activity, Season). Leave out tags a picture cannot show, like regions."
          values={settings.tagRoots}
          onChange={(v) => set("tagRoots", v)}
        />
        <Checkbox
          isSelected={settings.keywords}
          onChange={(v) => set("keywords", v)}
          isDisabled={!status.admin}
        >
          Also add plain keywords (beach, mountain, dog, ...): searchable, not
          tags
        </Checkbox>
        <div className="studio-assets__smarttags-numbers">
          <NumberField
            label="At most, per file"
            minValue={1}
            maxValue={20}
            value={settings.max}
            onChange={(v) => set("max", Number.isFinite(v) ? v : 5)}
            isDisabled={!status.admin}
          />
          <NumberField
            label="Least confidence"
            minValue={0}
            maxValue={1}
            step={0.01}
            formatOptions={{ style: "percent" }}
            value={settings.minConfidence}
            onChange={(v) =>
              set("minConfidence", Number.isFinite(v) ? v : 0.05)
            }
            isDisabled={!status.admin}
          />
        </div>
      </section>

      <SmartTagTrainingSection
        client={client}
        admin={status.admin}
        onNotice={setNotice}
        onLearned={setLearned}
      />

      <section
        className="studio-assets__smarttags-section"
        aria-labelledby="studio-st-run"
      >
        <Heading level={2} id="studio-st-run">
          Tag what is already there
        </Heading>
        <p className="studio-assets__muted">
          New uploads are tagged by themselves. For assets already in the DAM,
          choose a folder: its images are tagged in the background, from their
          renditions.
        </p>
        <div className="studio-assets__smarttags-run">
          <TextField
            label="Folder"
            value={folder}
            onChange={setFolder}
            width="size-4600"
          />
          <Button
            variant="secondary"
            isDisabled={
              !installed ||
              settings.tagRoots.length + (settings.keywords ? 1 : 0) + learned === 0
            }
            onPress={async () => {
              try {
                const n = await client.smartTag([
                  folder.trim().replace(/\/+$/, "") || DAM_ROOT,
                ]);
                setNotice(
                  `${n} file${n === 1 ? "" : "s"} queued for Auto tags`,
                );
              } catch (e) {
                setError(message(e));
              }
            }}
          >
            Tag now
          </Button>
        </div>
      </section>

      <section
        className="studio-assets__smarttags-section"
        aria-labelledby="studio-st-colors"
      >
        <Heading level={2} id="studio-st-colors">
          Color tags
        </Heading>
        <p className="studio-assets__muted">
          Every picture gets the names of the colors it is mostly made of
          (blue, white, ...) when it is processed, without a model; the search
          filters by them under Color. For files of the folder above that were
          there before, find them here.
        </p>
        <div className="studio-assets__smarttags-run">
          <Checkbox isSelected={colorAgain} onChange={setColorAgain}>
            Also files that have them
          </Checkbox>
          <Button
            variant="secondary"
            isPending={coloring}
            onPress={() => void findColors()}
          >
            Find colors
          </Button>
        </div>
      </section>
      {notice && (
        <div className="studio-assets__toast" role="status">
          <span>{notice}</span>
          <ActionButton
            isQuiet
            aria-label="Dismiss"
            onPress={() => setNotice(null)}
          >
            ×
          </ActionButton>
        </div>
      )}
    </div>
  );
}

/**
 * The Smart Tags of one asset in its properties: each with its confidence, to apply as a tag or
 * remove (removed ones are not suggested again).
 */
export function SmartTagsField({
  client,
  path,
  initial,
  onApplied,
}: {
  client: DamClient;
  path: string;
  initial?: SmartTags | null;
  onApplied(tag: string): void;
}) {
  const [smart, setSmart] = useState<SmartTags | null>(initial ?? null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => setSmart(initial ?? null), [initial]);
  if (!smart || (smart.tags.length === 0 && smart.keywords.length === 0))
    return null;

  const change = async (tag: string, apply: boolean) => {
    try {
      setSmart(await client.changeSmartTag(path, tag, apply));
      if (apply) onApplied(tag);
      setError(null);
    } catch (e) {
      setError(message(e));
    }
  };

  return (
    <div
      className="studio-assets__smartfield"
      role="group"
      aria-label="Auto tags"
    >
      <div className="studio-assets__cqtags-label">Auto tags</div>
      <ul className="studio-assets__smartchips">
        {smart.tags.map((t) => (
          <li key={t.id} title={t.id}>
            <span>{t.title}</span>
            {t.confidence != null && (
              <span className="studio-assets__smartconf">
                {percent(t.confidence)}
              </span>
            )}
            <ActionButton
              isQuiet
              aria-label={`Apply ${t.title} as a tag`}
              onPress={() => void change(t.id, true)}
            >
              <Add size="XS" />
            </ActionButton>
            <ActionButton
              isQuiet
              aria-label={`Remove ${t.title}`}
              onPress={() => void change(t.id, false)}
            >
              <Close size="XS" />
            </ActionButton>
          </li>
        ))}
        {smart.keywords.map((k) => (
          <li key={k} className="is-keyword">
            <span>{k}</span>
            <ActionButton
              isQuiet
              aria-label={`Remove ${k}`}
              onPress={() => void change(`keyword:${k}`, false)}
            >
              <Close size="XS" />
            </ActionButton>
          </li>
        ))}
      </ul>
      {error && <p className="studio-assets__dialog-error">{error}</p>}
    </div>
  );
}
