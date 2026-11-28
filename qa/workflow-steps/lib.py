"""Helpers for run.py: the author's endpoints through curl. Local test instances only (admin:admin)."""
import json, os, secrets, subprocess, time

A = os.environ.get("AUTHOR", "http://localhost:4502")
ADMIN = "admin:admin"
WF = "/bin/studio/workflow.json"
PT = "cq/workflow/components/model/"
HOME = "/content/wknd/language-masters/en"
TEMPLATE = "/conf/wknd/settings/wcm/templates/content-page-template"


def curl(user, method, path, data=None, form=None):
    cmd = ["curl", "-s", "-w", "\n%{http_code}", "-u", user, "-H", "X-Studio-CSRF: 1", "-X", method]
    for k, v in (data or {}).items():
        cmd += ["--data-urlencode", f"{k}={v}"]
    for k, v in (form or {}).items():
        cmd += ["--form-string", f"{k}={v}"]
    out = subprocess.run(cmd + [A + path], capture_output=True, text=True).stdout
    body, _, code = out.rpartition("\n")
    try:
        body = json.loads(body)
    except Exception:
        pass
    return int(code or 0), body


def get(path, user=ADMIN):
    return curl(user, "GET", path)[1]


def wf(op, user=ADMIN, **kw):
    return curl(user, "POST", WF, dict(op=op, **kw))


def must(r):
    if r[0] not in (200, 201):
        raise SystemExit(f"FAILED {r}")
    return r[1]


def prop(path, **props):
    return curl(ADMIN, "POST", path, form=props)[0]


def rm(path):
    return curl(ADMIN, "POST", path, form={":operation": "delete"})[0]


class Model:
    """A flow model built through the editor's endpoints; every name starts with zz-."""

    def __init__(self, name, title):
        wf("deleteModel", path=f"/conf/global/settings/workflow/models/{name}")
        must(wf("createModel", title=title, name=name))
        self.design = f"/conf/global/settings/workflow/models/{name}"
        self.flow = self.design + "/jcr:content/flow"

    def step(self, kind, title, container=None, **meta):
        p = must(wf("addStep", container=container or self.flow, resourceType=PT + kind, title=title))["path"]
        if meta:
            assert prop(p + "/metaData", **meta) in (200, 201), meta
        return p

    def split(self, kind, title, n=2, container=None, **meta):
        p = self.step(kind, title, container, **meta)
        if n != 2:
            prop(p, branches=str(n))
            must(wf("branches", path=p))
        return p, [f"{p}/{i}" for i in range(1, n + 1)]

    def sync(self):
        must(wf("touch", path=self.design))
        self.id = must(wf("sync", path=self.design))["id"]
        return self.id


def auto(**k):
    return dict(PROCESS_AUTO_ADVANCE="true", **k)


def start(model, payload, user=ADMIN, title="zz"):
    r = must(wf("start", user, model=model, payload=payload, title=title))
    time.sleep(1.5)
    return r["id"]


def inst(i):
    return get(f"{WF}?op=instance&id={i}")


def status(i):
    return inst(i)["status"]


def steps(i):
    return [(h.get("title"), h.get("status")) for h in inst(i)["history"]]


def active(i):
    return [h for h in inst(i)["history"] if h["status"] == "ACTIVE"]


def item(i, title):
    return [h for h in active(i) if h["title"] == title][0]["id"]


def inbox(user=ADMIN):
    return get(f"{WF}?op=inbox", user)


def complete(it, user=ADMIN, route=None, comment="ok"):
    kw = dict(item=it, comment=comment)
    if route:
        kw["route"] = route
    r = wf("complete", user, **kw)
    time.sleep(1.5)
    return r


def qa(page):
    return {k: v for k, v in get(page + "/jcr:content.json").items() if k.startswith("qa")}


def page(name, **props):
    p = f"{HOME}/{name}"
    rm(p)
    must(curl(ADMIN, "POST", "/bin/studio/sites.json", dict(op="create", parent=HOME, name=name, title="ZZ " + name, template=TEMPLATE)))
    if props:
        prop(p + "/jcr:content", **props)
    return p


def user(uid, *groups):
    """A throwaway user with a password made here and kept in memory only."""
    pw = secrets.token_hex(12)
    curl(ADMIN, "POST", "/bin/studio/security.json", dict(op="delete", id=uid))
    must(curl(ADMIN, "POST", "/bin/studio/security.json", dict(op="createUser", id=uid, password=pw)))
    for g in groups:
        must(curl(ADMIN, "POST", "/bin/studio/security.json", dict(op="memberOf", id=uid, group=g)))
    return f"{uid}:{pw}"


def cleanup():
    for day, v in get("/var/workflow/instances/server0.1.json").items():
        if isinstance(v, dict):
            for k in get(f"/var/workflow/instances/server0/{day}.1.json"):
                if k.startswith("zz-"):
                    rm(f"/var/workflow/instances/server0/{day}/{k}")
    for m in get(f"{WF}?op=models"):
        if m["name"].startswith("zz-"):
            wf("deleteModel", path=m.get("designPath") or "")
            rm("/var/workflow/models/" + m["name"])
    for k in get(HOME + ".1.json"):
        if k.startswith("zz-"):
            rm(f"{HOME}/{k}")
    for l in get(f"{WF}?op=launchers"):
        if "zz-" in str(l.get("glob")):
            wf("deleteLauncher", path=l["path"])
    for u in ("qa-wa", "qa-wb"):
        curl(ADMIN, "POST", "/bin/studio/security.json", dict(op="delete", id=u))
