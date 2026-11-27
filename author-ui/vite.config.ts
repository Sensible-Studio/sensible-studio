/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import type { Plugin } from 'vite';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** Serves the app at its AEM-style URLs (`/editor.html/<page>.html`, `/sites.html/<path>`, `/tags.html/<tag>`, `/workflow/*.html`, `/cf.html/<folder>`, `/assets.html/<folder>`, the image editor, `/security/*.html`, `/studio/start.html`) by answering with index.html. */
function editorUrls(): Plugin {
  return {
    name: 'studio-editor-urls',
    configureServer(server) {
      server.middlewares.use((req, _res, next) => {
        if (req.url && /^\/(editor\.html(\/|$|\?)|sites\.html(\/|$|\?)|tags\.html(\/|$|\?)|workflow\/(models|instances|launchers|inbox)\.html($|\?)|cf\.html(\/|$|\?)|(assets|assetdetails)\.html(\/|$|\?)|mnt\/overlay\/wcm\/core\/content\/sites\/properties\.html($|\?)|mnt\/overlay\/dam\/gui\/content\/assets\/assetedit\.html\/|security\/[a-z]+\.html($|\?)|studio\/start\.html($|\?|#))/.test(req.url)) req.url = '/index.html';
        next();
      });
    },
  };
}

/**
 * Sensible Studio's own icons in place of Spectrum's workflow icons (see `icons/README.md`): an import of
 * `@spectrum-icons/workflow/<Name>` is answered with `icons/custom/<Name>.svg`, else with the Lucide icon
 * `icons/map.json` names for it, wrapped in Spectrum's `Icon` so sizes and slots keep working. A name in
 * neither fails the build.
 */
function studioIcons(): Plugin {
  const PREFIX = '\0studio-icon:';
  const root = __dirname;
  const map = JSON.parse(readFileSync(resolve(root, 'icons/map.json'), 'utf8')) as Record<string, string>;
  const shapes = (svg: string) =>
    svg
      .replace(/<!--[\s\S]*?-->/g, '')
      // anything that is not a shape (an editor's or a transfer's metadata) stays out of the page
      .replace(/<metadata[\s\S]*?<\/metadata>/g, '')
      .replace(/^[\s\S]*?<svg[^>]*>/, '')
      .replace(/<\/svg>\s*$/, '')
      .trim();
  return {
    name: 'studio-icons',
    enforce: 'pre',
    resolveId(id, importer) {
      const m = /^@spectrum-icons\/workflow\/([A-Za-z0-9]+)$/.exec(id);
      if (!m) return null;
      // a glyph one of Spectrum's own components asks for and the set does not have stays Spectrum's
      const known = !!map[m[1]] || existsSync(resolve(root, 'icons/custom', `${m[1]}.svg`));
      return known || !importer || !importer.includes('/node_modules/') ? PREFIX + m[1] : null;
    },
    load(id) {
      if (!id.startsWith(PREFIX)) return null;
      const name = id.slice(PREFIX.length);
      const custom = resolve(root, 'icons/custom', `${name}.svg`);
      const lucide = map[name] ? resolve(root, 'node_modules/lucide-static/icons', `${map[name]}.svg`) : null;
      const file = existsSync(custom) ? custom : lucide && existsSync(lucide) ? lucide : null;
      if (!file) this.error(`No Sensible Studio icon for "${name}": add icons/custom/${name}.svg or name a Lucide icon for it in icons/map.json`);
      const inner = shapes(readFileSync(file as string, 'utf8'));
      return [
        "import { Icon } from '@adobe/react-spectrum/Icon';",
        "import { createElement as h } from 'react';",
        `const inner = ${JSON.stringify(inner)};`,
        `export default function ${/^[0-9]/.test(name) ? `I${name}` : name}(props) {`,
        "  return h(Icon, props, h('svg', { viewBox: '0 0 24 24', className: 'studio-icon', dangerouslySetInnerHTML: { __html: inner } }));",
        '}',
      ].join('\n');
    },
  };
}

/**
 * Dev server in front of a local Sensible Studio instance.
 *   STUDIO_TARGET   where Sensible Studio runs           (default http://localhost:4502)
 *   STUDIO_AUTH     user:password for Sling  (default admin:admin, dev only)
 * The proxy rewrites Origin/Referer so the Sling referrer filter accepts POSTs from the dev server.
 */
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.STUDIO_TARGET ?? 'http://localhost:4502';
  const auth = env.STUDIO_AUTH ?? 'admin:admin';
  const proxy = {
    target,
    changeOrigin: true,
    secure: false,
    configure: (p: { on(event: 'proxyReq', cb: (req: { setHeader(k: string, v: string): void; removeHeader(k: string): void }) => void): void }) => {
      p.on('proxyReq', (req) => {
        req.setHeader('Authorization', `Basic ${Buffer.from(auth).toString('base64')}`);
        req.setHeader('Referer', `${target}/`);
        req.setHeader('Origin', target);
      });
    },
  };
  return {
    // built for Sensible Studio, which serves the app from the studio-editor content package
    base: command === 'build' ? '/libs/studio/editor/app/' : '/',
    plugins: [studioIcons(), editorUrls(), react()],
    server: {
      port: 5173,
      // Everything that is not the editor app itself goes to Sensible Studio: content, clientlibs, DAM renditions and
      // the short URLs the resource resolver mapping produces (e.g. /wknd/us/en/... for /content/wknd/...).
      proxy: {
        '^/(?!src/|@|node_modules/|editor\\.html|sites\\.html|tags\\.html|workflow/(?:models|instances|launchers|inbox)\\.html|cf\\.html|assets\\.html|assetdetails\\.html|mnt/overlay/wcm/core/content/sites/properties\\.html|mnt/overlay/dam/gui/content/assets/assetedit\\.html|security/[a-z]+\\.html|studio/start\\.html|index\\.html|favicon\\.ico|$).*': proxy,
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['src/testing/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      css: false,
      // Spectrum ships its styles as .css imports inside node_modules; inline those packages so Vite handles them.
      server: { deps: { inline: [/@adobe\/react-spectrum/, /@react-spectrum\//, /@spectrum-icons\//] } },
    },
  };
});
