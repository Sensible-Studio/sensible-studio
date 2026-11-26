#!/usr/bin/env python3
"""Builds the Sensible Studio documentation site from the Markdown in docs/content.

The site is ordinary Sensible Studio content: pages under /content/studio-docs/<language>, made of
Title and Text components, on templates of its own (/conf/studio-docs), rendered by a small set of
components and one client library (/apps/studio-docs, from docs/site/apps). This script writes all
three into a running instance through Sling's POST servlet. Python 3.9+, nothing to install.

    docs/build.py                       everything, into the author on http://localhost:4502
    docs/build.py content               only the pages (after editing Markdown)
    docs/build.py apps conf             only the components, theme, templates and policies
    docs/build.py publish               publish the pages from the author (its Publishing agents)
    docs/build.py check                 every page answers, and so does every link between them
    docs/build.py --url http://localhost:4503 apps conf     a publish instance needs these too

    --url URL        the instance (default http://localhost:4502, or STUDIO_URL)
    --user U:P       who writes (default admin:admin, or STUDIO_USER; a developer's instance)
    --lang en        which language of docs/content

Markdown: a front matter block (title, description, order, nav, layout: home), then headings,
paragraphs, lists, tables, fenced code, quotes ("> **Note:** ..."), links to other .md files, and
two blocks for landing pages:

    :::hero                         :::cards
    eyebrow: Open source            - [Pages](author/pages.md): Create and organise pages.
    title: ...                      :::
    text: ...
    primary: Get started | getting-started/index.md
    secondary: Author guide | author/index.md
    :::
"""
import argparse
import base64
import html
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
SITE = "/content/studio-docs"
CONF = "/conf/studio-docs"
APPS = "/apps/studio-docs"
COMPONENTS = "studio-docs/components"
TEMPLATES = CONF + "/settings/wcm/templates"


# ---------------------------------------------------------------------------------------------
# the instance

class Studio:
    def __init__(self, url, user):
        self.url = url.rstrip("/")
        self.auth = "Basic " + base64.b64encode(user.encode()).decode()

    def request(self, method, path, fields=None, files=None, accept_errors=False):
        headers = {"Authorization": self.auth, "X-Studio-CSRF": "1", "Accept": "application/json,*/*"}
        body = None
        if fields is not None or files:
            boundary = "----studio-docs-" + uuid.uuid4().hex
            parts = []
            for name, value in (fields or []):
                parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n" % (boundary, name)).encode() + value.encode("utf-8") + b"\r\n")
            for name, filename, mime, data in (files or []):
                head = "--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n" % (boundary, name, filename, mime)
                parts.append(head.encode() + data + b"\r\n")
            parts.append(("--%s--\r\n" % boundary).encode())
            body = b"".join(parts)
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        request = urllib.request.Request(self.url + urllib.parse.quote(path, safe="/:.?=&%#"), data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=120) as answer:
                return answer.status, answer.read()
        except urllib.error.HTTPError as error:
            text = error.read()
            if accept_errors:
                return error.code, text
            sys.exit("%s %s: %s\n%s" % (method, path, error.code, text.decode("utf-8", "replace")[:600]))
        except urllib.error.URLError as error:
            sys.exit("%s does not answer: %s" % (self.url, error.reason))

    def exists(self, path):
        return self.request("GET", path + ".json", accept_errors=True)[0] == 200

    def json(self, path):
        status, body = self.request("GET", path, accept_errors=True)
        return json.loads(body) if status == 200 else None

    def post(self, path, fields):
        return self.request("POST", path, fields=[("_charset_", "utf-8")] + list(fields))

    def import_node(self, parent, name, content, replace=True):
        """One node and everything below it, from JSON."""
        self.post(parent, [
            (":operation", "import"), (":contentType", "json"), (":name", name),
            (":replace", "true" if replace else "false"), (":replaceProperties", "true"),
            (":content", json.dumps(content)),
        ])

    def delete(self, path):
        self.post(path, [(":operation", "delete")])

    def upload(self, folder, filename, data, mime):
        self.request("POST", folder, fields=[("_charset_", "utf-8")], files=[(filename, filename, mime, data)])


# ---------------------------------------------------------------------------------------------
# /apps: the folders of docs/site/apps as they are; .node.json says what a folder is

