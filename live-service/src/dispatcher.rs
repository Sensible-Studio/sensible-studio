//! Reading an existing dispatcher configuration (`dispatcher.any` and the files it includes) into a
//! `live.toml`: farms become sites, their filter rules the sites' rules, their cache rules, the
//! files they invalidate, the query parameters they ignore, the grace period, the vanity URLs and
//! the auth checker what this service calls them. What has no equivalent is written as a comment,
//! so that nothing is opened that the dispatcher kept closed.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};

/// A value of the configuration: a quoted or bare word (`regex`: written in single quotes), or a
/// block of entries, named (`/name value`) or not (`"www.example.com"` in `/virtualhosts`).
#[derive(Debug, Clone, PartialEq)]
pub enum Node {
    Value { text: String, regex: bool },
    Block(Vec<(Option<String>, Node)>),
}

impl Node {
    fn get(&self, name: &str) -> Option<&Node> {
        match self {
            Node::Block(entries) => entries.iter().find(|(n, _)| n.as_deref() == Some(name)).map(|(_, v)| v),
            _ => None,
        }
    }

    fn text(&self) -> Option<&str> {
        match self {
            Node::Value { text, .. } => Some(text),
            _ => None,
        }
    }

    fn entries(&self) -> &[(Option<String>, Node)] {
        match self {
            Node::Block(entries) => entries,
            _ => &[],
        }
    }
}

#[derive(Debug, PartialEq)]
enum Token {
    Name(String),
    Open,
    Close,
    Word(String, bool),
}

fn tokens(text: &str) -> Result<Vec<Token>, String> {
    let mut out = Vec::new();
    let chars: Vec<char> = text.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c.is_whitespace() {
            i += 1;
        } else if c == '#' {
            while i < chars.len() && chars[i] != '\n' {
                i += 1;
            }
        } else if c == '{' {
            out.push(Token::Open);
            i += 1;
        } else if c == '}' {
            out.push(Token::Close);
            i += 1;
        } else if c == '"' || c == '\'' {
            let mut j = i + 1;
            let mut word = String::new();
            while j < chars.len() && chars[j] != c {
                if chars[j] == '\\' && c == '"' && j + 1 < chars.len() {
                    word.push(chars[j + 1]);
                    j += 2;
                    continue;
                }
                word.push(chars[j]);
                j += 1;
            }
            if j >= chars.len() {
                return Err(format!("a quote that is not closed: {}", chars[i..chars.len().min(i + 40)].iter().collect::<String>()));
            }
            out.push(Token::Word(word, c == '\''));
            i = j + 1;
        } else {
            let mut j = i;
            while j < chars.len() && !chars[j].is_whitespace() && !matches!(chars[j], '{' | '}' | '"' | '\'') {
                j += 1;
            }
            let word: String = chars[i..j].iter().collect();
            match word.strip_prefix('/') {
                Some(name) if !name.is_empty() && !name.contains('/') => out.push(Token::Name(name.to_owned())),
                _ => out.push(Token::Word(word, false)),
            }
            i = j;
        }
    }
    Ok(out)
}

/// Reads a dispatcher configuration file, with the files its `$include`s name (relative to the
/// file, `*` in the name allowed, as for `enabled_farms/*.farm`). `${NAME}` is left as it is.
pub fn read(file: &Path) -> Result<Node, String> {
    read_with(file, &Variables::default())
}

/// The same, with `${NAME}` in values and file names replaced by the variable's value where it has
/// one; one without a value stays as it is (and `to_toml` says so).
pub fn read_with(file: &Path, variables: &Variables) -> Result<Node, String> {
    let mut seen = Vec::new();
    let tokens = expand(file, &mut seen, variables)?;
    let mut at = 0;
    let block = parse_block(&tokens, &mut at, false)?;
    Ok(block)
}

