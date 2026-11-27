import { Button, ButtonGroup, Checkbox, Content, Divider, FileTrigger, Heading, Text, TextField } from '@adobe/react-spectrum';
import { useState } from 'react';
import { metadataCsvUrl, type DamClient, type ImportResult } from './api';
import { StudioDialog } from '../shell/StudioDialog';

/** Metadata Export: CSV of the folder's assets, optionally of its subfolders too. */
export function ExportMetadataDialog({ path, title, close }: { path: string; title: string; close(): void }) {
  const [recursive, setRecursive] = useState(true);
  const [properties, setProperties] = useState('');
  const list = properties
    .split(/[\s,]+/)
    .map((s) => s.trim())
    .filter(Boolean);
  return (
    <StudioDialog size="S">
      <Heading>Export Metadata</Heading>
      <Divider />
      <Content>
        <p>
          A CSV file with the metadata of the files in <strong>{title}</strong>. Edit it in a spreadsheet and bring it back with Import Metadata.
        </p>
        <Checkbox isSelected={recursive} onChange={setRecursive}>
          Include subfolders
        </Checkbox>
        <TextField
          label="Properties"
          value={properties}
          onChange={setProperties}
          width="100%"
          description="Leave empty for all of them, or list some: dc:title, dc:description, cq:tags"
        />
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close}>
          Cancel
        </Button>
        <Button
          variant="accent"
          onPress={() => {
            const a = document.createElement('a');
            a.href = metadataCsvUrl(path, recursive, list);
            a.setAttribute('download', '');
            document.body.appendChild(a);
            a.click();
            a.remove();
            close();
          }}
        >
          Export
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

/** Metadata Import: a CSV as exported (assetPath first); empty cells leave the values as they are. */
export function ImportMetadataDialog({ client, onDone, close }: { client: DamClient; onDone(r: ImportResult): void; close(): void }) {
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  return (
    <StudioDialog size="M">
      <Heading>Import Metadata</Heading>
      <Divider />
      <Content>
        {result ? (
          <>
            <p>
              {result.updated} file{result.updated === 1 ? '' : 's'} updated.
            </p>
            {result.skipped.length > 0 && (
              <>
                <p>{result.skipped.length} rows were not imported:</p>
                <ul className="studio-assets__import-skipped">
                  {result.skipped.slice(0, 100).map((s) => (
                    <li key={s.line}>
                      Line {s.line}: <code>{s.path}</code> — {s.reason}
                    </li>
                  ))}
                </ul>
              </>
            )}
          </>
        ) : (
          <>
            <p>
              The first column is <code>assetPath</code>; the others are metadata properties such as <code>dc:title {'{{String}}'}</code> or{' '}
              <code>dc:subject {'{{String: multi}}'}</code> (values separated by <code>|</code>). Empty cells leave the values as they are.
            </p>
            <FileTrigger acceptedFileTypes={['text/csv', '.csv']} onSelect={(files) => setFile(files ? [...files][0] ?? null : null)}>
              <Button variant="secondary">Choose CSV File</Button>
            </FileTrigger>
            <Text UNSAFE_className="studio-assets__muted"> {file ? file.name : 'No file chosen'}</Text>
          </>
        )}
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        {result ? (
          <Button variant="accent" onPress={close}>
            Close
          </Button>
        ) : (
          <>
            <Button variant="secondary" onPress={close} isDisabled={busy}>
              Cancel
            </Button>
            <Button
              variant="accent"
              isDisabled={!file || busy}
              onPress={async () => {
                if (!file) return;
                setBusy(true);
                setError(null);
                try {
                  const r = await client.importMetadata(file);
                  setResult(r);
                  onDone(r);
                } catch (e) {
                  setError(e instanceof Error ? e.message : String(e));
                } finally {
                  setBusy(false);
                }
              }}
            >
              {busy ? 'Importing…' : 'Import'}
            </Button>
          </>
        )}
      </ButtonGroup>
    </StudioDialog>
  );
}
