#!/usr/bin/env python3
"""
Every kind of step, and process steps written by a customer, on a running author.

    (cd studio-uberjar && mvn -o -q -pl studio-workflow -am package)
    (cd qa/workflow-steps && mvn -o -q package && cp target/zz-wfqa-1.0.0.jar ../../instances/author/install/)
    python3 qa/workflow-steps/run.py          # AUTHOR=http://localhost:4502 by default

Needs the WKND site (pages are made below its language master) and admin:admin. Everything it makes is
named zz-… or qa-… and removed at the end; remove the jar from install/ afterwards.
"""
import os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import *

bad = []


def check(what, ok, seen=""):
    print(("ok   " if ok else "FAIL ") + what + ("" if ok else f"   <- {seen}"))
    if not ok:
        bad.append(what)


labels = get(f"{WF}?op=processes")
if "QA Stamp" not in labels["processes"]:
    raise SystemExit("The QA steps are not registered: put target/zz-wfqa-1.0.0.jar into the author's install/ folder")
check("the older API's steps are offered beside the Granite ones", all(l in labels["processes"] for l in ("QA Old Stamp", "QA Old Java Process", "QA Old Route")) and "QA Old Reviewer" in labels["choosers"], labels)
cleanup()
ua = user("qa-wa", "content-authors")
ub = user("qa-wb", "content-authors")

# ---- a straight line of steps
line = Model("zz-line", "ZZ line")
line.step("process", "Stamp one", **auto(PROCESS="QA Stamp", PROCESS_ARGS="one"))
line.step("process", "Jcr", **auto(PROCESS="com.example.wfqa.Steps$JcrStamp", PROCESS_ARGS="two"))
line.step("process", "Stamp again", **auto(PROCESS="QA Stamp", PROCESS_ARGS="three"))
line.sync()
p = page("zz-w1")
i = start(line.id, p, title="Line run")
q = qa(p)
check("a line of custom steps completes", status(i) == "COMPLETED", steps(i))
check("a step sees the flow: initiator, model, node, title", (q.get("qaInitiator"), q.get("qaModel"), q.get("qaNode"), q.get("qaTitle"), q.get("qaType")) == ("admin", "ZZ line", "Stamp again", "Line run", "PROCESS"), q)
check("metadata goes from step to step", q.get("qaVisits") == 2, q)
check("a step may save through JCR itself, as the service user", q.get("qaJcr") == "two" and q.get("qaJcrUser") == "studio-workflow-service", q)

# ---- every kind of branch
m = Model("zz-all", "ZZ all branches")
m.step("process", "Stamp", **auto(PROCESS="QA Stamp", PROCESS_ARGS="start"))
m.step("process", "Route by payload", PROCESS="QA Route By Payload", PROCESS_AUTO_ADVANCE="false")
orp, br = m.split("or", "Decision", 3)
m.step("participant", "Editor review", br[0], PARTICIPANT="content-authors")
andp, ab = m.split("and", "Both", 2, br[1])
m.step("participant", "Legal", ab[0], PARTICIPANT="administrators")
m.step("dynamic_participant", "Brand", ab[1], DYNAMIC_PARTICIPANT="QA Reviewer Of The Payload")
m.step("process", "Needs fixing", br[2], **auto(PROCESS="QA Fail Until Fixed"))
m.step("process", "Stamp end", **auto(PROCESS="QA Stamp", PROCESS_ARGS="end"))
m.sync()

p = page("zz-w2", qaRoute="Editor review")
i = start(m.id, p, ua)
check("a step chooses its route by code", qa(p).get("qaRoutes") == "Editor review|Both|Needs fixing|" and [t for t, s in steps(i) if s == "ACTIVE"] == ["Editor review"], steps(i))
check("a step for a group is in the Inbox of its members", [x["title"] for x in inbox(ub)] == ["Editor review"], inbox(ub))
complete(item(i, "Editor review"), ub)
check("after the branch the flow joins and ends", status(i) == "COMPLETED" and qa(p).get("qaStamp") == "end" and qa(p).get("qaVisits") == 2, steps(i))