/// The values of a dispatcher's `${NAME}`s: what AEM's dispatcher image takes from its environment
/// and from Apache's `Define`s (`conf.d/variables/*.vars` of an AEM project).
#[derive(Clone, Debug, Default)]
pub struct Variables {
    values: std::collections::HashMap<String, String>,
    /// Whether the environment is asked for a name the others have no value for.
    environment: bool,
}

impl Variables {
    /// Where the values come from, the first that has one winning: `given` (`--var NAME=VALUE`),
    /// the `files` (`--vars FILE`, `Define NAME VALUE` lines), the project's own
    /// `conf.d/variables/*.vars` beside the configuration's `conf.dispatcher.d`, the environment.
    pub fn gather(config: &Path, given: &[(String, String)], files: &[PathBuf]) -> Result<Variables, String> {
        let mut v = Variables::default();
        for (name, value) in given {
            v.values.entry(name.clone()).or_insert_with(|| value.clone());
        }
        for file in files {
            v.read_vars(file)?;
        }
        // <project>/conf.dispatcher.d/dispatcher.any → <project>/conf.d/variables/*.vars
        let mut dir = config.parent();
        while let Some(d) = dir {
            if d.file_name().is_some_and(|n| n == "conf.dispatcher.d") {
                if let Some(project) = d.parent() {
                    let vars = project.join("conf.d").join("variables");
                    if vars.is_dir() {
                        for file in matching(&vars, "*.vars")? {
                            v.read_vars(&file)?;
                        }
                    }
                }
                break;
            }
            dir = d.parent();
        }
        v.environment = true;
        Ok(v)
    }

    /// `Define NAME VALUE` lines, as Apache's; others are left out.
    fn read_vars(&mut self, file: &Path) -> Result<(), String> {
        let text = std::fs::read_to_string(file).map_err(|e| format!("{}: {e}", file.display()))?;
        for line in text.lines() {
            let line = line.trim();
            let Some(rest) = line.strip_prefix("Define").filter(|r| r.starts_with([' ', '\t'])) else { continue };
            let rest = rest.trim();
            let (name, value) = rest.split_once(char::is_whitespace).unwrap_or((rest, ""));
            let value = value.trim();
            let value = value.strip_prefix('"').and_then(|v| v.strip_suffix('"')).unwrap_or(value);
            if !name.is_empty() {
                self.values.entry(name.to_owned()).or_insert_with(|| value.to_owned());
            }
        }
        Ok(())
    }

    fn get(&self, name: &str) -> Option<String> {
        self.values.get(name).cloned().or_else(|| if self.environment { std::env::var(name).ok() } else { None })
    }

    /// `${NAME}` replaced wherever NAME has a value.
    pub fn apply(&self, text: &str) -> String {
        let mut out = String::with_capacity(text.len());
        let mut rest = text;
        while let Some(start) = rest.find("${") {
            out.push_str(&rest[..start]);
            let after = &rest[start + 2..];
            match after.find('}') {
                Some(end) if is_variable_name(&after[..end]) => {
                    match self.get(&after[..end]) {
                        Some(value) => out.push_str(&value),
                        None => out.push_str(&rest[start..start + 3 + end]),
                    }
                    rest = &after[end + 1..];
                }
                _ => {
                    out.push_str("${");
                    rest = after;
                }
            }
        }
        out.push_str(rest);
        out
    }
}

