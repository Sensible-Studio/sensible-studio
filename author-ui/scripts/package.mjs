// Packs the built editor (dist/) as a FileVault content package that installs it under
// /libs/studio/editor/app, where Sensible Studio serves it at /editor.html/<page>.html.
//   npm run package   ->   dist-package/studio-editor-<version>.zip
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { deflateRawSync } from 'node:zlib';

const ROOT = '/libs/studio/editor/app';
const pkg = JSON.parse(readFileSync('package.json', 'utf8'));
const version = process.env.PKG_VERSION ?? pkg.version;
const dist = 'dist';

const files = [];
const walk = (dir) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p);
    else files.push(p);
  }
};
walk(dist);

const entries = [
  ['META-INF/vault/filter.xml', `<?xml version="1.0" encoding="UTF-8"?>\n<workspaceFilter version="1.0">\n    <filter root="${ROOT}"/>\n</workspaceFilter>\n`],
  [
    'META-INF/vault/properties.xml',
    `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">\n<properties>\n` +
      `<entry key="group">io.sensible.studio</entry>\n<entry key="name">studio-editor</entry>\n<entry key="version">${version}</entry>\n` +
      `<entry key="description">Sensible Studio page editor (author UI)</entry>\n<entry key="requiresRoot">false</entry>\n</properties>\n`,
  ],
  ...files.map((f) => [`jcr_root${ROOT}/${relative(dist, f).split('\\').join('/')}`, readFileSync(f)]),
];

mkdirSync('dist-package', { recursive: true });
const out = `dist-package/studio-editor-${version}.zip`;
writeFileSync(out, zip(entries));
console.log(`${out}: ${files.length} files under ${ROOT}`);

// ------------------------------------------------------------------ minimal zip writer (deflate)

function zip(items) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, content] of items) {
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
    const packed = deflateRawSync(data);
    const nameBuf = Buffer.from(name, 'utf8');
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6); // utf-8 names
    local.writeUInt16LE(8, 8); // deflate
    local.writeUInt32LE(33 << 16, 10); // 1980-01-01 00:00
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(packed.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, nameBuf, packed);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(8, 10);
    central.writeUInt32LE(33 << 16, 12);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(packed.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBuf);
    offset += local.length + nameBuf.length + packed.length;
  }
  const centralSize = centrals.reduce((n, b) => n + b.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(items.length, 8);
  end.writeUInt16LE(items.length, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, ...centrals, end]);
}

function crc32(buf) {
  let c = ~0;
  for (const b of buf) {
    c ^= b;
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}
