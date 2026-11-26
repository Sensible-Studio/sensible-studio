/** Small inline icons of the Granite widgets (drawn for Sensible Studio; no icon font or external set). */

export function FolderGlyph() {
  return (
    <svg viewBox="0 0 18 18" width="16" height="16" aria-hidden="true" focusable="false">
      <path
        d="M1.5 4.5a1 1 0 0 1 1-1h4.2l1.6 1.8h7.2a1 1 0 0 1 1 1v7.7a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1z"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function ImageGlyph({ size = 24 }: { size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" focusable="false">
      <rect x="2.5" y="4.5" width="19" height="15" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <circle cx="8.5" cy="9.5" r="1.8" fill="currentColor" />
      <path d="M3.5 17.5l5.5-5.5 4 4 2.5-2.5 5 5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
    </svg>
  );
}

export function FileGlyph({ size = 24 }: { size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" focusable="false">
      <path d="M6 2.5h8l4.5 4.5v14.5H6z" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
      <path d="M14 2.5V7h4.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
    </svg>
  );
}

export function ChevronGlyph() {
  return (
    <svg viewBox="0 0 18 18" width="12" height="12" aria-hidden="true" focusable="false">
      <path d="M6.5 3.5L12 9l-5.5 5.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function ArrowGlyph({ up = false }: { up?: boolean }) {
  return (
    <svg viewBox="0 0 18 18" width="14" height="14" aria-hidden="true" focusable="false" style={up ? { transform: 'rotate(180deg)' } : undefined}>
      <path d="M9 3v12M4 10l5 5 5-5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function TrashGlyph() {
  return (
    <svg viewBox="0 0 18 18" width="15" height="15" aria-hidden="true" focusable="false">
      <path
        d="M3 4.5h12M7 4.5V3h4v1.5M4.5 4.5l.8 10.2a1 1 0 0 0 1 .8h5.4a1 1 0 0 0 1-.8l.8-10.2M7.5 7.5v5M10.5 7.5v5"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