p = page("zz-w2", qaRoute="Both", qaReviewer="qa-wb")
i = start(m.id, p, ua)
check("an AND split starts both branches; the chooser names the reviewer", sorted((h["title"], h["assignee"]) for h in active(i)) == [("Brand", "qa-wb"), ("Legal", "administrators")], active(i))
check("a step is completed only by who has it", complete(item(i, "Legal"), ub)[0] == 403 and complete(item(i, "Brand"), ua)[0] == 403)
check("who has a step may hand it on", wf("delegate", ub, item=item(i, "Brand"), assignee="qa-wa")[0] == 200 and [x["title"] for x in inbox(ua)] == ["Brand"])
complete(item(i, "Legal"))
check("the join waits for the other branch", status(i) == "RUNNING" and qa(p).get("qaStamp") == "start")
complete(item(i, "Brand"), ua)
check("and goes on when both arrived", status(i) == "COMPLETED" and qa(p).get("qaStamp") == "end", steps(i))

p = page("zz-w2", qaRoute="Both")
i = start(m.id, p, ua)
check("the chooser falls back to who started the flow", [h["assignee"] for h in active(i) if h["title"] == "Brand"] == ["qa-wa"])
complete(item(i, "Brand"), ua)
complete(item(i, "Legal"))
check("the other order of the AND", status(i) == "COMPLETED")

p = page("zz-w2", qaRoute="Both", qaReviewer="nobody-here")
i = start(m.id, p, ua)
failed = [h for h in inst(i)["history"] if h["status"] == "FAILED"]
check("a step for somebody who does not exist fails and says so", len(failed) == 1 and "nobody-here" in failed[0]["failure"], steps(i))
wf("terminate", id=i)

p = page("zz-w2", qaRoute="Needs fixing")
i = start(m.id, p, ua)
failed = [h for h in inst(i)["history"] if h["status"] == "FAILED"]
check("a failing step is FAILED with its message, and what it wrote is taken back", len(failed) == 1 and "qaFixed" in failed[0]["failure"] and "qaHalfDone" not in qa(p), steps(i))
check("it shows under Failures", any(x["id"] == i for x in get(f"{WF}?op=instances&failed=true")))
check("Retry is for administrators", wf("retry", ua, item=failed[0]["id"])[0] == 403)
wf("retry", item=failed[0]["id"])
time.sleep(2)
check("a retry of what is still broken fails again", [s for t, s in steps(i)].count("FAILED") == 1 and status(i) == "RUNNING", steps(i))
prop(p + "/jcr:content", qaFixed="true", **{"qaFixed@TypeHint": "Boolean"})
wf("retry", item=[h for h in inst(i)["history"] if h["status"] == "FAILED"][0]["id"])
time.sleep(2)
check("fixed and retried, the flow ends", status(i) == "COMPLETED" and qa(p).get("qaPassedFail") is True, steps(i))
check("Retry with something that is not a step is refused, not an error", wf("retry", item="nonsense")[0] == 400)

# ---- a failure after a participant step leaves that step done
h = Model("zz-hold", "ZZ hold")
h.step("participant", "Hold", PARTICIPANT="administrators")
h.step("process", "Needs fixing", **auto(PROCESS="QA Fail Until Fixed"))
h.sync()
p = page("zz-w3")
i = start(h.id, p)
complete(item(i, "Hold"))
check("a failing step does not take back the step before it", steps(i) == [("Hold", "COMPLETED"), ("Needs fixing", "FAILED")], steps(i))
wf("terminate", id=i)

# ---- what goes wrong, and ending or starting flows from a step
b = Model("zz-bad", "ZZ bad")
b.step("process", "Boom", **auto(PROCESS="QA Blow Up"))
b.sync()
i = start(b.id, page("zz-w3"))
check("any exception fails the step", steps(i) == [("Boom", "FAILED")] and "blown up" in inst(i)["history"][0]["failure"], steps(i))
wf("terminate", id=i)
check("a failed flow can be ended", status(i) == "ABORTED")
b = Model("zz-bad", "ZZ bad")
b.step("process", "Ghost", **auto(PROCESS="com.example.Nope"))
b.sync()
i = start(b.id, page("zz-w3"))
check("a process that is not registered fails and names it", "com.example.Nope" in inst(i)["history"][0].get("failure", ""), steps(i))
wf("terminate", id=i)

