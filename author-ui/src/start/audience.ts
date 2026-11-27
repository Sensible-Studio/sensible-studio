import { useEffect, useState } from 'react';
import { TOOLS, type ToolCategory } from './navigation';

/**
 * Which System tools the signed-in user is offered. The tools that only an administrator can use (they answer
 * "for administrators", or open with everything refused) are not put in front of an author: Home, the System
 * view and the command palette list what the user can work with. The server says who is an administrator
 * (`admin` in `/libs/granite/security/currentuser.json`); the address of a tool still opens for anyone, and the
 * tool itself still judges.
 */
const ADMIN_ONLY: RegExp[] = [
  /^\/replication\.html/,
  /^\/system\/mail\.html/,
  /^\/security\//,
  /^\/studio\/activity\.html/,
  /^\/studio\/health\.html/,
  /^\/mnt\/overlay\/granite\/async\/content\/asyncjobs\.html/,
  /^\/bin\/browser\.html/,
  /^\/bin\/packages\.html/,
  /^\/system\/console\//,
  /^\/starter\.html/,
];

export const isAdminOnly = (url: string) => ADMIN_ONLY.some((p) => p.test(url));

/** The categories as they are for an administrator, or without the administrators' tools (and without a category left empty). */
export function toolsFor(admin: boolean): ToolCategory[] {
  if (admin) return TOOLS;
  return TOOLS.map((c) => ({ ...c, tools: c.tools.filter((t) => !isAdminOnly(t.url)) })).filter((c) => c.tools.length > 0);
}

const KEY = 'studio.admin';
let known: boolean | null = null;
let asking: Promise<boolean> | null = null;

function remembered(): boolean | null {
  if (known !== null) return known;
  try {
    const v = window.sessionStorage.getItem(KEY);
    if (v === 'true' || v === 'false') known = v === 'true';
  } catch {
    /* asked again */
  }
  return known;
}

function ask(): Promise<boolean> {
  asking ??= fetch('/libs/granite/security/currentuser.json', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
    .then((r) => (r.ok ? (r.json() as Promise<{ admin?: boolean }>) : null))
    // a server that does not say (an older one) is taken as before: everything offered
    .then((u) => u?.admin !== false, () => true)
    .then((admin) => {
      known = admin;
      try {
        window.sessionStorage.setItem(KEY, String(admin));
      } catch {
        /* per-tab convenience only */
      }
      return admin;
    });
  return asking;
}

/** What is known now, without waiting (the command palette builds its list at once); everything until the answer is in. */
export function toolsNow(): ToolCategory[] {
  void ask();
  return toolsFor(remembered() ?? true);
}

/** The tools for the signed-in user; everything until the server has answered, so an administrator sees no flicker. */
export function useTools(): ToolCategory[] {
  const [admin, setAdmin] = useState<boolean>(() => remembered() ?? true);
  useEffect(() => {
    let live = true;
    void ask().then((a) => live && setAdmin(a));
    return () => {
      live = false;
    };
  }, []);
  return toolsFor(admin);
}
