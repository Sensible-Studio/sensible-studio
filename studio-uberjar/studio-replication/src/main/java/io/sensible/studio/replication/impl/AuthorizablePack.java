/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.jcr.AccessDeniedException;
import javax.jcr.ImportUUIDBehavior;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlEntry;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * A user or a group on its way from one publish instance to another: what a visitor's sign-in needs
 * (the password's hash), the profile, and for a group its members. A zip with {@code authorizable.json}
 * (path, id, group or user) and {@code authorizable.xml}, the node in JCR's system view: users cannot be
 * written node by node (the name, the password and the members are the repository's to write), so the
 * receiving repository imports them, which is what its user management is made for.
 *
 * <p>Never carried and never taken: system users, {@code admin}, {@code anonymous}, the
 * {@code administrators} and {@code everyone} groups, other people's access rights on the node and login
 * tokens; of the access rights only what the user may do with the own node. Who administers an
 * instance is decided on that instance.
 */
final class AuthorizablePack {

    static final String META_ENTRY = "authorizable.json";
    static final String XML_ENTRY = "authorizable.xml";
    /** Marks what this class writes, so that the instance does not offer it to the others again. */
    static final String MARK = "studio:authorizable-sync";
    /** Where users and groups are: Sling's places, and the ones Oak uses when it is not told. */
    private static final String[] USER_ROOTS = {"/home/users", "/rep:security/rep:authorizables/rep:users"};
    private static final String[] GROUP_ROOTS = {"/home/groups", "/rep:security/rep:authorizables/rep:groups"};
    /** What to watch for changes of users and groups. */
    static final String[] HOMES = {"/home", "/rep:security/rep:authorizables"};
    static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final String SV = "http://www.jcp.org/jcr/sv/1.0";
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList("admin", "anonymous", "administrators", "everyone"));
    /** Below a user or a group, and not for other instances: who may do what there, and login tokens. */
    private static final Set<String> NOT_CARRIED = new HashSet<>(Arrays.asList("rep:policy", "rep:cugPolicy", ".tokens"));

    private AuthorizablePack() {
    }

    static boolean isAuthorizablePath(String path) {
        return path != null && (root(path, false) != null || root(path, true) != null) && plain(path);
    }

    /** The folder of all users (or of all groups) this path is below, if it is. */
    static String root(String path, boolean group) {
        for (String root : group ? GROUP_ROOTS : USER_ROOTS) {
            if (path != null && path.startsWith(root + "/")) {
                return root;
            }
        }
        return null;
    }

    /** Below the folder of the system users, which belong to their instance. */
    static boolean systemPath(String path) {
        String root = root(path, false);
        return root != null && path.startsWith(root + "/system/");
    }

    private static boolean plain(String path) {
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /** Part of a user's or group's node that stays on its instance. */
    static boolean notCarried(String path) {
        for (String segment : path.split("/")) {
            if (NOT_CARRIED.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /** The user or group a path belongs to (itself, or the one it is a part of), if it is one that travels. */
    static Node unitOf(Session session, String path) throws RepositoryException {
        if (!isAuthorizablePath(path)) {
            return null;
        }
        for (String p = path; isAuthorizablePath(p); p = p.substring(0, p.lastIndexOf('/'))) {
            if (session.nodeExists(p)) {
                Node node = session.getNode(p);
                if (node.isNodeType("rep:Authorizable")) {
                    return travels(session, node) ? node : null;
                }
            }
        }
        return null;
    }

    private static boolean travels(Session session, Node node) throws RepositoryException {
        if (node.isNodeType("rep:SystemUser")) {
            return false;
        }
        Authorizable a = users(session).getAuthorizableByPath(node.getPath());
        return a != null && !NEVER.contains(a.getID()) && !NEVER.contains(a.getPrincipal().getName());
    }

    private static UserManager users(Session session) throws RepositoryException {
        if (!(session instanceof JackrabbitSession)) {
            throw new RepositoryException("This repository has no user management to ask");
        }
        return ((JackrabbitSession) session).getUserManager();
    }

    /**
     * Every user and group of this instance that travels (not a system user, not who administers it), as
     * {@code {path, id, group}}: the users first, so that a group's members exist where it arrives.
     */
    static java.util.List<java.util.Map<String, Object>> travelling(Session session) throws RepositoryException {
        java.util.List<java.util.Map<String, Object>> users = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> groups = new java.util.ArrayList<>();
        // every authorizable has a principal name: the user manager finds them wherever they are kept
        for (java.util.Iterator<Authorizable> it = users(session).findAuthorizables("rep:principalName", null, UserManager.SEARCH_TYPE_AUTHORIZABLE);
                it.hasNext();) {
            Authorizable a = it.next();
            String path = a.getPath();
            Node unit = unitOf(session, path);
            if (unit == null || !unit.getPath().equals(path)) {
                continue;
            }
            Node node = unit;
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("path", node.getPath());
            m.put("id", a.getID());
            m.put("group", a.isGroup());
            (a.isGroup() ? groups : users).add(m);
        }
        java.util.Comparator<java.util.Map<String, Object>> byPath = java.util.Comparator.comparing(m -> String.valueOf(m.get("path")));
        users.sort(byPath);
        groups.sort(byPath);
        users.addAll(groups);
        return users;
    }

    // ------------------------------------------------------------------ writing

    /** Packs the user or group at this node, which {@link #unitOf} gave. */
    static byte[] pack(Session session, Node node) throws RepositoryException, IOException {
        Authorizable a = users(session).getAuthorizableByPath(node.getPath());
        if (a == null || !travels(session, node)) {
            throw new RepositoryException(node.getPath() + " is not a user or group that is sent to other instances");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("path", node.getPath());
        meta.put("id", a.getID());
        meta.put("group", a.isGroup());
        try {
            meta.put("own", ownRights(session, node.getPath(), a.getPrincipal().getName()));
        } catch (AccessDeniedException e) {
            // this instance does not let the sender read access rights: the receiver keeps what it has
        }
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        try {
            session.exportSystemView(node.getPath(), new Writer(xml), false, false);
        } catch (SAXException e) {
            throw new IOException("Could not write " + node.getPath() + ": " + e.getMessage(), e);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(META_ENTRY));
            zip.write(Json.write(meta).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(XML_ENTRY));
            zip.write(xml.toByteArray());
            zip.closeEntry();
        }
        if (bytes.size() > MAX_BYTES) {
            throw new IOException(node.getPath() + " is larger than what is sent to other instances");
        }
        return bytes.toByteArray();
    }

    /**
     * What the user may do with the own node (the profile, the password): the one access right that
     * travels, by name, because a user who cannot change the own profile on the next instance is not
     * the same user there. Everybody else's rights on the node are that instance's own matter.
     */
    static List<String> ownRights(Session session, String path, String principal) throws RepositoryException {
        List<String> names = new ArrayList<>();
        for (AccessControlPolicy policy : session.getAccessControlManager().getPolicies(path)) {
            if (!(policy instanceof JackrabbitAccessControlList)) {
                continue;
            }
            for (AccessControlEntry entry : ((JackrabbitAccessControlList) policy).getAccessControlEntries()) {
                if (!(entry instanceof JackrabbitAccessControlEntry) || !((JackrabbitAccessControlEntry) entry).isAllow()
                        || ((JackrabbitAccessControlEntry) entry).getRestrictionNames().length > 0 || !principal.equals(entry.getPrincipal().getName())) {
                    continue;
                }
                for (Privilege privilege : entry.getPrivileges()) {
                    if (!names.contains(privilege.getName())) {
                        names.add(privilege.getName());
                    }
                }
            }
        }
        return names;
    }

    private static void grantOwn(Session session, Authorizable a, List<String> names) throws RepositoryException {
        if (names.isEmpty()) {
            return;
        }
        AccessControlManager acm = session.getAccessControlManager();
        Privilege[] privileges = new Privilege[names.size()];
        for (int i = 0; i < privileges.length; i++) {
            try {
                privileges[i] = acm.privilegeFromName(names.get(i));
            } catch (AccessControlException e) {
                throw new RepositoryException("Refused: " + names.get(i) + " is no right this repository knows");
            }
        }
        JackrabbitAccessControlList acl = null;
        for (AccessControlPolicyIterator it = acm.getApplicablePolicies(a.getPath()); it.hasNext() && acl == null;) {
            AccessControlPolicy policy = it.nextAccessControlPolicy();
            acl = policy instanceof JackrabbitAccessControlList ? (JackrabbitAccessControlList) policy : null;
        }
        for (AccessControlPolicy policy : acl == null ? acm.getPolicies(a.getPath()) : new AccessControlPolicy[0]) {
            acl = policy instanceof JackrabbitAccessControlList ? (JackrabbitAccessControlList) policy : acl;
        }
        if (acl == null) {
            throw new RepositoryException("No access rights can be set on " + a.getPath());
        }
        acl.addEntry(a.getPrincipal(), privileges, true);
        acm.setPolicy(a.getPath(), acl);
    }

    /** The system view as text, without what stays on the instance. Element and attribute names are the system view's own. */
    private static final class Writer extends DefaultHandler {
        private final StringBuilder out = new StringBuilder(4096);
        private final ByteArrayOutputStream target;
        private int depth;
        private int skipFrom = -1;

        Writer(ByteArrayOutputStream target) {
            this.target = target;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            depth++;
            if (skipFrom >= 0) {
                return;
            }
            String name = svName(atts);
            if ("node".equals(localName(localName, qName)) && name != null && NOT_CARRIED.contains(name)) {
                skipFrom = depth;
                return;
            }
            out.append('<').append(qName);
            if (depth == 1) {
                out.append(" xmlns:sv=\"").append(SV).append('"')
                        .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
                        .append(" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"");
            }
            for (int i = 0; i < atts.getLength(); i++) {
                String q = atts.getQName(i);
                if (q == null || q.isEmpty() || q.equals("xmlns") || q.startsWith("xmlns:")) {
                    continue;
                }
                out.append(' ').append(q).append("=\"");
                escape(atts.getValue(i), true);
                out.append('"');
            }
            out.append('>');
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (skipFrom < 0) {
                out.append("</").append(qName).append('>');
            } else if (skipFrom == depth) {
                skipFrom = -1;
            }
            depth--;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (skipFrom < 0) {
                escape(new String(ch, start, length), false);
            }
        }

        @Override
        public void endDocument() throws SAXException {
            try {
                target.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>".getBytes(StandardCharsets.UTF_8));
                target.write(out.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        private void escape(String text, boolean attribute) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '&': out.append("&amp;"); break;
                    case '<': out.append("&lt;"); break;
                    case '>': out.append("&gt;"); break;
                    case '"': out.append(attribute ? "&quot;" : "\""); break;
                    case '\n': out.append(attribute ? "&#10;" : "\n"); break;
                    case '\r': out.append("&#13;"); break;
                    case '\t': out.append(attribute ? "&#9;" : "\t"); break;
                    default: out.append(c);
                }
            }
        }
    }

    private static String localName(String localName, String qName) {
        if (localName != null && !localName.isEmpty()) {
            return localName;
        }
        return qName == null ? "" : qName.substring(qName.indexOf(':') + 1);
    }

    private static String svName(Attributes atts) {
        String name = atts.getValue(SV, "name");
        return name != null ? name : atts.getValue("sv:name");
    }

    // ------------------------------------------------------------------ reading

    /** What a pack holds. */
    static final class Unpacked {
        final String path;
        final String id;
        final boolean group;
        final byte[] xml;
        /** What the user may do with the own node, by name; null: the sender did not say. */
        final List<String> own;

        Unpacked(String path, String id, boolean group, byte[] xml, List<String> own) {
            this.path = path;
            this.id = id;
            this.group = group;
            this.xml = xml;
            this.own = own;
        }
    }

    /** Is this the pack of a user or a group (and not of content)? */
    static boolean is(byte[] zip) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry first = in.getNextEntry();
            return first != null && META_ENTRY.equals(first.getName());
        } catch (IOException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    static Unpacked unpack(byte[] zip) throws IOException {
        if (zip.length > MAX_BYTES) {
            throw new IOException("Larger than a user or a group is taken");
        }
        Map<String, Object> meta = null;
        byte[] xml = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                byte[] bytes = limited(in);
                if (META_ENTRY.equals(e.getName())) {
                    Object parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
                    meta = parsed instanceof Map ? (Map<String, Object>) parsed : null;
                } else if (XML_ENTRY.equals(e.getName())) {
                    xml = bytes;
                }
            }
        }
        if (meta == null || xml == null || !(meta.get("path") instanceof String) || !(meta.get("id") instanceof String)) {
            throw new IOException("Not the pack of a user or a group");
        }
        List<String> own = meta.get("own") instanceof List ? new ArrayList<>() : null;
        if (own != null) {
            for (Object name : (List<Object>) meta.get("own")) {
                if (!(name instanceof String) || !((String) name).matches("[A-Za-z]{1,16}:[A-Za-z]{1,48}") || own.size() >= 32) {
                    throw new IOException("Not the pack of a user or a group");
                }
                own.add((String) name);
            }
        }
        return new Unpacked((String) meta.get("path"), (String) meta.get("id"), Boolean.TRUE.equals(meta.get("group")), xml, own);
    }

    private static byte[] limited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) {
                throw new IOException("Larger than a user or a group is taken");
            }
        }
        return out.toByteArray();
    }

    /**
     * Imports the user or group into this repository: new, or in place of the one with the same id. The
     * session is one that may manage users; the caller saves. Refuses whatever is not exactly one user or
     * group that travels, and whatever would take the place of anything but that same user or group.
     */
    static void install(Session session, Unpacked pack) throws RepositoryException {
        String root = root(pack.path, pack.group);
        if (!isAuthorizablePath(pack.path) || root == null || systemPath(pack.path)
                || notCarried(pack.path) || pack.id.isEmpty() || NEVER.contains(pack.id)) {
            throw new RepositoryException("Refused: " + pack.path + " is not a user or group that other instances send");
        }
        // what the pack says it is, against what its content is
        Shape shape = new Shape();
        parse(pack.xml, shape);
        String name = pack.path.substring(pack.path.lastIndexOf('/') + 1);
        if (shape.problem != null || shape.roots != 1 || !name.equals(shape.name) || !pack.id.equals(shape.id) || shape.uuid == null
                || !(pack.group ? "rep:Group" : "rep:User").equals(shape.type) || (shape.principal != null && NEVER.contains(shape.principal))) {
            throw new RepositoryException("Refused: the pack for " + pack.path + " does not hold the " + (pack.group ? "group " : "user ") + pack.id
                    + (shape.problem == null ? "" : " (" + shape.problem + ")"));
        }
        // an import replaces the node that has the same identifier: that may only be this user or group itself
        UserManager users = users(session);
        Authorizable existing = users.getAuthorizable(pack.id);
        try {
            Node same = session.getNodeByIdentifier(shape.uuid);
            Authorizable there = users.getAuthorizableByPath(same.getPath());
            if (there == null || existing == null || !there.getID().equals(pack.id) || !same.getPath().equals(existing.getPath())) {
                throw new RepositoryException("Refused: the pack for " + pack.id + " would replace " + same.getPath());
            }
        } catch (ItemNotFoundException e) {
            if (existing != null) {
                throw new RepositoryException("Refused: " + pack.id + " exists here as another node than the one sent");
            }
        }
        if (existing != null && (existing.isGroup() != pack.group || (!existing.isGroup() && ((User) existing).isSystemUser()))) {
            throw new RepositoryException("Refused: " + pack.id + " is something else here");
        }
        // what the user may do with the own node: as the sender says, or as it is here
        List<String> own = pack.own != null ? pack.own
                : existing != null ? ownRights(session, existing.getPath(), existing.getPrincipal().getName()) : new ArrayList<>();
        // new here: at the place it has on the sending instance, with the folders on the way
        String parent = pack.path.substring(0, pack.path.lastIndexOf('/'));
        if (existing == null) {
            Node folder = session.getNode(root);
            for (String segment : parent.substring(root.length()).split("/")) {
                if (!segment.isEmpty()) {
                    folder = folder.hasNode(segment) ? folder.getNode(segment) : folder.addNode(segment, "rep:AuthorizableFolder");
                }
            }
        } else {
            // known here: replaced where it is, which need not be where the sender has it
            parent = existing.getPath().substring(0, existing.getPath().lastIndexOf('/'));
        }
        mark(session);
        ContentHandler importer = session.getImportContentHandler(parent, ImportUUIDBehavior.IMPORT_UUID_COLLISION_REPLACE_EXISTING);
        parse(pack.xml, new OwnIdentifiersOnly(importer));
        Authorizable now = users.getAuthorizable(pack.id);
        if (now == null || now.isGroup() != pack.group || (!now.isGroup() && ((User) now).isSystemUser())) {
            throw new RepositoryException("Refused: after the import " + pack.id + " is not the " + (pack.group ? "group" : "user") + " that was sent");
        }
        // the import put a new node in the place of the old one: what the user may do with it is set again
        grantOwn(session, now, own);
    }

    /** Removes the user or group at this path, if there is one that travels. The caller saves. */
    static boolean remove(Session session, String path) throws RepositoryException {
        if (!isAuthorizablePath(path) || systemPath(path) || !session.nodeExists(path)) {
            return false;
        }
        Node node = session.getNode(path);
        if (!node.isNodeType("rep:Authorizable") || !travels(session, node)) {
            return false;
        }
        mark(session);
        users(session).getAuthorizableByPath(path).remove();
        return true;
    }

    private static void mark(Session session) throws RepositoryException {
        session.getWorkspace().getObservationManager().setUserData(MARK);
    }

    private static void parse(byte[] xml, ContentHandler handler) throws RepositoryException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setContentHandler(handler);
            reader.parse(new InputSource(new ByteArrayInputStream(xml)));
        } catch (SAXException | ParserConfigurationException | IOException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RepositoryException) {
                throw (RepositoryException) cause;
            }
            throw new RepositoryException("Refused: the user or group cannot be read (" + e.getMessage() + ")", e);
        }
    }

    /** First reading: what the system view holds, and whether it holds anything it may not. */
    private static final class Shape extends DefaultHandler {
        int roots;
        String name;
        String type;
        String uuid;
        String id;
        String principal;
        String problem;
        private int depth;
        private String property;
        private final List<StringBuilder> value = new ArrayList<>();

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            String element = localName(localName, qName);
            if ("node".equals(element)) {
                depth++;
                String node = svName(atts);
                if (depth == 1) {
                    roots++;
                    name = node;
                }
                if (node == null || NOT_CARRIED.contains(node)) {
                    problem = "it holds " + node;
                }
            } else if ("property".equals(element)) {
                property = svName(atts);
            } else if ("value".equals(element)) {
                value.add(new StringBuilder());
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (!value.isEmpty() && property != null) {
                value.get(value.size() - 1).append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String element = localName(localName, qName);
            if ("node".equals(element)) {
                depth--;
            } else if ("property".equals(element)) {
                String first = value.isEmpty() ? null : value.get(0).toString();
                if ("jcr:primaryType".equals(property)) {
                    if (depth == 1) {
                        type = first;
                    } else if ("rep:User".equals(first) || "rep:Group".equals(first)) {
                        problem = "a user or group inside another";
                    }
                    if ("rep:SystemUser".equals(first)) {
                        problem = "a system user";
                    }
                } else if (depth == 1 && "jcr:uuid".equals(property)) {
                    uuid = first;
                } else if (depth == 1 && "rep:authorizableId".equals(property)) {
                    id = first;
                } else if (depth == 1 && "rep:principalName".equals(property)) {
                    principal = first;
                }
                property = null;
                value.clear();
            }
        }
    }

    /**
     * Second reading, into the repository: only the user's or group's own node keeps its identifier.
     * One further down could name any node of this repository, and the import would replace that node.
     */
    private static final class OwnIdentifiersOnly extends XMLFilterImpl {
        private int nodes;
        private int skipping;

        OwnIdentifiersOnly(ContentHandler importer) {
            setContentHandler(importer);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
            String element = AuthorizablePack.localName(localName, qName);
            if (skipping > 0) {
                skipping++;
                return;
            }
            if ("node".equals(element)) {
                nodes++;
            } else if ("property".equals(element) && nodes > 1 && "jcr:uuid".equals(svName(atts))) {
                skipping = 1;
                return;
            }
            super.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (skipping > 0) {
                skipping--;
                return;
            }
            if ("node".equals(AuthorizablePack.localName(localName, qName))) {
                nodes--;
            }
            super.endElement(uri, localName, qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (skipping == 0) {
                super.characters(ch, start, length);
            }
        }

        /**
         * The importing repository registers every prefix an XML document declares as a namespace of its
         * own, which is a right of its own and not this import's business: only the system view's is passed.
         */
        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            if (SV.equals(uri)) {
                super.startPrefixMapping(prefix, uri);
            }
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            if ("sv".equals(prefix)) {
                super.endPrefixMapping(prefix);
            }
        }
    }
}