t = Model("zz-term", "ZZ term")
ap, tb = t.split("and", "Par")
t.step("participant", "Waits", tb[0], PARTICIPANT="administrators")
t.step("process", "Ends it", tb[1], **auto(PROCESS="QA Terminate"))
t.step("process", "Never", **auto(PROCESS="QA Stamp", PROCESS_ARGS="never"))
t.sync()
p = page("zz-w3")
i = start(t.id, p)
check("a step ends its flow, parallel branches too", status(i) == "ABORTED" and not active(i) and qa(p) == {"qaTerminated": True}, (steps(i), qa(p)))

s = Model("zz-spawn", "ZZ spawn")
s.step("process", "Spawn", **auto(PROCESS="QA Start Another", PROCESS_ARGS=line.id))
s.sync()
p = page("zz-w3")
i = start(s.id, p, ua)
time.sleep(3)
child = qa(p).get("qaStarted")
check("a step starts another flow, for the same initiator", bool(child) and status(child) == "COMPLETED" and inst(child)["initiator"] == "qa-wa", qa(p))

# ---- Goto
g = Model("zz-loop", "ZZ loop")
d = g.step("participant", "Draft", PARTICIPANT="administrators")
g.step("goto", "Again", TARGET_NODE_PATH=d, rule="function check(){ return !jcrSession.getNode(workflowData.getPayload().toString() + '/jcr:content').hasProperty('qaDone'); }")
g.sync()
p = page("zz-w4")
i = start(g.id, p)
complete(active(i)[0]["id"])
complete(active(i)[0]["id"])
check("a Goto loops while its script says so", status(i) == "RUNNING" and len(inst(i)["history"]) == 5, steps(i))
prop(p + "/jcr:content", qaDone="yes")
complete(active(i)[0]["id"])
check("and lets go when it says no", status(i) == "COMPLETED", steps(i))

# ---- rules of an OR split
cases = [
    (dict(rule1="false", rule2="true"), "B"),
    (dict(rule1="false", rule2="false", default2="true", default1="false"), "B"),
    (dict(rule1="function check(){return true;}"), "A"),
    (dict(rule1="function check(){ return workflowData.getPayload().toString().indexOf('/content/wknd') == 0; }", default2="true", default1="false"), "A"),
    (dict(rule1="function check(){ return workflowData.getPayloadType() == 'DAM'; }", default2="true", default1="false"), "B"),
    (dict(rule1="function check(){ var n = jcrSession.getNode(workflowData.getPayload().toString() + '/jcr:content'); return n.hasProperty('qaRoute') && n.getProperty('qaRoute').getString() == 'A'; }", default2="true", default1="false"), "A"),
    (dict(rule1="function check(){ return workflowData.getMetaDataMap().get('workflowTitle', '') == 'special'; }", default2="true", default1="false"), "A"),
    (dict(rule1="function check(){ throw 'oops'; }", default2="true", default1="false"), "B"),
    (dict(rule1="return true", default2="true", default1="false"), "B"),
]
for rules, expect in cases:
    o = Model("zz-or", "ZZ or")
    o.step("process", "Go", **auto(PROCESS="No Operation"))
    op, ob = o.split("or", "Pick", 2, **rules)
    o.step("process", "A", ob[0], **auto(PROCESS="QA Stamp", PROCESS_ARGS="A"))
    o.step("process", "B", ob[1], **auto(PROCESS="QA Stamp", PROCESS_ARGS="B"))
    o.sync()
    p = page("zz-w4", qaRoute="A")
    i = start(o.id, p, title="special")
    check(f"OR rule {str(rules.get('rule1'))[:60]!r} takes {expect}", qa(p).get("qaStamp") == expect and status(i) == "COMPLETED", qa(p))
