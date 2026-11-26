"""Observe the DAM content contract from WKND content packages (MIT-licensed data).

Walks every dam:Asset node in a FileVault jcr_root, reads the original and
rendition binaries, and records sizes, formats and metadata keys. Output: JSON.
"""
import collections
import json
import os
import re
import sys

from PIL import Image

ROOTS = sys.argv[1:-1]
OUT = sys.argv[-1]

PRIMARY = re.compile(r'jcr:primaryType="([^"]+)"')
REND = re.compile(r'^cq5dam\.(\w+)\.(\d+)\.(\d+)\.(\w+)$')


def root_attrs(xml):
    """Attributes of the first element after <jcr:root (the node itself)."""
    m = re.search(r'<jcr:root(.*?)>', xml, re.S)
    return m.group(1) if m else ''


def metadata_attrs(xml):
    m = re.search(r'<metadata\b(.*?)(/?)>', xml, re.S)
    if not m:
        return {}
    return dict(re.findall(r'\s([\w:]+)="([^"]*)"', m.group(1)))


def jpeg_quality(img):
    """Rough IJG quality estimate from the luminance quantization table."""
    q = getattr(img, 'quantization', None)
    if not q or 0 not in q:
        return None
    std = [16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
           14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
           18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
           49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99]
    table = sorted(q[0])
    ratio = sum(q[0]) / sum(std) * 100
    if ratio <= 100:
        return round((200 - ratio) / 2)
    return round(5000 / ratio)


assets = []
meta_keys = collections.Counter()
for root in ROOTS:
    for dirpath, dirnames, filenames in os.walk(root):
        if '.content.xml' not in filenames:
            continue
        p = os.path.join(dirpath, '.content.xml')
        with open(p, encoding='utf-8', errors='replace') as f:
            xml = f.read()
        if 'jcr:primaryType="dam:Asset"' not in root_attrs(xml):
            continue
        rdir = os.path.join(dirpath, '_jcr_content', 'renditions')
        orig = os.path.join(rdir, 'original')
        md = metadata_attrs(xml)
        for k in md:
            meta_keys[k] += 1
        mime = md.get('dam:MIMEtype') or md.get('dc:format')
        a = {
            'path': dirpath.split('jcr_root', 1)[1],
            'mime': mime,
            'assetState': (re.search(r'dam:assetState="([^"]+)"', xml) or [None, None])[1],
            'renditions': {},
        }
        if os.path.exists(orig):
            a['originalBytes'] = os.path.getsize(orig)
            try:
                with Image.open(orig) as im:
                    a['original'] = {'w': im.width, 'h': im.height, 'mode': im.mode, 'fmt': im.format}
            except Exception:
                pass
        if os.path.isdir(rdir):
            for name in sorted(os.listdir(rdir)):
                full = os.path.join(rdir, name)
                if not os.path.isfile(full) or name == 'original':
                    continue
                r = {'bytes': os.path.getsize(full)}
                try:
                    with Image.open(full) as im:
                        r.update(w=im.width, h=im.height, mode=im.mode, fmt=im.format)
                        if im.format == 'JPEG':
                            r['q'] = jpeg_quality(im)
                            r['progressive'] = bool(im.info.get('progressive'))
                except Exception:
                    pass
                a['renditions'][name] = r
        failed = re.findall(r'<(cq[\w.]+)\s+jcr:primaryType="nt:unstructured"\s+message="([^"]*)"\s+reason="([^"]*)"', xml)
        if failed:
            a['failed'] = [{'rendition': n, 'reason': r} for n, _, r in failed]
        assets.append(a)

# Infer the sizing rule for each cq5dam rendition kind.
rules = collections.defaultdict(lambda: collections.Counter())
for a in assets:
    o = a.get('original')
    for name, r in a['renditions'].items():
        m = REND.match(name)
        if not m or 'w' not in r:
            continue
        bw, bh = int(m.group(2)), int(m.group(3))
        if o:
            s = min(bw / o['w'], bh / o['h'])
            fit = (round(o['w'] * s), round(o['h'] * s))
            fit_no_up = fit if s < 1 else (o['w'], o['h'])
            if (r['w'], r['h']) == (bw, bh):
                kind = 'exact-box'
            elif abs(r['w'] - fit_no_up[0]) <= 1 and abs(r['h'] - fit_no_up[1]) <= 1:
                kind = 'fit-inside-no-upscale'
            elif abs(r['w'] - fit[0]) <= 1 and abs(r['h'] - fit[1]) <= 1:
                kind = 'fit-inside-upscale'
            else:
                kind = 'other'
        else:
            kind = 'no-original-dims'
        rules[name][(kind, r.get('fmt'), r.get('mode'))] += 1

summary = {
    'assetCount': len(assets),
    'mimeTypes': collections.Counter(a['mime'] for a in assets).most_common(),
    'renditionSets': collections.Counter(tuple(sorted(a['renditions'])) for a in assets).most_common(10),
    'sizingRules': {k: [[list(kk), v] for kk, v in c.most_common()] for k, c in sorted(rules.items())},
    'jpegQuality': collections.Counter(
        (n, r.get('q'), r.get('progressive')) for a in assets for n, r in a['renditions'].items() if r.get('fmt') == 'JPEG'
    ).most_common(20),
    'failedReasons': collections.Counter((f['rendition'], f['reason']) for a in assets for f in a.get('failed', [])).most_common(),
    'metadataKeys': meta_keys.most_common(80),
}
with open(OUT, 'w') as f:
    json.dump({'summary': summary, 'assets': assets}, f, indent=1, default=list)
print(json.dumps(summary, indent=1, default=list)[:9000])