MIME = {".html": "text/html", ".css": "text/css", ".js": "application/javascript", ".txt": "text/plain", ".svg": "image/svg+xml",
        ".png": "image/png", ".json": "application/json"}


def push_apps(studio):
    source = os.path.join(HERE, "site", "apps", "studio-docs")
    if studio.exists(APPS):
        studio.delete(APPS)
    count = 0
    for folder, folders, files in os.walk(source):
        folders.sort()
        relative = os.path.relpath(folder, source)
        path = APPS if relative == "." else APPS + "/" + relative.replace(os.sep, "/")
        node = {"jcr:primaryType": "nt:folder"}
        if ".node.json" in files:
            with open(os.path.join(folder, ".node.json"), encoding="utf-8") as f:
                node = json.load(f)
        studio.import_node(path.rsplit("/", 1)[0], path.rsplit("/", 1)[1], node)
        for name in sorted(files):
            if name.startswith("."):
                continue
            with open(os.path.join(folder, name), "rb") as f:
                studio.upload(path, name, f.read(), MIME.get(os.path.splitext(name)[1], "application/octet-stream"))
            count += 1
    print("apps     %s: %d files" % (APPS, count))


# ---------------------------------------------------------------------------------------------
# /conf: two templates (the home page; a page with the sidebar) and their policies

def template(name, title, description, lang_root, sidebar):
    page = COMPONENTS + "/page"
    path = TEMPLATES + "/" + name
    frame = {"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/container"}
    frame["header"] = {
        "jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/header",
        "brand": "Sensible Studio", "home": lang_root,
        "topnav": {"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/navigation",
                   "navigationRoot": lang_root, "structureStart": 1, "structureDepth": 1, "collectAllPages": False},
    }
    if sidebar:
        frame["navigation"] = {"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/navigation",
                               "navigationRoot": lang_root, "structureStart": 1, "collectAllPages": True,
                               "accessibilityLabel": "Documentation"}
        frame["breadcrumb"] = {"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/breadcrumb",
                               "startLevel": 2, "showHidden": False, "hideCurrent": False}
    frame["container"] = {"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/container", "editable": True}
    frame["footer"] = {
        "jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/footer",
        "text": "Sensible Studio is open source, under the Apache License 2.0. This documentation is written and published with Sensible Studio.",
        "legal": "Sensible Studio is an independent project. It is not affiliated with, endorsed by or sponsored by Adobe. "
                 "Adobe, Adobe Experience Manager and AEM are trademarks of Adobe Inc., named here only to describe compatibility.",
    }
    mapping = "wcm/core/components/policies/mapping"
    return {
        "jcr:primaryType": "cq:Template",
        "jcr:content": {"jcr:primaryType": "cq:PageContent", "jcr:title": title, "jcr:description": description, "status": "enabled"},
        "structure": {"jcr:primaryType": "cq:Page", "jcr:content": {
            "jcr:primaryType": "cq:PageContent", "cq:template": path, "sling:resourceType": page, "root": frame}},
        "initial": {"jcr:primaryType": "cq:Page", "jcr:content": {
            "jcr:primaryType": "cq:PageContent", "cq:template": path, "sling:resourceType": page,
            "root": {"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/container",
                     "container": {"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/container"}}}},
        "policies": {"jcr:primaryType": "cq:Page", "jcr:content": {
            "jcr:primaryType": "nt:unstructured", "sling:resourceType": "wcm/core/components/policies/mappings",
            "cq:policy": COMPONENTS + "/page/site",
            "root": {"jcr:primaryType": "nt:unstructured", "sling:resourceType": mapping, "cq:policy": COMPONENTS + "/container/frame",
                     "container": {"jcr:primaryType": "nt:unstructured", "sling:resourceType": mapping,
                                   "cq:policy": COMPONENTS + "/container/content"}}}},
    }


