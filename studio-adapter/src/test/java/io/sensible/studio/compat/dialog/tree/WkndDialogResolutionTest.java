package io.sensible.studio.compat.dialog.tree;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Resolves real dialogs from the packages installed on the local quickstart
 * ({@code instances/author/install}): Core Components content + WKND ui.apps.
 * Skipped when the packages are not present.
 *
 * <p>With {@code -Dstudio.dialog.dump=<dir>} every WKND component dialog is also written as JSON, which is how the
 * offline fixtures of the author UI are produced.</p>
 */
class WkndDialogResolutionTest {

    private static final Path INSTALL = Paths.get("..", "instances", "author", "install");
    private static final Path CORE_ZIP = INSTALL.resolve("core.wcm.components.content-2.33.0.zip");
    private static final Path WKND_ZIP = INSTALL.resolve("aem-guides-wknd.all-4.0.4.zip");
    /** Sensible Studio's own authoring content (Style System). */
    private static final Path STUDIO_AUTHORING = Paths.get("..", "studio-authoring-content", "jcr_root");
    /** Sensible Studio's base components (basic page incl. page properties), maintained in studio-uberjar. */
    private static final Path STUDIO_WCM_FOUNDATION = Paths.get("..", "studio-uberjar", "studio-compat-content", "studio-wcm-foundation", "jcr_root");
    private static final String ARTICLE_TEMPLATE = "/conf/wknd/settings/wcm/templates/article-page-template";

    @TempDir
    static Path tmp;

    private static DialogTreeResolver resolver;
    private static FileVaultNodeSource source;

    @BeforeAll
    static void setUp() throws IOException {
        assumeTrue(Files.isRegularFile(CORE_ZIP) && Files.isRegularFile(WKND_ZIP),
                "Core Components / WKND packages not found in instances/author/install");
        Path core = tmp.resolve("core");
        Path wknd = tmp.resolve("wknd");
        Path wkndContent = tmp.resolve("wknd-content");
        try (InputStream in = Files.newInputStream(CORE_ZIP)) {
            unzip(in, core, null);
        }
        try (InputStream in = Files.newInputStream(WKND_ZIP)) {
            unzip(in, wknd, "aem-guides-wknd.ui.apps-");
        }
        try (InputStream in = Files.newInputStream(WKND_ZIP)) {
            unzip(in, wkndContent, "aem-guides-wknd.ui.content-");
        }
        List<Path> roots = new ArrayList<>(Arrays.asList(core.resolve("jcr_root"), wknd.resolve("jcr_root"), wkndContent.resolve("jcr_root")));
        if (Files.isDirectory(STUDIO_AUTHORING)) roots.add(0, STUDIO_AUTHORING);
        if (Files.isDirectory(STUDIO_WCM_FOUNDATION)) roots.add(0, STUDIO_WCM_FOUNDATION);
        source = new FileVaultNodeSource(roots);
        resolver = new DialogTreeResolver(source);
    }

    @Test
    void bylineInheritsImageDialogAndHidesMetadataTab() {
        DialogTreeResolver.Result r = resolver.resolve("wknd/components/byline", "cq:dialog");
        assertEquals(Arrays.asList("wknd/components/byline", "core/wcm/components/image/v3/image"), r.getChain().subList(0, 2));
        DialogNode tabs = r.getDialog().getDescendant("content/items/tabs/items");
        assertNotNull(tabs, "tabs present");
        List<String> tabNames = new ArrayList<>(tabs.getChildren().keySet());
        assertTrue(tabNames.contains("asset"), "asset tab kept: " + tabNames);
        assertTrue(tabNames.contains("properties"), "byline properties tab added: " + tabNames);
        assertFalse(tabNames.contains("metadata"), "metadata tab hidden: " + tabNames);
        DialogNode name = tabs.getDescendant("properties/items/columns/items/column/items/name");
        assertEquals("./name", name.getString("name"));
        assertTrue(name.getBoolean("required"));
        assertEquals("Byline", r.getDialog().getString("jcr:title"));
    }

