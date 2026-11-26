import { useEffect, useMemo, useRef, useState } from 'react';
import type { GraniteComponentProps } from '../../registry';
import { useField, useFormStore, useGranite } from '../../context';
import { asString, isContentNode } from '../../node';
import { ImageGlyph } from '../glyphs';
import { AssetPicker } from './path';

interface Thumb {
  /** a DAM asset chosen as thumbnail */
  ref: string;
  /** a file uploaded from the computer, posted when the form is saved */
  file: File | null;
  /** the stored thumbnail is removed */
  reverted: boolean;
}

const NAME = './image';
const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp'];

/**
 * `cq/gui/components/common/wcm/pagethumbnail`: the page's thumbnail (`jcr:content/image`, shown in the Sites
 * console), with Upload Image (a file from the computer, stored as `image/file`), Select Image (a DAM asset, stored
 * as `image/fileReference`) and Revert (back to no thumbnail of its own).
 */
export function PageThumbnail(_: GraniteComponentProps) {
  const store = useFormStore();
  const { env } = useGranite();
  const storedRef = asString(store.contentValue(`${NAME}/fileReference`));
  const storedFile = isContentNode(store.contentValue(`${NAME}/file`));
  const { value, setValue, disabled } = useField<Thumb>(NAME, () => ({ ref: storedRef, file: null, reverted: false }), {
    label: 'Thumbnail',
    entries: (v) => {
      const t = v as Thumb;
      if (t.file) return [{ name: `${NAME}/fileReference@Delete`, value: '' }];
      if (t.ref && t.ref !== storedRef) return [{ name: `${NAME}/fileReference`, value: t.ref }, { name: `${NAME}/file@Delete`, value: '' }];
      if (t.reverted) return [{ name: `${NAME}@Delete`, value: '' }];
      return [];
    },
    isDirty: () => {
      const t = store.get<Thumb>(NAME);
      return !!t && (!!t.file || t.reverted || t.ref !== storedRef);
    },
  });
  const t = value ?? { ref: storedRef, file: null, reverted: false };
  const [picking, setPicking] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const objectUrl = useMemo(() => (t.file ? URL.createObjectURL(t.file) : null), [t.file]);
  useEffect(() => () => void (objectUrl && URL.revokeObjectURL(objectUrl)), [objectUrl]);

  const src = objectUrl
    ? objectUrl
    : t.reverted
      ? null
      : t.ref
        ? (env.assetThumbnail?.(t.ref) ?? env.assetUrl?.(t.ref) ?? null)
        : storedFile && env.contentPath
          ? (env.fileUrl?.(`${env.contentPath}/image/file`) ?? null)
          : null;
  const hasOwn = !t.reverted && (!!t.file || !!t.ref || storedFile);
  // like AEM, a page without a thumbnail of its own shows its template's
  const template = asString(store.contentValue('./cq:template'));
  const [templateBroken, setTemplateBroken] = useState(false);
  const fallback = !src && template && !templateBroken ? (env.fileUrl?.(`${template}/thumbnail.png`) ?? null) : null;

  const choose = (patch: Partial<Thumb>) => {
    const next = { ref: '', file: null, reverted: false, ...patch };
    env.stageFile?.(`${NAME}/file`, next.file);
    setValue(next);
  };

  return (
    <div className="studio-g-thumbnail">
      <div className="studio-g-thumbnail__frame">
        {src ? (
          <img className="studio-g-thumbnail__image" src={src} alt="Page thumbnail" />
        ) : fallback ? (
          <img className="studio-g-thumbnail__image" src={fallback} alt="Thumbnail of the page's template" onError={() => setTemplateBroken(true)} />
        ) : (
          <div className="studio-g-thumbnail__none">
            <ImageGlyph size={40} />
            <span>No thumbnail.</span>
          </div>
        )}
      </div>
      <div className="studio-g-thumbnail__actions">
        {env.stageFile && (
          <>
            <button type="button" className="studio-g-linkbtn" disabled={disabled} onClick={() => input.current?.click()}>
              Upload Image
            </button>
            <input
              ref={input}
              type="file"
              accept={IMAGE_TYPES.join(',')}
              hidden
              onChange={(e) => {
                const f = e.target.files?.[0];
                e.target.value = '';
                if (f) choose({ file: f });
              }}
            />
          </>
        )}
        {env.listChildren && (
          <button type="button" className="studio-g-linkbtn" disabled={disabled} onClick={() => setPicking(true)}>
            Select Image
          </button>
        )}
        {hasOwn && (
          <button type="button" className="studio-g-linkbtn" disabled={disabled} onClick={() => choose({ reverted: storedFile || !!storedRef })}>
            Revert
          </button>
        )}
      </div>
      {t.file && <div className="studio-g-thumbnail__note">{t.file.name} is uploaded when the page properties are saved.</div>}
      {picking && (
        <AssetPicker
          initialPath={t.ref ? t.ref.slice(0, t.ref.lastIndexOf('/')) : '/content/dam'}
          mimeTypes={IMAGE_TYPES}
          onCancel={() => setPicking(false)}
          onSelect={(p) => {
            setPicking(false);
            choose({ ref: p });
          }}
        />
      )}
    </div>
  );
}