def push_conf(studio, lang):
    lang_root = SITE + "/" + lang
    policy = "wcm/core/components/policy/policy"
    folder = {"jcr:primaryType": "sling:Folder"}
    conf = {
        "jcr:primaryType": "sling:Folder", "jcr:title": "Sensible Studio documentation",
        "settings": {"jcr:primaryType": "sling:Folder", "wcm": {
            "jcr:primaryType": "cq:Page",
            "templates": {
                "jcr:primaryType": "cq:Page",
                "home-page": template("home-page", "Docs: home page", "Header, the page's content across the page, footer.", lang_root, False),
                "docs-page": template("docs-page", "Docs: page", "Header, the sidebar with every page, the page's content, footer.", lang_root, True),
            },
            "policies": {"jcr:primaryType": "cq:Page", "studio-docs": dict(folder, components=dict(
                folder,
                page=dict(folder, site={"jcr:primaryType": "nt:unstructured", "sling:resourceType": policy,
                                        "jcr:title": "Docs theme", "clientlibs": ["studio-docs.site"]}),
                container=dict(
                    folder,
                    frame={"jcr:primaryType": "nt:unstructured", "sling:resourceType": policy, "jcr:title": "Docs frame"},
                    content={"jcr:primaryType": "nt:unstructured", "sling:resourceType": policy, "jcr:title": "Docs content",
                             "components": ["group:Studio Docs"]}),
            ))},
        }},
    }
    studio.import_node("/conf", "studio-docs", conf)
    # Visitors of a publish instance may read nothing under /conf: as every site's package does,
    # let everyone read the templates and the policies, without which a page has no frame or theme.
    for part in ("templates", "policies"):
        studio.post("%s/settings/wcm/%s.modifyAce.html" % (CONF, part),
                    [("principalId", "everyone"), ("privilege@jcr:read", "granted")])
    print("conf     %s: 2 templates, 3 policies, readable by everyone" % CONF)


# ---------------------------------------------------------------------------------------------
# Markdown

def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", re.sub(r"<[^>]+>|`", "", text).lower()).strip("-")


class Markdown:
    """The Markdown this documentation is written in, to the HTML a Text component holds."""

    def __init__(self, link):
        self.link = link    # (target) -> href

    def inline(self, text):
        out = []
        for index, piece in enumerate(re.split(r"(`[^`]+`)", text)):
            if index % 2:
                out.append("<code>%s</code>" % html.escape(piece[1:-1], quote=False))
                continue
            piece = html.escape(piece, quote=False)
            piece = re.sub(r"!\[([^\]]*)\]\(([^)\s]+)\)", lambda m: '<img alt="%s" src="%s">' % (m.group(1), self.link(m.group(2))), piece)
            piece = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", lambda m: '<a href="%s">%s</a>' % (self.link(m.group(2)), m.group(1)), piece)
            piece = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", piece)
            piece = re.sub(r"(?<![\w*])\*([^*\s][^*]*?)\*(?![\w*])", r"<em>\1</em>", piece)
            out.append(piece)
        return "".join(out)

    def blocks(self, lines):
        """Block level Markdown of `lines`, as HTML."""
        out, i = [], 0
        while i < len(lines):
            line = lines[i]
            if not line.strip():
                i += 1
            elif line.startswith("```"):
                j = i + 1
                while j < len(lines) and not lines[j].startswith("```"):
                    j += 1
                out.append("<pre><code>%s</code></pre>" % html.escape("\n".join(lines[i + 1:j]), quote=False))
                i = j + 1
            elif re.match(r"#{3,4} ", line):
                level = len(line.split(" ")[0])
                out.append("<h%d>%s</h%d>" % (level, self.inline(line[level + 1:].strip()), level))
                i += 1
            elif re.match(r"\s*(-{3,}|\*{3,})\s*$", line):
                out.append("<hr>")
                i += 1
            elif line.startswith(">"):
                j = i
                quote = []
                while j < len(lines) and lines[j].startswith(">"):
                    quote.append(lines[j][1:].lstrip() if lines[j][1:].strip() else "")
                    j += 1
                out.append("<blockquote>%s</blockquote>" % self.blocks(quote))
                i = j
            elif line.lstrip().startswith("|") and i + 1 < len(lines) and re.match(r"\s*\|[\s:|-]+\|\s*$", lines[i + 1]):
                cells = lambda row: [c.strip() for c in re.split(r"(?<!\\)\|", row.strip().strip("|"))]
                rows = ["<thead><tr>%s</tr></thead><tbody>" % "".join("<th>%s</th>" % self.inline(c) for c in cells(line))]
                j = i + 2
                while j < len(lines) and lines[j].lstrip().startswith("|"):
                    rows.append("<tr>%s</tr>" % "".join("<td>%s</td>" % self.inline(c.replace("\\|", "|")) for c in cells(lines[j])))
                    j += 1
                out.append("<table>%s</tbody></table>" % "".join(rows))
                i = j
            elif re.match(r"\s*([-*]|\d+\.) ", line):
                j = i
                while j < len(lines) and (re.match(r"\s*([-*]|\d+\.) ", lines[j]) or (lines[j].startswith("  ") and lines[j].strip())
                                          or (not lines[j].strip() and j + 1 < len(lines) and lines[j + 1].startswith("  ") and lines[j + 1].strip())):
                    j += 1
                out.append(self.list(lines[i:j]))
                i = j
            else:
                j = i
                while j < len(lines) and lines[j].strip() and not re.match(r"(```|#{3,4} |>|\s*([-*]|\d+\.) |\s*\|)", lines[j]):
                    j += 1
                if j == i:
                    j = i + 1
                out.append("<p>%s</p>" % self.inline(" ".join(l.strip() for l in lines[i:j])))
                i = j
        return "".join(out)

    def list(self, lines):
        indent = len(lines[0]) - len(lines[0].lstrip())
        ordered = bool(re.match(r"\s*\d+\. ", lines[0]))
        items, current, inner = [], None, 0
        for line in lines:
            own = len(line) - len(line.lstrip())
            marker = re.match(r"\s*([-*]|\d+\.) ", line)
            if marker and own == indent:
                current = [line[marker.end():]]
                inner = marker.end()    # where the item's own text starts: what continues it is indented so far
                items.append(current)
            elif current is not None:
                current.append(line[min(own, inner):] if line.strip() else "")
        rendered = []
        for item in items:
            first, rest = item[0], [l for l in item[1:]]
            body = self.inline(first.strip())
            # what continues the first line is part of it; anything else is blocks of its own
            while rest and rest[0].strip() and not re.match(r"(```|>|\s*([-*]|\d+\.) |\s*\|)", rest[0]):
                body += " " + self.inline(rest.pop(0).strip())
            if any(l.strip() for l in rest):
                body += self.blocks(rest)
            rendered.append("<li>%s</li>" % body)
        tag = "ol" if ordered else "ul"
        return "<%s>%s</%s>" % (tag, "".join(rendered), tag)


