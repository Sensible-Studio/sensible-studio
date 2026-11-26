/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.license;

import org.osgi.framework.Version;

/**
 * Name and version of the running product.
 */
public interface ProductInfo {

    /** @return product name */
    String getName();

    /** @return product version */
    Version getVersion();

    /** @return short name */
    String getShortName();
}
