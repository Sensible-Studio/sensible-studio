import {
  Button,
  ButtonGroup,
  Content,
  Divider,
  Form,
  Heading,
  InlineAlert,
  Item,
  Picker,
  ProgressCircle,
  Text,
  TextField,
} from "@adobe/react-spectrum";
import ChevronRight from "@spectrum-icons/workflow/ChevronRight";
import Folder from "@spectrum-icons/workflow/Folder";
import { useEffect, useState } from "react";
import {
  DAM_ROOT,
  type DamClient,
  type DamItem,
  type FolderNode,
  type ImageProfile,
  type FieldValue,
  type MetadataSchema,
  type ProcessingProfile,
} from "./api";
import { FolderAccess } from "./folderAccess";
import { FolderMembersSection } from "./folderMembers";
import { nameOf } from "./format";
import { ReferenceList, useReferrers } from "./references";
import { changedValues, fieldValue, SchemaFields } from "./schemas";
import { StudioDialog, StudioConfirmDialog, StudioPickerDialog } from '../shell/StudioDialog';

/** Folder titles seen so far (listings, picker), so the picker breadcrumbs show titles, not node names. */
export const folderTitles = new Map<string, string>();

/** Same rule as the server (DamApiServlet.nodeName), shown live under the title. */
export function nodeName(raw: string): string {
  return raw
    .trim()
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/-{2,}/g, "-")
    .replace(/^[-.]+|-+$/g, "");
}

function useBusy() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  return { busy, error, run };
}

// ------------------------------------------------------------------------------------- create folder

