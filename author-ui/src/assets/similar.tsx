import { Button, ProgressCircle } from "@adobe/react-spectrum";
import { useCallback, useEffect, useState } from "react";
import type { DamClient, SimilarResult } from "./api";
import type { AssetsRoute } from "./route";
import { Thumb } from "./views";

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * The "Similar" rail of a file: the pictures that look most like it, best first. They are compared
 * by what the auto-tags model sees in them, so a surfer finds surfers, whatever the file is called.
 */
export function SimilarRail({ client, path, canEdit, navigate }: { client: DamClient; path: string; canEdit: boolean; navigate(route: AssetsRoute): void }) {
  const [result, setResult] = useState<SimilarResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [queued, setQueued] = useState<number | null>(null);

  const load = useCallback(() => {
    let live = true;
    setError(null);
    client.similar(path, 24).then(
      (r) => live && setResult(r),
      (e) => live && setError(message(e)),
    );
    return () => {
      live = false;
    };
  }, [client, path]);
  useEffect(() => {
    setResult(null);
    setQueued(null);
    return load();
  }, [load]);

  if (error) return <p className="studio-assets__muted">{error}</p>;
  if (!result) return <ProgressCircle size="S" isIndeterminate aria-label="Looking for similar pictures" />;
  return (
    <>
      {result.noImage ? (
        <p className="studio-assets__muted">This file has no picture to compare.</p>
      ) : result.items.length === 0 ? (
        <p className="studio-assets__muted">{result.notIndexed > 0 ? "No other picture has been looked at yet." : "No other pictures to compare with."}</p>
      ) : (
        <ul className="studio-assets__similar" aria-label="Similar pictures">
          {result.items.map((item) => (
            <li key={item.path}>
              <button type="button" className="studio-assets__similar-item" title={item.path} onClick={() => navigate({ kind: "details", path: item.path, properties: false })}>
                <Thumb item={item} src={item.thumbnail} className="studio-assets__similar-img" />
                <span className="studio-assets__similar-title">{item.title || item.name}</span>
                <span className="studio-assets__similar-score">{Math.round(item.score * 100)}%</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {result.notIndexed > 0 && (
        <div className="studio-assets__similar-more">
          <p className="studio-assets__muted">
            {queued !== null
              ? `${queued} ${queued === 1 ? "picture is" : "pictures are"} being looked at; this takes about a second each.`
              : `${result.notIndexed} ${result.notIndexed === 1 ? "picture has" : "pictures have"} not been looked at yet and cannot be compared.`}
          </p>
          {queued === null && canEdit && (
            <Button
              variant="secondary"
              onPress={() =>
                client.similarIndex().then(
                  (n) => setQueued(n),
                  (e) => setError(message(e)),
                )
              }
            >
              Look at them now
            </Button>
          )}
          {queued !== null && (
            <Button variant="secondary" onPress={() => load()}>
              Refresh
            </Button>
          )}
        </div>
      )}
    </>
  );
}
