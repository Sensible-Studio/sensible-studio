import { describe, expect, it } from 'vitest';
import { isVideoFormat, profileProblems } from './profiles';

describe('video renditions in a Processing Profile', () => {
  it('count only the height, from 144 to 2160 lines', () => {
    expect(isVideoFormat('mp4')).toBe(true);
    expect(isVideoFormat('webm')).toBe(true);
    expect(isVideoFormat('webp')).toBe(false);
    expect(profileProblems('video', [{ name: 'web', width: 0, height: 720, format: 'mp4', quality: 0 }])).toEqual([]);
    expect(profileProblems('video', [{ name: 'tiny', width: 0, height: 100, format: 'webm', quality: 0 }])).toEqual([
      "tiny: a video's height must be 144 to 2160.",
    ]);
    // the same name may be a picture and a video: the files differ
    expect(
      profileProblems('video', [
        { name: 'web', width: 1280, height: 720, format: 'jpeg', quality: 80 },
        { name: 'web', width: 0, height: 720, format: 'mp4', quality: 0 },
      ]),
    ).toEqual([]);
  });
});
