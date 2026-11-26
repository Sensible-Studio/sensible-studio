/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.jcr.Repository;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.security.principal.EveryonePrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The members of a folder, checked against what Oak then allows each of them. */
class DamFolderMembersOakTest {

    private static final String FOLDER = "/content/dam/brand";

    private Repository repo;
    private Session admin;

    @BeforeEach
    void setUp() throws Exception {
        repo = new Jcr(new Oak(new MemoryNodeStore())).createRepository();
        admin = repo.login(new SimpleCredentials("admin", "admin".toCharArray()));
        UserManager um = ((JackrabbitSession) admin).getUserManager();
        for (String u : new String[] {"ana", "bob", "cid", "dan"}) {
            um.createUser(u, u + "-secret-1");
        }
        Group designers = um.createGroup("designers");
        designers.addMember(um.getAuthorizable("cid"));
        um.createGroup("administrators");
        admin.getRootNode().addNode("content").addNode("dam").addNode("brand").addNode("logo.png");
        admin.save();
        // as on an instance: everyone reads Media, and edits nothing
        AccessControlManager acm = admin.getAccessControlManager();
        JackrabbitAccessControlList acl = AccessControlUtils.getAccessControlList(admin, "/");
        acl.addEntry(EveryonePrincipal.getInstance(), new Privilege[] {acm.privilegeFromName("jcr:read")}, true);
        acm.setPolicy("/", acl);
        admin.save();
    }

    private Session login(String user) throws Exception {
        return repo.login(new SimpleCredentials(user, (user + "-secret-1").toCharArray()));
    }

    private static boolean canWrite(Session s, String path) throws Exception {
        return s.hasPermission(path, Session.ACTION_ADD_NODE + "," + Session.ACTION_SET_PROPERTY + "," + Session.ACTION_REMOVE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rolesGiveWhatTheySay() throws Exception {
        DamFolderMembers members = new DamFolderMembers(admin);
        members.save(FOLDER, Arrays.asList(new DamFolderMembers.Member("ana", "owner"), new DamFolderMembers.Member("bob", "viewer"),
                new DamFolderMembers.Member("designers", "editor")), false);
        admin.save();

        Map<String, Object> d = members.describe(FOLDER);
        List<Map<String, Object>> list = (List<Map<String, Object>>) d.get("members");
        assertEquals(3, list.size(), String.valueOf(d));
        assertEquals("designers", list.get(2).get("id"));
        assertEquals("editor", list.get(2).get("role"));
        assertEquals(true, list.get(2).get("group"));
        assertEquals(false, d.get("private"));

        Session ana = login("ana");
        Session bob = login("bob");
        Session cid = login("cid");
        Session dan = login("dan");
        assertTrue(canWrite(ana, FOLDER + "/logo.png"));
        assertTrue(new DamFolderMembers(ana).canManage(FOLDER), "an owner changes the members");
        assertTrue(canWrite(cid, FOLDER + "/logo.png"), "an editor through a group");
        assertFalse(new DamFolderMembers(cid).canManage(FOLDER), "an editor does not change the members");
        assertTrue(bob.nodeExists(FOLDER + "/logo.png"));
        assertFalse(canWrite(bob, FOLDER + "/logo.png"), "a viewer only reads");
        assertTrue(dan.nodeExists(FOLDER), "not private: everyone still reads");
        assertFalse(canWrite(dan, FOLDER));

        // an owner changes them with their own session
        DamFolderMembers byAna = new DamFolderMembers(ana);
        byAna.save(FOLDER, Arrays.asList(new DamFolderMembers.Member("ana", "owner"), new DamFolderMembers.Member("bob", "editor")), false);
        ana.save();
        bob.refresh(false);
        assertTrue(canWrite(login("bob"), FOLDER + "/logo.png"));
        assertFalse(canWrite(login("cid"), FOLDER + "/logo.png"), "designers removed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPrivateFolderIsOnlyForItsMembers() throws Exception {
        DamFolderMembers members = new DamFolderMembers(admin);
        members.save(FOLDER, Arrays.asList(new DamFolderMembers.Member("ana", "owner"), new DamFolderMembers.Member("designers", "viewer")), true);
        admin.save();
        assertFalse(login("dan").nodeExists(FOLDER), "private: others do not see it");
        assertFalse(login("dan").nodeExists(FOLDER + "/logo.png"));
        assertTrue(login("cid").nodeExists(FOLDER + "/logo.png"), "a member through a group sees it");
        assertTrue(login("ana").nodeExists(FOLDER));
        Map<String, Object> d = members.describe(FOLDER);
        assertEquals(true, d.get("private"));
        assertEquals(2, ((List<?>) d.get("members")).size(), "the entry for administrators is not a member");

        // open again: everyone reads, the members stay
        members.save(FOLDER, Arrays.asList(new DamFolderMembers.Member("ana", "owner"), new DamFolderMembers.Member("designers", "viewer")), false);
        admin.save();
        assertTrue(login("dan").nodeExists(FOLDER + "/logo.png"));
        assertEquals(false, members.describe(FOLDER).get("private"));
    }

    @Test
    void refusesWhatCannotBe() throws Exception {
        DamFolderMembers members = new DamFolderMembers(admin);
        assertThrows(IllegalArgumentException.class, () -> members.save(FOLDER, List.of(new DamFolderMembers.Member("ana", "boss")), false));
        assertThrows(IllegalArgumentException.class, () -> members.save(FOLDER, List.of(new DamFolderMembers.Member("nobody", "viewer")), false));
        assertThrows(IllegalArgumentException.class, () -> members.save(FOLDER, List.of(new DamFolderMembers.Member("everyone", "viewer")), false));
        // a private folder without an owner, by someone who is not an administrator, would lock everybody out
        members.save(FOLDER, List.of(new DamFolderMembers.Member("ana", "owner")), false);
        admin.save();
        Session ana = login("ana");
        assertThrows(IllegalArgumentException.class,
                () -> new DamFolderMembers(ana).save(FOLDER, List.of(new DamFolderMembers.Member("bob", "viewer")), true));
        // someone without the right cannot change anything
        Session dan = login("dan");
        assertFalse(new DamFolderMembers(dan).canManage(FOLDER));
        assertThrows(javax.jcr.AccessDeniedException.class,
                () -> new DamFolderMembers(dan).save(FOLDER, List.of(new DamFolderMembers.Member("dan", "owner")), false));
    }

    @Test
    void entriesOfOtherShapesAreKept() throws Exception {
        AccessControlManager acm = admin.getAccessControlManager();
        JackrabbitAccessControlList acl = AccessControlUtils.getAccessControlList(admin, FOLDER);
        acl.addEntry(((JackrabbitSession) admin).getPrincipalManager().getPrincipal("dan"),
                new Privilege[] {acm.privilegeFromName("jcr:read"), acm.privilegeFromName("jcr:modifyProperties")}, true);
        acm.setPolicy(FOLDER, acl);
        admin.save();
        DamFolderMembers members = new DamFolderMembers(admin);
        members.save(FOLDER, List.of(new DamFolderMembers.Member("ana", "viewer")), false);
        admin.save();
        assertEquals(2, AccessControlUtils.getAccessControlList(admin, FOLDER).getAccessControlEntries().length,
                "dan's own entry stays next to the new member");
        assertEquals(1, ((List<?>) members.describe(FOLDER).get("members")).size());
    }
}
