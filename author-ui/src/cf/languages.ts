/** Language helpers for language copies (same rules as the server's FragmentTranslation). */

const CODE = /^[a-z]{2,3}([_-][a-zA-Z0-9]{2,4})?$/;

let names: Intl.DisplayNames | null = null;
try {
  names = new Intl.DisplayNames(['en'], { type: 'language' });
} catch {
  names = null;
}

/** A language's English name, e.g. de_ch → "Swiss High German"; null when the code is not a language. */
export function languageName(code: string): string | null {
  if (!CODE.test(code)) return null;
  try {
    const name = names?.of(code.replace('_', '-'));
    return name && name.toLowerCase() !== code.replace('_', '-').toLowerCase() ? name : null;
  } catch {
    return null;
  }
}

export const isLanguageCode = (code: string) => languageName(code) !== null;

/** The language root of a DAM path: the nearest ancestor folder named with a language code. */
export function languageRoot(path: string): { root: string; language: string; relative: string } | null {
  if (!path.startsWith('/content/dam/')) return null;
  const parts = path.split('/');
  for (let i = parts.length - 1; i >= 3; i--) {
    if (isLanguageCode(parts[i])) {
      const root = parts.slice(0, i + 1).join('/');
      return { root, language: parts[i], relative: path.slice(root.length + 1) };
    }
  }
  return null;
}

/** The path of a language copy, e.g. …/en/a/b in fr → …/fr/a/b. */
export function pathIn(path: string, language: string): string | null {
  const lr = languageRoot(path);
  if (!lr) return null;
  return `${lr.root.slice(0, lr.root.lastIndexOf('/') + 1)}${language}${lr.relative ? `/${lr.relative}` : ''}`;
}
