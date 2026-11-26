# Icons

Sensible Studio does not show Spectrum's workflow icons. Every `import X from '@spectrum-icons/workflow/X'`
in `src/` is answered by the `studio-icons` plugin of `vite.config.ts` with an icon of our own set:

1. `icons/custom/X.svg` when there is one — drawn for Sensible Studio (24 × 24 grid, 2 px stroke, round caps
   and joins, no fill; only the shapes, the plugin sets the stroke);
2. else the [Lucide](https://lucide.dev) icon `map.json` names for X (lucide-static, ISC license).

A name in neither is a build error, so no Spectrum glyph slips back in. The components keep Spectrum's `Icon`
wrapper, so sizes and slots in buttons and menus work as before. To change an icon: put `X.svg` in
`custom/`, or change its name in `map.json`.
