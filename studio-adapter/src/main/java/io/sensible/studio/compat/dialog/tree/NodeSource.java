package io.sensible.studio.compat.dialog.tree;

import java.util.List;

/**
 * Read access to a content tree. Implemented over a live Sling {@code ResourceResolver}
 * ({@link SlingNodeSource}) and over unpacked FileVault packages ({@link FileVaultNodeSource}), so the same
 * dialog resolution runs on the server and in tests / offline tooling.
 */
public interface NodeSource {

    /** Detached copy of the whole subtree at {@code absolutePath}, or {@code null} if it does not exist. */
    DialogNode load(String absolutePath);

    /** The node at {@code absolutePath} with its properties only (no children), or {@code null}. */
    default DialogNode loadShallow(String absolutePath) {
        DialogNode full = load(absolutePath);
        if (full == null) return null;
        DialogNode shallow = new DialogNode(full.getName());
        shallow.getProperties().putAll(full.getProperties());
        return shallow;
    }

    /** Sling search paths in priority order, e.g. {@code ["/apps/", "/libs/"]}. */
    List<String> searchPaths();
}
