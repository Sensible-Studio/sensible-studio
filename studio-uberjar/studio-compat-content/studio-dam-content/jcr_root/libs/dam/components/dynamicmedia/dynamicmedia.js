/*
 * Sensible Studio Dynamic Media component. From the component's properties (fileReference, alt, linkURL, caption,
 * and a smart crop per breakpoint: crop_<breakpoint>, optionally preset_<breakpoint>) and its content policy
 * (breakpoints/item0..n with name, min, max; preset, webp, widths; defaults without a policy) it
 * builds the /is/image URLs: one <source media="(min-width) and (max-width)"> per breakpoint with the crop
 * chosen for it, each with a srcset of widths the browser picks from, and the <img> for the largest.
 * Smart crops come from the asset (jcr:content/smartcrops); nothing is rendered twice. The asset's image map
 * (jcr:content/metadata/imageMap) becomes links placed in per cent over the picture, one set per breakpoint,
 * each projected into that breakpoint's crop and shown by the same media query as its <source>.
 */
"use strict";
use(function () {
    var DEFAULT_WIDTHS = [320, 480, 768, 1024, 1366, 1600, 1920];
    var DEFAULT_BREAKPOINTS = [
        { name: "Small", min: 0, max: 767 },
        { name: "Medium", min: 768, max: 1023 },
        { name: "Large", min: 1024, max: 1279 },
        { name: "X-Large", min: 1280, max: 1919 },
        { name: "XX-Large", min: 1920, max: 0 }
    ];
    var props = properties; // the component's values (Use API global)

    // resources reached from here are JS wrappers (`properties`), not Java resources (`getValueMap`)
    function prop(res, name, fallback) {
        var map = res.properties ? res.properties : (res.getValueMap ? res.getValueMap() : null);
        var v = map === null ? null : (typeof map.get === "function" ? map.get(name) : map[name]);
        return v === null || v === undefined ? fallback : String(v);
    }
    // a Java String from the ValueMap is an object in this engine: even "" is truthy, so coerce before testing
    function text(name, fallback) {
        var v = props.get(name, fallback);
        v = v === null || v === undefined ? "" : String(v);
        return v === "" ? (fallback === undefined ? "" : String(fallback)) : v;
    }
    // the property suffix of a breakpoint: "X-Large" -> "x-large"
    function slug(name) {
        return String(name).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
    }
    function parseWidths(s) {
        var out = [];
        if (!s) {
            return out;
        }
        var parts = String(s).split(",");
        for (var i = 0; i < parts.length; i++) {
            var n = parseInt(parts[i].trim(), 10);
            if (n > 0 && n <= 4096) {
                out.push(n);
            }
        }
        out.sort(function (a, b) { return a - b; });
        return out;
    }

    function hash(str) {
        var h = 5381;
        for (var i = 0; i < str.length; i++) {
            h = ((h * 33) ^ str.charCodeAt(i)) >>> 0;
        }
        return h.toString(36);
    }
    function pct(v) {
        return (Math.round(v * 100000) / 1000) + "%";
    }
    // where an area's link goes: a page path gets .html; anything that is not a path or an http(s), mailto or tel link is dropped
    function linkOf(href) {
        var h = String(href || "").trim();
        if (!h) {
            return "";
        }
        if (h.charAt(0) === "/" && h.charAt(1) !== "/") {
            var last = h.substring(h.lastIndexOf("/") + 1);
            if (h.indexOf("/content/") === 0 && h.indexOf("/content/dam/") !== 0 && last.indexOf(".") < 0 && last.indexOf("#") < 0 && last.indexOf("?") < 0) {
                return h + ".html";
            }
            return h;
        }
        return /^(https?:\/\/|mailto:|tel:|#)/i.test(h) ? h : "";
    }
    // jcr:content/metadata/imageMap (where AEM has it), or the jcr:content/imageMap written before it moved
    function imageMapOf(assetRes) {
        var places = ["jcr:content/metadata", "jcr:content"];
        for (var i = 0; i < places.length; i++) {
            var r = assetRes.getChild(places[i]);
            var v = r === null ? "" : prop(r, "imageMap", "");
            if (v && v.trim()) {
                return v;
            }
        }
        return "";
    }
    // [rect(x1,y1,x2,y2)"href"|"target"|"alt"|(rx1,ry1,rx2,ry2)][circle(cx,cy,r)"…"|"…"|"…"|(rcx,rcy,rr)][poly(…)…|(…)]
    // Only the relative coordinates (fractions of the image; a circle's radius of its width) are used.
    function parseMap(map) {
        var list = [];
        if (!map) {
            return list;
        }
        var re = /\[(rect|circle|poly)\(([^)]*)\)"([^"]*)"\|"([^"]*)"\|"([^"]*)"\|\(([^)]*)\)\]/g;
        var m;
        while ((m = re.exec(map)) !== null && list.length < 100) {
            var nums = m[6].split(",");
            var rel = [];
            var ok = true;
            for (var i = 0; i < nums.length; i++) {
                var n = parseFloat(nums[i]);
                if (isNaN(n)) {
                    ok = false;
                }
                rel.push(n);
            }
            var href = linkOf(m[3]);
            if (!ok || !href) {
                continue;
            }
            var target = /^_(self|blank|parent|top)$/.test(m[4]) ? m[4] : "";
            list.push({ shape: m[1], rel: rel, href: href, target: target, alt: m[5], card: "" });
        }
        return list;
    }
    // the areas as they fall inside a crop (fractions l, t, fw, fh of the image; no crop: the whole image)
    function project(list, c) {
        var l = c ? c.l : 0, t = c ? c.t : 0, fw = c ? c.fw : 1, fh = c ? c.fh : 1;
        var ratio = imageW && imageH ? imageW / imageH : 1; // a circle's radius is a fraction of the width
        var placed = [];
        for (var i = 0; i < list.length; i++) {
            var a = list[i];
            var style = null;
            if (a.shape === "rect" && a.rel.length === 4) {
                var x1 = Math.max(0, (Math.min(a.rel[0], a.rel[2]) - l) / fw), x2 = Math.min(1, (Math.max(a.rel[0], a.rel[2]) - l) / fw);
                var y1 = Math.max(0, (Math.min(a.rel[1], a.rel[3]) - t) / fh), y2 = Math.min(1, (Math.max(a.rel[1], a.rel[3]) - t) / fh);
                if (x2 - x1 > 0.005 && y2 - y1 > 0.005) {
                    style = "left:" + pct(x1) + ";top:" + pct(y1) + ";width:" + pct(x2 - x1) + ";height:" + pct(y2 - y1);
                }
            } else if (a.shape === "circle" && a.rel.length === 3) {
                var cx = (a.rel[0] - l) / fw, cy = (a.rel[1] - t) / fh;
                var rx = a.rel[2] / fw, ry = a.rel[2] * ratio / fh;
                if (cx + rx > 0 && cx - rx < 1 && cy + ry > 0 && cy - ry < 1) {
                    style = "left:" + pct(cx) + ";top:" + pct(cy) + ";width:" + pct(2 * rx) + ";aspect-ratio:1/1;border-radius:50%;transform:translate(-50%,-50%)";
                }
            } else if (a.shape === "poly" && a.rel.length >= 6 && a.rel.length % 2 === 0) {
                var pts = [];
                var inside = false;
                for (var k = 0; k < a.rel.length; k += 2) {
                    var px = (a.rel[k] - l) / fw, py = (a.rel[k + 1] - t) / fh;
                    if (px > 0 && px < 1 && py > 0 && py < 1) {
                        inside = true;
                    }
                    pts.push(pct(px) + " " + pct(py));
                }
                if (inside) {
                    style = "top:0;right:0;bottom:0;left:0;clip-path:polygon(" + pts.join(",") + ")";
                }
            }
            if (style !== null) {
                placed.push({ style: style, href: a.href, target: a.target, alt: a.alt, rel: a.target === "_blank" ? "noopener" : "", card: a.card });
            }
        }
        return placed;
    }

    function keysOf(res) {
        var map = res.properties ? res.properties : (res.getValueMap ? res.getValueMap() : null);
        var keys = [];
        if (map === null) {
            return keys;
        }
        if (typeof map.keySet === "function") {
            var it = map.keySet().iterator();
            while (it.hasNext()) {
                keys.push(String(it.next()));
            }
        } else {
            for (var k in map) {
                keys.push(String(k));
            }
        }
        return keys;
    }
    // The elements of a record's model in the model's order: [{ name, label }]. Empty when the model
    // cannot be read (a visitor of the published site may not see /conf): the card then goes by the data alone.
    function modelFields(rec, resolver) {
        var data = rec.getChild("jcr:content/data");
        var modelPath = data === null ? "" : prop(data, "cq:model", "");
        var out = [];
        if (!modelPath) {
            return out;
        }
        var items = resolver.getResource(modelPath + "/jcr:content/model/cq:dialog/content/items");
        if (items === null) {
            return out;
        }
        var kids = items.getChildren();
        var list = [];
        if (kids && typeof kids.length === "number") {
            list = kids;
        } else if (kids && typeof kids.iterator === "function") {
            var it = kids.iterator();
            while (it.hasNext()) {
                list.push(it.next());
            }
        }
        for (var i = 0; i < list.length; i++) {
            var name = prop(list[i], "name", "");
            if (name) {
                out.push({ name: name, label: prop(list[i], "fieldLabel", "") });
            }
        }
        return out;
    }
    // A Java array must be told apart without touching a member it does not have: this engine throws on that.
    function isList(v) {
        if (typeof v === "string") {
            return false;
        }
        var kind = Object.prototype.toString.call(v);
        return kind === "[object Array]" || kind === "[object JavaArray]";
    }
    // a multi-value comes as a Java array or a JS array: its values, joined
    function valueOf(res, name) {
        var map = res.properties ? res.properties : (res.getValueMap ? res.getValueMap() : null);
        var v = map === null ? null : (typeof map.get === "function" ? map.get(name) : map[name]);
        if (v === null || v === undefined) {
            return "";
        }
        if (isList(v)) {
            var parts = [];
            for (var i = 0; i < v.length; i++) {
                var one = String(v[i]).trim();
                if (one) {
                    parts.push(one);
                }
            }
            return parts.join(", ");
        }
        // a whole number kept as a decimal reads as "5000.0": show it as it was typed
        return String(v).trim().replace(/^(-?\d+)\.0$/, "$1");
    }
    var IMAGE_VALUE = /^\/content\/dam\/.+\.(jpe?g|png|webp|gif)$/i;
    function cardImage(v) {
        var r = v.substring("/content/dam".length);
        return "/is/image" + r.substring(0, r.lastIndexOf(".")) + "?wid=480&fmt=webp";
    }
    // "tripLength" -> "Trip length", for a field whose model gives no label
    function labelOf(name) {
        var words = name.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_\-]+/g, " ").trim().toLowerCase();
        return words.charAt(0).toUpperCase() + words.substring(1);
    }
    // A record (content fragment) an area points to, as the card shown over the image: its title, description,
    // first picture and a few of its fields. null when the path is not a record the visitor may read.
    // `chosen` (field names, in the order to show them) picks the fields; without it the card shows the first
    // four short texts, in the model's order. `labels` puts each field's name before its value.
    function recordCard(path, resolver, chosen, labels) {
        if (path.indexOf("/content/dam/") !== 0) {
            return null;
        }
        var rec = resolver.getResource(path);
        var content = rec === null ? null : rec.getChild("jcr:content");
        if (content === null || prop(content, "contentFragment", "") !== "true") {
            return null;
        }
        var card = { title: prop(content, "jcr:title", ""), description: prop(content, "jcr:description", ""), image: "", texts: [] };
        var master = rec.getChild("jcr:content/data/master");
        if (master !== null) {
            var fields = modelFields(rec, resolver);
            var labelByName = {};
            var names = [];
            for (var f = 0; f < fields.length; f++) {
                labelByName[fields[f].name] = fields[f].label;
                names.push(fields[f].name);
            }
            // what the data holds beyond the model (or all of it, when the model cannot be read)
            var stored = keysOf(master);
            for (var k = 0; k < stored.length; k++) {
                if (stored[k].indexOf(":") < 0 && stored[k].indexOf("@") < 0 && !(stored[k] in labelByName) && names.indexOf(stored[k]) < 0) {
                    names.push(stored[k]);
                }
            }
            var picked = chosen.length > 0;
            if (picked) {
                names = chosen;
            }
            var max = picked ? 12 : 4;
            for (var i = 0; i < names.length && card.texts.length < max; i++) {
                var n = names[i];
                var v = valueOf(master, n);
                if (!v) {
                    continue;
                }
                if (IMAGE_VALUE.test(v)) {
                    if (!card.image) {
                        card.image = cardImage(v);
                    }
                    continue;
                }
                if (v === "true" || v === "false") {
                    if (!picked) {
                        continue; // a bare yes or no says nothing without its name
                    }
                    v = v === "true" ? "Yes" : "No";
                } else if (!picked && (v.charAt(0) === "/" || v.length > 1200)) {
                    continue; // references and long bodies do not belong on a card unless asked for
                }
                var html = prop(master, n + "@ContentType", "") === "text/html" || /^<[a-z][^>]*>/i.test(v);
                var label = labels || (picked && (v === "Yes" || v === "No")) ? (labelByName[n] || labelOf(n)) : "";
                card.texts.push({ label: label, html: html ? v : "", text: html ? "" : v });
            }
            // a card of chosen fields still gets the record's picture when none of them is one
            if (picked && !card.image) {
                for (var j = 0; j < stored.length; j++) {
                    var sv = valueOf(master, stored[j]);
                    if (IMAGE_VALUE.test(sv)) {
                        card.image = cardImage(sv);
                        break;
                    }
                }
            }
        }
        return card.title || card.description || card.image || card.texts.length ? card : null;
    }
    // "price, tripLength; groupSize" -> ["price", "tripLength", "groupSize"]
    function fieldNames(s) {
        var out = [];
        var parts = String(s || "").split(/[\s,;]+/);
        for (var i = 0; i < parts.length && out.length < 12; i++) {
            if (/^[A-Za-z0-9_][A-Za-z0-9_\-.]*$/.test(parts[i]) && out.indexOf(parts[i]) < 0) {
                out.push(parts[i]);
            }
        }
        return out;
    }

    var resolver = request.getResourceResolver();
    var ref = text("fileReference", "");
    var out = { src: null, srcset: "", sources: [], layers: [], cards: [], css: "", id: "", sizes: "100vw", alt: text("alt", ""),
        link: text("linkURL", ""), caption: text("caption", ""), width: null, height: null };
    if (!ref || ref.indexOf("/content/dam/") !== 0) {
        return out;
    }
    var asset = resolver.getResource(ref);
    if (asset === null) {
        return out;
    }
    // An image set (several pictures kept as one file in Media, in an order): its pictures. The first is
    // "the asset" for everything below; with two or more the component shows them as a gallery.
    var members = [];
    var spin = false;
    var setContent = asset.getChild("jcr:content");
    if (setContent !== null && prop(setContent, "studio:imageSet", "") === "true") {
        var listed = valueOf(setContent, "studio:members");
        var paths = listed ? listed.split(", ") : [];
        for (var mi = 0; mi < paths.length && members.length < 60; mi++) {
            var member = paths[mi].indexOf("/content/dam/") === 0 ? resolver.getResource(paths[mi]) : null;
            // a picture that was deleted, or that this visitor may not see, is simply not in the gallery
            if (member !== null && member.getChild("jcr:content/renditions/original") !== null) {
                members.push(member);
            }
        }
        if (members.length === 0) {
            return out;
        }
        spin = prop(setContent, "studio:setKind", "") === "spin";
        asset = members[0];
    }

    // which picture the functions below speak about: the asset, and in a gallery each member in turn
    var base = "";
    var imageW = 0;
    var imageH = 0;
    function point(res) {
        asset = res;
        var rel = String(res.path || res.getPath()).substring("/content/dam".length);
        var slash = rel.lastIndexOf("/");
        var dot = rel.lastIndexOf(".");
        if (dot > slash + 1) {
            rel = rel.substring(0, dot);
        }
        base = "/is/image" + rel;
        var md = res.getChild("jcr:content/metadata");
        imageW = md === null ? 0 : parseInt(prop(md, "tiff:ImageWidth", "0"), 10) || 0;
        imageH = md === null ? 0 : parseInt(prop(md, "tiff:ImageLength", "0"), 10) || 0;
    }
    point(asset);

    // ---- the policy: breakpoints and default widths
    var policyWidths = [];
    var policyPreset = "";
    var policyCardFields = "";
    // the look of a gallery, as the template's policy sets it (a component may say otherwise)
    var policyGallery = { thumbs: "", arrows: "", ratio: "", preset: "" };
    var policyCardLabels = false;
    var policyWebp = null;
    var breakpoints = [];
    var policy = typeof currentContentPolicy !== "undefined" ? currentContentPolicy : null;
    if (policy !== null) {
        var policyRes = resolver.getResource(String(policy.getPath()));
        if (policyRes !== null) {
            policyWidths = parseWidths(prop(policyRes, "widths", ""));
            policyPreset = prop(policyRes, "preset", "");
            policyCardFields = prop(policyRes, "cardFields", "");
            policyGallery.thumbs = prop(policyRes, "galleryThumbs", "");
            policyGallery.arrows = prop(policyRes, "galleryArrows", "");
            policyGallery.ratio = prop(policyRes, "galleryRatio", "");
            policyGallery.preset = prop(policyRes, "galleryPreset", "");
            policyCardLabels = prop(policyRes, "cardLabels", "") === "true";
            var pw = prop(policyRes, "webp", "");
            policyWebp = pw === "" ? null : pw === "true";
            var list = policyRes.getChild("breakpoints");
            if (list !== null) {
                // the wrapper's getChildren() is a JS array; a Java resource's an Iterable
                var kids = list.getChildren();
                var items = [];
                if (kids && typeof kids.length === "number") {
                    items = kids;
                } else if (kids && kids.iterator) {
                    var it = kids.iterator();
                    while (it.hasNext()) {
                        items.push(it.next());
                    }
                }
                for (var bi = 0; bi < items.length; bi++) {
                    var name = prop(items[bi], "name", "").trim();
                    if (name) {
                        breakpoints.push({ name: name, min: parseInt(prop(items[bi], "min", "0"), 10) || 0, max: parseInt(prop(items[bi], "max", "0"), 10) || 0 });
                    }
                }
            }
        }
    }
    if (breakpoints.length === 0) {
        breakpoints = DEFAULT_BREAKPOINTS;
    }
    breakpoints.sort(function (a, b) { return a.min - b.min; });

    function crop(name) {
        if (!name || name.indexOf("/") >= 0) {
            return null;
        }
        var c = asset.getChild("jcr:content/smartcrops/" + name);
        if (c === null) {
            return null;
        }
        var iw = parseInt(prop(c, "imageWidth", "0"), 10) || imageW;
        var ih = parseInt(prop(c, "imageHeight", "0"), 10) || imageH;
        var fl = parseFloat(prop(c, "left", "0")) || 0, ft = parseFloat(prop(c, "top", "0")) || 0;
        var fw = parseFloat(prop(c, "width", "1")) || 1, fh = parseFloat(prop(c, "height", "1")) || 1;
        return { name: name, w: Math.round(fw * iw), h: Math.round(fh * ih), l: fl, t: ft, fw: fw, fh: fh };
    }

    // delivery is the policy's; a component from before it moved there keeps its own values
    var preset = policyPreset || text("preset", "");
    var ownWebp = text("webp", "");
    var webp = policyWebp !== null ? policyWebp : (ownWebp ? ownWebp === "true" : true);
    var widths = parseWidths(text("widths", ""));
    if (widths.length === 0) {
        widths = policyWidths.length ? policyWidths : DEFAULT_WIDTHS;
    }

    function url(c, p, w) {
        var q = [];
        if (p) {
            q.push("$" + p + "$");
        }
        if (w) {
            q.push("wid=" + w);
            if (p) {
                // the preset's own size and fit would fight the responsive width: keep its format, quality and sharpening only
                q.push("hei=");
                q.push("fit=fit");
            }
        }
        if (webp) {
            q.push("fmt=webp");
        }
        return base + (c ? ":" + c.name : "") + (q.length ? "?" + q.join("&") : "");
    }

    // the widths worth offering for a crop on a breakpoint: never wider than the crop, never more than twice the
    // breakpoint's upper edge (a 2x screen), plus the crop's own size when the list stops short of it
    function srcset(c, p, bp) {
        var max = c ? c.w : imageW;
        var cap = bp && bp.max ? bp.max * 2 : 0;
        var list = [];
        var last = 0;
        for (var i = 0; i < widths.length; i++) {
            var w = widths[i];
            if (max && w > max) {
                break;
            }
            list.push(url(c, p, w) + " " + w + "w");
            last = w;
            if (cap && w >= cap) {
                break;
            }
        }
        if (max && last < max && (!cap || last < cap)) {
            // the crop's own size, with wid= so a preset's size never applies on top of the crop
            list.push(url(c, p, max) + " " + max + "w");
        }
        return list.join(", ");
    }

    // ---- what each breakpoint shows: its own crop, else the old two-crop content, else the nearest larger one's
    var legacyMobile = text("mobileCrop", "");
    var legacyDesktop = text("desktopCrop", "");
    var legacyMax = parseInt(text("mobileMaxWidth", "767"), 10) || 767;
    // A video of a gallery: what plays (the preview made for browsers when there is one, else the file itself),
    // its poster and its thumbnail, all renditions of the video. null for a picture.
    function videoOf(res) {
        var original = res.getChild("jcr:content/renditions/original/jcr:content");
        var md = res.getChild("jcr:content/metadata");
        var type = (md === null ? "" : prop(md, "dc:format", "")) || (original === null ? "" : prop(original, "jcr:mimeType", ""));
        if (type.indexOf("video/") !== 0) {
            return null;
        }
        var path = String(res.path || res.getPath());
        var folder = path + "/jcr:content/renditions/";
        var v = { src: folder + "original", type: type, poster: "", thumb: "" };
        if (res.getChild("jcr:content/renditions/cq5dam.video.preview.mp4") !== null) {
            v.src = folder + "cq5dam.video.preview.mp4";
            v.type = "video/mp4";
        }
        var names = ["cq5dam.web.1280.1280.jpeg", "cq5dam.web.1280.1280.png", "cq5dam.poster.jpg"];
        for (var i = 0; i < names.length && !v.poster; i++) {
            if (res.getChild("jcr:content/renditions/" + names[i]) !== null) {
                v.poster = folder + names[i];
            }
        }
        if (res.getChild("jcr:content/renditions/cq5dam.thumbnail.319.319.png") !== null) {
            v.thumb = folder + "cq5dam.thumbnail.319.319.png";
        } else {
            v.thumb = v.poster;
        }
        return v;
    }
    // "Let visitors enlarge the pictures": each picture gets a button that opens it large (the browser's
    // own popover: no script; the large picture is fetched when it is first opened).
    // a named gallery preset (Media › Image Presets › Gallery Presets): the component's, else the policy's.
    // A value set on the component wins over its preset, which wins over the policy's values and preset.
    function galleryPreset(name) {
        var n = String(name || "").replace(/[^a-z0-9_-]/g, "");
        if (!n) {
            return null;
        }
        var res = resolver.getResource("/conf/global/settings/dam/gallerypresets/" + n);
        if (res === null) {
            return null;
        }
        // an empty ratio in a preset is "each picture's own": it says so, over the policy's ratio
        return { thumbs: prop(res, "thumbs", ""), arrows: prop(res, "arrows", ""), ratio: prop(res, "ratio", "") || "own",
            zoom: prop(res, "zoom", "") === "true" };
    }
    var compPreset = galleryPreset(text("galleryPreset", ""));
    var polPreset = galleryPreset(policyGallery.preset);
    var zoom = text("zoom", "") === "true" || !!(compPreset || polPreset || {}).zoom;
    // the pointer gestures (turning a spin, moving and zooming the enlarged picture) are in a script
    // of their own, so pages with a strict Content-Security-Policy need only allow this site's scripts
    // (the query names the version: browsers keep the script five minutes, a new one must not wait)
    var VIEWER = "/etc.clientlibs/dam/components/dynamicmedia/clientlibs/viewer.js?v=0.11.2";
    function zoomCss(id) {
        return "#" + id + " .studio-dynamicmedia__media{position:relative;display:inline-block;max-width:100%;vertical-align:top}"
            + "#" + id + " .studio-dynamicmedia__zoom{position:absolute;top:10px;right:10px;z-index:2;width:36px;height:36px;padding:0;border:0;border-radius:50%;background:rgba(0,0,0,.55);color:#fff;font:400 20px/36px system-ui,sans-serif;text-align:center;cursor:zoom-in}"
            + "#" + id + " .studio-dynamicmedia__zoom:hover,#" + id + " .studio-dynamicmedia__zoom:focus-visible{background:rgba(0,0,0,.8);outline:2px solid #fff;outline-offset:1px}"
            + "#" + id + " .studio-dynamicmedia__large{padding:0;border:0;background:#000;max-width:96vw;max-height:96vh;overflow:auto;border-radius:6px;box-shadow:0 12px 48px rgba(0,0,0,.5)}"
            + "#" + id + " .studio-dynamicmedia__large::backdrop{background:rgba(0,0,0,.8)}"
            + "#" + id + " .studio-dynamicmedia__large label{display:block;margin:0}"
            + "#" + id + " .studio-dynamicmedia__large img{display:block;max-width:96vw;max-height:96vh;width:auto;height:auto;object-fit:contain;cursor:zoom-in;touch-action:pan-x pan-y}"
            // a click on the large picture shows it at its full size, to be moved around by scrolling; another fits it again
            + "#" + id + " .studio-dynamicmedia__actual{position:absolute;width:1px;height:1px;opacity:0;margin:0}"
            + "#" + id + " .studio-dynamicmedia__actual:checked+label img{max-width:none;max-height:none;cursor:zoom-out}"
            + "#" + id + " .studio-dynamicmedia__actual:focus-visible+label img{outline:3px solid Highlight;outline-offset:-3px}";
    }
    function large() {
        return base + "?wid=" + (imageW ? Math.min(imageW, 2560) : 2560) + (webp ? "&fmt=webp" : "");
    }

    function choose() {
        var list = [];
        for (var b = 0; b < breakpoints.length; b++) {
            var bp = breakpoints[b];
            var s = slug(bp.name);
            var cName = text("crop_" + s, "");
            if (!cName && (legacyMobile || legacyDesktop)) {
                cName = bp.max && bp.max <= legacyMax ? legacyMobile : legacyDesktop;
            }
            list.push({ bp: bp, crop: crop(cName), preset: text("preset_" + s, "") || preset, own: !!cName });
        }
        for (var k = list.length - 2; k >= 0; k--) {
            if (!list[k].own) {
                list[k].crop = list[k + 1].crop;
            }
        }
        return list;
    }
    // the <source>s and the <img> of the picture pointed at, for the crops chosen for it
    function pictureOf(list) {
        var pic = { sources: [] };
        for (var j = 0; j < list.length; j++) {
            var ch = list[j];
            var media = [];
            if (ch.bp.min > 0) {
                media.push("(min-width: " + ch.bp.min + "px)");
            }
            if (ch.bp.max > 0) {
                media.push("(max-width: " + ch.bp.max + "px)");
            }
            if (media.length === 0 || j === list.length - 1) {
                continue; // the largest is the <img> itself
            }
            pic.sources.push({ media: media.join(" and "), srcset: srcset(ch.crop, ch.preset, ch.bp), name: ch.bp.name, crop: ch.crop ? ch.crop.name : "",
                width: ch.crop ? ch.crop.w : (imageW || null), height: ch.crop ? ch.crop.h : (imageH || null) });
        }
        var top = list[list.length - 1];
        var largest = top.crop ? Math.min(top.crop.w, 1920) : (imageW ? Math.min(imageW, 1920) : null);
        pic.src = url(top.crop, top.preset, largest);
        pic.srcset = srcset(top.crop, top.preset, top.bp);
        pic.width = top.crop ? top.crop.w : (imageW || null);
        pic.height = top.crop ? top.crop.h : (imageH || null);
        return pic;
    }
    // ---- image maps. A map is drawn once on the picture in Media; here its areas are projected into each
    // breakpoint's crop and positioned in per cent over the picture, so they follow it at any size without script.
    var mid = "dm-" + hash(String(resource.path || resource.getPath()));
    // the component's choice (else the policy's) of what a record's card shows
    var cardFields = fieldNames(text("cardFields", policyCardFields));
    var cardLabels = text("cardLabels", "") === "true" || policyCardLabels;
    // what every map of this component needs, whatever the picture
    function mapBase(id) {
        return "#" + id + " .studio-dynamicmedia__media{position:relative;display:inline-block;max-width:100%;overflow:hidden;vertical-align:top}"
            + "#" + id + " .studio-dynamicmedia__media img{display:block}"
            + "#" + id + " .studio-dynamicmedia__areas{display:none;position:absolute;top:0;right:0;bottom:0;left:0;pointer-events:none}"
            + "#" + id + " .studio-dynamicmedia__areas>*{position:absolute;display:block;pointer-events:auto;box-sizing:border-box;margin:0;padding:0;border:0;background:none;cursor:pointer;transition:background .15s,box-shadow .15s}"
            // the hint: an area shows itself under the pointer and when focused
            + "#" + id + " .studio-dynamicmedia__areas>*:hover,#" + id + " .studio-dynamicmedia__areas>*:focus-visible{background:rgba(255,255,255,.16);box-shadow:inset 0 0 0 2px rgba(255,255,255,.95),inset 0 0 0 3px rgba(0,0,0,.35);outline:0}"
            + "#" + id + " .studio-dynamicmedia__card{width:min(420px,90vw);max-height:85vh;overflow:auto;padding:0;border:0;border-radius:12px;background:#fff;color:#222;font:15px/1.5 system-ui,sans-serif;box-shadow:0 12px 40px rgba(0,0,0,.35)}"
            + "#" + id + " .studio-dynamicmedia__card::backdrop{background:rgba(0,0,0,.35)}"
            + "#" + id + " .studio-dynamicmedia__card img{display:block;width:100%;height:auto}"
            + "#" + id + " .studio-dynamicmedia__card h3{margin:16px 20px 4px;font:600 18px/1.3 system-ui,sans-serif}"
            // the site's text styles (justified, tall lines) do not belong on the card
            + "#" + id + " .studio-dynamicmedia__card *{line-height:1.5;text-align:left;letter-spacing:normal}"
            + "#" + id + " .studio-dynamicmedia__card>p,#" + id + " .studio-dynamicmedia__card>div{margin:8px 20px;font:inherit}"
            + "#" + id + " .studio-dynamicmedia__card>div p{margin:8px 0;font:inherit}"
            + "#" + id + " .studio-dynamicmedia__card .studio-dynamicmedia__label{display:block;font:600 12px/1.4 system-ui,sans-serif;color:#6b6b6b;text-transform:uppercase;letter-spacing:.04em}"
            + "#" + id + " .studio-dynamicmedia__card>:last-child{margin-bottom:20px}";
    }
    // The map of one picture for the crops chosen for it: a layer of areas per breakpoint, the record cards its
    // areas open, and the rules that show each layer at its widths. `scope` is the selector the layers sit in
    // (the component, or one picture of a gallery); `key` starts the ids of the cards.
    function mapFor(res, list, id, scope, key) {
        var found = { layers: [], cards: [], css: "" };
        var areas = parseMap(imageMapOf(res));
        if (areas.length === 0) {
            return found;
        }
        // an area that points to a record opens it as a card (the browser's own popover: no script)
        for (var ai = 0; ai < areas.length; ai++) {
            var rc = recordCard(areas[ai].href, resolver, cardFields, cardLabels);
            if (rc !== null) {
                rc.id = key + ai;
                rc.label = areas[ai].alt || rc.title;
                areas[ai].card = rc.id;
                if (!areas[ai].alt) {
                    areas[ai].alt = rc.title;
                }
                found.cards.push(rc);
            }
        }
        for (var li = 0; li < list.length; li++) {
            var lc = list[li];
            var placed = project(areas, lc.crop);
            if (placed.length === 0) {
                continue;
            }
            var mq = [];
            if (lc.bp.min > 0) {
                mq.push("(min-width: " + lc.bp.min + "px)");
            }
            if (lc.bp.max > 0) {
                mq.push("(max-width: " + lc.bp.max + "px)");
            }
            var show = scope + ".studio-dynamicmedia__areas[data-layer=\"" + li + "\"]{display:block}";
            found.css += mq.length ? "@media " + mq.join(" and ") + "{" + show + "}" : show;
            found.layers.push({ index: String(li), name: lc.bp.name, areas: placed });
        }
        if (found.layers.length === 0) {
            found.cards = [];
        }
        return found;
    }

    var chosen = choose();
    var first = pictureOf(chosen);
    out.sources = first.sources;
    out.src = first.src;
    out.srcset = first.srcset;
    out.width = first.width;
    out.height = first.height;

    // ---- a set of two or more pictures: a gallery. One picture shows at a time, picked by the thumbnails
    // below it; the choice is a group of radio buttons, so it works by keyboard and without script.
    if (members.length > 1) {
        var gid = mid;
        var mapped = false;
        // the look: where the thumbnails are (or dots, or none), arrows on the picture, one shape for all pictures
        var cp = compPreset || {};
        var pp = polPreset || {};
        var look = {
            thumbs: text("galleryThumbs", "") || cp.thumbs || policyGallery.thumbs || pp.thumbs || "below",
            arrows: (text("galleryArrows", "") || cp.arrows || policyGallery.arrows || pp.arrows) === "true",
            ratio: text("galleryRatio", "") || cp.ratio || policyGallery.ratio || pp.ratio // "own" is no shape at all
        };
        var shape = /^(\d{1,2}):(\d{1,2})$/.exec(look.ratio);
        if (spin) {
            look = { thumbs: "none", arrows: false, ratio: "" };
            shape = null;
        }
        var g = "#" + gid + " ";
        var gcss = g + ".studio-dynamicmedia__set{position:relative}"
            + g + ".studio-dynamicmedia__pick{position:absolute;width:1px;height:1px;opacity:0;margin:0;pointer-events:none}"
            + g + ".studio-dynamicmedia__frame{display:none}"
            + g + ".studio-dynamicmedia__frame img{display:block;max-width:100%;height:auto}"
            + g + ".studio-dynamicmedia__thumbs{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}"
            + g + ".studio-dynamicmedia__thumbs label{display:block;cursor:pointer;border-radius:4px;overflow:hidden;opacity:.7;outline:2px solid transparent;outline-offset:1px;transition:opacity .15s}"
            + g + ".studio-dynamicmedia__thumbs label:hover{opacity:1}"
            + g + ".studio-dynamicmedia__thumbs img{display:block;width:96px;height:72px;object-fit:cover}";
        out.frames = [];
        for (var fi = 0; fi < members.length; fi++) {
            var video = spin ? null : videoOf(members[fi]);
            var frame;
            if (video !== null) {
                frame = { video: video, sources: [], layers: [], cards: [], thumb: video.thumb };
            } else {
                point(members[fi]);
                var picked = choose();
                frame = pictureOf(picked);
                frame.thumb = base + "?wid=192&hei=144&fit=crop" + (webp ? "&fmt=webp" : "");
                // each picture brings the map drawn on it in Media (a spin shows none: it is dragged)
                // (nor does a gallery whose pictures are cut to one shape: the areas would no longer sit on what they mark)
                var fmap = spin || shape ? { layers: [], cards: [], css: "" }
                    : mapFor(members[fi], picked, gid, g + ".studio-dynamicmedia__frame:nth-child(" + (fi + 1) + ") ", gid + "-f" + fi + "-r");
                frame.layers = fmap.layers;
                frame.cards = fmap.cards;
                if (fmap.layers.length > 0) {
                    mapped = true;
                    gcss += fmap.css;
                }
                if (zoom && !spin) {
                    frame.zoom = gid + "-z" + fi;
                    frame.large = large();
                }
            }
            frame.loading = spin ? "eager" : "lazy";
            var fc = members[fi].getChild("jcr:content/metadata");
            var title = fc === null ? "" : prop(fc, "dc:title", "");
            frame.alt = title || String(members[fi].name || members[fi].getName());
            frame.pick = gid + "-" + fi;
            if (look.arrows) {
                frame.prev = gid + "-" + ((fi + members.length - 1) % members.length);
                frame.next = gid + "-" + ((fi + 1) % members.length);
            }
            frame.group = gid;
            frame.first = fi === 0;
            frame.label = (video !== null ? "Video " : "Picture ") + (fi + 1) + " of " + members.length + ": " + frame.alt;
            if (frame.zoom) {
                frame.zoomLabel = "Enlarge: " + frame.alt;
            }
            out.frames.push(frame);
            var nth = ":nth-of-type(" + (fi + 1) + ")";
            var nthChild = ":nth-child(" + (fi + 1) + ")";
            gcss += g + ".studio-dynamicmedia__pick" + nth + ":checked~.studio-dynamicmedia__frames .studio-dynamicmedia__frame" + nthChild + "{display:block}"
                + g + ".studio-dynamicmedia__pick" + nth + ":checked~.studio-dynamicmedia__thumbs label" + nthChild + "{opacity:1;outline-color:currentColor}"
                + g + ".studio-dynamicmedia__pick" + nth + ":focus-visible~.studio-dynamicmedia__thumbs label" + nthChild + "{outline:3px solid Highlight}";
        }
        point(members[0]);
        out.id = gid;
        if (look.arrows) {
            gcss += g + ".studio-dynamicmedia__frame{position:relative}"
                + g + ".studio-dynamicmedia__go{position:absolute;top:50%;z-index:2;width:40px;height:40px;margin-top:-20px;border-radius:50%;background:rgba(0,0,0,.5);color:#fff;font:400 26px/38px system-ui,sans-serif;text-align:center;cursor:pointer;user-select:none;-webkit-user-select:none}"
                + g + ".studio-dynamicmedia__go:hover{background:rgba(0,0,0,.8)}"
                + g + ".studio-dynamicmedia__go--prev{left:10px}"
                + g + ".studio-dynamicmedia__go--next{right:10px}";
        }
        if (shape) {
            gcss += g + ".studio-dynamicmedia__frame .studio-dynamicmedia__media," + g + ".studio-dynamicmedia__frame picture{display:block}"
                + g + ".studio-dynamicmedia__frame img," + g + ".studio-dynamicmedia__frame video{width:100%;height:auto;aspect-ratio:" + shape[1] + "/" + shape[2] + ";object-fit:cover}";
        }
        if (look.thumbs === "left" || look.thumbs === "right") {
            gcss += g + ".studio-dynamicmedia__set{display:flex;flex-direction:" + (look.thumbs === "left" ? "row-reverse" : "row") + ";gap:8px;align-items:flex-start}"
                + g + ".studio-dynamicmedia__frames{flex:1 1 auto;min-width:0}"
                + g + ".studio-dynamicmedia__thumbs{flex:none;flex-direction:column;flex-wrap:nowrap;margin-top:0}";
        } else if (look.thumbs === "dots") {
            gcss += g + ".studio-dynamicmedia__thumbs{justify-content:center;gap:10px}"
                + g + ".studio-dynamicmedia__thumbs label{width:12px;height:12px;border-radius:50%;background:currentColor;opacity:.3}"
                + g + ".studio-dynamicmedia__thumbs label>*{display:none}";
        } else if (look.thumbs === "none") {
            gcss += g + ".studio-dynamicmedia__thumbs{display:none}";
        }
        if (spin) {
            // turned by dragging (and by the arrow keys, which a group of radio buttons already answers to)
            out.spin = true;
            gcss += g + ".studio-dynamicmedia__set{cursor:ew-resize;touch-action:pan-y;user-select:none;-webkit-user-select:none}"
                + g + ".studio-dynamicmedia__thumbs{display:none}"
                + g + ".studio-dynamicmedia__frame img{pointer-events:none}"
                + g + ".studio-dynamicmedia__pick:focus-visible~.studio-dynamicmedia__frames{outline:3px solid Highlight;outline-offset:2px}";
        }
        gcss += g + ".studio-dynamicmedia__frame video{display:block;width:100%;height:auto;background:#000}";
        out.css = (mapped ? mapBase(gid) : "") + (zoom && !spin ? zoomCss(gid) : "") + gcss;
        out.viewer = spin || zoom ? VIEWER : null;
        return out;
    }

    // ---- the asset's image map (drawn once in the image editor), projected into each breakpoint's crop.
    var map = mapFor(asset, chosen, mid, "#" + mid + " ", mid + "-r");
    if (map.layers.length > 0) {
        out.layers = map.layers;
        out.cards = map.cards;
        out.id = mid;
        out.css = mapBase(mid) + map.css;
    }
    if (zoom) {
        out.zoom = mid + "-z";
        out.large = large();
        out.zoomLabel = "Enlarge" + (out.alt ? ": " + out.alt : "");
        out.id = mid;
        out.css = (out.css || "") + zoomCss(mid);
    }
    out.viewer = zoom ? VIEWER : null;

    return out;
});
