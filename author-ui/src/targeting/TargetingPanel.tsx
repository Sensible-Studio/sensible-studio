import { ActionButton, Button, ButtonGroup, Content, DialogContainer, Divider, Form, Heading, Item, NumberField, Picker, Radio, RadioGroup, Text, TextField, ToastQueue, Tooltip, TooltipTrigger, useDialogContainer } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import { useCallback, useEffect, useState } from 'react';
import { countedOn, describe, evenShares, GOAL_TYPES, segmentName, sharesProblem, totals, type TargetMode, type Experience, type Goal, type GoalType, type Redirect, type Segment, type TargetInfo, type TargetingClient } from './api';
import { RedirectsSection } from './RedirectsSection';
import { SegmentDialog } from './SegmentDialog';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  client: TargetingClient;
  pagePath: string;
  /** The targeted component the selection is (or is inside); null when nothing targeted is selected. */
  target: TargetInfo | null;
  /** Which of its experiences the page shows. */
  experience: string;
  onExperience(name: string): void;
  /** The target changed (experiences added or removed, targeting removed): the page re-renders. */
  onChanged(info: TargetInfo): void;
}

/**
 * The side panel of Targeting mode, AEM's Audiences and Experiences: the audiences of the site (create,
 * edit, remove) and, for the selected targeted component, its experiences, one per audience, the one
 * being shown, Add Experience, Remove.
 */