class Page:
    def __init__(self, source_root, file):
        self.file = file
        relative = os.path.relpath(file, source_root).replace(os.sep, "/")
        self.relative = relative
        parts = relative[:-3].split("/")
        if parts[-1] == "index":
            parts.pop()
        self.segments = parts
        with open(file, encoding="utf-8") as f:
            text = f.read()
        self.meta = {}
        match = re.match(r"---\n(.*?)\n---\n", text, re.S)
        if match:
            for line in match.group(1).splitlines():
                if ":" in line:
                    key, value = line.split(":", 1)
                    self.meta[key.strip()] = value.strip()
            text = text[match.end():]
        self.lines = text.splitlines()
        if "title" not in self.meta:
            sys.exit("%s: the front matter needs a title" % relative)
        self.order = float(self.meta.get("order", 1000))

    def path(self, lang_root):
        return "/".join([lang_root] + self.segments)


def directive(lines, i):
    """A ':::name ... :::' block at line i: (name, its lines, the line after)."""
    j = i + 1
    while j < len(lines) and lines[j].strip() != ":::":
        j += 1
    return lines[i].strip()[3:].strip(), lines[i + 1:j], j + 1


def page_content(page, pages, lang_root):
    """The page's jcr:content: Title and Text components, section by section."""
    by_file = {p.relative: p for p in pages}

    def link(target):
        if re.match(r"[a-z][a-z0-9+.-]*:|#|/", target):
            return target
        file, _, anchor = target.partition("#")
        resolved = os.path.normpath(os.path.join(os.path.dirname(page.relative), file)).replace(os.sep, "/")
        if resolved not in by_file:
            sys.exit("%s: links to %s, which is not a page" % (page.relative, target))
        return by_file[resolved].path(lang_root) + ".html" + ("#" + anchor if anchor else "")

    def page_link(target):
        return link(target)[:-5] if link(target).endswith(".html") else link(target)

    markdown = Markdown(link)
    items = []

    def add(component, **properties):
        node = {"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/" + component}
        node.update(properties)
        items.append(node)

    def text(lines):
        rendered = markdown.blocks(lines)
        if rendered:
            add("text", text=rendered, textIsRich="true")

    home = page.meta.get("layout") == "home"
    if not home:
        add("title", type="h1")     # the page's own title
    lines, pending, i = page.lines, [], 0
    in_code = False
    while i < len(lines):
        line = lines[i]
        if line.lstrip().startswith("```"):
            in_code = not in_code
        if in_code:
            pending.append(line)
            i += 1
        elif line.startswith("# "):
            i += 1      # the title is the front matter's
        elif line.startswith("## "):
            text(pending)
            pending = []
            title = line[3:].strip()
            add("title", **{"jcr:title": re.sub(r"`", "", title), "type": "h2", "id": slug(title)})
            i += 1
        elif line.startswith(":::"):
            text(pending)
            pending = []
            name, body, i = directive(lines, i)
            if name == "hero":
                fields = dict((l.split(":", 1)[0].strip(), l.split(":", 1)[1].strip()) for l in body if ":" in l)
                hero = {"jcr:title": fields.get("title", page.meta["title"]), "text": fields.get("text", ""), "eyebrow": fields.get("eyebrow", "")}
                for which in ("primary", "secondary"):
                    if which in fields:
                        label, _, target = fields[which].partition("|")
                        hero[which + "Label"], hero[which + "Link"] = label.strip(), page_link(target.strip())
                add("hero", **hero)
            elif name == "cards":
                cards = {}
                for n, entry in enumerate(l for l in body if l.strip().startswith("- ")):
                    match = re.match(r"- \[([^\]]+)\]\(([^)]+)\):?\s*(.*)", entry.strip())
                    plain = re.match(r"- \*\*([^*]+)\*\*:?\s*(.*)", entry.strip())
                    if match:
                        card = {"jcr:title": match.group(1), "link": page_link(match.group(2)), "text": match.group(3)}
                    elif plain:
                        card = {"jcr:title": plain.group(1), "text": plain.group(2)}
                    else:
                        sys.exit("%s: a card is '- [Title](page.md): text' or '- **Title**: text', not %s" % (page.relative, entry))
                    cards["card_%d" % n] = dict({"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/card"}, **card)
                add("cards", **cards)
            else:
                sys.exit("%s: unknown block :::%s" % (page.relative, name))
        else:
            pending.append(line)
            i += 1
    text(pending)

    container = {"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/container"}
    for n, item in enumerate(items):
        container["item_%d" % n] = item
    content = {
        "jcr:primaryType": "cq:PageContent",
        "jcr:title": page.meta["title"],
        "cq:template": TEMPLATES + ("/home-page" if home else "/docs-page"),
        "sling:resourceType": COMPONENTS + "/page",
        "root": {"jcr:primaryType": "nt:unstructured", "sling:resourceType": COMPONENTS + "/container", "container": container},
    }
    if page.meta.get("description"):
        content["jcr:description"] = page.meta["description"]
    if page.meta.get("nav"):
        content["navTitle"] = page.meta["nav"]
    return content


