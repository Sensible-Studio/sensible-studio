/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

import com.day.cq.commons.Filter;

/**
 * Filters pages by validity (on/off time) and navigation visibility.
 *
 * <p>The no-arg constructor excludes both invalid pages and pages hidden in navigation, which is
 * what navigation components expect.
 */
public class PageFilter implements Filter<Page> {

    private final boolean includeInvalid;
    private final boolean includeHidden;

    public PageFilter() {
        this(false, false);
    }

    /**
     * @param includeInvalid {@code true} to keep pages outside their on/off time window
     * @param includeHidden  {@code true} to keep pages with {@code hideInNav}
     */
    public PageFilter(boolean includeInvalid, boolean includeHidden) {
        this.includeInvalid = includeInvalid;
        this.includeHidden = includeHidden;
    }

    @Override
    public boolean includes(Page page) {
        if (page == null) {
            return false;
        }
        if (!includeInvalid && !page.isValid()) {
            return false;
        }
        return includeHidden || !page.isHideInNav();
    }

    public boolean isIncludeInvalid() {
        return includeInvalid;
    }

    public boolean isIncludeHidden() {
        return includeHidden;
    }
}
