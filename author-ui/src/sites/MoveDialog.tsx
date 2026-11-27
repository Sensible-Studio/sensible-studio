import { Button, ButtonGroup, Content, Divider, Form, Heading, Text, TextField, useDialogContainer } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import { ReferenceList, useReferences } from './References';
import { nameOf, parentOf, type StudioClient, type SiteItem } from '../editor/api/StudioClient';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  client: StudioClient;
  item: SiteItem;
  /** the other selected items: they go to the same place, each under its own name (renaming is for one) */
  more?: SiteItem[];
  /** `adjust`: pages whose references to the item are rewritten */
  onMove(destParent: string, name: string, adjust: string[]): void;
}

const NAME = /^[A-Za-z0-9_.~-]+$/;

/**
 * Rename and/or move a page or folder: new name, destination parent below /content and which of the
 * pages referring to it get their links rewritten (all by default; links inside it always are).
 */
export function MoveDialog({ client, item, more = [], onMove }: Props) {
  const all = [item, ...more];
  const several = all.length > 1;
  const container = useDialogContainer();
  const [name, setName] = useState(nameOf(item.path));
  const [dest, setDest] = useState(parentOf(item.path));
  const [destOk, setDestOk] = useState<boolean | null>(true);
  const [destTitle, setDestTitle] = useState<string | null>(null);
  /** the places below the destination, for choosing it by clicking instead of typing a path */
  const [below, setBelow] = useState<SiteItem[]>([]);
  const [browsing, setBrowsing] = useState(false);
  const refs = useReferences(
    client,
    all.map((i) => i.path),
  );
  const [adjust, setAdjust] = useState<Set<string> | null>(null);
  const chosen = adjust ?? new Set((refs.pages ?? []).map((p) => p.page));

  useEffect(() => {
    const d = dest.replace(/\/$/, '');
    if (!d.startsWith('/content')) {
      setDestOk(false);
      return;
    }
    let cancelled = false;
    setDestOk(null);
    const t = setTimeout(() => {
      client.siteChildren(d).then(
        (l) => {
          if (cancelled) return;
          const insideItself = all.some((i) => d === i.path || d.startsWith(`${i.path}/`));
          setDestOk(!insideItself);
          setDestTitle(l.item.title);
          setBelow(l.children.filter((c) => !all.some((i) => i.path === c.path)));
        },
        () => !cancelled && setDestOk(false),
      );
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
    // the selection is fixed while the dialog is open
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, dest, item.path, more.length]);

  const nameOk = NAME.test(name);
  const unchanged = name === nameOf(item.path) && dest.replace(/\/$/, '') === parentOf(item.path);

  return (
    <StudioDialog size="M">
      <Heading>{several ? `Move ${all.length} items` : `Move ${item.title}`}</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            if (nameOk && destOk && !unchanged) {
              container.dismiss();
              onMove(dest.replace(/\/$/, ''), name, [...chosen]);
            }
          }}
        >
          {several ? (
            <Text>
              {all.map((i) => i.title).join(', ')}: each keeps its name.
            </Text>
          ) : (
            <TextField
              label="Name"
              value={name}
              onChange={setName}
              isRequired
              validationState={nameOk ? undefined : 'invalid'}
              errorMessage="Letters, digits and - _ . ~ only"
              autoFocus
            />
          )}
          <TextField
            label="Destination"
            description={destOk && destTitle ? `Into ${destTitle}` : undefined}
            value={dest}
            onChange={setDest}
            isRequired
            validationState={destOk === false ? 'invalid' : undefined}
            errorMessage="Choose an existing location below /content, outside the item itself"
          />
          <div className="studio-move__browse">
            <Button variant="secondary" style="outline" onPress={() => setBrowsing((v) => !v)} aria-expanded={browsing}>
              {browsing ? 'Hide the places' : 'Browse…'}
            </Button>
            {browsing && (
              <ul aria-label="Places below the destination">
                {dest.replace(/\/$/, '') !== '/content' && (
                  <li>
                    <button type="button" onClick={() => setDest(parentOf(dest.replace(/\/$/, '')))}>
                      ↑ Up
                    </button>
                  </li>
                )}
                {below.map((c) => (
                  <li key={c.path}>
                    <button type="button" onClick={() => setDest(c.path)} title={c.path}>
                      {c.title} <small>{nameOf(c.path)}</small>
                    </button>
                  </li>
                ))}
                {below.length === 0 && destOk && <li className="is-empty">Nothing below this place.</li>}
              </ul>
            )}
          </div>
          <div className="studio-move__refs">
            <Text UNSAFE_className="studio-move__label">Adjust references</Text>
            <ReferenceList
              pages={refs.pages}
              error={refs.error}
              selected={chosen}
              onSelected={setAdjust}
              empty={several ? 'No other page refers to them. Links inside them are adjusted.' : 'No other page refers to it. Links inside it are adjusted.'}
            />
          </div>
        </Form>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={container.dismiss}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={!nameOk || !destOk || unchanged || (!refs.pages && !refs.error)}
          onPress={() => {
            container.dismiss();
            onMove(dest.replace(/\/$/, ''), name, [...chosen]);
          }}
        >
          Move
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
