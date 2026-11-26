import { ActionButton, Button, ButtonGroup, Content, Divider, Heading, ProgressCircle, Text, TextField, useDialogContainer } from '@adobe/react-spectrum';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import { useEffect, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import type { RepositoryItem } from '../granite';
import { StudioPickerDialog } from '../shell/StudioDialog';

interface Props {
  studio: StudioClient;
  onPick(path: string, title: string): void;
}

/** Picks the source page of a new launch: a path field and a small page browser under /content. */
export function SourcePickerDialog({ studio, onPick }: Props) {
  const dialog = useDialogContainer();
  const [folder, setFolder] = useState('/content');
  const [items, setItems] = useState<RepositoryItem[] | null>(null);
  const [selected, setSelected] = useState<RepositoryItem | null>(null);

  useEffect(() => {
    let live = true;
    setItems(null);
    studio.listChildren(folder).then(
      (list) => live && setItems(list.filter((i) => i.type === 'cq:Page' || i.type?.endsWith('Folder')).filter((i) => !i.path.startsWith('/content/launches'))),
      () => live && setItems([]),
    );
    return () => {
      live = false;
    };
  }, [studio, folder]);

  const crumbs = folder.split('/').filter(Boolean);
  return (
    <StudioPickerDialog size="M">
      <Heading>Create Release: source page</Heading>
      <Divider />
      <Content>
        <TextField label="Page" value={selected?.path ?? ''} onChange={(v) => setSelected({ name: v.split('/').pop() ?? v, path: v, type: 'cq:Page' })} width="100%" description="The page the release starts from" />
        <nav className="studio-launch__crumbs" aria-label="Folder">
          {crumbs.map((c, i) => (
            <span key={i}>
              {i > 0 && ' / '}
              <button type="button" className="studio-launch__crumb" onClick={() => setFolder('/' + crumbs.slice(0, i + 1).join('/'))}>
                {c}
              </button>
            </span>
          ))}
        </nav>
        {items === null ? (
          <ProgressCircle size="S" isIndeterminate aria-label="Loading" />
        ) : items.length === 0 ? (
          <Text UNSAFE_className="studio-publish__hint">No pages here.</Text>
        ) : (
          <ul className="studio-launch__list">
            {items.map((i) => (
              <li key={i.path} className={selected?.path === i.path ? 'is-selected' : ''}>
                <button type="button" className="studio-launch__item" onClick={() => (i.type === 'cq:Page' ? setSelected(i) : setFolder(i.path))}>
                  <span className="studio-publish__title">{i.title ?? i.name}</span>
                  <span className="studio-publish__meta">{i.type === 'cq:Page' ? i.name : 'Folder'}</span>
                </button>
                {i.hasChildren !== false && (
                  <ActionButton isQuiet aria-label="Open" onPress={() => setFolder(i.path)}>
                    <ChevronRight />
                  </ActionButton>
                )}
              </li>
            ))}
          </ul>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" isDisabled={!selected?.path.startsWith('/content/')} onPress={() => selected && onPick(selected.path, selected.title ?? selected.name)}>
          Next
        </Button>
      </ButtonGroup>
    </StudioPickerDialog>
  );
}