def read_pages(lang):
    source = os.path.join(HERE, "content", lang)
    if not os.path.isdir(source):
        sys.exit("no docs/content/%s" % lang)
    pages = []
    for folder, _, files in os.walk(source):
        for name in files:
            if name.endswith(".md"):
                pages.append(Page(source, os.path.join(folder, name)))
    # parents before children; siblings by their order, then by name
    pages.sort(key=lambda p: (len(p.segments), p.order, p.segments))
    for page in pages:
        if page.segments and not any(p.segments == page.segments[:-1] for p in pages):
            sys.exit("%s: its folder needs an index.md" % page.relative)
    return pages


def existing_pages(studio, path):
    """The paths of the pages at and below `path`."""
    found = []
    node = studio.json(path + ".1.json")
    if node is None:
        return found
    found.append(path)
    for name, child in node.items():
        if isinstance(child, dict) and child.get("jcr:primaryType") == "cq:Page":
            found.extend(existing_pages(studio, path + "/" + name))
    return found


def push_content(studio, lang):
    lang_root = SITE + "/" + lang
    pages = read_pages(lang)
    if not studio.exists(SITE):
        studio.post(SITE, [("jcr:primaryType", "cq:Page")])
    # the site's own node: its configuration, and where a visitor of the bare address is sent
    for name, value in (("jcr:title", "Sensible Studio documentation"), ("cq:conf", CONF), ("sling:configRef", CONF),
                        ("cq:redirectTarget", lang_root), ("cq:allowedTemplates", TEMPLATES + "/.*")):
        studio.post(SITE + "/jcr:content", [(name, value)])
    wanted = set()
    for page in pages:
        path = page.path(lang_root)
        wanted.add(path)
        if not studio.exists(path):
            studio.post(path, [("jcr:primaryType", "cq:Page")])
        content = page_content(page, pages, lang_root)
        if not page.segments:
            content["jcr:language"] = lang
        studio.import_node(path, "jcr:content", content)
    # the order of the sidebar is the order of the pages in the repository
    for page in pages:
        if page.segments:
            studio.post(page.path(lang_root), [(":order", "last")])
    stale = [p for p in existing_pages(studio, lang_root) if p not in wanted]
    for path in sorted(stale, key=len):
        if not any(path.startswith(other + "/") for other in stale):
            studio.delete(path)
    print("content  %s: %d pages%s" % (lang_root, len(pages), ", %d removed" % len(stale) if stale else ""))
    return pages


