import {
  ActionButton,
  Button,
  ButtonGroup,
  ComboBox,
  Content,
  DialogContainer,
  Divider,
  Form,
  Heading,
  Item,
  Picker,
  Switch,
  TabList,
  TabPanels,
  Tabs,
  Text,
  TextArea,
  TextField,
  ToastContainer,
  ToastQueue,
  useDialogContainer,
} from '@adobe/react-spectrum';
import Close from '@spectrum-icons/workflow/Close';
import User from '@spectrum-icons/workflow/User';
import UserGroup from '@spectrum-icons/workflow/UserGroup';
import { useEffect, useMemo, useState, type Key } from 'react';
import { ProductBar } from '../shell/ProductBar';
import { security, securityUrl, type AuthorizableDetail, type AuthorizableSummary, type Profile } from './api';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { FormPage } from '../shell/FormPage';
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  kind: 'user' | 'group';
  /** absent: create */
  id: string | null;
  user: string | null;
}

const ID_RE = /^[A-Za-z0-9._@-]+$/;

/**
 * Create or edit a user or group, full page like AEM's user editor: Details (account status and
 * password for users, profile), Groups (memberships), Members (groups) and Impersonators (users).
 */
export function AuthorizableEditor({ kind, id, user }: Props) {
  const go = useNavigate();
  const tab = useTab();
  const isUser = kind === 'user';
  const isNew = !id;
  const back = isUser ? securityUrl.users() : securityUrl.groups();
  /** Save and Cancel: back where the author came from, else the console of this kind. */
  const leave = () => (tab ? tab.leave(back) : go(back));
  const [detail, setDetail] = useState<AuthorizableDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [newId, setNewId] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [profile, setProfile] = useState<Profile>({});
  const [enabled, setEnabled] = useState(true);
  const [memberOf, setMemberOf] = useState<AuthorizableSummary[]>([]);
  const [members, setMembers] = useState<AuthorizableSummary[]>([]);
  const [impersonators, setImpersonators] = useState<string[]>([]);
  const [allGroups, setAllGroups] = useState<AuthorizableSummary[]>([]);
  const [allUsers, setAllUsers] = useState<AuthorizableSummary[]>([]);
  const [changingPassword, setChangingPassword] = useState(false);
  const [saving, setSaving] = useState(false);

  useScreenTitle(`${isNew ? 'Create' : 'Edit'} ${isUser ? 'User' : 'Group'}`, `${isNew ? 'Create' : 'Edit'} ${isUser ? 'User' : 'Group'} | Sensible Studio Security`);
  useEffect(() => {
    security.groups().then(setAllGroups, () => setAllGroups([]));
    security.users('', false).then(setAllUsers, () => setAllUsers([]));
    if (!id) return;
    security.get(id).then(
      (d) => {
        setDetail(d);
        setProfile(d.profile);
        setEnabled(!d.disabled);
        setMemberOf(d.memberOf);
        setMembers(d.members ?? []);
        setImpersonators(d.impersonators ?? []);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [id, isNew, isUser]);

  const field = (name: keyof Profile) => ({
    value: profile[name] ?? '',
    onChange: (v: string) => setProfile((p) => ({ ...p, [name]: v })),
  });

  const idOk = !isNew || ID_RE.test(newId);
  const passwordOk = !isNew || !isUser || (password.length > 0 && password === confirm);
  const canSave = idOk && passwordOk && !saving;
  const dirty = isNew
    ? !!(newId || password || confirm || Object.keys(profile).length || memberOf.length || members.length || impersonators.length || !enabled)
    : !!detail &&
      (JSON.stringify(profile) !== JSON.stringify(detail.profile) ||
        enabled !== !detail.disabled ||
        JSON.stringify(memberOf.map((x) => x.id)) !== JSON.stringify(detail.memberOf.map((x) => x.id)) ||
        JSON.stringify(members.map((x) => x.id)) !== JSON.stringify((detail.members ?? []).map((x) => x.id)) ||
        JSON.stringify(impersonators) !== JSON.stringify(detail.impersonators ?? []));

  const reset = () => {
    setError(null);
    setPassword('');
    setConfirm('');
    if (!detail) {
      setNewId('');
      setProfile({});
      setEnabled(true);
      setMemberOf([]);
      setMembers([]);
      setImpersonators([]);
      return;
    }
    setProfile(detail.profile);
    setEnabled(!detail.disabled);
    setMemberOf(detail.memberOf);
    setMembers(detail.members ?? []);
    setImpersonators(detail.impersonators ?? []);
  };

  const save = async () => {
    if (!canSave) return;
    setSaving(true);
    try {
      let target = id;
      if (isNew) {
        const created = isUser ? await security.createUser(newId, password, profile) : await security.createGroup(newId, profile);
        target = created.id;
      }
      if (!target) return;
      if (!isNew) await security.update(target, profile, isUser && !detail?.protected ? { disabled: !enabled } : {});
      else if (isUser && !enabled) await security.update(target, {}, { disabled: true });
      await security.setMemberOf(target, memberOf.map((g) => g.id));
      if (!isUser) await security.setMembers(target, members.map((m) => m.id));
      if (isUser) await security.setImpersonators(target, impersonators);
      if (isNew) {
        ToastQueue.positive(`${isUser ? 'User' : 'Group'} ${target} created`, { timeout: 3000 });
        go(isUser ? securityUrl.user(target) : securityUrl.group(target));
        return;
      }
      const next = await security.get(target);
      setDetail(next);
      setProfile(next.profile);
      setEnabled(!next.disabled);
      setMemberOf(next.memberOf);
      setMembers(next.members ?? []);
      setImpersonators(next.impersonators ?? []);
      ToastQueue.positive(`${isUser ? 'User' : 'Group'} saved`, { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      // also after a save that went well: it used to stay "Saving", and nothing could be saved a second time
      setSaving(false);
    }
  };

  const title = isNew ? `Create New ${isUser ? 'User' : 'Group'}` : `${isUser ? 'Edit User Settings' : 'Edit Group Settings'} for ${detail?.name ?? id}`;

  return (
    <div className="studio-sites studio-security">
      {!tab && <ProductBar user={user} />}
      <FormPage
        kind={isUser ? 'User' : 'Group'}
        title={title}
        path={id ?? undefined}
        dirty={dirty}
        busy={saving}
        canSave={canSave && (isNew || !!detail)}
        onReset={reset}
        onSave={save}
        onClose={leave}
        wide
      >
      <main className="studio-sites__content studio-editorpage">
        {error ? (
          <ErrorState title={`Could not open ${id}`}>{error}</ErrorState>
        ) : !isNew && !detail ? (
          <LoadingState />
        ) : (
          <Tabs aria-label="Settings" UNSAFE_className="studio-editorpage__tabs">
            <TabList>
              <Item key="details">Details</Item>
              <Item key="groups">Groups</Item>
              {(!isUser ? <Item key="members">Members</Item> : <Item key="impersonators">Impersonators</Item>) as JSX.Element}
            </TabList>
            <TabPanels>
              <Item key="details">
                <div className="studio-editorpage__details">
                  <div className="studio-editorpage__photo" aria-hidden>
                    {isUser ? <User size="XXL" /> : <UserGroup size="XXL" />}
                  </div>
                  <div className="studio-editorpage__form">
                    {isUser ? (
                      <>
                        <Heading level={3}>Account settings</Heading>
                        {isNew ? (
                          <>
                            <TextField label="Password" type="password" value={password} onChange={setPassword} isRequired />
                            <TextField
                              label="Retype Password"
                              type="password"
                              value={confirm}
                              onChange={setConfirm}
                              isRequired
                              validationState={confirm && confirm !== password ? 'invalid' : undefined}
                              errorMessage="The passwords do not match"
                            />
                          </>
                        ) : (
                          <div className="studio-editorpage__row">
                            <Button variant="secondary" onPress={() => setChangingPassword(true)}>
                              Change Password
                            </Button>
                          </div>
                        )}
                        <Switch isSelected={enabled} onChange={setEnabled} isDisabled={detail?.protected}>
                          Account enabled
                        </Switch>
                        {detail?.disabled && detail.disabledReason ? <Text UNSAFE_className="studio-editorpage__hint">Disabled: {detail.disabledReason}</Text> : <></>}
                      </>
                    ) : (
                      <></>
                    )}
                    <Heading level={3}>Details</Heading>
                    <TextField
                      label="ID"
                      value={isNew ? newId : (id ?? '')}
                      onChange={setNewId}
                      isReadOnly={!isNew}
                      isRequired
                      validationState={isNew && newId && !idOk ? 'invalid' : undefined}
                      errorMessage="Letters, digits and . _ @ - only"
                      autoFocus={isNew}
                    />
                    {isUser ? (
                      <>
                        <TextField label="Email" type="email" {...field('email')} />
                        <TextField label="Title" {...field('title')} />
                        <TextField label="First Name" {...field('givenName')} />
                        <TextField label="Last Name" {...field('familyName')} />
                        <Picker label="Gender" selectedKey={profile.gender || null} onSelectionChange={(k: Key | null) => setProfile((p) => ({ ...p, gender: k ? String(k) : '' }))}>
                          <Item key="female">Female</Item>
                          <Item key="male">Male</Item>
                          <Item key="other">Other</Item>
                        </Picker>
                        <TextField label="Phone Number" {...field('phoneNumber')} />
                        <TextField label="Job Title" {...field('jobTitle')} />
                        <TextArea label="About" {...field('aboutMe')} />
                        <TextField label="Street" {...field('street')} />
                        <TextField label="City" {...field('city')} />
                        <TextField label="Postal Code" {...field('postalCode')} />
                        <TextField label="Country/Region" {...field('country')} />
                      </>
                    ) : (
                      <>
                        <TextField label="Group Name" {...field('givenName')} />
                        <TextArea label="Description" {...field('aboutMe')} />
                        <TextField label="Email" type="email" {...field('email')} />
                      </>
                    )}
                  </div>
                </div>
              </Item>
              <Item key="groups">
                <MembershipList
                  label={`Groups ${isNew ? 'the new ' + kind : (detail?.name ?? id)} is a member of`}
                  items={memberOf}
                  candidates={allGroups.filter((g) => g.id !== (id ?? newId))}
                  onChange={setMemberOf}
                />
              </Item>
              {
                (!isUser ? (
                  <Item key="members">
                    <MembershipList
                      label="Members of this group"
                      items={members}
                      candidates={[...allUsers, ...allGroups].filter((a) => a.id !== (id ?? newId))}
                      onChange={setMembers}
                    />
                  </Item>
                ) : (
                  <Item key="impersonators">
                    <MembershipList
                      label="Users who may impersonate this user"
                      items={impersonators.map((p) => allUsers.find((u) => u.principal === p) ?? { id: p, principal: p, name: p, type: 'user', path: '' })}
                      candidates={allUsers.filter((u) => u.id !== id)}
                      onChange={(list) => setImpersonators(list.map((u) => u.principal))}
                    />
                  </Item>
                )) as JSX.Element
              }
            </TabPanels>
          </Tabs>
        )}
      </main>
      </FormPage>

      <DialogContainer onDismiss={() => setChangingPassword(false)}>
        {changingPassword && id && <ChangePasswordDialog id={id} />}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}

/** A list of users/groups with remove buttons and a combo box to add more. */
function MembershipList({
  label,
  items,
  candidates,
  onChange,
}: {
  label: string;
  items: AuthorizableSummary[];
  candidates: AuthorizableSummary[];
  onChange(list: AuthorizableSummary[]): void;
}) {
  const available = useMemo(() => candidates.filter((c) => !items.some((i) => i.id === c.id)), [candidates, items]);
  const [input, setInput] = useState('');
  return (
    <section className="studio-memberships" aria-label={label}>
      <ComboBox
        label="Add"
        placeholder="Search by name or ID"
        width="size-4600"
        items={available.filter((a) => !input || `${a.name} ${a.id}`.toLowerCase().includes(input.toLowerCase()))}
        inputValue={input}
        onInputChange={setInput}
        onSelectionChange={(k) => {
          const a = available.find((x) => x.id === k);
          if (a) {
            onChange([...items, a]);
            setInput('');
          }
        }}
      >
        {(a) => (
          <Item key={a.id} textValue={a.name}>
            {a.type === 'group' ? <UserGroup size="S" /> : <User size="S" />}
            <Text>{a.name}</Text>
            <Text slot="description">{a.id}</Text>
          </Item>
        )}
      </ComboBox>
      <Text UNSAFE_className="studio-memberships__label">{label}</Text>
      {items.length === 0 ? (
        <p className="studio-refs__empty">None.</p>
      ) : (
        <ul className="studio-memberships__list">
          {items.map((i) => (
            <li key={i.id}>
              <span className="studio-security__avatar">{i.type === 'group' ? <UserGroup size="S" /> : <User size="S" />}</span>
              <span className="studio-memberships__text">
                <span className="studio-memberships__name">{i.name}</span>
                <span className="studio-memberships__id">{i.id}</span>
              </span>
              <ActionButton isQuiet aria-label={`Remove ${i.name}`} onPress={() => onChange(items.filter((x) => x.id !== i.id))}>
                <Close />
              </ActionButton>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ChangePasswordDialog({ id }: { id: string }) {
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const ok = password.length > 0 && password === confirm;
  return (
    <StudioDialog size="S">
      <Heading>Change Password</Heading>
      <Divider />
      <Content>
        <Form>
          <TextField label="New Password" type="password" value={password} onChange={setPassword} autoFocus />
          <TextField
            label="Retype New Password"
            type="password"
            value={confirm}
            onChange={setConfirm}
            validationState={confirm && !ok ? 'invalid' : undefined}
            errorMessage="The passwords do not match"
          />
        </Form>
      </Content>
      <ButtonGroup>
        <ButtonCloser label="Cancel" />
        <ButtonSave
          isDisabled={!ok || busy}
          onSave={async (close) => {
            setBusy(true);
            try {
              await security.update(id, {}, { password });
              ToastQueue.positive('Password changed', { timeout: 3000 });
              close();
            } catch (e) {
              ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
              setBusy(false);
            }
          }}
        />
      </ButtonGroup>
    </StudioDialog>
  );
}


function ButtonCloser({ label }: { label: string }) {
  const c = useDialogContainer();
  return (
    <Button variant="secondary" onPress={c.dismiss}>
      {label}
    </Button>
  );
}

function ButtonSave({ isDisabled, onSave }: { isDisabled: boolean; onSave(close: () => void): Promise<void> }) {
  const c = useDialogContainer();
  return (
    <Button variant="accent" isDisabled={isDisabled} onPress={() => void onSave(c.dismiss)}>
      Save
    </Button>
  );
}