    @Test
    void imageListHidesItemSettingsTab() {
        DialogTreeResolver.Result r = resolver.resolve("wknd/components/image-list", "cq:dialog");
        DialogNode tabs = r.getDialog().getDescendant("content/items/tabs/items");
        assertNotNull(tabs);
        assertNull(tabs.getChild("itemSettings"), "itemSettings hidden: " + tabs.getChildren().keySet());
        assertFalse(tabs.getChildren().isEmpty(), "list tabs inherited");
    }

    @Test
    void titleUsesCoreComponentDialog() {
        DialogTreeResolver.Result r = resolver.resolve("/apps/wknd/components/title", "cq:dialog");
        assertEquals("wknd/components/title", r.getResourceType());
        assertNotNull(r.getDialog());
        assertTrue(contains(r.getDialog(), "./jcr:title"), "title field present");
    }

    @Test
    void staticIncludesAreExpandedAndMissingOnesFlagged() {
        // teaser v2 includes the shared link dialog of Core Components and the AEM style tab (not shipped by Sensible Studio)
        DialogTreeResolver.Result r = resolver.resolve("wknd/components/teaser", "cq:dialog");
        assertNotNull(r.getDialog());
        assertFalse(containsResourceType(r.getDialog(), DialogTreeResolver.INCLUDE_TYPE, true),
                "every resolvable include was expanded");
    }

    @Test
    void pagePropertiesComeFromStudioBasicPage() {
        assumeTrue(Files.isDirectory(STUDIO_WCM_FOUNDATION));
        DialogTreeResolver.Result r = resolver.resolve("wknd/components/page", "cq:dialog");
        assertTrue(r.getChain().contains("wcm/foundation/components/basicpage/v1/basicpage"), "chain: " + r.getChain());
        for (String w : r.getWarnings()) {
            assertFalse(w.contains("basicpage"), "unresolved basicpage reference: " + w);
        }
        DialogNode tabs = r.getDialog().getDescendant("content/items/tabs/items");
        assertEquals(Arrays.asList("basic", "advanced", "thumbnail", "pwa"), new ArrayList<>(tabs.getChildren().keySet()));
        for (String field : new String[]{"./jcr:title", "./cq:tags", "./hideInNav", "./pageTitle", "./navTitle",
                "./jcr:description", "./onTime", "./offTime", "./sling:vanityPath", "./sling:redirect", "./id"}) {
            assertTrue(contains(tabs.getChild("basic"), field), "basic tab has " + field);
        }
        assertTrue(contains(tabs.getChild("advanced"), "./jcr:language"));
        assertTrue(tabs.getChild("pwa").getBoolean("granite:hide"), "PWA is hidden on Sensible Studio");
    }

    @Test
    void styleTabIsProvidedBySem() {
        assumeTrue(Files.isDirectory(STUDIO_AUTHORING));
        DialogTreeResolver.Result edit = resolver.resolve("wknd/components/teaser", "cq:dialog");
        assertTrue(containsResourceType(edit.getDialog(), "studio/granite/components/stylesystem/styleselector", false));
        assertTrue(edit.getWarnings().isEmpty(), "teaser resolves completely: " + edit.getWarnings());
        DialogTreeResolver.Result design = resolver.resolve("wknd/components/teaser", "cq:design_dialog");
        assertTrue(contains(design.getDialog(), "./cq:styleGroups"));
    }

    @Test
    void policiesResolveFromTheEditableTemplate() {
        PolicyResolver policies = new PolicyResolver(source);
        PolicyResolver.Policy title = policies.resolveInTemplate(ARTICLE_TEMPLATE, "root/container/title", "wknd/components/title");
        assertNotNull(title, "structural mapping");
        // an authored teaser below the template's container: mapping by resource type
        PolicyResolver.Policy teaser = policies.resolveInTemplate(ARTICLE_TEMPLATE, "root/container/container/teaser_123", "wknd/components/teaser");
        assertNotNull(teaser, "mapping by resource type below the nearest container");
        assertTrue(teaser.getPath().startsWith("/conf/wknd/settings/wcm/policies/wknd/components/teaser/"));
        PolicyResolver.Policy page = policies.resolveInTemplate(ARTICLE_TEMPLATE, "", "wknd/components/page");
        assertNotNull(page, "page policy");
        assertNull(policies.resolveInTemplate(ARTICLE_TEMPLATE, "root/container/x", "wknd/components/unknown"));
    }

