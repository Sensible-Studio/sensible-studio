package io.sensible.studio.cfm.impl.headless;

import graphql.schema.DataFetchingEnvironment;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A multi-line text element ({@code MultilineString}): the stored text in its content type
 * ({@code text/html}, {@code text/x-markdown} or {@code text/plain}) and the other renditions GraphQL offers:
 * {@code html}, {@code markdown}, {@code plaintext} and {@code json}, the structured form headless clients render
 * (paragraph / header / text / link / list / image nodes, as AEM).
 */
final class RichText implements Gettable {

    static final String HTML = "text/html";
    static final String MARKDOWN = "text/x-markdown";
    static final String PLAIN = "text/plain";

    private static final Set<String> BLOCKS = new HashSet<>(Arrays.asList(
            "p", "div", "section", "article", "header", "footer", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "table", "tr", "hr"));

    final String contentType;
    final String text;

    RichText(String contentType, String text) {
        this.contentType = contentType == null ? PLAIN : contentType;
        this.text = text == null ? "" : text;
    }

    @Override
    public Object get(String field, DataFetchingEnvironment env) {
        switch (field) {
            case "html": return html();
            case "markdown": return markdown();
            case "plaintext": return plaintext();
            case "json": return json();
            default: return null;
        }
    }

    String html() {
        if (contentType.contains("html")) return text;
        if (contentType.contains("markdown")) return markdownToHtml(text);
        return plainToHtml(text);
    }

    String plaintext() {
        if (!contentType.contains("html") && !contentType.contains("markdown")) return text;
        return htmlToPlain(Jsoup.parseBodyFragment(html()).body());
    }

