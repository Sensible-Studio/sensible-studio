/*
 * Sensible Studio ContextHub: the visitor's stores (profile, geolocation, surferinfo, and the configuration's own:
 * values it starts with, or the answer of a JSON address) kept in localStorage, the
 * personas the author simulates, and the resolution of targeted components: each .studio-target shows
 * the first experience whose audience rules match, else its default; a target run as an A/B test shows
 * the experience the visitor was dealt by the experiences' shares, the same one on every visit.
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
(function (global) {
    "use strict";
    if (global.StudioContextHub) { global.StudioContextHub.resolve(); return; }
    var KEY = "studio.contexthub";
    var SEEN_KEY = "studio.contexthub.seen";
    var PENDING_KEY = "studio.contexthub.pending";
    var UI_COOKIE = "studio-contexthub-ui";
    var config = global.StudioContextHubConfig || {};
    var JSON_KEY = "studio.contexthub.json.";

    /* ContextHub's configuration (Tools › ContextHub): the stores and the bar's fields. It comes with the page,
       in StudioContextHubConfig or on the script tag of a targeted component. */
    function hubConfig() {
        if (config.contexthub) { return config.contexthub; }
        var tag = document.querySelector("script[data-studio-contexthub]");
        try { return JSON.parse(tag && tag.getAttribute("data-studio-contexthub") || "null") || {}; } catch (e) { return {}; }
    }
    var hub = hubConfig();
    var storeDefs = hub.stores && hub.stores.length ? hub.stores : [{ name: "profile", type: "profile", enabled: true },
        { name: "geolocation", type: "geolocation", enabled: true }, { name: "surferinfo", type: "surferinfo", enabled: true }];
    function storeDef(name) { return storeDefs.filter(function (d) { return d.name === name; })[0]; }
    function enabled(name) { var d = storeDef(name); return !d || d.enabled !== false; }

    function surferinfo() {
        var ua = navigator.userAgent || "";
        var mobile = /Mobi|Android|iPhone|iPod/i.test(ua) && !/iPad|Tablet/i.test(ua);
        var tablet = /iPad|Tablet|Android(?!.*Mobi)/i.test(ua);
        var browser = /Edg\//.test(ua) ? "Edge" : /OPR\//.test(ua) ? "Opera" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : /Firefox\//.test(ua) ? "Firefox" : "Other";
        var os = /Windows/.test(ua) ? "Windows" : /Android/.test(ua) ? "Android" : /iPhone|iPad|iPod/.test(ua) ? "iOS" : /Mac OS/.test(ua) ? "macOS" : /Linux/.test(ua) ? "Linux" : "Other";
        var d = new Date();
        return { device: mobile ? "mobile" : tablet ? "tablet" : "desktop", browser: browser, os: os, isMobile: mobile, isTablet: tablet, isDesktop: !mobile && !tablet,
            month: d.getMonth() + 1, year: d.getFullYear(), hour: d.getHours(), weekday: ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"][d.getDay()] };
    }

    /* A JSON store's last answer, kept a while (its refresh) in this browser. */
    function cachedJson(name) {
        try { return JSON.parse(global.localStorage.getItem(JSON_KEY + name) || "null"); } catch (e) { return null; }
    }

    /* What each enabled store starts with: the values the configuration gives it, the browser and the time for
       surferinfo, the last answer of a JSON store. A store turned off stays empty. */
    function defaults() {
        var out = {};
        storeDefs.forEach(function (d) {
            if (d.enabled === false) { return; }
            var base = Object.assign({}, d.initial || {});
            if (d.type === "surferinfo") { base = Object.assign(base, surferinfo()); }
            if (d.type === "json") { var c = cachedJson(d.name); if (c && c.data && typeof c.data === "object") { base = Object.assign(base, c.data); } }
            out[d.name] = base;
        });
        return out;
    }

    /* Reads the JSON stores whose answer is missing or older than their refresh, then resolves again. */
    function fetchJsonStores() {
        storeDefs.forEach(function (d) {
            if (d.type !== "json" || d.enabled === false || !d.url) { return; }
            var c = cachedJson(d.name);
            var maxAge = (d.refresh || 60) * 60000;
            if (c && c.at && Date.now() - c.at < maxAge) { return; }
            try {
                var x = new XMLHttpRequest();
                x.open("GET", d.url, true);
                x.onload = function () {
                    if (x.status < 200 || x.status >= 300) { return; }
                    var data;
                    try { data = JSON.parse(x.responseText); } catch (e) { return; }
                    if (d.select) { String(d.select).split(".").forEach(function (k) { data = data == null ? null : data[k]; }); }
                    if (!data || typeof data !== "object" || Array.isArray(data)) { return; }
                    try { global.localStorage.setItem(JSON_KEY + d.name, JSON.stringify({ at: Date.now(), data: data })); } catch (e) { /* no storage: this page only */ }
                    stores[d.name] = Object.assign({}, data);
                    persist(stores);
                    resolve();
                };
                x.send();
            } catch (e) { /* offline, or the address refuses this site: the store keeps what it had */ }
        });
    }

    function load() {
        var saved = {};
        try { saved = JSON.parse(global.localStorage.getItem(KEY) || "{}") || {}; } catch (e) { saved = {}; }
        var base = defaults();
        var out = { _persona: saved._persona || null };
        Object.keys(base).forEach(function (store) {
            out[store] = Object.assign({}, base[store], saved[store] || {});
        });
        Object.keys(saved).forEach(function (store) {
            if (!out[store] && store.charAt(0) !== "_" && enabled(store)) { out[store] = Object.assign({}, saved[store]); }
        });
        return out;
    }

    function persist(stores) {
        try { global.localStorage.setItem(KEY, JSON.stringify(stores)); } catch (e) { /* private window: the stores live for this page only */ }
    }

    var stores = load();

    function get(store, property) {
        var s = stores[store];
        if (!s) { return undefined; }
        var v = s;
        var parts = String(property || "").split(".");
        for (var i = 0; i < parts.length && v != null; i++) { v = v[parts[i]]; }
        return v;
    }

    function num(v) { var n = parseFloat(v); return isNaN(n) ? null : n; }

    /* A store property as a segment names it: "profile/age" (AEM's form) or "profile.age". */
    function lookup(ref) {
        var parts = String(ref || "").split(/[\/.]/);
        return get(parts[0], parts.slice(1).join("."));
    }

    function text(v) { return v == null ? "" : Array.isArray(v) ? v.join(",") : String(v); }

    function compare(c) {
        var actual = lookup(c.property);
        var expected = c.otherProperty != null ? lookup(c.otherProperty) : c.value;
        var a = text(actual), b = text(expected);
        var numeric = c.dataType === "number" || c.dataType === "integer" || c.dataType === "float";
        var an = num(a), bn = num(b);
        switch (c.operator) {
            case "exists": return actual != null && a !== "";
            case "not-exists": return actual == null || a === "";
            case "not-equals": return numeric && an != null && bn != null ? an !== bn : a.toLowerCase() !== b.toLowerCase();
            case "contains": return a.toLowerCase().indexOf(b.toLowerCase()) >= 0;
            case "not-contains": return a.toLowerCase().indexOf(b.toLowerCase()) < 0;
            case "starts-with": return a.toLowerCase().indexOf(b.toLowerCase()) === 0;
            case "ends-with": return b === "" || a.toLowerCase().slice(-b.length) === b.toLowerCase();
            case "greater-than": return an != null && bn != null && an > bn;
            case "greater-than-or-equal": return an != null && bn != null && an >= bn;
            case "less-than": return an != null && bn != null && an < bn;
            case "less-than-or-equal": return an != null && bn != null && an <= bn;
            default: return numeric && an != null && bn != null ? an === bn : a.toLowerCase() === b.toLowerCase();
        }
    }

    /* An expression tree: and/or over items, or a comparison. No expression: never matches. */
    function evaluate(e) {
        if (!e || typeof e !== "object") { return false; }
        if (e.type === "and") { return !!(e.items && e.items.length) && e.items.every(evaluate); }
        if (e.type === "or") { return !!(e.items && e.items.length) && e.items.some(evaluate); }
        if (e.type === "comparison") { return compare(e); }
        return false;
    }

    /* ---- tracking: impressions and goals, counted per target once per browser session ---- */
    var queue = [];
    var flushTimer = null;
    function session(key) {
        try { return JSON.parse(global.sessionStorage.getItem(key) || "{}") || {}; } catch (e) { return {}; }
    }
    function sessionSet(key, v) { try { global.sessionStorage.setItem(key, JSON.stringify(v)); } catch (e) { /* ignore */ } }
    function send() {
        flushTimer = null;
        if (!queue.length) { return; }
        var body = JSON.stringify({ page: config.page || location.pathname.replace(/\.html.*$/, ""), events: queue.splice(0, 50) });
        try {
            if (navigator.sendBeacon) { navigator.sendBeacon("/bin/studio/targeting/track", new Blob([body], { type: "application/json" })); }
            else { var x = new XMLHttpRequest(); x.open("POST", "/bin/studio/targeting/track", true); x.setRequestHeader("Content-Type", "application/json"); x.send(body); }
        } catch (e) { /* offline: the event is lost */ }
        if (queue.length) { send(); }
    }
    function track(type, target, experience) {
        if (!target || !experience) { return; }
        var seen = session(SEEN_KEY);
        var k = type + ":" + target;
        if (seen[k] === experience) { return; }
        seen[k] = experience;
        sessionSet(SEEN_KEY, seen);
        queue.push({ type: type, target: target, experience: experience });
        if (!flushTimer) { flushTimer = setTimeout(send, 400); }
    }
    function tracked(el) { var v = el.getAttribute("data-studio-track"); return v === "true" || v === ""; }

    /* The goal of a target: click (a selector, or any click in the shown experience), pageview (a page
       path, converted when the visitor gets there), custom (StudioContextHub.convert(name)). */
    function armGoal(target, chosen) {
        var type = target.getAttribute("data-studio-goal-type") || "none";
        var value = target.getAttribute("data-studio-goal-value") || "";
        var path = target.getAttribute("data-studio-target");
        var exp = chosen.getAttribute("data-studio-experience");
        if (type === "click") {
            var scope = chosen;
            var handler = function (ev) {
                if (!value || (ev.target.closest && ev.target.closest(value))) { track("conversion", path, exp); }
            };
            if (value && !chosen.querySelector(value)) { scope = document; }
            if (!target.__studioGoal) { scope.addEventListener("click", handler, true); target.__studioGoal = true; }
        } else if (type === "pageview" && value) {
            var pending = session(PENDING_KEY);
            pending[path] = { page: value.replace(/\.html$/, ""), experience: exp };
            sessionSet(PENDING_KEY, pending);
        } else if (type === "custom" && value) {
            customGoals[value] = customGoals[value] || [];
            customGoals[value].push({ target: path, experience: exp });
        }
    }
    var customGoals = {};
    function pageviewGoals() {
        var pending = session(PENDING_KEY);
        var here = (config.page || location.pathname).replace(/\.html.*$/, "");
        var rest = {};
        Object.keys(pending).forEach(function (t) {
            var p = pending[t];
            if (p && (p.page === here || here.indexOf(p.page + "/") === 0)) { track("conversion", t, p.experience); } else { rest[t] = p; }
        });
        sessionSet(PENDING_KEY, rest);
    }

    /* ---- page-level audience redirects (the page's "Audience redirects" rules) ---- */
    function redirect() {
        var rules = config.redirects || [];
        if (!rules.length || /[?&]studio-noredirect\b/.test(location.search)) { return false; }
        var best = null, boost = -Infinity;
        rules.forEach(function (r) { if (evaluate(r.expression) && (r.boost || 0) > boost) { best = r; boost = r.boost || 0; } });
        if (!best || !best.target) { return false; }
        var here = (config.page || location.pathname).replace(/\.html.*$/, "");
        var to = String(best.target).replace(/\.html$/, "");
        if (to === here) { return false; }
        var hops = session("studio.contexthub.hops");
        var n = (hops[to] || 0) + 1;
        if (n > 3) { return false; }
        hops[to] = n;
        sessionSet("studio.contexthub.hops", hops);
        location.replace(to + ".html" + location.search + location.hash);
        return true;
    }

    /* ---- A/B tests: a target whose mode is "abtest" splits the visitors by its experiences' shares ---- */
    var AB_KEY = "studio.contexthub.ab";
    function dealtAll() { try { return JSON.parse(global.localStorage.getItem(AB_KEY) || "{}") || {}; } catch (e) { return {}; } }
    var dealtHere = {};
    /* The experience this visitor was dealt for the target: drawn once by the shares, then the same one on
       every visit (kept in this browser). An experience that is gone, or whose share is now 0, is drawn again. */
    function dealt(path, exps, random) {
        var shares = [], total = 0;
        for (var i = 0; i < exps.length; i++) {
            var w = parseFloat(exps[i].getAttribute("data-studio-share") || "0") || 0;
            if (w > 0) { shares.push({ el: exps[i], name: exps[i].getAttribute("data-studio-experience"), w: w }); total += w; }
        }
        if (!shares.length) { return null; }
        var all = dealtAll();
        var had = dealtHere[path] || all[path];
        for (var j = 0; j < shares.length; j++) { if (shares[j].name === had) { return shares[j].el; } }
        var r = (random || Math.random)() * total, pick = shares[shares.length - 1];
        for (var k = 0, acc = 0; k < shares.length; k++) { acc += shares[k].w; if (r < acc) { pick = shares[k]; break; } }
        dealtHere[path] = pick.name;
        all[path] = pick.name;
        try { global.localStorage.setItem(AB_KEY, JSON.stringify(all)); } catch (e) { /* private window: dealt for this page only */ }
        return pick.el;
    }

    function resolve() {
        if (redirect()) { return; }
        var targets = document.querySelectorAll(".studio-target");
        for (var i = 0; i < targets.length; i++) {
            var exps = targets[i].querySelectorAll(":scope > .studio-target__experience");
            var chosen = null, dflt = null, best = -Infinity;
            var ab = targets[i].getAttribute("data-studio-mode") === "abtest";
            if (ab) { chosen = dealt(targets[i].getAttribute("data-studio-target"), exps); }
            for (var j = 0; j < exps.length; j++) {
                var e = exps[j];
                if (e.getAttribute("data-studio-experience") === "default") { dflt = e; continue; }
                if (ab) { continue; }
                var expr = null;
                try { expr = JSON.parse(e.getAttribute("data-studio-expression") || "null"); } catch (err) { expr = null; }
                var boost = parseFloat(e.getAttribute("data-studio-boost") || "0") || 0;
                // the matching audience with the highest boost wins; the first one on a tie
                if (evaluate(expr) && boost > best) { chosen = e; best = boost; }
            }
            if (!chosen) { chosen = dflt; }
            for (var k = 0; k < exps.length; k++) { exps[k].hidden = exps[k] !== chosen; }
            targets[i].setAttribute("data-studio-resolved", chosen ? chosen.getAttribute("data-studio-experience") : "");
            if (chosen && tracked(targets[i])) {
                track("impression", targets[i].getAttribute("data-studio-target"), chosen.getAttribute("data-studio-experience"));
                armGoal(targets[i], chosen);
            }
        }
        pageviewGoals();
        document.dispatchEvent(new CustomEvent("studio-contexthub-resolved", { detail: { stores: stores } }));
    }

    /* ---- ContextHub's own bar in the page (?contexthub=ui, kept by a cookie) ---- */
    /* The bar's fields: the configuration's (AEM's UI modules), else the usual ones; only of enabled stores. */
    var FIELDS = (hub.modules && hub.modules.length ? hub.modules : [{ store: "profile", property: "age", title: "Age", type: "number" },
        { store: "profile", property: "gender", title: "Gender", type: "text" }, { store: "geolocation", property: "country", title: "Country", type: "text" },
        { store: "geolocation", property: "city", title: "City", type: "text" }, { store: "surferinfo", property: "device", title: "Device", type: "text" },
        { store: "surferinfo", property: "month", title: "Month", type: "number" }]).filter(function (f) { return enabled(f.store); });
    function esc(v) { return String(v == null ? "" : v).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" }[c]; }); }

    /* ---- AEM's UI modes (Persona, Device…) of the configuration: their modules, shown in the bar ---- */
    var modes = [];
    var segments = null;
    var MODE_KEY = "studio.contexthub.mode";
    /* The devices of the Device Emulator module: what the audiences see of the visitor's device (surferinfo),
       the emulators store AEM's modules read, and the size the page is shown at (deviceFrame). */
    var DEVICES = [{ id: "native", title: "This browser" },
        { id: "iphone", title: "iPhone", width: 390, height: 844, platform: "iOS", platformVersion: "17", device: "mobile" },
        { id: "android", title: "Android phone", width: 412, height: 915, platform: "Android", platformVersion: "14", device: "mobile" },
        { id: "ipad", title: "iPad", width: 820, height: 1180, platform: "iOS", platformVersion: "17", device: "tablet" },
        { id: "desktop", title: "Desktop", width: 1920, height: 1080, platform: "Windows", platformVersion: "11", device: "desktop" }];
    function currentDevice() {
        var d = stores.emulators && stores.emulators.currentDevice;
        if (d && d.id) { return d; }
        var s = surferinfo();
        return { id: "native", title: "This browser", width: global.innerWidth || 0, height: global.innerHeight || 0, platform: s.os, platformVersion: "",
            orientation: (global.innerWidth || 0) >= (global.innerHeight || 0) ? "landscape" : "portrait" };
    }
    /* The page at the emulated device's size: while the bar is shown and a device is emulated, the page is
       loaded again in a frame of that size over this one (its own viewport, so its media queries answer),
       above the bar. The framed page shows no bar; it reads the same stores, so it resolves the same way. */
    var FRAME_NAME = "studio-contexthub-device";
    var framed = false;
    try { framed = global.name === FRAME_NAME && global.parent !== global; } catch (e) { framed = false; }
    var rerender = null;
    function viewDocument() {
        var f = document.querySelector("#studio-contexthub-device iframe");
        try { return f && f.contentDocument && f.contentDocument.readyState !== "loading" ? f.contentDocument : document; } catch (e) { return document; }
    }
    function deviceFrame() {
        if (framed || !config.ui || global.parent !== global || !document.body) { return; }
        var d = stores.emulators && stores.emulators.currentDevice;
        var host = document.getElementById("studio-contexthub-device");
        var barEl = document.getElementById("studio-contexthub-bar");
        if (!barEl || !d || !d.id || d.id === "native" || !d.width) {
            if (host) { host.parentNode.removeChild(host); document.documentElement.style.overflow = ""; if (rerender) { rerender(); } }
            return;
        }
        var frame;
        if (!host) {
            host = document.createElement("div"); host.id = "studio-contexthub-device";
            host.style.cssText = "position:fixed;left:0;right:0;top:0;z-index:2147482999;display:flex;overflow:auto;padding:16px;box-sizing:border-box;background:#3a3a3a";
            frame = document.createElement("iframe"); frame.name = FRAME_NAME;
            frame.style.cssText = "flex:none;margin:auto;border:0;background:#fff;box-shadow:0 2px 16px rgba(0,0,0,.5)";
            frame.addEventListener("load", function () {
                try { frame.contentDocument.addEventListener("studio-contexthub-resolved", function () { if (rerender) { rerender(); } }); } catch (e) { /* another origin */ }
                if (rerender) { rerender(); }
            });
            frame.src = location.href;
            host.appendChild(frame);
            document.body.appendChild(host);
            document.documentElement.style.overflow = "hidden";
        } else { frame = host.querySelector("iframe"); }
        host.style.bottom = barEl.offsetHeight + "px";
        frame.title = d.title + " " + d.width + " x " + d.height;
        frame.style.width = d.width + "px"; frame.style.height = d.height + "px";
        host.setAttribute("data-device", d.id); host.setAttribute("data-size", d.width + "x" + d.height);
    }
    function emulate(id, orientation) {
        var d = DEVICES.filter(function (x) { return x.id === id; })[0];
        if (!d || d.id === "native") {
            delete stores.emulators;
            if (stores.surferinfo) { ["device", "os", "isMobile", "isTablet", "isDesktop"].forEach(function (k) { delete stores.surferinfo[k]; }); }
            persist(stores);
            stores = load();
            resolve();
            deviceFrame();
            return;
        }
        var land = d.device === "desktop" || orientation === "landscape";
        stores.emulators = { currentDevice: { id: d.id, title: d.title, width: land ? Math.max(d.width, d.height) : d.width, height: land ? Math.min(d.width, d.height) : d.height,
            platform: d.platform, platformVersion: d.platformVersion, orientation: land ? "landscape" : "portrait" } };
        stores.surferinfo = Object.assign({}, stores.surferinfo || {}, { device: d.device, os: d.platform, isMobile: d.device === "mobile", isTablet: d.device === "tablet", isDesktop: d.device === "desktop" });
        changed();
        deviceFrame();
    }
    /* A store's value for a module: the emulators store always has a current device (this browser's when none is emulated). */
    function moduleValue(store, property) {
        if (store === "emulators" && !(stores.emulators && stores.emulators.currentDevice)) {
            var v = { currentDevice: currentDevice() };
            String(property || "").split(".").forEach(function (k) { if (k) { v = v == null ? undefined : v[k]; } });
            return v;
        }
        return get(store, property);
    }
    /* The lines AEM's generic module (contexthub.base) shows: its template with {{alias.property}} read from the
       stores of its storeMapping and {{i18n "Text"}} as the text; only the text is kept, never the markup. */
    function templateLines(template, mapping) {
        var filled = String(template || "")
            .replace(/\{\{\{?\s*i18n\s+"([^"]*)"\s*\}?\}\}/g, function (m, t) { return esc(t); })
            .replace(/\{\{\{?\s*([A-Za-z0-9_.\-]+)\s*\}?\}\}/g, function (m, ref) {
                var parts = ref.split(".");
                var store = (mapping || {})[parts[0]];
                return store ? esc(text(moduleValue(store, parts.slice(1).join(".")))) : "";
            });
        var lines = [];
        try {
            var doc = new DOMParser().parseFromString(filled, "text/html");
            var blocks = doc.body.children.length ? doc.body.children : [doc.body];
            for (var i = 0; i < blocks.length; i++) {
                if (/^(SCRIPT|STYLE)$/.test(blocks[i].tagName || "")) { continue; }
                var t = (blocks[i].textContent || "").replace(/\s+/g, " ").trim();
                if (t) { lines.push(t); }
            }
        } catch (e) {
            filled.split(/<\/(?:p|div|li)>/i).forEach(function (part) { var t = part.replace(/<[^>]*>/g, " ").replace(/&amp;/g, "&").replace(/\s+/g, " ").trim(); if (t) { lines.push(t); } });
        }
        return lines;
    }
    /* The tags of AEM's tagcloud store ({tags: {name: count}} or a list), the most seen first, ten at most. */
    function tagNames(store) {
        var tags = store && (store.tags || store);
        if (!tags || typeof tags !== "object") { return []; }
        var names = Array.isArray(tags) ? tags.map(function (t) { return t && typeof t === "object" ? t.name || t.id : t; })
            : Object.keys(tags).sort(function (a, b) { return (parseFloat(tags[b]) || 0) - (parseFloat(tags[a]) || 0); });
        return names.filter(Boolean).map(String).slice(0, 10);
    }
    /* The audiences of the page's configuration that match the visitor now (AEM's Resolved Segments). */
    function resolvedSegments() {
        return (segments || []).filter(function (s) { return evaluate(s.expression); }).map(function (s) { return s.title || s.name; });
    }
    function activeMode() { try { return global.sessionStorage.getItem(MODE_KEY) || ""; } catch (e) { return ""; } }

    function bar() {
        if (!config.ui || framed || document.getElementById("studio-contexthub-bar")) { return; }
        var css = "#studio-contexthub-bar{position:fixed;left:0;right:0;bottom:0;z-index:2147483000;display:flex;flex-wrap:wrap;gap:8px 16px;align-items:center;" +
            "padding:8px 16px;background:#2c2c2c;color:#f5f5f5;font:13px/1.4 -apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;box-shadow:0 -1px 6px rgba(0,0,0,.4)}" +
            "#studio-contexthub-bar label{display:inline-flex;align-items:center;gap:6px}#studio-contexthub-bar select,#studio-contexthub-bar input{font:inherit;color:#111;background:#fff;border:1px solid #777;border-radius:3px;padding:2px 6px;min-width:70px}" +
            "#studio-contexthub-bar input{width:90px}#studio-contexthub-bar .studio-ch-title{font-weight:600;margin-right:8px}#studio-contexthub-bar .studio-ch-resolved{opacity:.75;margin-left:auto}" +
            "#studio-contexthub-bar .studio-ch-modes{display:inline-flex;gap:2px;margin-right:4px}#studio-contexthub-bar .studio-ch-modes button{border-color:transparent;border-radius:3px}" +
            "#studio-contexthub-bar .studio-ch-modes button[aria-selected=true]{background:#555;border-color:#888}#studio-contexthub-bar .studio-ch-module{display:inline-flex;gap:6px;align-items:baseline;padding:2px 8px;border:1px solid #555;border-radius:3px}" +
            "#studio-contexthub-bar .studio-ch-module b{font-weight:600}#studio-contexthub-bar .studio-ch-off{opacity:.6}" +
            "#studio-contexthub-bar button{font:inherit;color:#f5f5f5;background:transparent;border:1px solid #888;border-radius:3px;padding:2px 10px;cursor:pointer}#studio-contexthub-bar button:hover{background:#444}body{padding-bottom:52px}";
        var style = document.createElement("style"); style.textContent = css; document.head.appendChild(style);
        var el = document.createElement("div"); el.id = "studio-contexthub-bar";
        el.setAttribute("role", "region"); el.setAttribute("aria-label", "ContextHub");
        function personaSelect(label) {
            var h = "<label>" + esc(label) + ' <select data-ch-persona><option value="">(none)</option>';
            (personas || []).forEach(function (p) { h += '<option value="' + esc(p.id) + '"' + (stores._persona === p.id ? " selected" : "") + ">" + esc(p.title || p.id) + "</option>"; });
            return h + "</select></label>";
        }
        function input(title, store, prop) {
            return "<label>" + esc(title) + ' <input type="text" data-ch-store="' + esc(store) + '" data-ch-prop="' + esc(prop) + '" value="' + esc(get(store, prop)) + '"></label>';
        }
        function chip(it, title, value) {
            return '<span class="studio-ch-module" data-ch-module="' + esc(it.name) + '"><b>' + esc(title) + "</b> " + esc(value) + "</span>";
        }
        /* One module of an AEM mode, by its type. */
        function moduleHtml(it) {
            var dev = currentDevice();
            var h;
            switch (it.type) {
                case "granite.profile": return personaSelect(it.title || "Persona");
                case "contexthub.location": return enabled("geolocation") ? input("Country", "geolocation", "country") + input("City", "geolocation", "city") : "";
                case "granite.resolvedsegments":
                    var names = segments === null ? ["…"] : resolvedSegments();
                    return '<span class="studio-ch-module" data-ch-module="' + esc(it.name) + '"><b>' + esc(it.title || "Resolved Segments") + "</b> " + esc(names.length ? names.join(", ") : "none") + "</span>";
                case "contexthub.device":
                    h = "<label>" + esc(it.title || "Device") + " <select data-ch-device>";
                    DEVICES.forEach(function (d) { h += '<option value="' + d.id + '"' + (dev.id === d.id ? " selected" : "") + ">" + esc(d.title) + "</option>"; });
                    return h + "</select></label>";
                case "contexthub.screen-orientation":
                    var fixed = dev.id === "native" || dev.id === "desktop";
                    h = "<label>" + esc(it.title || "Orientation") + " <select data-ch-orientation" + (fixed ? " disabled" : "") + ">";
                    ["portrait", "landscape"].forEach(function (o) { h += '<option value="' + o + '"' + (dev.orientation === o ? " selected" : "") + ">" + o + "</option>"; });
                    return h + "</select></label>";
                case "contexthub.browserinfo":
                    return chip(it, it.title || "Browser", [text(get("surferinfo", "browser")), text(get("surferinfo", "os"))].filter(Boolean).join(" · "));
                case "contexthub.datetime":
                    return chip(it, it.title || "Date & Time", new Date().toLocaleString(undefined, { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }));
                case "contexthub.tagcloud":
                    return chip(it, it.title || "Tags", tagNames(stores.tagcloud).join(", ") || "none yet");
                case "contexthub.base":
                    var lines = it.template ? templateLines(it.template, it.storeMapping) : [];
                    if (lines.length) {
                        return '<span class="studio-ch-module" data-ch-module="' + esc(it.name) + '"><b>' + esc(lines[0]) + "</b> " + esc(lines.slice(1).join(" · ")) + "</span>";
                    }
                    return '<span class="studio-ch-module" data-ch-module="' + esc(it.name) + '"><b>' + esc(it.title) + "</b></span>";
                default:
                    return '<span class="studio-ch-module studio-ch-off" data-ch-module="' + esc(it.name) + '" title="' + esc(it.type) + ': not shown in this bar">' + esc(it.title) + "</span>";
            }
        }
        function render() {
            var h = '<span class="studio-ch-title">ContextHub</span>';
            var mode = modes.filter(function (m) { return m.name === activeMode(); })[0];
            if (modes.length) {
                h += '<span class="studio-ch-modes" role="tablist" aria-label="Modes">';
                [{ name: "", title: "Fields" }].concat(modes).forEach(function (m) {
                    h += '<button type="button" role="tab" data-ch-mode="' + esc(m.name) + '" aria-selected="' + ((mode ? mode.name : "") === m.name ? "true" : "false") + '">' + esc(m.title || m.name) + "</button>";
                });
                h += "</span>";
            }
            if (mode) { (mode.items || []).forEach(function (it) { if (it.enabled !== false) { h += moduleHtml(it); } }); }
            else { h += personaSelect("Persona"); }
            (mode ? [] : FIELDS).forEach(function (f) {
                var value = get(f.store, f.property);
                var attrs = ' data-ch-store="' + esc(f.store) + '" data-ch-prop="' + esc(f.property) + '"';
                if (f.type === "select") {
                    h += "<label>" + esc(f.title) + " <select" + attrs + '><option value=""></option>';
                    (f.options || []).forEach(function (o) { h += '<option value="' + esc(o) + '"' + (String(value) === String(o) ? " selected" : "") + ">" + esc(o) + "</option>"; });
                    h += "</select></label>";
                } else {
                    h += "<label>" + esc(f.title) + ' <input type="' + (f.type === "number" ? "number" : "text") + '"' + attrs + ' value="' + esc(value) + '"></label>';
                }
            });
            var shown = [];
            viewDocument().querySelectorAll(".studio-target[data-studio-resolved]").forEach(function (t) { shown.push(t.getAttribute("data-studio-resolved") || "default"); });
            h += '<span class="studio-ch-resolved">' + (shown.length ? shown.length + " targeted: " + esc(shown.join(", ")) : "no targeted components") + "</span>";
            h += '<button type="button" data-ch-reset>Reset</button><button type="button" data-ch-close title="Hide ContextHub">×</button>';
            el.innerHTML = h;
        }
        var personas = null;
        render();
        rerender = function () { render(); var host = document.getElementById("studio-contexthub-device"); if (host) { host.style.bottom = el.offsetHeight + "px"; } };
        document.body.appendChild(el);
        el.addEventListener("change", function (ev) {
            var t = ev.target;
            if (t.hasAttribute("data-ch-persona")) {
                var p = (personas || []).filter(function (x) { return x.id === t.value; })[0];
                api.setPersona(p ? p.id : null, p ? p.stores : {});
            } else if (t.hasAttribute("data-ch-device")) {
                emulate(t.value, currentDevice().orientation);
            } else if (t.hasAttribute("data-ch-orientation")) {
                emulate(currentDevice().id, t.value);
            } else if (t.hasAttribute("data-ch-store")) {
                api.set(t.getAttribute("data-ch-store"), t.getAttribute("data-ch-prop"), t.type === "number" && t.value !== "" ? parseFloat(t.value) : t.value);
            }
        });
        el.addEventListener("click", function (ev) {
            if (ev.target.hasAttribute("data-ch-mode")) {
                try { global.sessionStorage.setItem(MODE_KEY, ev.target.getAttribute("data-ch-mode")); } catch (e) { /* this page only */ }
                render();
            }
            if (ev.target.hasAttribute("data-ch-reset")) { api.reset(); }
            if (ev.target.hasAttribute("data-ch-close")) {
                document.cookie = UI_COOKIE + "=0;path=/;max-age=0";
                el.remove();
                deviceFrame();
                var u = location.href.replace(/([?&])contexthub=[^&]*&?/, "$1").replace(/[?&]$/, "");
                history.replaceState(null, "", u);
            }
        });
        document.addEventListener("studio-contexthub-resolved", render);
        document.cookie = UI_COOKIE + "=1;path=/;max-age=" + (3600 * 8);
        /* the configuration's AEM modes and, for Resolved Segments, the audiences: asked for only when the bar is shown */
        var page = (config.page || location.pathname).replace(/\.html.*$/, "");
        function ask(op, done) {
            try {
                var q = new XMLHttpRequest();
                q.open("GET", "/bin/studio/targeting.json?op=" + op + "&path=" + encodeURIComponent(page), true);
                q.onload = function () { if (q.status >= 200 && q.status < 300) { try { done(JSON.parse(q.responseText)); } catch (e) { /* not JSON */ } } };
                q.send();
            } catch (e) { /* the bar keeps its fields */ }
        }
        ask("contexthub", function (r) {
            modes = (r.modes || []).filter(function (m) { return m.items && m.items.length; });
            render();
            var wanted = modes.some(function (m) { return m.items.some(function (it) { return it.type === "granite.resolvedsegments"; }); });
            if (wanted) { ask("segments", function (s) { segments = s.segments || []; render(); }); }
        });
        if (config.personas) {
            try {
                var x = new XMLHttpRequest();
                x.open("GET", config.personas, true);
                x.onload = function () { try { var r = JSON.parse(x.responseText); personas = r.personas || r || []; render(); } catch (e) { /* no personas */ } };
                x.send();
            } catch (e) { /* no personas */ }
        }
    }

    function changed() { persist(stores); resolve(); deviceFrame(); }

    var api = global.StudioContextHub = {
        convert: function (name) { (customGoals[name] || []).forEach(function (g) { track("conversion", g.target, g.experience); }); },
        showUI: function () { config.ui = true; bar(); },
        stores: function () { return stores; },
        get: get,
        set: function (store, property, value) {
            stores[store] = stores[store] || {};
            if (value === undefined || value === null || value === "") { delete stores[store][property]; } else { stores[store][property] = value; }
            changed();
        },
        setStore: function (store, values) { stores[store] = Object.assign({}, values || {}); changed(); },
        setPersona: function (id, personaStores) {
            var fresh = defaults();
            stores = { _persona: id || null };
            Object.keys(fresh).forEach(function (s) { stores[s] = fresh[s]; });
            Object.keys(personaStores || {}).forEach(function (s) { stores[s] = Object.assign({}, stores[s] || {}, personaStores[s]); });
            changed();
        },
        persona: function () { return stores._persona; },
        reset: function () { stores = defaults(); stores._persona = null; dealtHere = {}; try { global.localStorage.removeItem(KEY); global.localStorage.removeItem(AB_KEY); } catch (e) { /* ignore */ } resolve(); deviceFrame(); },
        /* A/B tests: which experience this visitor was dealt, per target; show(path, name) puts the visitor in one (to look at it). */
        dealt: function () { return Object.assign({}, dealtAll(), dealtHere); },
        show: function (path, name) { dealtHere[path] = name; var all = dealtAll(); all[path] = name; try { global.localStorage.setItem(AB_KEY, JSON.stringify(all)); } catch (e) { /* ignore */ } resolve(); },
        _deal: dealt,
        /* the Device Emulator module: what the audiences see of the device; "native" is this browser again */
        emulate: emulate,
        device: currentDevice,
        _template: templateLines,
        reload: function () { stores = load(); resolve(); },
        evaluate: evaluate,
        resolve: resolve
    };

    global.addEventListener("storage", function (ev) { if (ev.key === KEY) { stores = load(); resolve(); deviceFrame(); } });
    global.addEventListener("studio-contexthub-changed", function () { stores = load(); resolve(); deviceFrame(); });
    function boot() { resolve(); bar(); deviceFrame(); fetchJsonStores(); }
    global.addEventListener("pagehide", send);
    if (document.readyState === "loading") { document.addEventListener("DOMContentLoaded", boot); } else { boot(); }
})(window);