# ---------------------------------------------------------------------------------------------
# publish, check

def publish(studio, lang):
    lang_root = SITE + "/" + lang
    paths = [SITE] + [p.path(lang_root) for p in read_pages(lang)]
    for start in range(0, len(paths), 40):
        studio.post("/bin/studio/replication.json", [("op", "activate")] + [("path", p) for p in paths[start:start + 40]])
    print("publish  %d pages handed to the Publishing agents" % len(paths))


def check(studio, lang):
    lang_root = SITE + "/" + lang
    pages = read_pages(lang)
    failed, links = [], {}
    for page in pages:
        path = page.path(lang_root) + ".html"
        status, body = studio.request("GET", path, accept_errors=True)
        text = body.decode("utf-8", "replace")
        if status != 200:
            failed.append("%s: %s" % (path, status))
            continue
        if html.escape(page.meta["title"], quote=False) not in text:
            failed.append("%s: the title is not on the page" % path)
        if "studio-docs.site" not in text and "/etc.clientlibs/studio-docs" not in text:
            failed.append("%s: the theme is not included" % path)
        for href in re.findall(r'href="([^"#]+)', text):
            if href.startswith("/content/") or href.startswith("/etc.clientlibs/"):
                links.setdefault(href, path)
    for href, source in sorted(links.items()):
        status, _ = studio.request("GET", href, accept_errors=True)
        if status != 200:
            failed.append("%s: its link %s answers %s" % (source, href, status))
    for line in failed:
        print("FAIL  " + line)
    print("check    %d pages, %d links, %d failed" % (len(pages), len(links), len(failed)))
    return not failed


def main():
    parser = argparse.ArgumentParser(description="Builds the documentation site into a running Sensible Studio.")
    parser.add_argument("steps", nargs="*", help="apps, conf, content, publish, check (default: apps conf content check)")
    parser.add_argument("--url", default=os.environ.get("STUDIO_URL", "http://localhost:4502"))
    parser.add_argument("--user", default=os.environ.get("STUDIO_USER", "admin:admin"))
    parser.add_argument("--lang", default="en")
    args = parser.parse_args()
    steps = args.steps or ["apps", "conf", "content", "check"]
    unknown = [s for s in steps if s not in ("apps", "conf", "content", "publish", "check")]
    if unknown:
        parser.error("unknown step: " + ", ".join(unknown))
    studio = Studio(args.url, args.user)
    ok = True
    for step in steps:
        if step == "apps":
            push_apps(studio)
        elif step == "conf":
            push_conf(studio, args.lang)
        elif step == "content":
            push_content(studio, args.lang)
        elif step == "publish":
            publish(studio, args.lang)
        elif step == "check":
            ok = check(studio, args.lang) and ok
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
