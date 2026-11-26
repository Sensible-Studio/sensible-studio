import { describe, expect, it } from 'vitest';
import { annotationName, arrowEntries, collectAnnotations, createEntries, DEFAULT_COLOR, formatPoints, parsePoints } from './annotations';

describe('annotations', () => {
  it('collects cq:annotations below the content root, with AEM defaults', () => {
    const content = {
      'jcr:primaryType': 'cq:PageContent',
      root: {
        'jcr:primaryType': 'nt:unstructured',
        container: {
          'jcr:primaryType': 'nt:unstructured',
          title: {
            'jcr:primaryType': 'nt:unstructured',
            'cq:annotations': {
              'jcr:primaryType': 'nt:unstructured',
              '1_1': { 'jcr:primaryType': 'nt:unstructured', text: 'Bigger', x: 31, y: 11, 'jcr:createdBy': 'admin' },
              '2_2': { 'jcr:primaryType': 'nt:unstructured', text: '', color: '#d7373f', x: 5, y: 5 },
            },
          },
          text: { 'jcr:primaryType': 'nt:unstructured', text: 'hi' },
        },
      },
    };
    const out = collectAnnotations(content, '/content/p/jcr:content');
    expect(out.map((a) => a.path)).toEqual(['/content/p/jcr:content/root/container/title/cq:annotations/1_1', '/content/p/jcr:content/root/container/title/cq:annotations/2_2']);
    expect(out[0]).toMatchObject({ component: '/content/p/jcr:content/root/container/title', text: 'Bigger', color: DEFAULT_COLOR, x: 31, y: 11, createdBy: 'admin' });
    expect(out[1].color).toBe('#d7373f');
    expect(collectAnnotations(null, '/x')).toEqual([]);
  });

  it('names and creates annotations like AEM', () => {
    expect(annotationName(2, 1700000000000)).toBe('3_1700000000000');
    expect(createEntries(31.6, 11.2)).toEqual(expect.arrayContaining([{ name: 'x', value: '32' }, { name: 'y', value: '11' }, { name: 'text', value: '' }]));
  });

  it('reads the arrows of an annotation (its sketches with points) and writes them back', () => {
    const content = {
      title: {
        'cq:annotations': {
          '1_1': {
            text: 'Look',
            x: 1,
            y: 2,
            sketches: {
              'jcr:primaryType': 'nt:unstructured',
              arrow_1: { points: '10,20 30.4,40 50,60', arrow: true },
              junk: { path: 'M0 0' },
            },
          },
        },
      },
    };
    const [a] = collectAnnotations(content, '/c');
    expect(a.arrows).toEqual([
      {
        path: '/c/title/cq:annotations/1_1/sketches/arrow_1',
        points: [
          { x: 10, y: 20 },
          { x: 30.4, y: 40 },
          { x: 50, y: 60 },
        ],
      },
    ]);
    expect(parsePoints('1,2 x,3 4,5')).toEqual([
      { x: 1, y: 2 },
      { x: 4, y: 5 },
    ]);
    expect(formatPoints([{ x: 1.4, y: 2.6 }])).toBe('1,3');
    expect(arrowEntries([{ x: 1, y: 2 }, { x: 3, y: 4 }])).toEqual(expect.arrayContaining([{ name: 'points', value: '1,2 3,4' }]));
  });
});
