import { Provider } from '@adobe/react-spectrum';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { HttpClient } from './editor/api/HttpClient';
import { parsePlace } from './shell/places';
import { Shell } from './shell/Shell';
import { applyTheme, readTheme } from './theme/pref';
import { studioTheme } from './theme/theme';
import './editor/styles/editor.scss';
import './editor/styles/redesign.scss';
import './start/start.scss';
import './sites/sites.scss';
import './tags/tags.scss';
import './linkchecker/linkchecker.scss';
import './health/health.scss';
import './replication/replication.scss';
import './targeting/targeting.scss';
import './workflow/workflow.scss';
import './security/security.scss';
import './cf/cf.scss';

/**
 * One app at every URL of Studio. `shell/places.ts` says which place a URL names (the editor, Sites,
 * Assets, a form, a tool…), `shell/views.tsx` which view shows it, and `Shell` keeps the tabs, the
 * history and the frame around them. A URL that names nothing opens Start.
 */
applyTheme(readTheme());

const client = new HttpClient();
const place = parsePlace(window.location);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Provider theme={studioTheme} colorScheme="light" scale="medium" height="100%" UNSAFE_className="studio-provider">
      <Shell client={client} initial={place} />
    </Provider>
  </React.StrictMode>,
);
