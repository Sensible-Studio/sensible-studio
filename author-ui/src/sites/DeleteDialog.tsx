import { Text } from '@adobe/react-spectrum';
import { nameOf, type StudioClient, type SiteItem } from '../editor/api/StudioClient';
import { ReferenceList, useReferences } from './References';
import { StudioConfirmDialog } from '../shell/StudioDialog';

interface Props {
  client: StudioClient;
  items: SiteItem[];
  onDelete(): void;
}

/** Delete confirmation. Like AEM, it lists the pages that still refer to what is being deleted. */
export function DeleteDialog({ client, items, onDelete }: Props) {
  const refs = useReferences(
    client,
    items.map((i) => i.path),
  );
  const referenced = (refs.pages?.length ?? 0) > 0;
  // deleting unpublishes: say so, a live page disappearing is the bigger news
  const live = items.filter((i) => i.publication === 'published' || i.publication === 'modified');
  return (
    <StudioConfirmDialog
      title={referenced ? 'Delete referenced content' : 'Delete'}
      variant="destructive"
      primaryActionLabel={referenced ? 'Force Delete' : 'Delete'}
      cancelLabel="Cancel"
      isPrimaryActionDisabled={!refs.pages && !refs.error}
      onPrimaryAction={onDelete}
    >
      {items.length === 1 ? (
        <>
          You are going to delete <strong>{items[0].title}</strong> ({nameOf(items[0].path)})
          {items[0].hasChildren && ' and all the pages below it'}.
        </>
      ) : (
        <>
          You are going to delete {items.length} items: <strong>{items.map((d) => d.title).join(', ')}</strong>, with the
          pages below them.
        </>
      )}
      {live.length > 0 && (
        <p className="studio-delete__live">
          {live.length === 1 && items.length === 1 ? 'It is published' : `${live.length} of them ${live.length === 1 ? 'is' : 'are'} published`}: {live.length === 1 ? 'it is' : 'they are'} taken off the live site
          first. Pages below that are published go with {live.length === 1 ? 'it' : 'them'}.
        </p>
      )}
      <div className="studio-delete__refs">
        {referenced && <Text>These pages refer to {items.length === 1 ? 'it' : 'them'}; their links will break:</Text>}
        <ReferenceList pages={refs.pages} error={refs.error} empty={`No other page refers to ${items.length === 1 ? 'it' : 'them'}.`} />
      </div>
    </StudioConfirmDialog>
  );
}