o = Model("zz-or", "ZZ or")
o.step("participant", "Look", PARTICIPANT="administrators")
op, ob = o.split("or", "Pick", 2, rule1="function check(){ return false; }", rule2="function check(){ return true; }")
o.step("process", "A", ob[0], **auto(PROCESS="QA Stamp", PROCESS_ARGS="A"))
o.step("process", "B", ob[1], **auto(PROCESS="QA Stamp", PROCESS_ARGS="B"))
o.sync()
p = page("zz-w4")
i = start(o.id, p)
complete(active(i)[0]["id"])
check("with no route named, the rule decides after a participant", qa(p).get("qaStamp") == "B")
p = page("zz-w4")
i = start(o.id, p)
routes = [x for x in inbox() if x["workflow"]["id"] == i][0]["routes"]
complete(active(i)[0]["id"], route=routes[0]["id"])
check("the route the person names wins over the rules", [r["name"] for r in routes] == ["A", "B"] and qa(p).get("qaStamp") == "A", routes)

# ---- suspend, handler advance off, timeout, payloads, a trigger
p = page("zz-w5", qaRoute="Editor review")
i = start(m.id, p, ua)
it = active(i)[0]["id"]
check("who started a flow may pause it; a paused flow takes no completion and leaves the Inbox", wf("suspend", ua, id=i)[0] == 200 and status(i) == "SUSPENDED" and complete(it)[0] == 400 and inbox(ua) == [])
check("resumed, it goes on", wf("resume", ua, id=i)[0] == 200 and complete(it, ua)[0] == 200 and status(i) == "COMPLETED")

w = Model("zz-wait", "ZZ wait")
w.step("process", "External", PROCESS="No Operation", PROCESS_AUTO_ADVANCE="false")
w.step("process", "After", **auto(PROCESS="QA Stamp", PROCESS_ARGS="after"))
w.sync()
p = page("zz-w5")
i = start(w.id, p)
check("with Handler Advance off the step waits", steps(i) == [("External", "ACTIVE")], steps(i))
complete(active(i)[0]["id"])
check("until something completes it", status(i) == "COMPLETED" and qa(p).get("qaStamp") == "after")

tm = Model("zz-time", "ZZ time")
tm.step("participant", "Wait a bit", PARTICIPANT="administrators", timeoutMillis="20", timeoutHandler="com.adobe.granite.workflow.console.timeout.autoadvance.AutoAdvancer")
tm.step("process", "After", **auto(PROCESS="QA Stamp", PROCESS_ARGS="timed"))
tm.sync()
timed_page = page("zz-w6")
timed = start(tm.id, timed_page)
began = time.time()

c, bdy = curl(ADMIN, "POST", WF, dict(op="start", model=h.id, payload="https://example.com/x", payloadType="URL"))
time.sleep(1.5)
complete(active(bdy["id"])[0]["id"])
check("a payload that is no path: the step says so", "Not a path payload" in str(inst(bdy["id"])["history"][-1].get("failure")), steps(bdy["id"]))
wf("terminate", id=bdy["id"])
p = page("zz-w7")
i = start(h.id, p)
rm(p)
complete(active(i)[0]["id"])
check("a payload deleted under a flow: the step says so", "No payload at" in str(inst(i)["history"][-1].get("failure")), steps(i))
wf("terminate", id=i)

c, l = wf("saveLauncher", workflow=line.id, glob=HOME + "/zz-w8(/.*)?", eventType="16", nodetype="cq:PageContent", enabled="true", description="zz")
p = page("zz-w8")
prop(p + "/jcr:content", subtitle="changed")
time.sleep(6)
runs = [x for x in get(f"{WF}?op=instances") if str(x.get("payload", "")).startswith(p)]
check("a trigger starts the custom flow on a change, once (its own writes do not start it again)", len(runs) == 1 and runs[0]["status"] == "COMPLETED" and qa(p).get("qaStamp") == "three", runs)
wf("deleteLauncher", path=l.get("path", ""))

# ---- the Granite calls a step may make on its flow
hd = Model("zz-hist", "ZZ history")
ap, hb = hd.split("and", "Par")
hd.step("participant", "Waits", hb[0], PARTICIPANT="administrators")
hd.step("process", "Reads and hands on", hb[1], **auto(PROCESS="QA History And Delegate"))
hd.sync()
p = page("zz-w9", qaDelegate="qa-wb")
i = start(hd.id, p, ua)
q = qa(p)
check("a step reads the history of its flow", "Waits:ACTIVE:administrators" in str(q.get("qaHistory")) and "Reads and hands on:ACTIVE" in str(q.get("qaHistory")), q)
check("a step hands another waiting step on", q.get("qaDelegated") == 1 and [(x["title"], x["assignee"]) for x in inbox(ub)] == [("Waits", "qa-wb")], (q, inbox(ub)))
complete(item(i, "Waits"), ub)
check("and the flow ends when that one is done", status(i) == "COMPLETED", steps(i))

