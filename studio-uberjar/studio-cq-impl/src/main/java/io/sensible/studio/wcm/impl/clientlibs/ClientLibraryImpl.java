/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.clientlibs;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.adobe.granite.ui.clientlibs.ClientLibrary;
import com.adobe.granite.ui.clientlibs.LibraryType;

/**
 * Metadata of one client library folder. Content-dependent answers (types, dependencies) are
 * computed by {@link HtmlLibraryManagerImpl}, which has the index and a resolver.
 */
final class ClientLibraryImpl implements ClientLibrary {

    static final String PROXY_PREFIX = "/etc.clientlibs/";

    private final String path;
    private final String[] categories;
    private final String[] embed;
    private final String[] dependencies;
    private final boolean allowProxy;
    private volatile Set<LibraryType> types = EnumSet.noneOf(LibraryType.class);

    ClientLibraryImpl(String path, String[] categories, String[] embed, String[] dependencies, boolean allowProxy) {
        this.path = path;
        this.categories = categories.clone();
        this.embed = embed.clone();
        this.dependencies = dependencies.clone();
        this.allowProxy = allowProxy;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String[] getCategories() {
        return categories.clone();
    }

    @Override
    public String[] getEmbeddedCategories() {
        return embed.clone();
    }

    @Override
    public String[] getDependentCategories() {
        return dependencies.clone();
    }

    @Override
    public boolean allowProxy() {
        return allowProxy;
    }

    @Override
    public Set<LibraryType> getTypes() {
        return Collections.unmodifiableSet(types);
    }

    void setTypes(Set<LibraryType> types) {
        this.types = EnumSet.copyOf(types.isEmpty() ? EnumSet.noneOf(LibraryType.class) : types);
    }

    @Override
    public Map<String, ? extends ClientLibrary> getDependencies(boolean transitive) {
        return Collections.emptyMap(); // resolved by the manager, which knows the index
    }

    @Override
    public Map<String, ? extends ClientLibrary> getEmbedded(LibraryType type) {
        return Collections.emptyMap(); // resolved by the manager, which knows the index
    }

    /**
     * The public URL base: {@code /etc.clientlibs/wknd/clientlibs/site} for proxied libraries in
     * {@code /apps} or {@code /libs}, the repository path otherwise.
     */
    String getPublicPath() {
        if (allowProxy) {
            if (path.startsWith("/apps/")) {
                return PROXY_PREFIX + path.substring("/apps/".length());
            }
            if (path.startsWith("/libs/")) {
                return PROXY_PREFIX + path.substring("/libs/".length());
            }
        }
        return path;
    }

    @Override
    public String toString() {
        return "ClientLibrary{" + path + "}";
    }
}
