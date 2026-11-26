import { defaultTheme } from '@adobe/react-spectrum';
import '@fontsource-variable/inter';
import './theme.scss';
import './overlays.scss';

/**
 * Sensible Studio's look. The components are React Spectrum's (Apache-2.0); the look is not Adobe's:
 * our own accent and neutrals, our own corner radii and button shape, our own typeface (Inter, OFL) and
 * our own icons (`icons/`, the `studio-icons` plugin). Spectrum reads all of it from CSS variables, so the
 * theme is Spectrum's default one plus one more class — `studio-theme` — whose rules in `theme.scss` set
 * those variables. Every Provider carries the class, the ones Spectrum makes for dialogs, menus and
 * tooltips included, so nothing renders in the stock colours.
 */
export const studioTheme: typeof defaultTheme = {
  ...defaultTheme,
  global: { ...defaultTheme.global, 'studio-theme': 'studio-theme' },
};
