import { ActionButton, Breadcrumbs, Button, ButtonGroup, Content, Divider, Heading, Item, ProgressCircle, Text, useDialogContainer } from '@adobe/react-spectrum';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import { useEffect, useState, type Key } from 'react';
import { TAG_ROOT, type TagListing, type TagsClient } from './api';
import { TagIcon } from './views';
import { StudioPickerDialog } from '../shell/StudioDialog';

interface Props {
  client: TagsClient;
  title: string;
  /** where browsing starts */
  start: string;
  /** whether the tag root itself can be chosen (it holds the namespaces) */
  allowRoot?: boolean;
  /** paths that cannot be chosen (and are not entered), e.g. the tag being moved */
  exclude?: string[];
  onPick(path: string): void;
}

/** Browse the tag tree and choose a tag (or namespace, or the root): the "Move to" / "Merge into" picker. */
export function TagPicker({ client, title, start, allowRoot = false, exclude = [], onPick }: Props) {
  const container = useDialogContainer();
  const [path, setPath] = useState(start);
  const [listing, setListing] = useState<TagListing | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setListing(null);
    client.children(path).then(
      (l) => {
        if (!live) return;
        setListing(l);
        setError(null);
      },
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [client, path]);

  const blocked = (p: string) => exclude.some((x) => p === x || p.startsWith(`${x}/`));
  const choice = chosen ?? (path !== TAG_ROOT || allowRoot ? path : null);

  return (
    <StudioPickerDialog size="M">
      <Heading>{title}</Heading>
      <Divider />
      <Content>
        {listing && (
          <Breadcrumbs onAction={(k: Key) => setPath(String(k))} size="S">
            {listing.trail.map((t) => (
              <Item key={t.path}>{t.title}</Item>
            ))}
          </Breadcrumbs>
        )}
        <div className="studio-tagpicker" role="listbox" aria-label="Tags">
          {error && <p className="studio-sites__empty">{error}</p>}
          {!error && !listing && <ProgressCircle isIndeterminate size="S" aria-label="Loading" />}
          {listing?.children.length === 0 && <p className="studio-sites__empty">No tags below {listing.item.title}.</p>}
          {listing?.children.map((t) => {
            const disabled = blocked(t.path);
            return (
              <div key={t.path} className={`studio-tagpicker__row${chosen === t.path ? ' is-selected' : ''}${disabled ? ' is-disabled' : ''}`} role="option" aria-selected={chosen === t.path} aria-disabled={disabled}>
                <button type="button" className="studio-tagpicker__pick" disabled={disabled} onClick={() => setChosen(t.path)} onDoubleClick={() => t.hasChildren && setPath(t.path)}>
                  <TagIcon item={t} />
                  <span>{t.title}</span>
                  <span className="studio-tagpicker__id">{t.tagID}</span>
                </button>
                {t.hasChildren && !disabled && (
                  <ActionButton isQuiet aria-label={`Open ${t.title}`} onPress={() => setPath(t.path)}>
                    <ChevronRight size="S" />
                  </ActionButton>
                )}
              </div>
            );
          })}
        </div>
        <Text UNSAFE_className="studio-tagpicker__choice">{choice ? choice : 'Choose a tag'}</Text>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={container.dismiss}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={!choice || blocked(choice)}
          onPress={() => {
            if (!choice) return;
            container.dismiss();
            onPick(choice);
          }}
        >
          Select
        </Button>
      </ButtonGroup>
    </StudioPickerDialog>
  );
}
