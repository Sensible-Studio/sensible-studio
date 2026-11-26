import { ActionButton, Item, Menu, MenuTrigger, Provider, Section } from '@adobe/react-spectrum';
import { useCallback, useEffect, useRef, useState, type Key } from 'react';
import type { DamClient } from './api';
import { AssetDetails } from './AssetDetails';
import { AssetsBrowser } from './AssetsBrowser';
import { ImageEditor } from './ImageEditor';
import { CollectionPage, CollectionsPage } from './collections';
import { ProfilesPage } from './profiles';
import { ReportPage, ReportsPage } from './reports';
import { SmartTagsPage } from './smarttags';
import { ImagePresetsPage } from './dynamicmedia';
import { ImageProfilesPage } from './smartcrop';
import { FOLDER_SCHEMAS, METADATA_PROFILES, SchemasPage } from './schemas';
import { SharedLinksPage } from './sharedLinks';
import { SearchFormsPage } from './searchForms';
import { AssetsConfigurationsPage } from './assetsConfigurations';
import { assetsRoute, routeUrl, type AssetsRoute } from './route';
import './assets.scss';
import { InboxBell } from '../workflow/InboxBell';
import { useTab } from '../shell/ShellContext';

interface Props {
  client: DamClient;
  initial: AssetsRoute;
}

/**
 * The Assets console, laid out like AEM's: product bar, then the folder browser
 * (`/assets.html/...`) or one asset (`/assetdetails.html/...`). Navigation inside the app uses
 * the History API, so the back button and reloads work.
 */