export function TargetingPanel({ client, pagePath, target, experience, onExperience, onChanged }: Props) {
  const [segments, setSegments] = useState<Segment[]>([]);
  const [conf, setConf] = useState('/conf/global');
  const [editing, setEditing] = useState<Segment | 'new' | null>(null);
  const [adding, setAdding] = useState(false);
  const [editingExp, setEditingExp] = useState<Experience | null>(null);

  const load = useCallback(() => {
    client.segments(pagePath).then(
      (r) => {
        setSegments(r.segments);
        setConf(r.conf);
      },
      (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }),
    );
  }, [client, pagePath]);
  useEffect(load, [load]);

  const segmentOf = (path?: string) => segments.find((s) => s.path === path);
  const act = async (work: () => Promise<TargetInfo>, done: string) => {
    try {
      const info = await work();
      onChanged(info);
      ToastQueue.positive(done, { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  return (
    <div className="studio-targeting">
      <section className="studio-targeting__section" aria-label="Experiences">
        <h3>Experiences</h3>
        {!target ? (
          <p className="studio-targeting__hint">Select a component and use its Personalize action; a personalized component gets one experience per audience.</p>
        ) : (
          <>
            <ul className="studio-targeting__experiences" role="listbox" aria-label="Experiences">
              {target.experiences.map((e) => {
                const seg = segmentOf(e.segment);
                const st = target.stats?.[e.name];
                return (
                  <li key={e.name} className={`studio-targeting__experience${e.name === experience ? ' is-current' : ''}`} role="option" aria-selected={e.name === experience} onClick={() => onExperience(e.name)}>
                    <div className="studio-targeting__exp-main">
                      <span className="studio-targeting__exp-title">{e.title}</span>
                      <span className="studio-targeting__exp-seg">
                        {target.mode === 'abtest'
                          ? `${e.share ?? 0}% of the visitors`
                          : e.name === 'default'
                            ? 'Everyone else'
                            : seg
                              ? seg.title
                              : e.segment
                                ? e.segment.split('/').pop()
                                : 'No audience'}
                      </span>
                      {st && st.impressions > 0 && (
                        <span className="studio-targeting__exp-stats" title={`${st.impressions} impressions, ${st.conversions} conversions`}>
                          {st.impressions} shown · {st.conversions} converted · {st.rate}%
                        </span>
                      )}
                    </div>
                    {e.name !== 'default' && (
                      <span className="studio-targeting__exp-actions" onClick={(ev) => ev.stopPropagation()}>
                        <ActionButton isQuiet aria-label="Edit experience" onPress={() => setEditingExp(e)}>
                          <Edit />
                        </ActionButton>
                        <ActionButton
                          isQuiet
                          aria-label="Remove experience"
                          onPress={() => {
                            if (experience === e.name) onExperience('default');
                            void act(() => client.removeExperience(target.path, e.name), `Experience ${e.title} removed`);
                          }}
                        >
                          <Delete />
                        </ActionButton>
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
            <div className="studio-targeting__buttons">
              <Button variant="secondary" onPress={() => setAdding(true)} isDisabled={segments.length === 0 && target.mode !== 'abtest'}>
                <Add />
                <Text>Add Experience</Text>
              </Button>
              <Button variant="negative" style="outline" onPress={() => void act(() => client.untarget(target.path, experience), 'Personalize removed')}>
                Remove personalization
              </Button>
            </div>
            <p className="studio-targeting__hint">The page shows the highlighted experience; edit it like any component. Removing personalization keeps the experience shown.</p>
          </>
        )}
      </section>
      {target && <SplitSection client={client} target={target} onChanged={onChanged} />}
      {target && <GoalSection client={client} target={target} onChanged={onChanged} />}
      <RedirectsSection client={client} pagePath={pagePath} segments={segments} />
      <section className="studio-targeting__section" aria-label="Audiences">
        <div className="studio-targeting__head">
          <h3>Audiences</h3>
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Create audience" onPress={() => setEditing('new')}>
              <Add />
            </ActionButton>
            <Tooltip>Create audience</Tooltip>
          </TooltipTrigger>
        </div>
        {segments.length === 0 && <p className="studio-targeting__hint">No audiences under {conf} yet.</p>}
        <ul className="studio-targeting__segments">
          {segments.map((s) => (
            <li key={s.path} className="studio-targeting__segment">
              <div className="studio-targeting__exp-main">
                <span className="studio-targeting__exp-title">{s.title}</span>
                <span className="studio-targeting__exp-seg" title={describe(s.expression)}>
                  {describe(s.expression)}
                </span>
              </div>
              <span className="studio-targeting__exp-actions">
                <ActionButton isQuiet aria-label="Edit audience" onPress={() => setEditing(s)}>
                  <Edit />
                </ActionButton>
                <ActionButton
                  isQuiet
                  aria-label="Remove audience"
                  onPress={() =>
                    client.removeSegment(s.path).then(
                      () => {
                        load();
                        ToastQueue.positive(`Audience ${s.title} removed`, { timeout: 3000 });
                      },
                      (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }),
                    )
                  }
                >
                  <Delete />
                </ActionButton>
              </span>
            </li>
          ))}
        </ul>
      </section>
      <DialogContainer onDismiss={() => setEditing(null)}>
        {editing && (
          <SegmentDialog
            conf={conf}
            segment={editing === 'new' ? null : editing}
            onSave={async (s) => {
              await client.saveSegment(s);
              load();
              ToastQueue.positive(`Audience ${s.title} saved`, { timeout: 3000 });
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setAdding(false)}>
        {adding && target && (
          <ExperienceDialog
            segments={segments}
            experiences={target.experiences}
            ab={target.mode === 'abtest'}
            onSave={async (e) => {
              const info = await client.addExperience(target.path, { name: e.name, title: e.title, segment: e.segment, from: experience });
              onChanged(info);
              onExperience(e.name);
              ToastQueue.positive(`Experience ${e.title} added`, { timeout: 3000 });
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setEditingExp(null)}>
        {editingExp && target && (
          <ExperienceDialog
            segments={segments}
            experiences={target.experiences}
            ab={target.mode === 'abtest'}
            experience={editingExp}
            onSave={async (e) => {
              onChanged(await client.updateExperience(target.path, { name: e.name, title: e.title, segment: e.segment }));
              ToastQueue.positive(`Experience ${e.title} saved`, { timeout: 3000 });
            }}
          />
        )}
      </DialogContainer>
    </div>
  );
}

function ExperienceDialog({ segments, experiences, experience, ab = false, onSave }: { segments: Segment[]; experiences: Experience[]; experience?: Experience; /** an A/B test: an experience needs no audience */ ab?: boolean; onSave(e: { name: string; title: string; segment: string }): Promise<void> }) {
  const dialog = useDialogContainer();
  const used = new Set(experiences.filter((e) => e.name !== experience?.name).map((e) => e.segment));
  const free = segments.filter((s) => !used.has(s.path));
  const [segment, setSegment] = useState(experience?.segment ?? (ab ? '' : free[0]?.path) ?? '');
  const [title, setTitle] = useState(experience?.title ?? (ab ? `Variant ${String.fromCharCode(65 + Math.min(25, experiences.length))}` : free[0]?.title) ?? '');
  const [touched, setTouched] = useState(!!experience);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const name = experience?.name ?? uniqueName(segmentName(title) || 'experience', experiences);
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await onSave({ name, title: title.trim() || name, segment });
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="S">
      <Heading>{experience ? `Experience: ${experience.title}` : 'Add Experience'}</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          {ab ? (
            <p className="studio-targeting__hint">An A/B test splits the visitors by the shares, whatever their audience: this experience needs none.</p>
          ) : (
            <Picker
              label="Audience"
              selectedKey={segment}
              onSelectionChange={(k) => {
                setSegment(String(k));
                if (!touched) setTitle(segments.find((s) => s.path === k)?.title ?? '');
              }}
              isRequired
              description={free.length === 0 && !experience ? 'Every audience has an experience already' : undefined}
            >
              {(experience ? segments : free).map((s) => (
                <Item key={s.path}>{s.title}</Item>
              ))}
            </Picker>
          )}
          <TextField
            label="Title"
            value={title}
            onChange={(t) => {
              setTitle(t);
              setTouched(true);
            }}
            description={experience ? undefined : `Starts as a copy of the experience shown now · ${name}`}
          />
        </Form>
        {error && (
          <p className="studio-publish__error" role="alert">
            {error}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isPending={busy} isDisabled={!segment && !ab}>
          {experience ? 'Save' : 'Add'}
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

function uniqueName(base: string, experiences: Experience[]): string {
  const taken = new Set(experiences.map((e) => e.name));
  if (!taken.has(base) && base !== 'default') return base;
  for (let i = 1; ; i++) if (!taken.has(`${base}-${i}`)) return `${base}-${i}`;
}

/**
 * Who sees which experience: by audience (targeting), or an A/B test that splits the visitors among the
 * experiences by percent. A visitor keeps the experience they were dealt; the report below compares the rates.
 */
function SplitSection({ client, target, onChanged }: { client: TargetingClient; target: TargetInfo; onChanged(info: TargetInfo): void }) {
  const savedMode: TargetMode = target.mode ?? 'targeting';
  const names = target.experiences.map((e) => e.name);
  const savedShares = (): Record<string, number> => {
    const s = Object.fromEntries(target.experiences.map((e) => [e.name, e.share ?? 0]));
    // never tested before: start from an even split
    return Object.values(s).every((v) => v === 0) ? evenShares(names) : s;
  };
  const [mode, setMode] = useState<TargetMode>(savedMode);
  const [shares, setShares] = useState<Record<string, number>>(savedShares);
  const [busy, setBusy] = useState(false);
  const key = `${target.path}|${savedMode}|${target.experiences.map((e) => `${e.name}:${e.share ?? 0}`).join(',')}`;
  useEffect(() => {
    setMode(savedMode);
    setShares(savedShares());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  const problem = mode === 'abtest' ? (names.length < 2 ? 'Add a second experience to test against the default' : sharesProblem(shares)) : null;
  const dirty = mode !== savedMode || (mode === 'abtest' && target.experiences.some((e) => (e.share ?? 0) !== (shares[e.name] ?? 0)));
  const save = async () => {
    setBusy(true);
    try {
      onChanged(await client.setSplit(target.path, mode, mode === 'abtest' ? shares : {}));
      ToastQueue.positive(mode === 'abtest' ? 'A/B test saved' : 'Shown by audience', { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="studio-targeting__section" aria-label="Who sees what">
      <h3>Who sees what</h3>
      <RadioGroup aria-label="Who sees what" value={mode} onChange={(v) => setMode(v as TargetMode)} isDisabled={busy}>
        <Radio value="targeting">By audience: each visitor sees the experience of their audience</Radio>
        <Radio value="abtest">A/B test: the visitors are split among the experiences</Radio>
      </RadioGroup>
      {mode === 'abtest' && (
        <div className="studio-targeting__split">
          {target.experiences.map((e) => (
            <NumberField
              key={e.name}
              label={e.title}
              value={shares[e.name] ?? 0}
              minValue={0}
              maxValue={100}
              step={1}
              formatOptions={{ style: 'unit', unit: 'percent' }}
              onChange={(v) => setShares((s) => ({ ...s, [e.name]: Number.isFinite(v) ? Math.round(v) : 0 }))}
              isDisabled={busy}
              width="100%"
            />
          ))}
          {problem && <p className="studio-targeting__hint studio-targeting__hint--problem">{problem}</p>}
          <p className="studio-targeting__hint">
            A visitor keeps the experience they were dealt, on every visit from the same browser. Set a goal below, let it run on the live site, and
            keep the best one with Remove personalization.
          </p>
        </div>
      )}
      <div className="studio-targeting__buttons">
        {mode === 'abtest' && (
          <Button variant="secondary" isDisabled={busy || names.length < 2} onPress={() => setShares(evenShares(names))}>
            Split evenly
          </Button>
        )}
        <Button variant="accent" isDisabled={!dirty || busy || !!problem} onPress={() => void save()}>
          Save
        </Button>
      </div>
    </section>
  );
}

/** AEM's "Goal & Settings" step: the activity's name, what counts as a conversion, and the report so far. */
function GoalSection({ client, target, onChanged }: { client: TargetingClient; target: TargetInfo; onChanged(info: TargetInfo): void }) {
  const saved: Goal = target.goal ?? { type: 'none', value: '', activity: '' };
  const [type, setType] = useState<GoalType>(saved.type);
  const [value, setValue] = useState(saved.value ?? '');
  const [activity, setActivity] = useState(saved.activity ?? '');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setType(saved.type);
    setValue(saved.value ?? '');
    setActivity(saved.activity ?? '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target.path, saved.type, saved.value, saved.activity]);
  const dirty = type !== saved.type || value !== (saved.value ?? '') || activity !== (saved.activity ?? '');
  const sum = totals(target.stats);
  const counted = countedOn(target.statsSources);
  const run = async (work: () => Promise<void>, done: string) => {
    setBusy(true);
    try {
      await work();
      ToastQueue.positive(done, { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    } finally {
      setBusy(false);
    }
  };
  const info = GOAL_TYPES.find((g) => g.id === type);
  return (
    <section className="studio-targeting__section" aria-label="Goal & Settings">
      <h3>Goal &amp; Settings</h3>
      <div className="studio-targeting__form">
        <TextField label="Activity name" value={activity} onChange={setActivity} placeholder={target.path.split('/').pop()} description="How the report names this personalized component." width="100%" />
        <Picker label="Goal" selectedKey={type} onSelectionChange={(k) => setType(k as GoalType)} description={info?.hint} width="100%">
          {GOAL_TYPES.map((g) => (
            <Item key={g.id}>{g.label}</Item>
          ))}
        </Picker>
        {type === 'click' && <TextField label="Element (CSS selector, optional)" value={value} onChange={setValue} placeholder="a.cmp-button" width="100%" />}
        {type === 'pageview' && <TextField label="Page" value={value} onChange={setValue} placeholder="/content/my-site/language-masters/en/adventures" isRequired width="100%" />}
        {type === 'custom' && <TextField label="Event name" value={value} onChange={setValue} placeholder="newsletter-signup" isRequired width="100%" />}
      </div>
      <div className="studio-targeting__buttons">
        <Button
          variant="accent"
          isDisabled={!dirty || busy || (type !== 'none' && type !== 'click' && !value.trim())}
          onPress={() => void run(async () => onChanged(await client.setGoal(target.path, { type, value: type === 'none' ? '' : value.trim(), activity: activity.trim() })), 'Goal saved')}
        >
          Save goal
        </Button>
      </div>
      <div className="studio-targeting__report" aria-label="Activity report">
        <h4>Report</h4>
        {counted && sum.impressions > 0 && <p className={`studio-targeting__hint${counted.warn ? ' studio-targeting__hint--problem' : ''}`}>{counted.text}</p>}
        {counted?.warn && sum.impressions === 0 && <p className="studio-targeting__hint studio-targeting__hint--problem">{counted.text}</p>}
        {sum.impressions === 0 ? (
          <p className="studio-targeting__hint">Nothing counted yet. Impressions and conversions are counted on the live site (not in the editor or preview).</p>
        ) : (
          <>
            <table className="studio-targeting__table studio-table">
              <thead>
                <tr>
                  <th>Experience</th>
                  <th>Shown</th>
                  <th>Converted</th>
                  <th>Rate</th>
                </tr>
              </thead>
              <tbody>
                {target.experiences.map((e) => {
                  const st = target.stats?.[e.name];
                  return (
                    <tr key={e.name} className={st && st.rate === Math.max(...Object.values(target.stats ?? {}).map((x) => x.rate)) && st.conversions > 0 ? 'is-best' : undefined}>
                      <td>{e.title}</td>
                      <td>{st?.impressions ?? 0}</td>
                      <td>{st?.conversions ?? 0}</td>
                      <td>{st ? `${st.rate}%` : '–'}</td>
                    </tr>
                  );
                })}
                <tr className="is-total">
                  <td>All</td>
                  <td>{sum.impressions}</td>
                  <td>{sum.conversions}</td>
                  <td>{sum.rate}%</td>
                </tr>
              </tbody>
            </table>
            <div className="studio-targeting__buttons">
              <Button
                variant="secondary"
                isDisabled={busy}
                onPress={() =>
                  void run(async () => {
                    const missed = (await client.resetStats(target.path)) ?? [];
                    if (missed.length > 0) ToastQueue.negative(`Not reset on ${missed.join(', ')}: the instance did not answer`, { timeout: 8000 });
                    onChanged(await client.target(target.path));
                  }, 'Report reset')
                }
              >
                Reset report
              </Button>
            </div>
          </>
        )}
      </div>
    </section>
  );
}

export type { Redirect };
