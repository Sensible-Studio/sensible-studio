/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.jcr.LoginException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A user and a group travel between publish instances: here out of one Oak and, after the original is
 * removed or changed, back into the same one, which is what the receiving instance does.
 */
@ExtendWith(SlingContextExtension.class)
class AuthorizablePackTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver rr;
    private Session session;
    private UserManager users;
    private String anaPath;
    private String membersPath;

    @BeforeEach
    void setUp() throws Exception {
        rr = context.resourceResolver();
        session = rr.adaptTo(Session.class);
        users = ((JackrabbitSession) session).getUserManager();
        User ana = users.createUser("ana", "secret-1");
        ana.setProperty("profile/givenName", session.getValueFactory().createValue("Ana"));
        ana.setProperty("profile/about", session.getValueFactory().createValue("climbs & <writes> \"a lot\"\nevery day"));
        Group members = users.createGroup("members");
        members.addMember(ana);
        // as Sling's user management does for a new user: she may change what is hers
        org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils.addAccessControlEntry(session, ana.getPath(), ana.getPrincipal(),
                new String[] {"jcr:all"}, true);
        org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils.addAccessControlEntry(session, ana.getPath(), members.getPrincipal(),
                new String[] {"jcr:read"}, true);
        session.save();
        anaPath = ana.getPath();
        membersPath = members.getPath();
    }

    private byte[] packOf(String path) throws Exception {
        return AuthorizablePack.pack(session, AuthorizablePack.unitOf(session, path));
    }

    private void signIn(String user, String password) throws RepositoryException {
        session.getRepository().login(new SimpleCredentials(user, password.toCharArray())).logout();
    }

    private static byte[] zip(String meta, String xml) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(AuthorizablePack.META_ENTRY));
            zip.write(meta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(AuthorizablePack.XML_ENTRY));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String meta(String path, String id, boolean group) {
        return meta(path, id, group, List.of());
    }

    private static String meta(String path, String id, boolean group, List<String> own) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("id", id);
        m.put("group", group);
        if (own != null) {
            m.put("own", own);
        }
        return Json.write(m);
    }

    @Test
    void aUserArrivesWithPasswordAndProfile() throws Exception {
        // a change of the profile belongs to the user
        assertEquals(anaPath, AuthorizablePack.unitOf(session, anaPath + "/profile").getPath());
        byte[] zip = packOf(anaPath + "/profile");
        assertTrue(AuthorizablePack.is(zip));
        assertFalse(AuthorizablePack.is("PK not a pack".getBytes(StandardCharsets.UTF_8)));
        AuthorizablePack.Unpacked pack = AuthorizablePack.unpack(zip);
        assertEquals(anaPath, pack.path);
        assertEquals("ana", pack.id);
        assertFalse(pack.group);

        // an instance that does not know her
        users.getAuthorizable("ana").remove();
        session.save();
        assertNull(users.getAuthorizable("ana"));
        assertThrows(LoginException.class, () -> signIn("ana", "secret-1"));

        AuthorizablePack.install(session, pack);
        session.save();
        Authorizable ana = users.getAuthorizable("ana");
        assertNotNull(ana);
        assertEquals(anaPath, ana.getPath());
        assertEquals("Ana", ana.getProperty("profile/givenName")[0].getString());
        assertEquals("climbs & <writes> \"a lot\"\nevery day", ana.getProperty("profile/about")[0].getString());
        signIn("ana", "secret-1");
        assertThrows(LoginException.class, () -> signIn("ana", "wrong"));
        // and what is hers is hers there too; what others may do with her node is not carried
        assertEquals(List.of("jcr:all"), pack.own);
        assertEquals(List.of("jcr:all"), AuthorizablePack.ownRights(session, anaPath, "ana"));
        assertEquals(List.of(), AuthorizablePack.ownRights(session, anaPath, "members"));
        Session hers = session.getRepository().login(new SimpleCredentials("ana", "secret-1".toCharArray()));
        try {
            hers.getNode(anaPath + "/profile").setProperty("givenName", "Ana Maria");
            hers.save();
        } finally {
            hers.logout();
        }
    }

    @Test
    void aChangeReplacesTheUserWhereSheIs() throws Exception {
        byte[] sent = packOf(anaPath);
        // meanwhile, on the receiving instance, she is as she was before
        User ana = (User) users.getAuthorizable("ana");
        ana.changePassword("older");
        ana.setProperty("profile/givenName", session.getValueFactory().createValue("A."));
        ana.setProperty("profile/onlyHere", session.getValueFactory().createValue("x"));
        session.save();
        signIn("ana", "older");

        AuthorizablePack.install(session, AuthorizablePack.unpack(sent));
        session.save();
        signIn("ana", "secret-1");
        assertThrows(LoginException.class, () -> signIn("ana", "older"));
        Authorizable now = users.getAuthorizable("ana");
        assertEquals("Ana", now.getProperty("profile/givenName")[0].getString());
        assertNull(now.getProperty("profile/onlyHere"), "the user as sent, whole");
        assertEquals(List.of("jcr:all"), AuthorizablePack.ownRights(session, anaPath, "ana"));
        assertTrue(((Group) users.getAuthorizable("members")).isMember(now), "still a member");
    }

    @Test
    void aSenderThatDoesNotSayWhatIsHersLeavesItAsItIsHere() throws Exception {
        AuthorizablePack.Unpacked sent = AuthorizablePack.unpack(packOf(anaPath));
        AuthorizablePack.Unpacked silent = AuthorizablePack.unpack(zip(meta(anaPath, "ana", false, null), new String(sent.xml, StandardCharsets.UTF_8)));
        assertNull(silent.own);
        AuthorizablePack.install(session, silent);
        session.save();
        assertEquals(List.of("jcr:all"), AuthorizablePack.ownRights(session, anaPath, "ana"));
        // and a sender that says "nothing" is taken at its word
        AuthorizablePack.install(session, AuthorizablePack.unpack(zip(meta(anaPath, "ana", false, List.of()), new String(sent.xml, StandardCharsets.UTF_8))));
        session.save();
        assertEquals(List.of(), AuthorizablePack.ownRights(session, anaPath, "ana"));
    }

    @Test
    void aGroupArrivesWithItsMembers() throws Exception {
        AuthorizablePack.Unpacked pack = AuthorizablePack.unpack(packOf(membersPath));
        assertTrue(pack.group);
        assertEquals("members", pack.id);
        users.getAuthorizable("members").remove();
        session.save();
        AuthorizablePack.install(session, pack);
        session.save();
        Group members = (Group) users.getAuthorizable("members");
        assertNotNull(members);
        assertTrue(members.isMember(users.getAuthorizable("ana")));
    }

    @Test
    void whoAdministersAnInstanceDoesNotTravel() throws Exception {
        String admin = users.getAuthorizable("admin").getPath();
        assertNull(AuthorizablePack.unitOf(session, admin));
        assertNull(AuthorizablePack.unitOf(session, users.getAuthorizable("anonymous").getPath()));
        User service = users.createSystemUser("a-service", null);
        Group administrators = users.createGroup("administrators");
        session.save();
        assertNull(AuthorizablePack.unitOf(session, service.getPath()));
        assertNull(AuthorizablePack.unitOf(session, administrators.getPath()));
        assertNull(AuthorizablePack.unitOf(session, "/content"));
        assertNull(AuthorizablePack.unitOf(session, "/home/users/../../content"));
        assertTrue(AuthorizablePack.isAuthorizablePath("/home/users/a/ana") && AuthorizablePack.isAuthorizablePath("/home/groups/m/members/profile"));
        assertFalse(AuthorizablePack.isAuthorizablePath("/home/users") || AuthorizablePack.isAuthorizablePath("/home/other/x") || AuthorizablePack.isAuthorizablePath("/homes/users/x"));
        assertTrue(AuthorizablePack.systemPath("/home/users/system/studio/x") && !AuthorizablePack.systemPath("/home/users/s/system"));
        assertFalse(AuthorizablePack.remove(session, admin));
        assertFalse(AuthorizablePack.remove(session, service.getPath()));
        assertFalse(AuthorizablePack.remove(session, administrators.getPath()));
        assertNotNull(users.getAuthorizable("admin"));
    }

    @Test
    void aPackThatIsNotExactlyOneUserIsRefused() throws Exception {
        AuthorizablePack.Unpacked good = AuthorizablePack.unpack(packOf(anaPath));
        String xml = new String(good.xml, StandardCharsets.UTF_8);
        assertFalse(xml.contains("rep:policy"));
        Node adminNode = session.getNode(users.getAuthorizable("admin").getPath());
        String adminId = adminNode.getIdentifier();
        String anaId = session.getNode(anaPath).getIdentifier();
        assertTrue(xml.contains(anaId));

        // says admin, holds ana; says a user, is put among the groups; a path that climbs out
        refused(zip(meta(anaPath, "admin", false), xml));
        refused(zip(meta(anaPath, "bob", false), xml));
        refused(zip(meta(anaPath, "ana", true), xml));
        String usersRoot = AuthorizablePack.root(anaPath, false);
        refused(zip(meta(AuthorizablePack.root(membersPath, true) + "/x/ana", "ana", false), xml));
        refused(zip(meta(usersRoot + "/../../content/ana", "ana", false), xml));
        refused(zip(meta(usersRoot + "/system/ana", "ana", false), xml));
        refused(zip(meta("/content/ana", "ana", false), xml));
        refused(zip(meta(anaPath.substring(0, anaPath.lastIndexOf('/')) + "/other-name", "ana", false), xml));
        // carries the identifier of the administrator's node: the import would replace that node
        refused(zip(meta(anaPath, "ana", false), xml.replace(anaId, adminId)));
        // a system user, access rights, a document type, two users, not XML
        refused(zip(meta(anaPath, "ana", false), xml.replace("<sv:value>rep:User</sv:value>", "<sv:value>rep:SystemUser</sv:value>")));
        int profile = xml.indexOf("<sv:node sv:name=\"profile\"");
        assertTrue(profile > 0, xml);
        String policy = "<sv:node sv:name=\"rep:policy\"><sv:property sv:name=\"jcr:primaryType\" sv:type=\"Name\"><sv:value>rep:ACL</sv:value></sv:property></sv:node>";
        refused(zip(meta(anaPath, "ana", false), xml.substring(0, profile) + policy + xml.substring(profile)));
        String inner = "<sv:node sv:name=\"eve\"><sv:property sv:name=\"jcr:primaryType\" sv:type=\"Name\"><sv:value>rep:User</sv:value></sv:property></sv:node>";
        refused(zip(meta(anaPath, "ana", false), xml.substring(0, profile) + inner + xml.substring(profile)));
        refused(zip(meta(anaPath, "ana", false), xml.replace("?>", "?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>")));
        refused(zip(meta(anaPath, "ana", false), "not xml"));
        // a right this repository does not know; a name that is none
        refused(zip(meta(anaPath, "ana", false, List.of("jcr:everything")), xml));
        assertThrows(java.io.IOException.class, () -> AuthorizablePack.unpack(zip(meta(anaPath, "ana", false, List.of("jcr:all on /")), xml)));

        assertNotNull(users.getAuthorizable("admin"));
        assertEquals(adminId, session.getNode(users.getAuthorizable("admin").getPath()).getIdentifier());
        signIn("ana", "secret-1");
        assertFalse(session.hasPendingChanges());
    }

    private void refused(byte[] zip) throws Exception {
        AuthorizablePack.Unpacked pack = AuthorizablePack.unpack(zip);
        RepositoryException e = assertThrows(RepositoryException.class, () -> AuthorizablePack.install(session, pack));
        assertTrue(e.getMessage().startsWith("Refused"), e.getMessage());
        session.refresh(false);
    }

    @Test
    void anIdentifierFurtherDownIsNotTaken() throws Exception {
        AuthorizablePack.Unpacked good = AuthorizablePack.unpack(packOf(anaPath));
        String xml = new String(good.xml, StandardCharsets.UTF_8);
        String adminPath = users.getAuthorizable("admin").getPath();
        String adminId = session.getNode(adminPath).getIdentifier();
        int profile = xml.indexOf("<sv:node sv:name=\"profile\"");
        int after = xml.indexOf("</sv:property>", profile) + "</sv:property>".length();
        String forged = xml.substring(0, after) + "<sv:property sv:name=\"jcr:uuid\" sv:type=\"String\"><sv:value>" + adminId + "</sv:value></sv:property>" + xml.substring(after);
        AuthorizablePack.install(session, AuthorizablePack.unpack(zip(meta(anaPath, "ana", false), forged)));
        session.save();
        assertEquals(adminPath, users.getAuthorizable("admin").getPath());
        assertEquals(adminId, session.getNode(adminPath).getIdentifier());
        assertEquals("Ana", users.getAuthorizable("ana").getProperty("profile/givenName")[0].getString());
    }

    @Test
    void whatChangesOnAnInstanceIsOfferedToTheOthers() throws Exception {
        Outbox outbox = new Outbox();
        Set<String> touched = new LinkedHashSet<>(List.of(anaPath + "/profile", anaPath, membersPath, AuthorizablePack.root(anaPath, false), users.getAuthorizable("admin").getPath()));
        assertEquals(2, AuthorizableOutbox.record(rr, session, outbox, touched, Collections.emptySet()));
        rr.commit();
        List<Map<String, Object>> entries = Outbox.list(rr);
        assertEquals(2, entries.size());
        assertEquals(Set.of(anaPath, membersPath), Set.of(entries.get(0).get("path"), entries.get(1).get("path")));
        assertEquals("Activate", entries.get(0).get("action"));
        byte[] pack = rr.getResource(Outbox.ROOT + "/" + Outbox.entryName(anaPath) + "/pack/jcr:content").adaptTo(java.io.InputStream.class).readAllBytes();
        assertEquals("ana", AuthorizablePack.unpack(pack).id);

        // an entry that was replaced since it was listed is not taken away by the acknowledgement of the old one
        assertTrue(Outbox.ack(rr, Outbox.entryName(anaPath), "2000-01-01T00:00:00Z"));
        assertNotNull(rr.getResource(Outbox.ROOT + "/" + Outbox.entryName(anaPath)));
        assertTrue(Outbox.ack(rr, Outbox.entryName(anaPath), String.valueOf(Outbox.list(rr).stream().filter(e -> anaPath.equals(e.get("path"))).findFirst().get().get("created"))));
        assertNull(rr.getResource(Outbox.ROOT + "/" + Outbox.entryName(anaPath)));
        assertTrue(Outbox.ack(rr, Outbox.entryName(membersPath)));

        // a part of her profile removed: she changed; she herself removed: she is gone, said once
        session.getNode(anaPath + "/profile").remove();
        session.save();
        assertEquals(1, AuthorizableOutbox.record(rr, session, outbox, Collections.emptySet(), Set.of(anaPath + "/profile")));
        rr.commit();
        assertEquals("Activate", Outbox.list(rr).get(0).get("action"));
        users.getAuthorizable("ana").remove();
        session.save();
        assertEquals(1, AuthorizableOutbox.record(rr, session, outbox, Collections.emptySet(),
                new LinkedHashSet<>(List.of(anaPath, anaPath + "/profile", anaPath + "/rep:policy", AuthorizablePack.root(anaPath, false) + "/system/x"))));
        rr.commit();
        entries = Outbox.list(rr);
        assertEquals(1, entries.size());
        assertEquals("Delete", entries.get(0).get("action"));
        assertEquals(anaPath, entries.get(0).get("path"));
    }

    @Test
    void aRemovedUserIsRemoved() throws Exception {
        assertTrue(AuthorizablePack.remove(session, anaPath));
        session.save();
        assertNull(users.getAuthorizable("ana"));
        assertFalse(AuthorizablePack.remove(session, anaPath));
        assertFalse(AuthorizablePack.remove(session, anaPath + "/profile"));
    }

    @Test
    void whichAgentsTalkToTheSameInstance() {
        assertEquals(ReverseReplication.origin("http://localhost:4503/bin/outbox"), ReverseReplication.origin("http://LOCALHOST:4503/bin/receive"));
        assertFalse(ReverseReplication.origin("http://localhost:4503/bin/outbox").equals(ReverseReplication.origin("http://localhost:4504/bin/receive")));
        assertEquals("https://publish.example:443", ReverseReplication.origin("https://publish.example/bin/receive"));
        assertNull(ReverseReplication.origin("not a url"));
    }

    @Test
    void theFirstCopyListsWhatTravelsUsersFirst() throws Exception {
        users.createSystemUser("some-service", "system/some");
        users.createGroup("administrators");
        session.save();
        java.util.List<java.util.Map<String, Object>> all = AuthorizablePack.travelling(session);
        java.util.List<Object> ids = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> m : all) {
            ids.add(m.get("id"));
        }
        assertEquals(java.util.Arrays.asList("ana", "members"), ids, "not admin, anonymous, administrators, everyone or a system user");
        assertEquals(anaPath, all.get(0).get("path"));
        assertEquals(Boolean.FALSE, all.get(0).get("group"));
        assertEquals(Boolean.TRUE, all.get(1).get("group"));
    }
}