export function AssetsApp({ client, initial }: Props) {
  const [route, setRoute] = useState<AssetsRoute>(initial);
  const [user, setUser] = useState<string | null>(null);
  // Copy → Paste, across folders (kept while the app is open).
  const [clipboard, setClipboard] = useState<string[]>([]);
  // In the shell the route is the tab's place: Back and Forward change it, moves inside the app push it.
  const tab = useTab();
  const tabRef = useRef(tab);
  tabRef.current = tab;
  const tabRoute = tab?.place.kind === 'assets' ? tab.place.route : undefined;
  useEffect(() => {
    if (tabRoute && routeUrl(tabRoute) !== routeUrl(route)) setRoute(tabRoute);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabRoute]);

  useEffect(() => {
    if (tab) return; // the shell owns the history
    const onPop = () => {
      const r = assetsRoute(window.location);
      if (r) setRoute(r);
    };
    window.addEventListener('popstate', onPop);
    window.addEventListener('hashchange', onPop);
    return () => {
      window.removeEventListener('popstate', onPop);
      window.removeEventListener('hashchange', onPop);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!tab]);

  useEffect(() => {
    client.currentUser().then(setUser, () => setUser(null));
  }, [client]);

  const navigate = useCallback((r: AssetsRoute) => {
    if (tabRef.current) {
      tabRef.current.setPlace({ kind: 'assets', route: r });
    } else {
      const url = routeUrl(r);
      // Marked, so the image editor knows it can return with history.back().
      if (url !== `${window.location.pathname}${window.location.search}${window.location.hash}`) window.history.pushState({ studioAssets: true }, '', url);
    }
    setRoute(r);
  }, []);

  if (route.kind === 'edit') {
    // Full screen, like AEM's editor: no product bar.
    return (
      <div className="studio-assets studio-assets--editor">
        <ImageEditor key={`edit:${route.path}`} client={client} path={route.path} navigate={navigate} />
      </div>
    );
  }

  return (
    <div className="studio-assets studio-assets-console">
      {!tab && <ProductBar user={user} go={(k) => navigate({ kind: k })} />}
      {route.kind === 'browse' ? (
        <AssetsBrowser
          key="browse"
          client={client}
          path={route.path}
          search={route.search}
          navigate={navigate}
          clipboard={clipboard}
          setClipboard={setClipboard}
        />
      ) : route.kind === 'details' ? (
        <AssetDetails key={`details:${route.path}`} client={client} path={route.path} properties={route.properties} similar={route.similar} annotate={route.annotate} timeline={route.timeline} navigate={navigate} />
      ) : route.kind === 'profiles' ? (
        <ProfilesPage client={client} navigate={navigate} />
      ) : route.kind === 'schemas' ? (
        <SchemasPage client={client} navigate={navigate} />
      ) : route.kind === 'folderschemas' ? (
        <SchemasPage key="folderschemas" client={client} navigate={navigate} kind={FOLDER_SCHEMAS} />
      ) : route.kind === 'metadataprofiles' ? (
        <SchemasPage key="metadataprofiles" client={client} navigate={navigate} kind={METADATA_PROFILES} />
      ) : route.kind === 'collections' ? (
        <CollectionsPage client={client} navigate={navigate} />
      ) : route.kind === 'smarttags' ? (
        <SmartTagsPage client={client} navigate={navigate} />
      ) : route.kind === 'imagepresets' ? (
        <ImagePresetsPage client={client} navigate={navigate} />
      ) : route.kind === 'imageprofiles' ? (
        <ImageProfilesPage client={client} navigate={navigate} />
      ) : route.kind === 'sharedlinks' ? (
        <SharedLinksPage client={client} navigate={navigate} />
      ) : route.kind === 'searchforms' ? (
        <SearchFormsPage key={`searchforms:${route.form ?? 'assets'}`} client={client} navigate={navigate} form={route.form} />
      ) : route.kind === 'assetsconfigurations' ? (
        <AssetsConfigurationsPage key={`assetsconfig:${route.editor ?? ''}`} client={client} navigate={navigate} editor={route.editor} />
      ) : route.kind === 'reports' ? (
        <ReportsPage client={client} navigate={navigate} />
      ) : route.kind === 'report' ? (
        <ReportPage key={`report:${route.id}`} client={client} id={route.id} navigate={navigate} />
      ) : route.kind === 'collection' ? (
        <CollectionPage key={`collection:${route.path}`} client={client} path={route.path} navigate={navigate} />
      ) : null}
    </div>
  );
}

function ProductBar({ user, go }: { user: string | null; go(kind: 'profiles' | 'collections' | 'schemas' | 'reports' | 'smarttags' | 'imagepresets' | 'imageprofiles'): void }) {
  const here = `${window.location.pathname}${window.location.hash}`;
  return (
    <Provider colorScheme="dark" UNSAFE_className="studio-shell">
      <header className="studio-shell__bar">
        <a className="studio-shell__brand" href="/studio/start.html">
          <span className="studio-shell__mark" aria-hidden>
            S
          </span>
          <span>Sensible Studio</span>
        </a>
        <div className="studio-shell__actions">
          <InboxBell />
          <MenuTrigger align="end">
            <ActionButton isQuiet aria-label="User">
              <span className="studio-shell__avatar" aria-hidden>
                {(user ?? '?').slice(0, 1).toUpperCase()}
              </span>
            </ActionButton>
            <Menu
              onAction={(k: Key) => {
                if (k === 'start') window.location.href = '/studio/start.html';
                if (k === 'profiles' || k === 'collections' || k === 'schemas' || k === 'reports' || k === 'smarttags' || k === 'imagepresets' || k === 'imageprofiles') go(k);
                if (k === 'signout') window.location.href = `/system/sling/logout?resource=${encodeURIComponent(here)}`;
              }}
            >
              <Section title={user ? `Signed in as ${user}` : 'Not signed in'}>
                <Item key="start">Go to Start</Item>
                <Item key="collections">Collections</Item>
                <Item key="profiles">Processing Profiles</Item>
                <Item key="schemas">Metadata Schemas</Item>
                <Item key="smarttags">Auto tags</Item>
                <Item key="imagepresets">Image Presets</Item>
                <Item key="imageprofiles">Image Profiles</Item>
                <Item key="reports">Media Reports</Item>
                <Item key="signout">Sign Out</Item>
              </Section>
            </Menu>
          </MenuTrigger>
        </div>
      </header>
    </Provider>
  );
}