    String markdown() {
        if (contentType.contains("markdown")) return text;
        if (!contentType.contains("html")) return text;
        StringBuilder sb = new StringBuilder();
        markdownBlocks(Jsoup.parseBodyFragment(text).body(), sb, "");
        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    List<Object> json() {
        Document doc = Jsoup.parseBodyFragment(html());
        return blocks(doc.body());
    }

    /** Paths in links and images of the text that point into the repository ({@code /content/...}). */
    List<String> repositoryLinks() {
        if (text.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (Element e : Jsoup.parseBodyFragment(html()).select("a[href], img[src]")) {
            String target = e.hasAttr("href") ? e.attr("href") : e.attr("src");
            if (target.startsWith("/content/")) {
                int cut = target.indexOf('?');
                String p = cut > 0 ? target.substring(0, cut) : target;
                if (p.endsWith(".html")) p = p.substring(0, p.length() - 5);
                if (!out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- json

    private static List<Object> blocks(Element parent) {
        List<Object> out = new ArrayList<>();
        List<Object> pending = new ArrayList<>();
        for (Node n : parent.childNodes()) {
            if (n instanceof Element && BLOCKS.contains(((Element) n).normalName())) {
                flushParagraph(pending, out);
                Object block = block((Element) n);
                if (block instanceof List) out.addAll((List<?>) block);
                else if (block != null) out.add(block);
            } else if (n instanceof Element && "img".equals(((Element) n).normalName())) {
                flushParagraph(pending, out);
                out.add(image((Element) n));
            } else {
                inline(n, pending, Collections.emptyList());
            }
        }
        flushParagraph(pending, out);
        return out;
    }

    private static void flushParagraph(List<Object> pending, List<Object> out) {
        boolean meaningful = false;
        for (Object o : pending) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            if (!"text".equals(m.get("nodeType")) || !String.valueOf(m.get("value")).trim().isEmpty()) meaningful = true;
        }
        if (meaningful) out.add(node("paragraph", new ArrayList<>(pending)));
        pending.clear();
    }

    private static Object block(Element e) {
        String tag = e.normalName();
        switch (tag) {
            case "p":
            case "pre":
                return node("paragraph", inlineOf(e));
            case "h1": case "h2": case "h3": case "h4": case "h5": case "h6": {
                Map<String, Object> h = node("header", inlineOf(e));
                h.put("style", tag);
                return h;
            }
            case "ul":
            case "ol": {
                List<Object> items = new ArrayList<>();
                for (Element li : e.children()) {
                    if (!"li".equals(li.normalName())) continue;
                    items.add(node("list-item", listItemContent(li)));
                }
                return node("ul".equals(tag) ? "unordered-list" : "ordered-list", items);
            }
            case "li":
                return node("list-item", listItemContent(e));
            case "hr":
                return node("horizontal-rule", null);
            case "table": {
                List<Object> rows = new ArrayList<>();
                for (Element tr : e.select("tr")) {
                    List<Object> cells = new ArrayList<>();
                    for (Element td : tr.children()) cells.add(node("table-cell", inlineOf(td)));
                    rows.add(node("table-row", cells));
                }
                return node("table", rows);
            }
            default:
                return blocks(e);
        }
    }

    private static List<Object> listItemContent(Element li) {
        boolean hasBlocks = false;
        for (Element c : li.children()) if (BLOCKS.contains(c.normalName())) hasBlocks = true;
        return hasBlocks ? blocks(li) : inlineOf(li);
    }

    private static List<Object> inlineOf(Element e) {
        List<Object> out = new ArrayList<>();
        for (Node n : e.childNodes()) inline(n, out, Collections.emptyList());
        return out;
    }

    private static void inline(Node n, List<Object> out, List<String> variants) {
        if (n instanceof TextNode) {
            String value = ((TextNode) n).getWholeText().replaceAll("\\s+", " ");
            if (value.isEmpty()) return;
            Map<String, Object> t = node("text", null);
            t.put("value", value);
            if (!variants.isEmpty()) {
                Map<String, Object> format = new LinkedHashMap<>();
                format.put("variants", new ArrayList<>(variants));
                t.put("format", format);
            }
            out.add(t);
            return;
        }
        if (!(n instanceof Element)) return;
        Element e = (Element) n;
        String tag = e.normalName();
        switch (tag) {
            case "br":
                out.add(node("line-break", null));
                return;
            case "img":
                out.add(image(e));
                return;
            case "a": {
                Map<String, Object> link = node("link", null);
                link.put("value", e.text());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("href", e.attr("href"));
                if (e.hasAttr("target")) data.put("target", e.attr("target"));
                if (e.hasAttr("title")) data.put("title", e.attr("title"));
                link.put("data", data);
                out.add(link);
                return;
            }
            default:
                String variant = variant(tag);
                List<String> next = variants;
                if (variant != null && !variants.contains(variant)) {
                    next = new ArrayList<>(variants);
                    next.add(variant);
                }
                for (Node c : e.childNodes()) inline(c, out, next);
        }
    }

    private static String variant(String tag) {
        switch (tag) {
            case "b": case "strong": return "bold";
            case "i": case "em": return "italic";
            case "u": return "underline";
            case "s": case "strike": case "del": return "strikethrough";
            case "sup": return "superscript";
            case "sub": return "subscript";
            case "code": return "code";
            default: return null;
        }
    }

    private static Map<String, Object> image(Element e) {
        Map<String, Object> img = node("image", null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("src", e.attr("src"));
        if (e.hasAttr("alt")) data.put("alt", e.attr("alt"));
        img.put("data", data);
        return img;
    }

    private static Map<String, Object> node(String type, List<Object> content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeType", type);
        if (content != null) m.put("content", content);
        return m;
    }

    // ------------------------------------------------------------------------------------------- plain text

    private static String htmlToPlain(Element body) {
        StringBuilder sb = new StringBuilder();
        plain(body, sb);
        return sb.toString().replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static void plain(Node n, StringBuilder sb) {
        if (n instanceof TextNode) {
            sb.append(((TextNode) n).getWholeText().replaceAll("\\s+", " "));
            return;
        }
        if (!(n instanceof Element)) return;
        Element e = (Element) n;
        String tag = e.normalName();
        if ("br".equals(tag)) {
            sb.append('\n');
            return;
        }
        boolean block = BLOCKS.contains(tag);
        if (block && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        for (Node c : e.childNodes()) plain(c, sb);
        if (block && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
    }

    private static String plainToHtml(String text) {
        StringBuilder sb = new StringBuilder();
        for (String para : text.split("\\r?\\n\\s*\\r?\\n")) {
            if (para.trim().isEmpty()) continue;
            sb.append("<p>").append(escape(para.trim()).replaceAll("\\r?\\n", "<br>")).append("</p>");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------- markdown

    private static void markdownBlocks(Element parent, StringBuilder sb, String indent) {
        for (Node n : parent.childNodes()) {
            if (!(n instanceof Element)) {
                String inline = markdownInline(n).trim();
                if (!inline.isEmpty()) sb.append(indent).append(inline).append("\n\n");
                continue;
            }
            Element e = (Element) n;
            String tag = e.normalName();
            switch (tag) {
                case "h1": case "h2": case "h3": case "h4": case "h5": case "h6":
                    sb.append("#".repeat(tag.charAt(1) - '0')).append(' ').append(markdownInline(e).trim()).append("\n\n");
                    break;
                case "ul":
                case "ol": {
                    int i = 1;
                    for (Element li : e.children()) {
                        String marker = "ul".equals(tag) ? "- " : (i++) + ". ";
                        sb.append(indent).append(marker).append(markdownInline(li).trim()).append('\n');
                    }
                    sb.append('\n');
                    break;
                }
                case "blockquote":
                    sb.append("> ").append(markdownInline(e).trim()).append("\n\n");
                    break;
                case "pre":
                    sb.append("```\n").append(e.wholeText()).append("\n```\n\n");
                    break;
                case "hr":
                    sb.append("---\n\n");
                    break;
                case "div":
                case "section":
                case "article":
                    markdownBlocks(e, sb, indent);
                    break;
                default: {
                    String inline = markdownInline(e).trim();
                    if (!inline.isEmpty()) sb.append(indent).append(inline).append("\n\n");
                }
            }
        }
    }

    private static String markdownInline(Node n) {
        if (n instanceof TextNode) return ((TextNode) n).getWholeText().replaceAll("\\s+", " ");
        if (!(n instanceof Element)) return "";
        Element e = (Element) n;
        StringBuilder inner = new StringBuilder();
        for (Node c : e.childNodes()) inner.append(markdownInline(c));
        String s = inner.toString();
        switch (e.normalName()) {
            case "b": case "strong": return s.trim().isEmpty() ? s : "**" + s + "**";
            case "i": case "em": return s.trim().isEmpty() ? s : "_" + s + "_";
            case "code": return "`" + s + "`";
            case "a": return "[" + s + "](" + e.attr("href") + ")";
            case "img": return "![" + e.attr("alt") + "](" + e.attr("src") + ")";
            case "br": return "  \n";
            default: return s;
        }
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UL = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern OL = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");

    static String markdownToHtml(String md) {
        StringBuilder out = new StringBuilder();
        List<String> para = new ArrayList<>();
        String list = null;
        boolean code = false;
        StringBuilder codeText = new StringBuilder();
        for (String line : md.split("\\r?\\n", -1)) {
            if (line.trim().startsWith("```")) {
                if (code) {
                    out.append("<pre>").append(escape(codeText.toString())).append("</pre>");
                    codeText.setLength(0);
                } else {
                    flush(para, out);
                    list = closeList(list, out);
                }
                code = !code;
                continue;
            }
            if (code) {
                codeText.append(codeText.length() > 0 ? "\n" : "").append(line);
                continue;
            }
            Matcher h = HEADING.matcher(line);
            Matcher ul = UL.matcher(line);
            Matcher ol = OL.matcher(line);
            if (line.trim().isEmpty()) {
                flush(para, out);
                list = closeList(list, out);
            } else if (h.matches()) {
                flush(para, out);
                list = closeList(list, out);
                int level = h.group(1).length();
                out.append("<h").append(level).append('>').append(inlineMarkdown(h.group(2).trim())).append("</h").append(level).append('>');
            } else if (ul.matches() || ol.matches()) {
                flush(para, out);
                String kind = ul.matches() ? "ul" : "ol";
                if (!kind.equals(list)) {
                    list = closeList(list, out);
                    out.append('<').append(kind).append('>');
                    list = kind;
                }
                out.append("<li>").append(inlineMarkdown((ul.matches() ? ul : ol).group(1).trim())).append("</li>");
            } else if (line.matches("^\\s*(---+|\\*\\*\\*+)\\s*$")) {
                flush(para, out);
                list = closeList(list, out);
                out.append("<hr>");
            } else {
                list = closeList(list, out);
                para.add(line);
            }
        }
        if (code) out.append("<pre>").append(escape(codeText.toString())).append("</pre>");
        flush(para, out);
        closeList(list, out);
        return out.toString();
    }

    private static void flush(List<String> para, StringBuilder out) {
        if (para.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < para.size(); i++) {
            String l = para.get(i);
            boolean hardBreak = l.endsWith("  ") && i < para.size() - 1;
            sb.append(inlineMarkdown(l.trim()));
            if (i < para.size() - 1) sb.append(hardBreak ? "<br>" : " ");
        }
        out.append("<p>").append(sb).append("</p>");
        para.clear();
    }

    private static String closeList(String list, StringBuilder out) {
        if (list != null) out.append("</").append(list).append('>');
        return null;
    }

    private static String inlineMarkdown(String s) {
        String h = escape(s);
        h = h.replaceAll("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)", "<img src=\"$2\" alt=\"$1\">");
        h = h.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
        h = h.replaceAll("`([^`]+)`", "<code>$1</code>");
        h = h.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        h = h.replaceAll("__([^_]+)__", "<strong>$1</strong>");
        h = h.replaceAll("(?<![*\\w])\\*([^*]+)\\*(?![*\\w])", "<em>$1</em>");
        h = h.replaceAll("(?<![_\\w])_([^_]+)_(?![_\\w])", "<em>$1</em>");
        return h;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String normalizeType(String contentType) {
        return contentType == null ? PLAIN : contentType.toLowerCase(Locale.ROOT);
    }
}
