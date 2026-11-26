import { ActionButton, Button, ButtonGroup, Checkbox, Content, Divider, Heading, Item, Picker, Text, TextField } from '@adobe/react-spectrum';
import Copy from '@spectrum-icons/workflow/Copy';
import { useEffect, useState } from 'react';
import type { DamClient, DamItem, ShareLink } from './api';
import { DEFAULT_ASSETS_CONFIG, loadAssetsConfig, validityLabel, type LinkShareSettings, type ValidityUnit } from './assetsConfig';
import { StudioDialog } from '../shell/StudioDialog';

const DAYS: Array<[number, string]> = [
  [1, '1 day'],
  [7, '1 week'],
  [30, '30 days'],
  [90, '90 days'],
];

/** The validity choices: the usual ones, and the configured default when it is another (Assets Configurations › Link Share). */
export function validityChoices(l: Pick<LinkShareSettings, 'validityAmount' | 'validityUnit'>): Array<{ key: string; label: string }> {
  const out = DAYS.map(([d, label]) => ({ key: `${d} days`, label }));
  const dflt = `${l.validityAmount} ${l.validityUnit}`;
  if (!out.some((o) => o.key === dflt)) out.push({ key: dflt, label: validityLabel(l.validityAmount, l.validityUnit) });
  return out;
}

/**
 * Share Link: a link anyone can open to download the items, without an account, until it
 * expires. The link is shown once; the owner's links for these items can be revoked here.
 * What it starts with (originals, renditions, validity) and what may be changed come from
 * Assets Configurations › Link Share.
 */
export function ShareLinkDialog({ client, items, close }: { client: DamClient; items: DamItem[]; close(): void }) {
  const [settings, setSettings] = useState<LinkShareSettings>(DEFAULT_ASSETS_CONFIG.linkShare);
  const [validity, setValidity] = useState('7 days');
  const [originals, setOriginals] = useState(true);
  const [renditions, setRenditions] = useState(false);
  useEffect(() => {
    let live = true;
    loadAssetsConfig().then(
      (c) => {
        if (!live) return;
        setSettings(c.linkShare);
        setValidity(`${c.linkShare.validityAmount} ${c.linkShare.validityUnit}`);
        setOriginals(c.linkShare.includeOriginals);
        setRenditions(c.linkShare.includeRenditions);
      },
      () => undefined, // the usual defaults
    );
    return () => {
      live = false;
    };
  }, []);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [made, setMade] = useState<{ url: string; expires: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [existing, setExisting] = useState<ShareLink[]>([]);
  const one = items.length === 1 ? items[0].path : undefined;

  const loadExisting = () => {
    if (!one) return;
    client.shareLinks(one).then(setExisting, () => setExisting([]));
  };
  useEffect(loadExisting, [client, one]); // eslint-disable-line react-hooks/exhaustive-deps

  const full = made ? new URL(made.url, window.location.origin).toString() : '';
  return (
    <StudioDialog size="M">
      <Heading>Share Link</Heading>
      <Divider />
      <Content>
        <p>
          Anyone with the link can download {items.length === 1 ? <strong>{items[0].title}</strong> : `${items.length} items`} without signing in.
        </p>
        {made ? (
          <>
            <div className="studio-assets__share-url">
              <TextField label="Link" value={full} isReadOnly width="100%" />
              <ActionButton
                aria-label="Copy link"
                onPress={async () => {
                  try {
                    await navigator.clipboard.writeText(full);
                    setCopied(true);
                  } catch {
                    setCopied(false);
                  }
                }}
              >
                <Copy />
                <Text>{copied ? 'Copied' : 'Copy'}</Text>
              </ActionButton>
            </div>
            <Text UNSAFE_className="studio-assets__muted">
              Valid until {new Date(made.expires).toLocaleString()}. Copy it now: it is not shown again.
            </Text>
          </>
        ) : (
          <>
            <Picker label="Expires after" selectedKey={validity} onSelectionChange={(k) => setValidity(String(k))} width="size-2400">
              {validityChoices(settings).map((c) => (
                <Item key={c.key}>{c.label}</Item>
              ))}
            </Picker>
            {settings.originalsMode !== 'hidden' && (
              <Checkbox isSelected={originals} onChange={setOriginals} isReadOnly={settings.originalsMode === 'readonly'}>
                Include originals
              </Checkbox>
            )}
            {settings.renditionsMode !== 'hidden' && (
              <Checkbox isSelected={renditions} onChange={setRenditions} isReadOnly={settings.renditionsMode === 'readonly'}>
                Include renditions
              </Checkbox>
            )}
            {!originals && !renditions && <p className="studio-assets__dialog-error">Include the originals, the renditions, or both.</p>}
          </>
        )}
        {existing.length > 0 && (
          <div className="studio-assets__share-existing">
            <h3>Your links to this item</h3>
            <ul>
              {existing.map((l) => (
                <li key={l.id}>
                  <span>until {new Date(l.expires).toLocaleDateString()}</span>
                  <ActionButton
                    isQuiet
                    onPress={async () => {
                      try {
                        await client.revokeShareLink(l.id);
                        loadExisting();
                      } catch (e) {
                        setError(e instanceof Error ? e.message : String(e));
                      }
                    }}
                  >
                    Revoke
                  </ActionButton>
                </li>
              ))}
            </ul>
          </div>
        )}
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        {made ? (
          <Button variant="accent" onPress={close}>
            Done
          </Button>
        ) : (
          <>
            <Button variant="secondary" onPress={close} isDisabled={busy}>
              Cancel
            </Button>
            <Button
              variant="accent"
              isDisabled={busy || (!originals && !renditions)}
              onPress={async () => {
                setBusy(true);
                setError(null);
                try {
                  const [amount, unit] = validity.split(' ') as [string, ValidityUnit];
                  const n = Number(amount);
                  setMade(
                    unit === 'days'
                      ? await client.createShareLink(items.map((i) => i.path), n, renditions, { originals })
                      : await client.createShareLink(items.map((i) => i.path), 0, renditions, { amount: n, unit, originals }),
                  );
                  loadExisting();
                } catch (e) {
                  setError(e instanceof Error ? e.message : String(e));
                } finally {
                  setBusy(false);
                }
              }}
            >
              Create Link
            </Button>
          </>
        )}
      </ButtonGroup>
    </StudioDialog>
  );
}