# ---- the older API, com.day.cq.workflow
old = Model("zz-old", "ZZ old")
old.step("process", "Old stamp", **auto(PROCESS="QA Old Stamp", PROCESS_ARGS="legacy"))
old.step("process", "Old java", **auto(PROCESS="com.example.wfqa.OldSteps$Java", PROCESS_ARGS="a, b ,c"))
old.step("process", "Old route", PROCESS="QA Old Route", PROCESS_AUTO_ADVANCE="false")
op, ob = old.split("or", "Pick", 2)
old.step("dynamic_participant", "Old reviewer", ob[0], DYNAMIC_PARTICIPANT="QA Old Reviewer")
old.step("process", "Old fail", ob[1], **auto(PROCESS="QA Old Fail"))
old.step("process", "Old stamp end", **auto(PROCESS="QA Old Stamp", PROCESS_ARGS="legacy end"))
old.sync()
p = page("zz-w10", qaRoute="Old reviewer", qaReviewer="qa-wb")
i = start(old.id, p, ua)
q = qa(p)
check("older API: a WorkflowProcess runs and sees its flow", (q.get("qaOld"), q.get("qaOldInitiator"), q.get("qaOldModel"), q.get("qaOldNode")) == ("legacy", "qa-wa", "ZZ old", "Old stamp"), q)
check("older API: a JavaProcessExt gets its arguments as an array", q.get("qaOldArgs") == "a+b+c" and q.get("qaOldArgCount") == 3, q)
check("older API: a step routes by code and a chooser names who is next", [(h["title"], h["assignee"]) for h in active(i)] == [("Old reviewer", "qa-wb")], steps(i))
complete(item(i, "Old reviewer"), ub)
q = qa(p)
check("older API: metadata, history and the end of the flow", status(i) == "COMPLETED" and q.get("qaOld") == "legacy end" and q.get("qaOldVisits") == 2 and q.get("qaOldHistory") >= 5, q)
p = page("zz-w10", qaRoute="Old fail")
i = start(old.id, p, ua)
check("older API: its WorkflowException fails the step with its message", "failed the old way" in str(inst(i)["history"][-1].get("failure")), steps(i))
wf("terminate", id=i)

# ---- code outside a flow, as the signed-in user, through the older API
p = page("zz-w11")
c, started = curl(ua, "POST", "/bin/qa/wf", dict(model=h.id, payload=p))
time.sleep(1.5)
check("older API: WorkflowService.getWorkflowSession starts a flow as the user", c == 200 and str(started).startswith("/var/workflow/instances/") and inst(started)["initiator"] == "qa-wa", (c, started))
c, told = curl(ADMIN, "GET", "/bin/qa/wf")
check("older API: an adapted session lists the administrator's work items", c == 200 and "superuser=true" in str(told) and "Hold <- administrators in " + str(started) in str(told), told)
c, told = curl(ua, "GET", "/bin/qa/wf")
check("an author's session: no item of others, their own flows", c == 200 and "superuser=false" in str(told) and "Hold" not in str(told) and "workflows=0" not in str(told), told)
c, refused = curl(ub, "POST", "/bin/qa/wf", dict(model=h.id, payload="/content/zz-does-not-exist"))
check("starting on what is not there is refused with a reason", c == 500 and "does not exist" in str(refused), (c, str(refused)[:200]))
wf("terminate", id=started)

# ---- the rest of the two APIs, as the signed-in user
def api(user, **kw):
    c, body = curl(user, "POST", "/bin/qa/wfapi", kw)
    return c, dict(l.split("=", 1) for l in str(body).splitlines() if "=" in l) if c == 200 else str(body)