fn is_variable_name(name: &str) -> bool {
    !name.is_empty() && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

/// The `${NAME}`s of a text, each once, in order.
fn variables_in(text: &str) -> Vec<String> {
    let mut names: Vec<String> = Vec::new();
    let mut rest = text;
    while let Some(start) = rest.find("${") {
        let after = &rest[start + 2..];
        if let Some(end) = after.find('}').filter(|end| is_variable_name(&after[..*end])) {
            if !names.iter().any(|n| n == &after[..end]) {
                names.push(after[..end].to_owned());
            }
            rest = &after[end + 1..];
        } else {
            rest = after;
        }
    }
    names
}

fn expand(file: &Path, seen: &mut Vec<PathBuf>, variables: &Variables) -> Result<Vec<Token>, String> {
    let canonical = file.canonicalize().map_err(|e| format!("{}: {e}", file.display()))?;
    if seen.contains(&canonical) || seen.len() > 200 {
        return Err(format!("{} includes itself", file.display()));
    }
    seen.push(canonical);
    let text = std::fs::read_to_string(file).map_err(|e| format!("{}: {e}", file.display()))?;
    let mut out = Vec::new();
    let mut iter = tokens(&text).map_err(|e| format!("{}: {e}", file.display()))?.into_iter().peekable();
    let dir = file.parent().unwrap_or(Path::new("."));
    while let Some(t) = iter.next() {
        if t == Token::Word("$include".into(), false) {
            let Some(Token::Word(name, _)) = iter.next() else { return Err(format!("{}: $include needs a file", file.display())) };
            for included in matching(dir, &variables.apply(&name))? {
                out.extend(expand(&included, seen, variables)?);
            }
        } else if let Token::Word(text, regex) = t {
            out.push(Token::Word(variables.apply(&text), regex));
        } else {
            out.push(t);
        }
    }
    seen.pop();
    Ok(out)
}

/// The files a name of `$include` means: one, or those its `*` matches, sorted.
fn matching(dir: &Path, name: &str) -> Result<Vec<PathBuf>, String> {
    let path = dir.join(name);
    let file_name = path.file_name().and_then(|n| n.to_str()).unwrap_or("").to_owned();
    if !file_name.contains('*') {
        return Ok(vec![path]);
    }
    let parent = path.parent().unwrap_or(dir);
    let mut files: Vec<PathBuf> = std::fs::read_dir(parent)
        .map_err(|e| format!("{}: {e}", parent.display()))?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_file() && p.file_name().and_then(|n| n.to_str()).is_some_and(|n| crate::url::glob(&file_name, n)))
        .collect();
    files.sort();
    Ok(files)
}

fn parse_block(tokens: &[Token], at: &mut usize, nested: bool) -> Result<Node, String> {
    let mut entries = Vec::new();
    while *at < tokens.len() {
        match &tokens[*at] {
            Token::Close if nested => {
                *at += 1;
                return Ok(Node::Block(entries));
            }
            Token::Close => return Err("a } too many".into()),
            Token::Name(name) => {
                *at += 1;
                let value = match tokens.get(*at) {
                    Some(Token::Open) => {
                        *at += 1;
                        parse_block(tokens, at, true)?
                    }
                    Some(Token::Word(text, regex)) => {
                        *at += 1;
                        Node::Value { text: text.clone(), regex: *regex }
                    }
                    _ => return Err(format!("/{name} has no value")),
                };
                entries.push((Some(name.clone()), value));
            }
            Token::Word(text, regex) => {
                *at += 1;
                entries.push((None, Node::Value { text: text.clone(), regex: *regex }));
            }
            Token::Open => {
                *at += 1;
                entries.push((None, parse_block(tokens, at, true)?));
            }
        }
    }
    if nested {
        return Err("a { that is not closed".into());
    }
    Ok(Node::Block(entries))
}

/// A regular expression of a dispatcher rule as a list of plain words, when it is no more than
/// that: `(GET|HEAD)`, `html|json`, `^model$`.
fn regex_words(regex: &str) -> Option<Vec<String>> {
    let r = regex.trim().trim_start_matches('^').trim_end_matches('$');
    let r = r.strip_prefix('(').and_then(|r| r.strip_suffix(')')).unwrap_or(r);
    let words: Vec<String> = r.split('|').map(|w| w.replace("\\.", ".")).collect();
    words.iter().all(|w| !w.is_empty() && w.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | ':'))).then_some(words)
}

