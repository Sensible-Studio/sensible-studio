"""Observe the MSM (live copy) content contract in WKND content packages (MIT-licensed data).

Reads FileVault jcr_root trees (docview .content.xml) and writes:
  1. a Sling JSON fixture of the MSM-relevant pages (blueprints and their live copies) for sling-mock tests;
  2. a summary of the contract: live copy roots and their configuration, inheritance markers, edge cases.

Only content data is read, no Adobe code.

Usage: python3 observe_msm.py <jcr_root> [<jcr_root> ...] <fixture.json> <expected.json>
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOTS = sys.argv[1:-2]
FIXTURE = sys.argv[-2]
EXPECTED = sys.argv[-1]

# Trees copied into the fixture: the English blueprint and the live copies that use it, the Swiss French
# copy (property inheritance cancelled) and the experience fragments with cancelled components.
FIXTURE_TREES = [
    '/content/wknd/language-masters/en',
    '/content/wknd/language-masters/fr',
    '/content/wknd/us/en',
    '/content/wknd/ca/en',
    '/content/wknd/ch/fr',
    '/content/wknd/language-masters/es',
    '/content/experience-fragments/wknd/language-masters/en/featured',
    '/content/experience-fragments/wknd/us/en',
]

NS = {
    'http://www.jcp.org/jcr/1.0': 'jcr',
    'http://www.jcp.org/jcr/nt/1.0': 'nt',
    'http://www.jcp.org/jcr/mix/1.0': 'mix',
    'http://www.day.com/jcr/cq/1.0': 'cq',
    'http://sling.apache.org/jcr/sling/1.0': 'sling',
    'http://www.day.com/dam/1.0': 'dam',
    'http://www.adobe.com/jcr/granite/1.0': 'granite',
    'http://purl.org/dc/elements/1.1/': 'dc',
    'http://www.jcp.org/jcr/sv/1.0': 'sv',
    'http://ns.adobe.com/xap/1.0/': 'xmp',
    'http://ns.adobe.com/tiff/1.0/': 'tiff',
    'http://ns.adobe.com/exif/1.0/': 'exif',
    'http://www.day.com/jcr/rep/1.0': 'rep',
    'http://www.jcp.org/jcr/oak/1.0': 'oak',
    'internal': 'rep',
}

ESC = re.compile(r'_x([0-9a-fA-F]{4})_')


def qname(tag):
    if tag.startswith('{'):
        uri, local = tag[1:].split('}', 1)
        prefix = NS.get(uri)
        if prefix is None:
            prefix = uri.rstrip('/').split('/')[-1]
        tag = prefix + ':' + local
    return ESC.sub(lambda m: chr(int(m.group(1), 16)), tag)


def dir_name(name):
    m = re.match(r'^_([a-zA-Z0-9]+)_(.*)$', name)
    if m:
        return m.group(1) + ':' + m.group(2)
    return name


def split_array(body):
    out, cur, i = [], '', 0
    while i < len(body):
        c = body[i]
        if c == '\\' and i + 1 < len(body):
            cur += body[i + 1]
            i += 2
            continue
        if c == ',':
            out.append(cur)
            cur = ''
        else:
            cur += c
        i += 1
    if cur or out:
        out.append(cur)
    return out


def convert(t, v):
    if t == 'Boolean':
        return v == 'true'
    if t == 'Long':
        return int(v)
    if t in ('Double',):
        return float(v)
    return v  # String, Date (ISO 8601, sling-mock turns it into a Calendar), Name, Path, Decimal...


def value(raw):
    m = re.match(r'^\{(\w+)\}(.*)$', raw, re.S)
    t, body = (m.group(1), m.group(2)) if m else ('String', raw)
    if body.startswith('[') and body.endswith(']'):
        return [convert(t, x) for x in split_array(body[1:-1])]
    if body.startswith('\\'):
        body = body[1:]
    return convert(t, body)


def element(el):
    node = {}
    for k, v in el.attrib.items():
        name = qname(k)
        if name.startswith('xmlns'):
            continue
        node[name] = value(v)
    for child in el:
        node[qname(child.tag)] = element(child)
    return node


def merge(a, b):
    for k, v in b.items():
        if isinstance(v, dict) and isinstance(a.get(k), dict):
            merge(a[k], v)
        else:
            a[k] = v


def load_dir(path):
    """Node for a FileVault directory: its .content.xml merged with its sub directories."""
    node = {}
    cx = os.path.join(path, '.content.xml')
    if os.path.isfile(cx):
        node = element(ET.parse(cx).getroot())
    for entry in sorted(os.listdir(path)):
        full = os.path.join(path, entry)
        if os.path.isdir(full):
            name = dir_name(entry)
            child = load_dir(full)
            if isinstance(node.get(name), dict):
                merge(node[name], child)
            else:
                node[name] = child
    return node


def find_tree(path):
    for root in ROOTS:
        d = os.path.join(root, *[('_%s_%s' % tuple(s.split(':', 1)) if ':' in s else s) for s in path.strip('/').split('/')])
        if os.path.isdir(d):
            return load_dir(d)
    return None


def walk(node, path):
    yield path, node
    for k, v in node.items():
        if isinstance(v, dict):
            yield from walk(v, path + '/' + k)


def mixins(node):
    m = node.get('jcr:mixinTypes', [])
    return m if isinstance(m, list) else [m]


def main():
    content = {}
    for tree in FIXTURE_TREES:
        node = find_tree(tree)
        if node is None:
            raise SystemExit('missing ' + tree)
        cur = content
        parts = tree[len('/content/'):].split('/')
        for p in parts[:-1]:
            cur = cur.setdefault(p, {'jcr:primaryType': 'sling:Folder'} if not cur.get(p) else cur[p])
        cur[parts[-1]] = node
    # pages above the trees, so the page tree is complete
    for p in ['wknd', 'wknd/language-masters', 'wknd/us', 'wknd/ca', 'wknd/ch']:
        cur = content
        for s in p.split('/'):
            cur = cur[s]
        cur['jcr:primaryType'] = 'cq:Page' if p != 'wknd/language-masters' else cur.get('jcr:primaryType', 'cq:Page')
    for p in ['experience-fragments', 'experience-fragments/wknd', 'experience-fragments/wknd/language-masters',
              'experience-fragments/wknd/language-masters/en']:
        cur = content
        for s in p.split('/'):
            cur = cur[s]
        cur['jcr:primaryType'] = 'sling:OrderedFolder'

    os.makedirs(os.path.dirname(FIXTURE), exist_ok=True)
    with open(FIXTURE, 'w') as f:
        json.dump(content, f, indent=None, separators=(',', ':'), sort_keys=False)

    # ------------------------------------------------------------------ contract summary, whole packages
    roots, related, cancelled, prop_cancelled, orphan = [], 0, [], [], []
    everything = {}
    for top in ['/content/wknd', '/content/experience-fragments/wknd']:
        everything[top] = find_tree(top)
    config_paths = set()
    for top, tree in everything.items():
        for path, node in walk(tree, top):
            if path.endswith('/jcr:content/cq:LiveSyncConfig'):
                page = path[:-len('/jcr:content/cq:LiveSyncConfig')]
                config_paths.add(page)
                roots.append({
                    'path': page,
                    'primaryType': node.get('jcr:primaryType'),
                    'master': node.get('cq:master'),
                    'isDeep': node.get('cq:isDeep'),
                    'rolloutConfigs': node.get('cq:rolloutConfigs'),
                    'rootMixins': sorted(mixins(tree_at(everything, page + '/jcr:content'))),
                })
    for top, tree in everything.items():
        for path, node in walk(tree, top):
            mx = mixins(node)
            if 'cq:LiveRelationship' in mx:
                related += 1
                if not any(path == r or path.startswith(r + '/') for r in config_paths):
                    orphan.append(path)
            if 'cq:LiveSyncCancelled' in mx:
                cancelled.append({'path': path, 'cq:isCancelledForChildren': node.get('cq:isCancelledForChildren')})
            if 'cq:PropertyLiveSyncCancelled' in mx:
                prop_cancelled.append({'path': path, 'cq:propertyInheritanceCancelled': node.get('cq:propertyInheritanceCancelled')})
    for r in roots:
        r['masterExists'] = tree_at(everything, r['master']) is not None
    orphan_pages = sorted({p.split('/jcr:content')[0] for p in orphan})
    summary = {
        'liveCopies': sorted(roots, key=lambda r: r['path']),
        'liveRelationshipNodes': related,
        'cancelledNodes': sorted(cancelled, key=lambda c: c['path']),
        'propertyCancelledNodes': sorted(prop_cancelled, key=lambda c: c['path']),
        'relationshipMixinOutsideLiveCopy': orphan_pages,
        'fixtureTrees': FIXTURE_TREES,
    }
    with open(EXPECTED, 'w') as f:
        json.dump(summary, f, indent=2)
        f.write('\n')
    print('live copies', len(roots), 'related', related, 'cancelled', len(cancelled), 'prop', len(prop_cancelled),
          'orphan pages', len(orphan_pages))


def tree_at(everything, path):
    for top, tree in everything.items():
        if path == top or path.startswith(top + '/'):
            cur = tree
            for s in path[len(top):].strip('/').split('/') if path != top else []:
                if not isinstance(cur, dict) or s not in cur:
                    return None
                cur = cur[s]
            return cur
    return None


if __name__ == '__main__':
    main()
