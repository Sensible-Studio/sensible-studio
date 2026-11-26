import './colors.scss';
import { Checkbox } from '@adobe/react-spectrum';

/** The metadata property the color tags are stored in (DamColors on the server). */
export const COLORS_PROPERTY = 'studio:colors';

/** The color names the server gives, in the order a picker shows them, with the swatch each is shown as. */
export const COLOR_SWATCHES: ReadonlyArray<{ name: string; label: string; hex: string }> = [
  { name: 'red', label: 'Red', hex: '#d32f2f' },
  { name: 'orange', label: 'Orange', hex: '#f57c00' },
  { name: 'yellow', label: 'Yellow', hex: '#fbc02d' },
  { name: 'green', label: 'Green', hex: '#388e3c' },
  { name: 'teal', label: 'Teal', hex: '#00897b' },
  { name: 'blue', label: 'Blue', hex: '#1976d2' },
  { name: 'purple', label: 'Purple', hex: '#7b1fa2' },
  { name: 'pink', label: 'Pink', hex: '#ec407a' },
  { name: 'brown', label: 'Brown', hex: '#795548' },
  { name: 'beige', label: 'Beige', hex: '#d7c4a3' },
  { name: 'white', label: 'White', hex: '#ffffff' },
  { name: 'gray', label: 'Gray', hex: '#9e9e9e' },
  { name: 'black', label: 'Black', hex: '#212121' },
];

export const colorLabel = (name: string) => COLOR_SWATCHES.find((c) => c.name === name)?.label ?? name;
const hexOf = (name: string) => COLOR_SWATCHES.find((c) => c.name === name)?.hex ?? '#888';

function Swatch({ name }: { name: string }) {
  return <span className="studio-colors__swatch" style={{ background: hexOf(name) }} aria-hidden="true" />;
}

/** The color tags of one file, in its properties: the colors it is mostly made of, each with its share. */
export function ColorTagsField({ colors }: { colors?: Array<{ name: string; share: number }> | null }) {
  if (!colors) return null;
  return (
    <div className="studio-colors" role="group" aria-label="Color tags">
      <div className="studio-assets__cqtags-label">Color tags</div>
      {colors.length === 0 ? (
        <p className="studio-assets__muted">No color takes a twentieth of the picture.</p>
      ) : (
        <ul className="studio-colors__chips">
          {colors.map((c) => (
            <li key={c.name} title={`${colorLabel(c.name)}: ${Math.round(c.share * 100)}% of the picture`}>
              <Swatch name={c.name} />
              <span>{colorLabel(c.name)}</span>
              <span className="studio-colors__share">{Math.round(c.share * 100)}%</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** The Color filter of the search: a picture has one of the colors ticked. */
export function ColorFacet({
  label,
  description,
  values,
  onChange,
}: {
  label: string;
  description?: string;
  values: string[];
  onChange(values: string[]): void;
}) {
  const chosen = new Set(values);
  return (
    <fieldset className="studio-colors__facet">
      <legend>{label}</legend>
      {description && <p className="studio-assets__muted">{description}</p>}
      <div className="studio-colors__grid">
        {COLOR_SWATCHES.map((c) => (
          <Checkbox
            key={c.name}
            isSelected={chosen.has(c.name)}
            onChange={(on) => {
              const next = new Set(chosen);
              if (on) next.add(c.name);
              else next.delete(c.name);
              onChange(COLOR_SWATCHES.map((s) => s.name).filter((n) => next.has(n)));
            }}
          >
            <span className="studio-colors__option">
              <Swatch name={c.name} />
              {c.label}
            </span>
          </Checkbox>
        ))}
      </div>
    </fieldset>
  );
}