/// A regular expression as a glob, when it is one in disguise: `/content/.*`, `^/content/site/.*\.html$`.
fn regex_glob(regex: &str) -> Option<String> {
    let mut r = regex.trim().to_owned();
    let anchored_end = r.ends_with('$');
    r = r.trim_start_matches('^').trim_end_matches('$').to_owned();
    let mut out = String::new();
    let chars: Vec<char> = r.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        match chars[i] {
            '.' if chars.get(i + 1) == Some(&'*') => {
                out.push('*');
                i += 2;
            }
            '.' if chars.get(i + 1) == Some(&'+') => {
                out.push_str("?*");
                i += 2;
            }
            '\\' if i + 1 < chars.len() && matches!(chars[i + 1], '.' | '/' | '-' | '_') => {
                out.push(chars[i + 1]);
                i += 2;
            }
            c if c.is_ascii_alphanumeric() || matches!(c, '/' | '-' | '_' | ':' | '~' | '@' | ',' | '=') => {
                out.push(c);
                i += 1;
            }
            _ => return None,
        }
    }
    let _ = anchored_end; // a dispatcher regex matches the whole value either way
    Some(out)
}

/// What a value of a rule says, as a glob (double quotes) or as words.
fn glob_of(node: &Node) -> Option<String> {
    match node {
        Node::Value { text, regex: false } => Some(text.clone()),
        Node::Value { text, regex: true } => regex_glob(text),
        _ => None,
    }
}

fn words_of(node: &Node) -> Option<Vec<String>> {
    match node {
        Node::Value { text, regex: false } => Some(text.split_whitespace().map(str::to_owned).collect()),
        Node::Value { text, regex: true } => regex_words(text),
        _ => None,
    }
}

/// The alternatives of a regex that are plain words, and those that are not: a rule that keeps
/// only the words matches less than the dispatcher's, which for a selector or extension list is
/// the side to err on (an allow opens less; a deny's other cases are mostly closed anyway).
fn some_words(node: &Node) -> Option<(Vec<String>, Vec<String>)> {
    let Node::Value { text, regex: true } = node else { return words_of(node).map(|w| (w, Vec::new())) };
    let r = text.trim().trim_start_matches('^').trim_end_matches('$');
    let r = r.strip_prefix('(').and_then(|r| r.strip_suffix(')')).unwrap_or(r);
    let (mut words, mut dropped) = (Vec::new(), Vec::new());
    for w in r.split('|') {
        let plain = w.replace("\\.", ".");
        if !plain.is_empty() && plain.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | ':')) {
            words.push(plain);
        } else {
            dropped.push(w.to_owned());
        }
    }
    (!words.is_empty()).then_some((words, dropped))
}

