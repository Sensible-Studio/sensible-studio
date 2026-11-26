/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

/**
 * Node types, node names and property names used by page content in the repository.
 *
 * <p>The values are the on-disk content format (what is stored in {@code .content.xml} files of
 * {@code ui.content} packages), which Sensible Studio must read as-is to be content compatible.
 */
public interface NameConstants {

    // node types
    String NT_PAGE = "cq:Page";
    String NT_PAGE_CONTENT = "cq:PageContent";
    String NT_TEMPLATE = "cq:Template";

    // node names
    String NN_CONTENT = "jcr:content";

    // page content properties
    String PN_TITLE = "jcr:title";
    String PN_DESCRIPTION = "jcr:description";
    String PN_PAGE_TITLE = "pageTitle";
    String PN_NAV_TITLE = "navTitle";
    String PN_SHORT_TITLE = "shortTitle";
    String PN_HIDE_IN_NAV = "hideInNav";
    String PN_TEMPLATE = "cq:template";
    String PN_TAGS = "cq:tags";
    String PN_ON_TIME = "onTime";
    String PN_OFF_TIME = "offTime";
    String PN_PAGE_LAST_MOD = "cq:lastModified";
    String PN_PAGE_LAST_MOD_BY = "cq:lastModifiedBy";
    String PN_SLING_VANITY_PATH = "sling:vanityPath";
    String PN_DESIGN_PATH = "cq:designPath";

    // template properties
    String PN_ALLOWED_PATHS = "allowedPaths";
    String PN_ALLOWED_PARENTS = "allowedParents";
    String PN_ALLOWED_CHILDREN = "allowedChildren";
    String PN_RANKING = "ranking";

    // misc
    String PN_CREATED = "jcr:created";
    String PN_CREATED_BY = "jcr:createdBy";
    String PN_JCR_LAST_MODIFIED = "jcr:lastModified";
    String PN_JCR_LAST_MODIFIED_BY = "jcr:lastModifiedBy";
}
