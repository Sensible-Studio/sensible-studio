import { useEffect, useMemo, useState } from 'react';
import type { InheritanceModel } from '../granite';
import type { MsmClient } from './MsmClient';
import { pageInheritance } from './pageInheritance';
import type { MsmStatus } from './types';

/**
 * The inheritance model for the page properties of `contentPath` (a page's `jcr:content`): `undefined` while it loads
 * (the dialog must not save yet: it would post inherited fields), `null` for pages outside live copies, or when the
 * status cannot be read. Breaking or closing a chain goes to Sensible Studio at once, like AEM.
 */
export function usePageInheritance(msm: MsmClient | undefined, contentPath: string | null): InheritanceModel | null | undefined {
  const [status, setStatus] = useState<MsmStatus | null | undefined>(undefined);

  useEffect(() => {
    setStatus(undefined);
    if (!msm || !contentPath) return;
    let cancelled = false;
    msm.status(contentPath).then(
      (s) => !cancelled && setStatus(s),
      (e: unknown) => {
        if (cancelled) return;
        console.warn('Sync status unavailable, page properties open without inheritance', e);
        setStatus(null);
      },
    );
    return () => {
      cancelled = true;
    };
  }, [msm, contentPath]);

  return useMemo(
    () =>
      !msm || !contentPath
        ? null
        : status === undefined
          ? undefined
          : pageInheritance(status, async (name, cancel) => {
            const next = await msm.propertyInheritance(contentPath, [name], cancel);
              setStatus(next ?? (await msm.status(contentPath)));
            }),
    [msm, contentPath, status],
  );
}