fn toml_string(s: &str) -> String {
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            c if (c as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn toml_list(items: &[String]) -> String {
    format!("[{}]", items.iter().map(|s| toml_string(s)).collect::<Vec<_>>().join(", "))
}

/// A dispatcher regex that this service's regular expressions read the same way, or why not.
fn readable(text: &str) -> Result<&str, String> {
    regex::Regex::new(&format!("^(?:{text})$")).map(|_| text).map_err(|_| format!("the regex {text} is not one this service can read"))
}

/// One filter rule of a farm as `[[site.filter]]` lines, or the reason it is not one.
fn filter_rule(rule: &Node) -> Result<Vec<String>, String> {
    let mut lines = Vec::new();
    let kind = rule.get("type").and_then(Node::text).map(str::to_ascii_lowercase);
    match kind.as_deref() {
        Some("allow") | Some("deny") => lines.push(format!("type = \"{}\"", kind.unwrap())),
        _ => return Err("no /type allow or deny".into()),
    }
    for (name, value) in rule.entries() {
        let Some(name) = name else { return Err("an entry without a name".into()) };
        match name.as_str() {
            "type" => {}
            "method" => lines.push(format!("method = {}", toml_list(&words_of(value).ok_or("a /method regex that is not a list of words")?))),
            // a glob when the value is one (or a regex that is a glob in disguise), else the regex itself
            "url" | "path" | "query" | "suffix" => match (glob_of(value), value) {
                (Some(glob), _) => lines.push(format!("{name} = {}", toml_string(&glob))),
                (None, Node::Value { text, regex: true }) => lines.push(format!("{name} = {{ regex = {} }}", toml_string(readable(text)?))),
                _ => return Err(format!("a /{name} that is not a value")),
            },
            "extension" | "selectors" => {
                let key = if name == "extension" { "extensions" } else { "selectors" };
                match (some_words(value), value) {
                    (Some((words, dropped)), _) if dropped.is_empty() => lines.push(format!("{key} = {}", toml_list(&words))),
                    (_, Node::Value { text, regex: true }) => {
                        let r = text.trim().trim_start_matches('^').trim_end_matches('$');
                        lines.push(format!("{key} = {{ regex = {} }}", toml_string(readable(r)?)));
                    }
                    _ => return Err(format!("a /{name} with no word in it")),
                }
            }
            "glob" => {
                // the old form: "<method> <url> [protocol]"
                let text = value.text().ok_or("a /glob that is not a value")?;
                let parts: Vec<&str> = text.split_whitespace().collect();
                match parts.as_slice() {
                    [url] => lines.push(format!("url = {}", toml_string(url))),
                    [method, url, ..] => {
                        if *method != "*" {
                            lines.push(format!("method = {}", toml_list(&[method.to_string()])));
                        }
                        lines.push(format!("url = {}", toml_string(url)));
                    }
                    _ => return Err("an empty /glob".into()),
                }
            }
            other => return Err(format!("/{other} has no equivalent here")),
        }
    }
    Ok(lines)
}

/// The globs of a rule block (`/0000 { /glob "*" /type "deny" }`) by kind, in order.
fn globs(block: Option<&Node>) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for (_, rule) in block.map(Node::entries).unwrap_or(&[]) {
        if let (Some(kind), Some(glob)) = (rule.get("type").and_then(Node::text), rule.get("glob").and_then(Node::text)) {
            out.push((kind.to_ascii_lowercase(), glob.to_owned()));
        }
    }
    out
}

/// Trees below /content that are no site's pages, though filter rules open them.
const NOT_SITES: &[&str] = &[
    "dam", "experience-fragments", "forms", "screens", "_cq_graphql", "graphql", "usergenerated", "campaigns", "launches",
    "projects", "communities", "sites", "catalogs",
];

fn site_name(farm: &str) -> String {
    let name: String = farm.chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '-' }).collect();
    if name.is_empty() {
        "site".into()
    } else {
        name
    }
}

/// A virtual host of a farm as a host name: without a scheme, a port or a path.
fn host_of(vhost: &str) -> Option<String> {
    let v = vhost.split("://").last().unwrap_or(vhost);
    let v = v.split('/').next().unwrap_or(v);
    let v = v.rsplit_once(':').filter(|(_, port)| port.chars().all(|c| c.is_ascii_digit() || c == '*')).map_or(v, |(h, _)| h);
    (!v.is_empty()).then(|| v.to_ascii_lowercase())
}