export function CreateFolderDialog({
  client,
  parent,
  onDone,
  close,
}: {
  client: DamClient;
  parent: string;
  onDone(path: string): void;
  close(): void;
}) {
  const [title, setTitle] = useState("");
  const [name, setName] = useState("");
  const [nameTouched, setNameTouched] = useState(false);
  const { busy, error, run } = useBusy();
  const effectiveName = nameTouched ? nodeName(name) : nodeName(title);
  const submit = () =>
    run(async () => {
      const path = await client.createFolder(
        parent,
        title.trim() || effectiveName,
        effectiveName,
      );
      onDone(path);
      close();
    });
  return (
    <StudioDialog size="S">
      <Heading>Create Folder</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            if (effectiveName) void submit();
          }}
        >
          <TextField
            label="Title"
            value={title}
            onChange={setTitle}
            autoFocus
            isRequired
          />
          <TextField
            label="Name"
            value={nameTouched ? name : effectiveName}
            onChange={(v) => {
              setNameTouched(true);
              setName(v);
            }}
            description={
              effectiveName
                ? `${parent}/${effectiveName}`
                : "Letters, digits, dot, dash and underscore"
            }
          />
        </Form>
        {error && (
          <InlineAlert variant="negative" marginTop="size-200">
            <Heading>Cannot create the folder</Heading>
            <Content>{error}</Content>
          </InlineAlert>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button
          variant="accent"
          onPress={submit}
          isDisabled={!effectiveName || busy}
        >
          Create
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

// -------------------------------------------------------------------------------------------- delete

export function DeleteDialog({
  client,
  items,
  onDone,
  close,
}: {
  client: DamClient;
  items: DamItem[];
  onDone(): void;
  close(): void;
}) {
  const { busy, error, run } = useBusy();
  const folders = items.filter((i) => i.type === "folder").length;
  const { referrers, error: refError } = useReferrers(
    client,
    items.map((i) => i.path),
  );
  const referenced = (referrers?.length ?? 0) > 0;
  return (
    <StudioConfirmDialog
      title="Delete"
      variant="destructive"
      primaryActionLabel={busy ? "Deleting…" : "Delete"}
      cancelLabel="Cancel"
      isPrimaryActionDisabled={busy}
      onPrimaryAction={() =>
        run(async () => {
          await client.remove(items.map((i) => i.path));
          onDone();
          close();
        })
      }
      onCancel={close}
    >
      {items.length === 1 ? (
        <>
          You are going to delete <strong>{items[0].title}</strong>
          {items[0].type === "folder" ? " and everything in it" : ""}.
        </>
      ) : (
        <>
          You are going to delete {items.length} items
          {folders
            ? `, including ${folders} folder${folders > 1 ? "s" : ""} with their content`
            : ""}
          .
        </>
      )}{" "}
      This cannot be undone. What is published is taken off the live site first.
      {referenced ? (
        <>
          <InlineAlert variant="notice" marginTop="size-200">
            <Heading>
              Referenced by{" "}
              {referrers!.length === 1
                ? "one page"
                : `${referrers!.length} pages`}
            </Heading>
            <Content>These references will break.</Content>
          </InlineAlert>
          <ReferenceList referrers={referrers} max={8} />
        </>
      ) : (
        referrers === null && <ReferenceList referrers={null} />
      )}
      {refError && (
        <p className="studio-assets__dialog-error">
          References could not be checked: {refError}
        </p>
      )}
      {error && <p className="studio-assets__dialog-error">{error}</p>}
    </StudioConfirmDialog>
  );
}

// -------------------------------------------------------------------------------------- folder picker

export function FolderPicker({
  client,
  value,
  onChange,
  exclude,
}: {
  client: DamClient;
  value: string;
  onChange(p: string): void;
  exclude: string[];
}) {
  const [folders, setFolders] = useState<FolderNode[] | null>(null);
  useEffect(() => {
    let live = true;
    setFolders(null);
    client.folders(value).then(
      (f) => {
        for (const x of f) folderTitles.set(x.path, x.title);
        if (live) setFolders(f);
      },
      () => live && setFolders([]),
    );
    return () => {
      live = false;
    };
  }, [client, value]);
  const crumbs: string[] = [];
  for (
    let p = value;
    p.startsWith(DAM_ROOT);
    p = p.slice(0, p.lastIndexOf("/"))
  )
    crumbs.unshift(p);
  const blocked = (p: string) =>
    exclude.some((x) => p === x || p.startsWith(`${x}/`));
  return (
    <div className="studio-assets__picker">
      <nav className="studio-assets__picker-crumbs" aria-label="Destination path">
        {crumbs.map((c, i) => (
          <span key={c}>
            {i > 0 && <ChevronRight size="XS" />}
            <button
              type="button"
              onClick={() => onChange(c)}
              aria-current={c === value ? "location" : undefined}
            >
              {c === DAM_ROOT ? "Media" : (folderTitles.get(c) ?? nameOf(c))}
            </button>
          </span>
        ))}
      </nav>
      <ul className="studio-assets__picker-list" aria-label="Folders">
        {folders === null && (
          <li>
            <ProgressCircle size="S" isIndeterminate aria-label="Loading" />
          </li>
        )}
        {folders?.length === 0 && (
          <li className="studio-assets__colempty">No sub-folders</li>
        )}
        {folders?.map((f) => (
          <li key={f.path}>
            <button
              type="button"
              disabled={blocked(f.path)}
              onClick={() => onChange(f.path)}
            >
              <Folder size="S" />
              <span>{f.title}</span>
              {f.hasChildren && <ChevronRight size="XS" />}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

// ----------------------------------------------------------------------------------------------- copy

/** Copies items into a folder picked from the tree (Records' Copy; Media copies through its clipboard). */
export function CopyDialog({
  client,
  items,
  start,
  onDone,
  close,
}: {
  client: DamClient;
  items: DamItem[];
  start: string;
  onDone(paths: string[]): void;
  close(): void;
}) {
  const [dest, setDest] = useState(start);
  const { busy, error, run } = useBusy();
  const what = items.length === 1 ? items[0].title || items[0].name : `${items.length} items`;
  return (
    <StudioPickerDialog size="M">
      <Heading>Copy {what}</Heading>
      <Divider />
      <Content>
        <FolderPicker client={client} value={dest} onChange={setDest} exclude={[]} />
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" isPending={busy} onPress={() => void run(async () => onDone(await client.copy(items.map((i) => i.path), dest)))}>
          Copy here
        </Button>
      </ButtonGroup>
    </StudioPickerDialog>
  );
}

// ----------------------------------------------------------------------------------------------- move

export function MoveDialog({
  client,
  items,
  start,
  onDone,
  close,
}: {
  client: DamClient;
  items: DamItem[];
  start: string;
  onDone(paths: string[], adjusted: number): void;
  close(): void;
}) {
  const single = items.length === 1 ? items[0] : null;
  const [dest, setDest] = useState(start);
  const [name, setName] = useState(single?.name ?? "");
  const { busy, error, run } = useBusy();
  const { referrers, error: refError } = useReferrers(
    client,
    items.map((i) => i.path),
  );
  // Like AEM's move wizard: every referencing page is adjusted unless the author unticks it.
  const [unticked, setUnticked] = useState<ReadonlySet<string>>(new Set());
  const ticked = new Set(
    (referrers ?? []).map((r) => r.key).filter((k) => !unticked.has(k)),
  );
  const folderPaths = items
    .filter((i) => i.type === "folder")
    .map((i) => i.path);
  const unchanged = single
    ? dest === start && name === single.name
    : dest === start;
  return (
    <StudioPickerDialog size="M">
      <Heading>
        {single
          ? `Move or rename "${single.title}"`
          : `Move ${items.length} items`}
      </Heading>
      <Divider />
      <Content>
        {single && (
          <TextField
            label="Name"
            value={name}
            onChange={setName}
            width="100%"
            marginBottom="size-200"
          />
        )}
        <Text>Destination</Text>
        <FolderPicker
          client={client}
          value={dest}
          onChange={setDest}
          exclude={folderPaths}
        />
        <Text UNSAFE_className="studio-assets__refs-title">Adjust references</Text>
        <ReferenceList
          referrers={referrers}
          error={refError}
          selected={ticked}
          onSelected={(next) =>
            setUnticked(
              new Set(
                (referrers ?? []).map((r) => r.key).filter((k) => !next.has(k)),
              ),
            )
          }
          empty="No page references these items."
        />
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={
            busy ||
            unchanged ||
            referrers === null ||
            (single !== null && !name.trim())
          }
          onPress={() =>
            run(async () => {
              const rename = single && name !== single.name ? name : undefined;
              const adjust = (referrers ?? [])
                .filter((r) => ticked.has(r.key))
                .flatMap((r) => r.resources);
              const result = await client.move(
                items.map((i) => i.path),
                dest,
                rename,
                adjust,
              );
              onDone(result.paths, result.adjusted);
              close();
            })
          }
        >
          Move
        </Button>
      </ButtonGroup>
    </StudioPickerDialog>
  );
}

// ---------------------------------------------------------------------------------- folder properties

export function FolderPropertiesDialog({
  client,
  folder,
  onDone,
  close,
}: {
  client: DamClient;
  folder: DamItem;
  onDone(): void;
  close(): void;
}) {
  const [title, setTitle] = useState(
    folder.title === folder.name ? "" : folder.title,
  );
  const own = folder.type === "folder" ? (folder.processingProfile ?? "") : "";
  const [profile, setProfile] = useState(own);
  const [profiles, setProfiles] = useState<ProcessingProfile[] | null>(null);
  const [inherited, setInherited] = useState<string | null>(null);
  const ownSchema =
    folder.type === "folder" ? (folder.metadataSchema ?? "") : "";
  const [schema, setSchema] = useState(ownSchema);
  const [schemas, setSchemas] = useState<MetadataSchema[] | null>(null);
  const [inheritedSchema, setInheritedSchema] = useState<string | null>(null);
  const { busy, error, run } = useBusy();
  useEffect(() => {
    let live = true;
    client
      .schemas(folder.path.slice(0, folder.path.lastIndexOf("/")) || DAM_ROOT)
      .then(
        (r) => {
          if (!live) return;
          setSchemas(r.schemas);
          setInheritedSchema(r.effective ?? null);
        },
        () => live && setSchemas([]),
      );
    return () => {
      live = false;
    };
  }, [client, folder.path]);
  // metadata profile and folder metadata schema: own, or inherited from the parent
  const parentPath =
    folder.path.slice(0, folder.path.lastIndexOf("/")) || DAM_ROOT;
  const ownMetaProfile =
    folder.type === "folder" ? (folder.metadataProfile ?? "") : "";
  const [metaProfile, setMetaProfile] = useState(ownMetaProfile);
  const [metaProfiles, setMetaProfiles] = useState<MetadataSchema[] | null>(
    null,
  );
  const [inheritedMetaProfile, setInheritedMetaProfile] = useState<
    string | null
  >(null);
  const [applyNotice, setApplyNotice] = useState<string | null>(null);
  const ownFolderSchema =
    folder.type === "folder" ? (folder.folderMetadataSchema ?? "") : "";
  const [folderSchema, setFolderSchema] = useState(ownFolderSchema);
  const [folderSchemas, setFolderSchemas] = useState<MetadataSchema[] | null>(
    null,
  );
  const [inheritedFolderSchema, setInheritedFolderSchema] = useState<
    string | null
  >(null);
  const [folderValues, setFolderValues] = useState<Record<
    string,
    unknown
  > | null>(null);
  const [folderEdits, setFolderEdits] = useState<Record<string, FieldValue>>(
    {},
  );
  useEffect(() => {
    let live = true;
    client.forms("profile", parentPath).then(
      (r) => {
        if (!live) return;
        setMetaProfiles(r.forms);
        setInheritedMetaProfile(r.effective ?? null);
      },
      () => live && setMetaProfiles([]),
    );
    client.forms("folderSchema", parentPath).then(
      (r) => {
        if (!live) return;
        setFolderSchemas(r.forms);
        setInheritedFolderSchema(r.effective ?? null);
      },
      () => live && setFolderSchemas([]),
    );
    client.folderMetadata(folder.path).then(
      (r) => live && setFolderValues(r.values),
      () => live && setFolderValues({}),
    );
    return () => {
      live = false;
    };
  }, [client, folder.path, parentPath]);
  const formTitle = (list: MetadataSchema[] | null, p: string | null) =>
    list?.find((x) => x.path === p)?.title ??
    p?.slice(p.lastIndexOf("/") + 1) ??
    "none";
  // the folder's fields: the schema chosen here, or the inherited one
  const shownFolderSchema =
    folderSchemas?.find(
      (s) => s.path === (folderSchema || inheritedFolderSchema),
    ) ?? null;
  const folderFields = shownFolderSchema?.tabs ?? [];
  const loadedFolderValues: Record<string, FieldValue> = {};
  for (const f of folderFields.flatMap((t) => t.fields))
    loadedFolderValues[f.property] = fieldValue(
      f,
      folderValues?.[f.property],
    );
  const shownFolderValues = { ...loadedFolderValues, ...folderEdits };
  const ownImage = folder.type === "folder" ? (folder.imageProfile ?? "") : "";
  const [imageProfile, setImageProfile] = useState(ownImage);
  const [imageProfiles, setImageProfiles] = useState<ImageProfile[] | null>(
    null,
  );
  const [inheritedImage, setInheritedImage] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    client
      .imageProfiles(
        folder.path.slice(0, folder.path.lastIndexOf("/")) || DAM_ROOT,
      )
      .then(
        (r) => {
          if (!live) return;
          setImageProfiles(r.profiles);
          setInheritedImage(r.effective ?? null);
        },
        () => live && setImageProfiles([]),
      );
    return () => {
      live = false;
    };
  }, [client, folder.path]);
  const imageTitleOf = (p: string | null) =>
    imageProfiles?.find((x) => x.path === p)?.title ??
    p?.slice(p.lastIndexOf("/") + 1) ??
    "none";
  const ownSmart = folder.type === "folder" ? (folder.smartTagging ?? "") : "";
  const [smart, setSmart] = useState<string>(ownSmart);
  const [smartInfo, setSmartInfo] = useState<{
    installed: boolean;
    inherited: boolean;
  } | null>(null);
  const [smartNotice, setSmartNotice] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    client
      .smartTagsStatus(
        folder.path.slice(0, folder.path.lastIndexOf("/")) || DAM_ROOT,
      )
      .then(
        (s) =>
          live &&
          setSmartInfo({
            installed: s.model.installed === true,
            inherited: s.effective ?? s.settings.enabled,
          }),
        () => live && setSmartInfo(null),
      );
    return () => {
      live = false;
    };
  }, [client, folder.path]);
  const ownThumb =
    folder.type === "folder" ? (folder.thumbnailAsset ?? "") : "";
  const [thumb, setThumb] = useState(ownThumb);
  const [images, setImages] = useState<Array<{
    path: string;
    title: string;
    thumbnail?: string | null;
  }> | null>(null);
  useEffect(() => {
    let live = true;
    client.list(folder.path, { limit: 500 }).then(
      (l) =>
        live &&
        setImages(
          l.items
            .filter((i) => i.type === "asset" && !!i.thumbnail)
            .map((i) => ({
              path: i.path,
              title: i.title,
              thumbnail: i.type === "asset" ? i.thumbnail : null,
            })),
        ),
      () => live && setImages([]),
    );
    return () => {
      live = false;
    };
  }, [client, folder.path]);
  const chosenThumb = images?.find((i) => i.path === thumb)?.thumbnail;
  const schemaTitle = (p: string | null) =>
    schemas?.find((x) => x.path === p)?.title ??
    p?.slice(p.lastIndexOf("/") + 1) ??
    "Default";
  useEffect(() => {
    let live = true;
    client
      .profiles(folder.path.slice(0, folder.path.lastIndexOf("/")) || DAM_ROOT)
      .then(
        (r) => {
          if (!live) return;
          setProfiles(r.profiles);
          setInherited(r.effective ?? null);
        },
        () => live && setProfiles([]),
      );
    return () => {
      live = false;
    };
  }, [client, folder.path]);
  const titleOf = (p: string | null) =>
    profiles?.find((x) => x.path === p)?.title ??
    p?.slice(p.lastIndexOf("/") + 1) ??
    "none";
  return (
    <StudioDialog size="M">
      <Heading>Folder Properties</Heading>
      <Divider />
      <Content>
        <Form UNSAFE_className="studio-assets__folder-form">
          <section
            className="studio-assets__folder-section"
            aria-labelledby="studio-fp-basic"
          >
            <h3 id="studio-fp-basic">Basic</h3>
            <TextField
              label="Title"
              value={title}
              onChange={setTitle}
              autoFocus
            />
            <TextField
              label="Name"
              value={folder.name}
              isReadOnly
              description="Use Move to rename"
            />
            <TextField label="Path" value={folder.path} isReadOnly />
            <div className="studio-assets__folder-thumb">
              <Picker
                label="Thumbnail"
                selectedKey={thumb}
                onSelectionChange={(k) => setThumb(String(k))}
                isDisabled={images === null}
                description="The image on the folder's card. Automatic shows up to three files from inside."
                items={[{ path: "", title: "Automatic" }, ...(images ?? [])]}
              >
                {(i) => <Item key={i.path}>{i.title}</Item>}
              </Picker>
              {chosenThumb && <img src={chosenThumb} alt="" />}
            </div>
          </section>
          <section
            className="studio-assets__folder-section"
            aria-labelledby="studio-fp-processing"
          >
            <h3 id="studio-fp-processing">Processing</h3>
            <p className="studio-assets__muted">
              What the images uploaded or processed here (and in subfolders)
              get.
            </p>
            <Picker
              label="Processing Profile"
              selectedKey={profile}
              onSelectionChange={(k) => setProfile(String(k))}
              isDisabled={profiles === null}
              description="Applies to files uploaded or reprocessed from now on, here and in subfolders."
              items={[
                {
                  path: "",
                  title: inherited ? `Inherit (${titleOf(inherited)})` : "None",
                },
                ...(profiles ?? []),
              ]}
            >
              {(p) => <Item key={p.path}>{p.title}</Item>}
            </Picker>
            <div>
              {((imageProfiles?.length ?? 0) > 0 || inheritedImage) && (
                <Picker
                  label="Image Profile (Focus crop)"
                  selectedKey={imageProfile}
                  onSelectionChange={(k) => setImageProfile(String(k))}
                  description="Crops of these proportions for the images here and in subfolders."
                  items={[
                    {
                      path: "",
                      title: inheritedImage
                        ? `Inherit (${imageTitleOf(inheritedImage)})`
                        : "None",
                    },
                    ...(imageProfiles ?? []),
                  ]}
                >
                  {(p) => <Item key={p.path}>{p.title}</Item>}
                </Picker>
              )}
            </div>
            <div className="studio-assets__folder-smart">
              {smartInfo?.installed && (
                <>
                  <Picker
                    label="Auto tags"
                    selectedKey={smart}
                    onSelectionChange={(k) => setSmart(String(k))}
                    description="Tag images uploaded or processed here and in subfolders."
                    items={[
                      {
                        id: "",
                        title: `Inherit (${smartInfo.inherited ? "On" : "Off"})`,
                      },
                      { id: "on", title: "On" },
                      { id: "off", title: "Off" },
                    ]}
                  >
                    {(o) => <Item key={o.id}>{o.title}</Item>}
                  </Picker>
                  <Button
                    variant="secondary"
                    isDisabled={busy}
                    onPress={() =>
                      run(async () => {
                        const n = await client.smartTag([folder.path]);
                        setSmartNotice(
                          n
                            ? `${n} image${n === 1 ? "" : "s"} queued for Auto tags`
                            : "No images to tag here",
                        );
                      })
                    }
                  >
                    Tag this folder now
                  </Button>
                  {smartNotice && (
                    <p className="studio-assets__muted">{smartNotice}</p>
                  )}
                </>
              )}
            </div>
          </section>
          <section
            className="studio-assets__folder-section"
            aria-labelledby="studio-fp-metadata"
          >
            <h3 id="studio-fp-metadata">Metadata</h3>
            <Picker
              label="Metadata Schema"
              selectedKey={schema}
              onSelectionChange={(k) => setSchema(String(k))}
              isDisabled={schemas === null}
              description="The Properties form of the files here and in subfolders."
              items={[
                {
                  path: "",
                  title: inheritedSchema
                    ? `Inherit (${schemaTitle(inheritedSchema)})`
                    : "Default",
                },
                ...(schemas ?? []),
              ]}
            >
              {(s) => <Item key={s.path}>{s.title}</Item>}
            </Picker>
            <Picker
              label="Metadata Profile"
              selectedKey={metaProfile}
              onSelectionChange={(k) => {
                setMetaProfile(String(k));
                setApplyNotice(null);
              }}
              isDisabled={metaProfiles === null}
              description="Default values for the files uploaded here and in subfolders, for what a file does not bring itself."
              items={[
                {
                  path: "",
                  title: inheritedMetaProfile
                    ? `Inherit (${formTitle(metaProfiles, inheritedMetaProfile)})`
                    : "None",
                },
                ...(metaProfiles ?? []),
              ]}
            >
              {(p) => <Item key={p.path}>{p.title}</Item>}
            </Picker>
            {(ownMetaProfile || inheritedMetaProfile) &&
              metaProfile === ownMetaProfile && (
                <div className="studio-assets__folder-smart">
                  <Button
                    variant="secondary"
                    isDisabled={busy}
                    onPress={() =>
                      run(async () => {
                        const r = await client.applyProfile(folder.path);
                        setApplyNotice(
                          r.changed
                            ? `${r.changed} of ${r.files} file${r.files === 1 ? "" : "s"} got default values${r.truncated ? "; the first files only, apply again for the rest" : ""}`
                            : `The ${r.files} file${r.files === 1 ? "" : "s"} here had every value already`,
                        );
                      })
                    }
                  >
                    Apply to the files here
                  </Button>
                  {applyNotice && (
                    <p className="studio-assets__muted">{applyNotice}</p>
                  )}
                </div>
              )}
            <Picker
              label="Folder Metadata Schema"
              selectedKey={folderSchema}
              onSelectionChange={(k) => setFolderSchema(String(k))}
              isDisabled={folderSchemas === null}
              description="Fields of your own for this folder and its subfolders, below."
              items={[
                {
                  path: "",
                  title: inheritedFolderSchema
                    ? `Inherit (${formTitle(folderSchemas, inheritedFolderSchema)})`
                    : "None",
                },
                ...(folderSchemas ?? []),
              ]}
            >
              {(s) => <Item key={s.path}>{s.title}</Item>}
            </Picker>
          </section>
        </Form>
        {shownFolderSchema && folderValues && (
          <div className="studio-assets__folder-form">
            <section
              className="studio-assets__folder-section"
              aria-labelledby="studio-fp-folder-metadata"
            >
              <h3 id="studio-fp-folder-metadata">Folder Metadata</h3>
              {folderFields.map((tab) => (
                <div key={tab.title}>
                  {folderFields.length > 1 && <h4>{tab.title}</h4>}
                  <SchemaFields
                    tab={tab}
                    values={shownFolderValues}
                    onChange={(p, v) =>
                      setFolderEdits((x) => ({ ...x, [p]: v }))
                    }
                  />
                </div>
              ))}
            </section>
          </div>
        )}
        {folder.type === "folder" && (
          <div className="studio-assets__folder-form">
            <FolderMembersSection path={folder.path} />
            <FolderAccess path={folder.path} />
          </div>
        )}
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={busy}
          onPress={() =>
            run(async () => {
              await client.setFolderTitle(folder.path, title);
              if (profile !== own)
                await client.setFolderProfile(folder.path, profile || null);
              if (schema !== ownSchema)
                await client.setFolderSchema(folder.path, schema || null);
              if (metaProfile !== ownMetaProfile)
                await client.setFolderForm(
                  "profile",
                  folder.path,
                  metaProfile || null,
                );
              if (folderSchema !== ownFolderSchema)
                await client.setFolderForm(
                  "folderSchema",
                  folder.path,
                  folderSchema || null,
                );
              const folderChanges = shownFolderSchema
                ? changedValues(
                    folderFields,
                    loadedFolderValues,
                    shownFolderValues,
                  )
                : {};
              if (Object.keys(folderChanges).length > 0)
                await client.saveFolderMetadata(folder.path, folderChanges);
              if (thumb !== ownThumb)
                await client.setFolderThumbnail(folder.path, thumb || null);
              if (imageProfile !== ownImage)
                await client.setFolderImageProfile(
                  folder.path,
                  imageProfile || null,
                );
              if (smart !== ownSmart)
                await client.setFolderSmartTagging(
                  folder.path,
                  smart === "on" || smart === "off" ? smart : null,
                );
              onDone();
              close();
            })
          }
        >
          Save
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
