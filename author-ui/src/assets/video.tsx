import { ActionButton, Text } from '@adobe/react-spectrum';
import Download from '@spectrum-icons/workflow/Download';
import ImageIcon from '@spectrum-icons/workflow/Image';
import { useEffect, useRef, useState } from 'react';
import type { AssetDetails, DamClient } from './api';
import { formatDuration, kindOf } from './format';
import { KindIcon } from './views';
import { AnnotatedStage, SEEK_EVENT, type CommentsState } from './annotations';

/** Frames are stored at most this wide / high (the web rendition is made from them). */
const MAX_FRAME = 1920;

/** The picture a video element shows now, as a JPEG. Same-origin videos only (a canvas reads it). */
export function captureFrame(video: HTMLVideoElement): Promise<Blob> {
  const w = video.videoWidth;
  const h = video.videoHeight;
  if (!w || !h) return Promise.reject(new Error('The video has no picture yet'));
  const scale = Math.min(1, MAX_FRAME / Math.max(w, h));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(w * scale);
  canvas.height = Math.round(h * scale);
  const ctx = canvas.getContext('2d');
  if (!ctx) return Promise.reject(new Error('Cannot draw the frame'));
  ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
  return new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Cannot encode the frame'))), 'image/jpeg', 0.9),
  );
}

export function isVideoFile(file: File): boolean {
  return file.type.startsWith('video/') || /\.(mp4|m4v|mov|webm)$/i.test(file.name);
}

/**
 * A frame of a local video file, for its thumbnail: one second in (or a tenth of a short clip),
 * where the first frame is often black. Null when this browser cannot decode the file.
 */
export function frameOfFile(file: File, timeoutMs = 15000): Promise<Blob | null> {
  if (typeof document === 'undefined' || typeof URL.createObjectURL !== 'function') return Promise.resolve(null);
  const video = document.createElement('video');
  // QuickTime files are usually H.264 / HEVC, which browsers play as MP4.
  const probe = file.type && file.type !== 'video/quicktime' ? file.type : 'video/mp4';
  if (!video.canPlayType(probe)) return Promise.resolve(null);
  const url = URL.createObjectURL(file);
  return new Promise<Blob | null>((resolve) => {
    let done = false;
    const finish = (b: Blob | null) => {
      if (done) return;
      done = true;
      window.clearTimeout(timer);
      video.removeAttribute('src');
      video.load();
      URL.revokeObjectURL(url);
      resolve(b);
    };
    const timer = window.setTimeout(() => finish(null), timeoutMs);
    video.muted = true;
    video.preload = 'auto';
    video.playsInline = true;
    video.onerror = () => finish(null);
    video.onloadedmetadata = () => {
      const d = video.duration;
      video.currentTime = Number.isFinite(d) && d > 0 ? Math.min(1, d / 10) : 0;
    };
    video.onseeked = () => {
      captureFrame(video).then(finish, () => finish(null));
    };
    video.src = url;
  });
}

const num = (v: unknown): number | null => {
  const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : NaN;
  return Number.isFinite(n) ? n : null;
};

/** 0:10 · 1920 x 1080 · 25 fps · H.264 · AAC, Stereo: what a video is, from its metadata. */
export function mediaSummary(asset: AssetDetails): string {
  const md = asset.metadata;
  const fps = num(md['xmpDM:videoFrameRate']);
  const codec = (v: unknown) => (typeof v === 'string' ? v.replace(/\s*\([^)]*\)$/, '') : '');
  const audio = [codec(md['xmpDM:audioCompressor']), typeof md['xmpDM:audioChannelType'] === 'string' ? md['xmpDM:audioChannelType'] : '']
    .filter(Boolean)
    .join(', ');
  return [
    formatDuration(asset.duration),
    asset.width && asset.height ? `${asset.width} x ${asset.height}` : '',
    fps ? `${Math.round(fps * 100) / 100} fps` : '',
    codec(md['xmpDM:videoCompressor']),
    audio,
  ]
    .filter(Boolean)
    .join(' · ');
}