/// The configuration as a `live.toml`, with what could not be carried over as comments.
pub fn to_toml(root: &Node, source: &Path) -> Result<String, String> {
    let farms = root.get("farms").ok_or("no /farms in the configuration")?;
    let mut out = String::new();
    let mut notes: Vec<String> = Vec::new();
    let _ = writeln!(out, "# Made from {} by studio-live --import-dispatcher.", source.display());
    let _ = writeln!(out, "# Read it through before using it: every line that could not be carried over is a comment that says why.");
    let mut cache_done = false;
    let mut vanity_url = None;
    let mut balance: Option<String> = None;
    let mut sites = String::new();
    let mut all_publish: Vec<String> = Vec::new();
    for (farm_name, farm) in farms.entries() {
        let Some(farm_name) = farm_name else { continue };
        let name = site_name(farm_name);
        let mut site = String::new();
        let _ = writeln!(site, "\n# farm /{farm_name}\n[[site]]\nname = {}", toml_string(&name));
        // host names
        let mut hosts: Vec<String> = Vec::new();
        for (_, v) in farm.get("virtualhosts").map(Node::entries).unwrap_or(&[]) {
            if let Some(h) = v.text().and_then(host_of) {
                if !hosts.contains(&h) {
                    hosts.push(h);
                }
            }
        }
        if hosts.is_empty() {
            hosts.push(format!("{name}.example"));
            let _ = writeln!(site, "# no /virtualhosts: give the site its host names");
        }
        let _ = writeln!(site, "hosts = {}", toml_list(&hosts));
        // roots: the trees below /content its allow rules open
        let mut roots: Vec<String> = Vec::new();
        for (_, rule) in farm.get("filter").map(Node::entries).unwrap_or(&[]) {
            if rule.get("type").and_then(Node::text).is_some_and(|t| t.eq_ignore_ascii_case("allow")) {
                for field in ["url", "path", "glob"] {
                    if let Some(g) = rule.get(field).and_then(glob_of) {
                        let g = g.split_whitespace().find(|p| p.starts_with('/')).unwrap_or(&g).to_owned();
                        let segments: Vec<&str> = g.trim_start_matches('/').split('/').collect();
                        if segments.len() >= 2 && segments[0] == "content" && !segments[1].contains(['*', '?', '.', ':', '['])
                            && !NOT_SITES.contains(&segments[1])
                        {
                            let root = format!("/content/{}", segments[1]);
                            if !roots.contains(&root) {
                                roots.push(root);
                            }
                        }
                    }
                }
            }
        }
        if roots.is_empty() {
            roots.push(format!("/content/{name}"));
            let _ = writeln!(site, "# its pages are not named by a filter rule: say where they are");
        }
        let _ = writeln!(site, "roots = {}", toml_list(&roots));
        let _ = writeln!(site, "# what it shows from elsewhere (experience fragments, media, /conf): list it in shared");
        // publish instances
        let mut publish = Vec::new();
        for (_, render) in farm.get("renders").map(Node::entries).unwrap_or(&[]) {
            if let (Some(host), Some(port)) = (render.get("hostname").and_then(Node::text), render.get("port").and_then(Node::text)) {
                let secure = render.get("secure").and_then(Node::text) == Some("1");
                publish.push(format!("{}://{host}:{port}", if secure { "https" } else { "http" }));
            }
        }
        if !publish.is_empty() {
            let _ = writeln!(site, "publish = {}", toml_list(&publish));
            for p in &publish {
                if !all_publish.contains(p) {
                    all_publish.push(p.clone());
                }
            }
        }
        // the request headers the publish is told; "*" is all of them, which is what no list means here
        let told: Vec<String> = farm
            .get("clientheaders")
            .map(Node::entries)
            .unwrap_or(&[])
            .iter()
            .filter_map(|(_, v)| v.text())
            .map(|h| h.trim().to_ascii_lowercase())
            .filter(|h| !h.is_empty())
            .collect();
        if !told.is_empty() && !told.iter().any(|h| h == "*") {
            let mut told = told;
            told.dedup();
            let _ = writeln!(site, "client_headers = {}", toml_list(&told));
        }
        // the auth checker's pages: protected
        if let Some(checker) = farm.get("auth_checker") {
            let protected: Vec<String> =
                globs(checker.get("filter")).into_iter().filter(|(k, _)| k == "allow").map(|(_, g)| g).collect();
            if !protected.is_empty() {
                let _ = writeln!(site, "# the auth checker's pages; the check is cache.auth_checker on the publish (studio-auth), not {}",
                    checker.get("url").and_then(Node::text).unwrap_or("its /url"));
                let _ = writeln!(site, "protected = {}", toml_list(&protected));
            }
        }
        // filter rules
        let mut rules = String::new();
        for (id, rule) in farm.get("filter").map(Node::entries).unwrap_or(&[]) {
            let id = id.as_deref().unwrap_or("?");
            let deny = rule.get("type").and_then(Node::text).is_some_and(|t| t.eq_ignore_ascii_case("deny"));
            match filter_rule(rule) {
                Ok(lines) => {
                    let _ = writeln!(rules, "\n# /{id}\n[[site.filter]]\n{}", lines.join("\n"));
                }
                Err(why) => {
                    let warning = if deny { " WARNING: a deny rule, so what it closed is open here unless another rule closes it" } else { "" };
                    let _ = writeln!(rules, "\n# /{id} not carried over ({why}){warning}; it was:");
                    for (n, v) in rule.entries() {
                        let shown = match v {
                            Node::Value { text, regex: true } => format!("'{text}'"),
                            Node::Value { text, .. } => format!("\"{text}\""),
                            Node::Block(_) => "{ … }".into(),
                        };
                        let _ = writeln!(rules, "#   /{} {shown}", n.as_deref().unwrap_or(""));
                    }
                    notes.push(format!("farm /{farm_name}: filter /{id} not carried over ({why}){}", if deny { ", a DENY rule" } else { "" }));
                }
            }
        }
        // the cache settings: the first farm's are the general ones ([cache]); every other farm's are
        // its site's own ([site.cache], before its filter rules: a table's keys come before the next one)
        if let Some(cache) = farm.get("cache") {
            if cache_done {
                let _ = write!(site, "\n# its own cache settings (/cache of the farm)\n[site.cache]\n{}", cache_settings(cache));
                if let Some(level) = cache.get("statfileslevel").and_then(Node::text).and_then(|l| l.parse::<usize>().ok()) {
                    let _ = writeln!(site, "stat_level = {level}");
                }
            } else {
                cache_done = true;
                sites.insert_str(0, &format!("\n[cache]\n{}", cache_settings(cache)));
                if let Some(level) = cache.get("statfileslevel").and_then(Node::text).and_then(|l| l.parse::<usize>().ok()) {
                    out.push_str(&format!("stat_level = {level}\n"));
                }
                let docroot = cache.get("docroot").and_then(Node::text).unwrap_or("cache");
                out.push_str(&format!("docroot = {}\n", toml_string(docroot)));
            }
        }
        site.push_str(&rules);
        sites.push_str(&site);
        // how the renders are chosen and checked: one setting for all, the first farm's
        if balance.is_none() {
            if let Some(check) = farm.get("health_check").and_then(|h| h.get("url")).and_then(Node::text) {
                balance.get_or_insert_with(String::new).push_str(&format!("health_check = {}\n", toml_string(check)));
            }
            if farm.get("statistics").is_some() {
                balance.get_or_insert_with(String::new).push_str("balance = \"fastest\"\n");
            }
            // in tenths of a second
            if let Some(penalty) = farm.get("unavailablePenalty").and_then(Node::text).and_then(|p| p.parse::<u64>().ok()) {
                balance.get_or_insert_with(String::new).push_str(&format!("unavailable_penalty_ms = {}\n", penalty * 100));
            }
        }
        if let Some(vanity) = farm.get("vanity_urls").and_then(|v| v.get("url")).and_then(Node::text) {
            vanity_url = Some(vanity.replace("vanityUrls.html", "vanityUrls.json"));
        }
    }
    if !cache_done {
        out.push_str("docroot = \"cache\"\n");
    }
    if all_publish.is_empty() {
        all_publish.push("http://127.0.0.1:4503".into());
        out.push_str("# no /renders: the publish instances to ask\n");
    }
    out.push_str(&format!("publish = {}\n", toml_list(&all_publish)));
    if let Some(lines) = balance {
        out.push_str(&lines);
    }
    if let Some(url) = vanity_url {
        out.push_str(&format!("\n[vanity]\nurl = {}\n", toml_string(&url)));
    }
    out.push_str(&sites);
    let missing: Vec<String> = variables_in(&format!("{out}{sites}")).into_iter().map(|n| format!("${{{n}}}")).collect();
    if !missing.is_empty() {
        notes.push(format!(
            "{} had no value (the dispatcher's environment variables or Apache Defines): give them with --var NAME=VALUE or --vars FILE, or put the values in",
            missing.join(", ")
        ));
    }
    if !notes.is_empty() {
        out.push_str("\n# Not carried over:\n");
        for n in notes {
            out.push_str(&format!("#   {n}\n"));
        }
    }
    Ok(out)
}

