import type { ContentNode } from '../../granite';

/**
 * A small WKND-shaped repository for working on the UI without a running Sensible Studio instance.
 * Resource types are the real WKND ones, so the dialogs are the real (fixture) dialogs.
 */
export function createDemoRepository(): ContentNode {
  return {
    content: {
      'jcr:primaryType': 'sling:Folder',
      wknd: {
        'jcr:primaryType': 'cq:Page',
        'jcr:content': { 'jcr:primaryType': 'cq:PageContent', 'jcr:title': 'WKND' },
        us: {
          'jcr:primaryType': 'cq:Page',
          'jcr:content': { 'jcr:primaryType': 'cq:PageContent', 'jcr:title': 'United States' },
          en: {
            'jcr:primaryType': 'cq:Page',
            'jcr:content': { 'jcr:primaryType': 'cq:PageContent', 'jcr:title': 'English' },
            adventures: {
              'jcr:primaryType': 'cq:Page',
              'jcr:content': { 'jcr:primaryType': 'cq:PageContent', 'jcr:title': 'Adventures' },
            },
            magazine: {
              'jcr:primaryType': 'cq:Page',
              'jcr:content': { 'jcr:primaryType': 'cq:PageContent', 'jcr:title': 'Magazine' },
              'ski-touring': {
                'jcr:primaryType': 'cq:Page',
                'jcr:content': {
                  'jcr:primaryType': 'cq:PageContent',
                  'jcr:title': 'Ski Touring Mont Blanc',
                  'jcr:description': 'Ski touring the Haute Route from Chamonix to Zermatt.',
                  'sling:resourceType': 'wknd/components/page',
                  'cq:template': '/conf/wknd/settings/wcm/templates/article-page-template',
                  'cq:tags': ['wknd-shared:activity/skiing', 'wknd-shared:region/europe'],
                  root: {
                    'jcr:primaryType': 'nt:unstructured',
                    'sling:resourceType': 'wknd/components/container',
                    container: {
                      'jcr:primaryType': 'nt:unstructured',
                      'sling:resourceType': 'wknd/components/container',
                      layout: 'responsiveGrid',
                      title: {
                        'jcr:primaryType': 'nt:unstructured',
                        'sling:resourceType': 'wknd/components/title',
                        'jcr:title': 'Ski Touring Mont Blanc',
                        type: 'h1',
                      },
                      byline: {
                        'jcr:primaryType': 'nt:unstructured',
                        'sling:resourceType': 'wknd/components/byline',
                        name: 'Stacey Roswells',
                        occupations: ['Photographer', 'Writer'],
                        fileReference: '/content/dam/wknd-shared/en/contributors/stacey-roswells.jpg',
                      },
                      image: {
                        'jcr:primaryType': 'nt:unstructured',
                        'sling:resourceType': 'wknd/components/image',
                        fileReference: '/content/dam/wknd-shared/en/magazine/ski-touring/skitouring1sjoeberg.jpg',
                        alt: 'Skiers ascending a ridge',
                        isDecorative: false,
                        displayPopupTitle: true,
                      },
                      text: {
                        'jcr:primaryType': 'nt:unstructured',
                        'sling:resourceType': 'wknd/components/text',
                        text: '<p>The <b>Haute Route</b> links Chamonix and Zermatt across glaciers and high passes.</p>',
                        textIsRich: 'true',
                      },
                      teaser: {
                        'jcr:primaryType': 'nt:unstructured',
                        'sling:resourceType': 'wknd/components/teaser',
                        'jcr:title': 'Where to stay',
                        titleFromPage: false,
                        descriptionFromPage: false,
                        actionsEnabled: true,
                        actions: {
                          'jcr:primaryType': 'nt:unstructured',
                          item0: {
                            'jcr:primaryType': 'nt:unstructured',
                            link: '/content/wknd/us/en/adventures',
                            text: 'See adventures',
                          },
                        },
                      },
                      separator: {
                        'jcr:primaryType': 'nt:unstructured',
                        'sling:resourceType': 'wknd/components/separator',
                      },
                      button: {
                        'jcr:primaryType': 'nt:unstructured',
                        'sling:resourceType': 'wknd/components/button',
                        'jcr:title': 'Book a guide',
                        linkURL: '/content/wknd/us/en/adventures',
                      },
                    },
                  },
                },
              },
            },
          },
        },
      },
      dam: {
        'jcr:primaryType': 'sling:Folder',
        'wknd-shared': {
          'jcr:primaryType': 'sling:Folder',
          en: {
            'jcr:primaryType': 'sling:Folder',
            contributors: {
              'jcr:primaryType': 'sling:Folder',
              'stacey-roswells.jpg': { 'jcr:primaryType': 'dam:Asset' },
              'ayo-ogunseinde.jpg': { 'jcr:primaryType': 'dam:Asset' },
            },
            magazine: {
              'jcr:primaryType': 'sling:Folder',
              'ski-touring': {
                'jcr:primaryType': 'sling:Folder',
                'skitouring1sjoeberg.jpg': { 'jcr:primaryType': 'dam:Asset' },
                'skitouring3sjoeberg.jpg': { 'jcr:primaryType': 'dam:Asset' },
              },
            },
          },
        },
      },
    },
  };
}

export const DEMO_PAGE = '/content/wknd/us/en/magazine/ski-touring';

/** Offline components: what WKND offers in its "WKND - Content" group. */
export const DEMO_COMPONENTS = [
  ['wknd/components/title', 'Title'],
  ['wknd/components/text', 'Text'],
  ['wknd/components/image', 'Image'],
  ['wknd/components/teaser', 'Teaser'],
  ['wknd/components/button', 'Button'],
  ['wknd/components/byline', 'Byline'],
  ['wknd/components/list', 'List'],
  ['wknd/components/separator', 'Separator'],
  ['wknd/components/container', 'Container'],
  ['wknd/components/tabs', 'Tabs'],
  ['wknd/components/embed', 'Embed'],
  ['wknd/components/download', 'Download'],
] as const;