/**
 * The stage of a video or audio asset: the browser's own player on the original (served in
 * ranges, so seeking does not download the whole file), and for videos "Use frame as
 * thumbnail". Formats the browser cannot play fall back to the download.
 */
export function MediaStage({
  client,
  asset,
  src,
  onPosterSet,
  onError,
  notes,
}: {
  client: DamClient;
  asset: AssetDetails;
  /** the original, or the rendition picked in the rail */
  src: string;
  onPosterSet(): void;
  onError(message: string): void;
  /** a video's notes (Annotate): drawn on the frame, each shown around its moment */
  notes?: { state: CommentsState; annotating: boolean; highlight?: string | null; onHighlight?(id: string | null): void };
}) {
  const video = kindOf(asset) === 'video';
  const ref = useRef<HTMLVideoElement>(null);
  const [time, setTime] = useState(0);
  // a note chosen in the Timeline: the video goes to its moment
  useEffect(() => {
    const seek = (e: Event) => {
      const el = ref.current;
      const t = (e as CustomEvent<number>).detail;
      if (!el || typeof t !== 'number') return;
      el.pause();
      el.currentTime = t;
      setTime(t);
    };
    window.addEventListener(SEEK_EVENT, seek);
    return () => window.removeEventListener(SEEK_EVENT, seek);
  }, []);
  useEffect(() => {
    if (notes?.annotating) ref.current?.pause();
  }, [notes?.annotating]);
  const [unplayable, setUnplayable] = useState(false);
  const [saving, setSaving] = useState(false);
  useEffect(() => setUnplayable(false), [src]);
  const summary = mediaSummary(asset);

  if (unplayable) {
    return (
      <div className="studio-assets__stage-file">
        <KindIcon item={asset} size="XL" />
        <p>This browser cannot play this {video ? 'video' : 'file'}{summary ? ` (${summary})` : ''}.</p>
        <a className="studio-assets__linkbtn" href={src} download={asset.name}>
          <Download size="S" /> Download {asset.name}
        </a>
      </div>
    );
  }

  const takeFrame = async () => {
    const el = ref.current;
    if (!el) return;
    setSaving(true);
    try {
      el.pause();
      await client.setPoster(asset.path, await captureFrame(el));
      onPosterSet();
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const player = (
    <video
      ref={ref}
      className="studio-assets__stage-video"
      src={src}
      poster={asset.web ?? undefined}
      controls
      preload="metadata"
      playsInline
      aria-label={asset.title}
      onError={() => setUnplayable(true)}
      onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)}
      onSeeked={(e) => setTime(e.currentTarget.currentTime)}
    />
  );

  return (
    <div className={`studio-assets__media${video ? '' : ' studio-assets__media--audio'}`}>
      {video ? (
        notes ? (
          <AnnotatedStage
            state={notes.state}
            annotating={notes.annotating}
            highlight={notes.highlight}
            onHighlight={notes.onHighlight}
            time={time}
            controlsHeight={48}
            onDrawStart={() => ref.current?.pause()}
          >
            {player}
          </AnnotatedStage>
        ) : (
          player
        )
      ) : (
        <>
          <KindIcon item={asset} size="XL" />
          <audio className="studio-assets__stage-audio" src={src} controls preload="metadata" aria-label={asset.title} onError={() => setUnplayable(true)} />
        </>
      )}
      <div className="studio-assets__media-bar">
        {summary && <span className="studio-assets__media-info">{summary}</span>}
        {video && (
          <ActionButton isQuiet isDisabled={saving} onPress={() => void takeFrame()}>
            <ImageIcon />
            <Text>{saving ? 'Saving…' : 'Use frame as thumbnail'}</Text>
          </ActionButton>
        )}
      </div>
    </div>
  );
}