/// A farm's `/cache` as the keys of a `[cache]` or `[site.cache]` table.
fn cache_settings(cache: &Node) -> String {
    let mut c = String::new();
    let rules = globs(cache.get("rules"));
    if !rules.is_empty() {
        let items: Vec<String> = rules.iter().map(|(k, g)| format!("  {{ type = \"{k}\", glob = {} }}", toml_string(g))).collect();
        let _ = writeln!(c, "rules = [\n{},\n]", items.join(",\n"));
    }
    let invalidate: Vec<String> = globs(cache.get("invalidate")).into_iter().filter(|(k, _)| k == "allow").map(|(_, g)| g).collect();
    if !invalidate.is_empty() {
        let _ = writeln!(c, "invalidate = {}", toml_list(&invalidate));
    }
    let ignored: Vec<String> = globs(cache.get("ignoreUrlParams")).into_iter().filter(|(k, _)| k == "allow").map(|(_, g)| g).collect();
    if !ignored.is_empty() {
        let _ = writeln!(c, "ignore_query = {}", toml_list(&ignored));
    }
    let mut headers: Vec<String> = Vec::new();
    for (_, v) in cache.get("headers").map(Node::entries).unwrap_or(&[]) {
        if let Some(h) = v.text() {
            headers.push(h.to_owned());
        }
    }
    if !headers.is_empty() {
        let _ = writeln!(c, "headers = {}", toml_list(&headers));
    }
    if let Some(grace) = cache.get("gracePeriod").and_then(Node::text).and_then(|g| g.parse::<u64>().ok()) {
        let _ = writeln!(c, "grace_seconds = {grace}");
    }
    if cache.get("allowAuthorized").and_then(Node::text) == Some("1") {
        let _ = writeln!(c, "# /allowAuthorized \"1\": signed-in visitors get kept pages, and their pages are kept: only if no page is personal");
        let _ = writeln!(c, "allow_authorized = true");
    }
    if cache.get("enableTTL").and_then(Node::text) == Some("1") {
        let _ = writeln!(c, "# /enableTTL \"1\": kept as long as the publish's Cache-Control (or Expires) says");
        let _ = writeln!(c, "enable_ttl = true");
    }
    c
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn regexes_that_are_lists_or_globs() {
        assert_eq!(regex_words("(GET|HEAD)"), Some(vec!["GET".into(), "HEAD".into()]));
        assert_eq!(regex_words("^(model|search\\.results)$"), Some(vec!["model".into(), "search.results".into()]));
        assert_eq!(regex_words("(a|b.*)"), None);
        assert_eq!(regex_glob("/content/.*"), Some("/content/*".into()));
        assert_eq!(regex_glob("^/content/site/.*\\.html$"), Some("/content/site/*.html".into()));
        assert_eq!(regex_glob("/content/(a|b)/.*"), None);
        assert_eq!(host_of("https://www.Example.com:443/content/x"), Some("www.example.com".into()));
        assert_eq!(host_of("*"), Some("*".into()));
    }
}