c, r = api(ADMIN, do="model", title="ZZ by api", id="zz-api")
check("createNewModel makes a model that can be started; deployModel syncs it; getModel knows its version", c == 200 and r.get("id") == "/var/workflow/models/zz-api" and r.get("nodes") == "2" and r.get("deployed") != r.get("version") and r.get("same") == r.get("id") and r.get("old") == "refused", r)
i = start("/var/workflow/models/zz-api", page("zz-w12"))
check("an empty model runs from start to end", status(i) == "COMPLETED", steps(i))
check("an author may not make a model", api(ua, do="model", title="ZZ no", id="zz-no")[0] == 500 and "zz-no" not in [x["name"] for x in get(f"{WF}?op=models")])
c, r = api(ADMIN, do="deleteModel", id="/var/workflow/models/zz-api")
check("deleteModel removes it", c == 200 and "zz-api" not in [x["name"] for x in get(f"{WF}?op=models")], r)

bk = Model("zz-back", "ZZ back")
bk.step("participant", "Write", PARTICIPANT="content-authors")
bk.step("participant", "Check", PARTICIPANT="administrators", timeoutMillis="3600", timeoutHandler="com.adobe.granite.workflow.console.timeout.autoadvance.AutoAdvancer")
bk.step("process", "Bad", **auto(PROCESS="QA Fail Until Fixed"))
bk.sync()
p = page("zz-w12")
i = start(bk.id, p, ua)
c, r = api(ua, do="back", id=item(i, "Write"))
check("the first step has nowhere to go back to", c == 200 and r.get("back") == "", r)
complete(item(i, "Write"), ua)
c, r = api(ADMIN, do="item", id=item(i, "Check"))
check("a work item tells its kind and when it is due; the delegates are offered", c == 200 and r.get("type") == "PARTICIPANT" and r.get("due") == "true" and r.get("delegates") == "true" and r.get("groupOffered") == "true", r)
c, r = api(ADMIN, do="back", id=item(i, "Check"), go="true")
time.sleep(1.5)
check("a step is sent back to an earlier one, which waits again", c == 200 and r.get("back") == "Write|" and [h["title"] for h in active(i)] == ["Write"], (r, steps(i)))
c, r = api(ADMIN, do="pages")
check("paged lists: a page and the size of the whole", c == 200 and r.get("two", "").startswith("2/") and r.get("second") == "true" and r.get("items", "").startswith("1/") and r.get("models", "").startswith("2/") and r.get("all", "").split("/")[0] == r.get("all", "/").split("/")[1] == r.get("allWorkflows"), r)
# as an administrator: whom a session may hand a step to is whom its user may see, and an author sees few
c, r = api(ADMIN, do="old", id=item(i, "Write"), to="qa-wb")
time.sleep(1.5)
check("older API: getUser, getRoutes(item), evaluate, getDelegatees, delegateWorkItem(Authorizable), startWorkflow(Dictionary)", c == 200 and r.get("user") == "admin" and r.get("routes") == "1" and r.get("yes") == "true" and r.get("no") == "false" and r.get("delegatees") == "true" and r.get("given") == "qa-wb" and r.get("title") == "by dictionary" and r.get("session") == "true" and [x["assignee"] for x in active(i)] == ["qa-wb"], (r, active(i)))
wf("terminate", id=r.get("started", "")) if isinstance(r, dict) else None
complete(item(i, "Write"), ub)
complete(item(i, "Check"))
check("the flow, sent on again, reaches the failing step", [t for t, s in steps(i) if s == "FAILED"] == ["Bad"], steps(i))
prop(p + "/jcr:content", qaFixed="true", **{"qaFixed@TypeHint": "Boolean"})
check("restartWorkflow is for administrators", api(ua, do="restart", id=i)[0] == 500)
c, r = api(ADMIN, do="restart", id=i)
time.sleep(2)
check("restartWorkflow runs the failed steps again", c == 200 and status(i) == "COMPLETED", (r, steps(i)))

# ---- the timeout, last: it needs a minute
time.sleep(max(0, 85 - (time.time() - began)))
check("a step with a timeout and Auto Advancer goes on by itself", status(timed) == "COMPLETED" and qa(timed_page).get("qaStamp") == "timed", steps(timed))

cleanup()
print(f"\n{'ALL PASSED' if not bad else str(len(bad)) + ' FAILED: ' + '; '.join(bad)}")
sys.exit(1 if bad else 0)
