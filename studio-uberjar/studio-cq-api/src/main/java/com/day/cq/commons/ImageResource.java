/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.commons;

import org.apache.sling.api.resource.Resource;

/**
 * A {@link DownloadResource} that is an image: adds alt text and link.
 */
public class ImageResource extends DownloadResource {

    public static final String PN_ALT = "alt";
    public static final String PN_LINK_URL = "linkURL";

    public ImageResource(Resource resource) {
        super(resource);
    }

    /** @return the alt text, falling back to the title */
    public String getAlt() {
        String alt = properties.get(PN_ALT, String.class);
        return alt != null ? alt : getTitle();
    }

    /** @return the link URL, or {@code null} */
    public String getLinkURL() {
        return properties.get(PN_LINK_URL, String.class);
    }
}