    @Test
    void unknownComponentHasNoDialog() {
        DialogTreeResolver.Result r = resolver.resolve("wknd/components/does-not-exist", "cq:dialog");
        assertNull(r.getDialog());
        assertTrue(r.getChain().isEmpty());
        assertFalse(r.getWarnings().isEmpty());
    }

    @Test
    void dumpAllWkndDialogs() throws IOException {
        String dump = System.getProperty("studio.dialog.dump");
        DialogNode components = source.load("/apps/wknd/components");
        assertNotNull(components);
        List<String> types = new ArrayList<>();
        collectComponents(components, "wknd/components", types);
        int withDialog = 0;
        for (String type : types) {
            for (String dialogName : new String[]{"cq:dialog", "cq:design_dialog"}) {
                DialogTreeResolver.Result r = resolver.resolve(type, dialogName);
                if (r.getDialog() == null) continue;
                if ("cq:dialog".equals(dialogName)) withDialog++;
                String json = DialogJson.toJson(r);
                assertTrue(json.startsWith("{") && json.endsWith("}"));
                if (dump != null) {
                    String suffix = "cq:dialog".equals(dialogName) ? "" : ".design";
                    Path out = Paths.get(dump).resolve(type.replace('/', '_') + suffix + ".json");
                    Files.createDirectories(out.getParent());
                    Files.write(out, json.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        assertTrue(withDialog >= 20, "most WKND components resolve a dialog, got " + withDialog + " of " + types.size());

        // Policies of the components as placed in the article template's main container (offline fixtures).
        if (dump != null) {
            PolicyResolver policies = new PolicyResolver(source);
            Path policyDir = Paths.get(dump).resolveSibling("policies");
            Files.createDirectories(policyDir);
            for (String type : types) {
                String name = type.substring(type.lastIndexOf('/') + 1);
                String relative = "wknd/components/page".equals(type) ? "" : "root/container/container/" + name;
                PolicyResolver.Policy p = policies.resolveInTemplate(ARTICLE_TEMPLATE, relative, type);
                if (p == null) continue;
                String json = "{\"policyPath\":" + DialogJson.quote(p.getPath()) + ",\"policy\":" + DialogJson.toJson(p.getNode()) + "}";
                Files.write(policyDir.resolve(type.replace('/', '_') + ".json"), json.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------

    private static void collectComponents(DialogNode node, String path, List<String> out) {
        for (DialogNode child : node.getChildren().values()) {
            String childPath = path + "/" + child.getName();
            if ("cq:Component".equals(child.getString("jcr:primaryType"))) out.add(childPath);
            if (!child.getName().contains(":")) collectComponents(child, childPath, out);
        }
    }

    private static boolean contains(DialogNode node, String fieldName) {
        if (fieldName.equals(node.getString("name"))) return true;
        for (DialogNode c : node.getChildren().values()) if (contains(c, fieldName)) return true;
        return false;
    }

    /** True if a node of the type exists that is NOT flagged as unresolved (when onlyResolved). */
    private static boolean containsResourceType(DialogNode node, String type, boolean ignoreFlagged) {
        if (type.equals(node.getString(DialogTreeResolver.RESOURCE_TYPE))
                && !(ignoreFlagged && node.get(DialogTreeResolver.UNRESOLVED_INCLUDE) != null)) {
            return true;
        }
        for (DialogNode c : node.getChildren().values()) if (containsResourceType(c, type, ignoreFlagged)) return true;
        return false;
    }

    /** Unzips a content package. With {@code nestedPrefix}, unzips the first nested zip whose name starts with it. */
    private static void unzip(InputStream in, Path target, String nestedPrefix) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (nestedPrefix != null) {
                    String file = name.substring(name.lastIndexOf('/') + 1);
                    if (file.startsWith(nestedPrefix) && file.endsWith(".zip")) {
                        byte[] bytes = readAll(zip);
                        unzip(new ByteArrayInputStream(bytes), target, null);
                        return;
                    }
                    continue;
                }
                if (e.isDirectory() || !name.startsWith("jcr_root/")) continue;
                Path out = target.resolve(name).normalize();
                if (!out.startsWith(target)) continue; // zip slip
                Files.createDirectories(out.getParent());
                Files.write(out, readAll(zip));
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
