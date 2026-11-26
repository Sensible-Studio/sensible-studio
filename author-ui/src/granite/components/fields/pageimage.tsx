import { useEffect, useState, useSyncExternalStore } from 'react';
import type { GraniteComponentProps } from '../../registry';
import type { FormStore } from '../../form/FormStore';
import { useGranite } from '../../context';
import { asString } from '../../node';
import { ImageGlyph } from '../glyphs';
import { AssetPreview } from './path';

/** A page path from a link value (`/content/a/b.html#x` -> `/content/a/b`), or null for external links. */
export function pageOfLink(link: string): string | null {
  const v = link.trim();
  if (!v.startsWith('/content/')) return null;
  return v.replace(/[?#].*$/, '').replace(/\.html$/, '') || null;
}

/**
 * The page whose featured image an image or teaser inherits, as Core Components choose it: the link, else the
 * link of the first call to action, else the page being edited.
 */
export function inheritingPage(store: FormStore, contentPath: string | undefined): string | null {
  const link = pageOfLink(asString(store.get('./linkURL')));
  if (link) return link;
  const actions = store.get<Array<{ store: FormStore }>>('./actions');
  if (Array.isArray(actions) && actions[0]?.store) {
    const first = pageOfLink(asString(actions[0].store.get('link')));
    if (first) return first;
  }
  if (!contentPath) return null;
  const i = contentPath.indexOf('/jcr:content');
  return i > 0 ? contentPath.slice(0, i) : null;
}

/**
 * `core/wcm/components/commons/editor/dialog/pageimagethumbnail/v1/pageimagethumbnail`: a preview of the featured
 * image the component inherits (page properties > Image), shown while "Inherit featured image from page" is on.
 */
export function PageImageThumbnail(_: GraniteComponentProps) {
  const { env, rootStore } = useGranite();
  useSyncExternalStore(rootStore.subscribe, rootStore.getVersion, rootStore.getVersion);
  const page = inheritingPage(rootStore, env.contentPath);
  const [state, setState] = useState<{ page: string | null; image?: string | null }>({ page: null });

  useEffect(() => {
    if (!page || !env.pageImage) return;
    let live = true;
    env.pageImage(page).then(
      (img) => live && setState({ page, image: img?.fileReference ?? null }),
      () => live && setState({ page, image: null }),
    );
    return () => {
      live = false;
    };
  }, [env, page]);

  const loaded = !env.pageImage || state.page === page;
  const image = loaded ? state.image : undefined;
  const pageName = page ? page.slice(page.lastIndexOf('/') + 1) : '';
  return (
    <div className="studio-g-pageimage" aria-live="polite">
      {image ? (
        <AssetPreview path={image} className="studio-g-pageimage__preview" />
      ) : (
        <div className="studio-g-pageimage__none">
          <ImageGlyph size={28} />
        </div>
      )}
      <div className="studio-g-pageimage__caption">
        {!page
          ? 'No page to inherit from.'
          : !loaded
            ? 'Loading the featured image…'
            : image
              ? `Featured image of ${pageName}`
              : `${pageName} has no featured image.`}
      </div>
    </div>
  );
}
