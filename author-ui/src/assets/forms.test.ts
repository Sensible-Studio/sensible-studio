import { describe, expect, it } from 'vitest';
import type { SchemaTab } from './api';
import { assetsRoute, routeUrl } from './route';
import { profileValues, valuesSummary } from './schemas';
import { MemoryDam } from './testing/MemoryDam';

const TABS: SchemaTab[] = [
  {
    title: 'Basic',
    fields: [
      { label: 'Copyright', property: 'dc:rights', type: 'text' },
      { label: 'Keywords', property: 'dc:subject', type: 'multi' },
      { label: 'Approved', property: 'wknd:approved', type: 'checkbox' },
    ],
  },
];

describe('metadata profiles and folder metadata schemas', () => {
  it('keeps the values that are set and belong to a field', () => {
    expect(profileValues(TABS, { 'dc:rights': '© WKND', 'dc:subject': [], 'wknd:approved': false, 'dc:gone': 'x' })).toEqual({ 'dc:rights': '© WKND' });
    expect(profileValues(TABS, { 'dc:subject': ['wknd', 'travel'], 'wknd:approved': true })).toEqual({ 'dc:subject': ['wknd', 'travel'], 'wknd:approved': true });
  });

  it('sums a profile up by field label', () => {
    expect(valuesSummary({ path: '/p', name: 'p', title: 'P', tabs: TABS, values: { 'dc:rights': '© WKND', 'dc:subject': ['a', 'b'] } })).toBe('Copyright: © WKND · Keywords: a, b');
  });

  it('routes the two consoles at AEM’s addresses', () => {
    for (const kind of ['folderschemas', 'metadataprofiles'] as const) {
      const url = routeUrl({ kind });
      expect(assetsRoute({ pathname: url, hash: '' })).toEqual({ kind });
    }
    expect(routeUrl({ kind: 'metadataprofiles' })).toBe('/mnt/overlay/dam/gui/content/processingprofilepage/metadataprofiles.html/conf/global/settings/dam/adminui-extension/metadataprofile');
    // the processing profiles console is still its own
    expect(assetsRoute({ pathname: routeUrl({ kind: 'profiles' }), hash: '' })).toEqual({ kind: 'profiles' });
  });

  it('a folder inherits the forms of its parent', async () => {
    const dam = new MemoryDam();
    await dam.createFolder('/content/dam', 'WKND', 'wknd');
    await dam.createFolder('/content/dam/wknd', 'Summer', 'summer');
    const p = await dam.saveForm('profile', 'wknd', 'WKND', TABS, { 'dc:rights': '© WKND' });
    await dam.setFolderForm('profile', '/content/dam/wknd', p.path);
    expect((await dam.forms('profile', '/content/dam/wknd/summer')).effective).toBe(p.path);
    const s = await dam.saveForm('folderSchema', 'campaign', 'Campaign', TABS);
    await dam.setFolderForm('folderSchema', '/content/dam/wknd', s.path);
    await dam.saveFolderMetadata('/content/dam/wknd/summer', { 'dc:rights': 'x' });
    const md = await dam.folderMetadata('/content/dam/wknd/summer');
    expect(md.schema?.title).toBe('Campaign');
    expect(md.values).toEqual({ 'dc:rights': 'x' });
  });
});
