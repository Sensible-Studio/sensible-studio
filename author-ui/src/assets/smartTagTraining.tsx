import "./smartTagTraining.scss";
import { ActionButton, Button, DialogContainer, Heading, Text } from "@adobe/react-spectrum";
import Delete from "@spectrum-icons/workflow/Delete";
import { useCallback, useEffect, useState } from "react";
import { StudioConfirmDialog } from "../shell/StudioDialog";
import type { DamClient, LearnedTag, SmartTagTraining, TrainingResult } from "./api";
import { AssetTagsField } from "./tagsField";

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));
const pct = (n: number) => `${Math.round(n * 100)}%`;

/** What a learned tag's numbers say, in words. */
export function learnedNote(m: Pick<LearnedTag, "examples" | "recall" | "others">, good = 10): string {
  const parts: string[] = [];
  if (m.examples < good) parts.push(`learned from few pictures: ${good} or more teach it better`);
  if (m.others > 0) parts.push(`it would also go to ${m.others} picture${m.others === 1 ? "" : "s"} without it, which may look alike`);
  return parts.join("; ");
}

/**
 * Smart Tag training (AEM's Smart Tags training): Auto tags learn tags of your taxonomy from the pictures you tagged
 * with them, and give them to new pictures that look like those. Shown on the Auto tags page.
 */
export function SmartTagTrainingSection({
  client,
  admin,
  onNotice,
  onLearned,
}: {
  client: DamClient;
  admin: boolean;
  onNotice(text: string): void;
  /** how many tags are learned, once known and after each change */
  onLearned?(count: number): void;
}) {
  const [state, setState] = useState<SmartTagTraining | null>(null);
  const [tags, setTags] = useState<string[]>([]);
  const [ready, setReady] = useState<SmartTagTraining["ready"]>([]);
  const [results, setResults] = useState<TrainingResult[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forgetting, setForgetting] = useState<LearnedTag | null>(null);

  const load = useCallback(async () => {
    try {
      const s = await client.smartTagTraining();
      setState(s);
      onLearned?.(s.models.length);
    } catch (e) {
      setError(message(e));
    }
  }, [client, onLearned]);
  useEffect(() => {
    void load();
  }, [load]);

  // how many pictures could teach each tag chosen
  useEffect(() => {
    if (tags.length === 0) {
      setReady([]);
      return undefined;
    }
    let live = true;
    client.smartTagTraining(tags).then(
      (s) => live && setReady(s.ready ?? []),
      (e: unknown) => live && setError(message(e)),
    );
    return () => {
      live = false;
    };
  }, [client, tags]);

  const min = state?.minExamples ?? 5;
  const good = state?.goodExamples ?? 10;
  const train = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await client.trainSmartTags(tags);
      setResults(r);
      const ok = r.filter((x) => !x.error).length;
      onNotice(ok === 0 ? "No tag could be learned" : `${ok} tag${ok === 1 ? "" : "s"} learned: new pictures get ${ok === 1 ? "it" : "them"} as Auto tags`);
      if (ok > 0) setTags(r.filter((x) => x.error).map((x) => x.tag));
      await load();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="studio-assets__smarttags-section" aria-labelledby="studio-st-training">
      <Heading level={2} id="studio-st-training">
        Learn your tags
      </Heading>
      <p className="studio-assets__muted">
        Auto tags can learn tags of your own from the pictures you tagged with them (a tag below one counts too): a new
        picture that looks like those gets the tag, beside what the model reads from the tags&apos; names. A tag needs{" "}
        {min} pictures that were looked at; {good} or more teach it better. Tag pictures first, then learn.
      </p>
      {admin && (
        <>
          <AssetTagsField label="Tags to learn" values={tags} onChange={setTags} />
          {ready && ready.length > 0 && (
            <ul className="studio-assets__training-ready">
              {ready.map((r) => (
                <li key={r.tag} className={r.examples < min ? "is-short" : undefined}>
                  <strong>{r.title}</strong>
                  {`: ${r.examples} picture${r.examples === 1 ? "" : "s"}`}
                  {r.tagged > r.examples && ` (${r.tagged - r.examples} more not looked at yet: use Tag now)`}
                  {r.examples < min && ` — ${min - r.examples} more needed`}
                </li>
              ))}
            </ul>
          )}
          <div className="studio-assets__smarttags-run">
            <Button
              variant="secondary"
              isPending={busy}
              isDisabled={tags.length === 0 || (ready ?? []).every((r) => r.examples < min)}
              onPress={() => void train()}
            >
              Learn
            </Button>
          </div>
          {results.some((r) => r.error) && (
            <ul className="studio-assets__dialog-error" role="alert">
              {results
                .filter((r) => r.error)
                .map((r) => (
                  <li key={r.tag}>{`${r.title}: ${r.error}`}</li>
                ))}
            </ul>
          )}
        </>
      )}
      {error && (
        <p className="studio-assets__dialog-error" role="alert">
          {error}
        </p>
      )}
      {state && state.models.length > 0 && (
        <table className="studio-assets__profiles-table studio-assets__training-table">
          <thead>
            <tr>
              <th>Learned tag</th>
              <th>Pictures</th>
              <th>Found again</th>
              <th>Would also tag</th>
              <th>Learned</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {state.models.map((m) => {
              const note = learnedNote(m, good);
              return (
                <tr key={m.tag}>
                  <td>
                    <strong>{m.title}</strong>
                    <div className="studio-assets__muted">{m.tag}</div>
                    {note && <div className="studio-assets__training-note">{note}</div>}
                  </td>
                  <td>{m.examples}</td>
                  <td title="Of the pictures it was learned from, how many it finds again, each compared with the others">{pct(m.recall)}</td>
                  <td title="Pictures without the tag it would be given to">{m.others}</td>
                  <td className="studio-assets__muted">
                    {[m.trained ? new Date(m.trained).toLocaleDateString() : "", m.trainedBy].filter(Boolean).join(" · ")}
                  </td>
                  <td>
                    {admin && (
                      <ActionButton isQuiet onPress={() => setForgetting(m)} aria-label={`Forget ${m.title}`}>
                        <Delete />
                        <Text>Forget</Text>
                      </ActionButton>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <DialogContainer onDismiss={() => setForgetting(null)}>
        {forgetting && (
          <StudioConfirmDialog
            title="Forget tag"
            variant="destructive"
            primaryActionLabel="Forget"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              client.forgetSmartTag(forgetting.tag).then(
                () => load(),
                (e: unknown) => setError(message(e)),
              )
            }
          >
            {`New pictures no longer get ${forgetting.title} from what was learned. The pictures that have it keep it; learn it again any time.`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </section>
  );
}
