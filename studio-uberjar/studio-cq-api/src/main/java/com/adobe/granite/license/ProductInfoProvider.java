/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.license;

/**
 * OSGi service exposing {@link ProductInfo}.
 */
public interface ProductInfoProvider {

    /** @return the product info */
    ProductInfo getProductInfo();
}
