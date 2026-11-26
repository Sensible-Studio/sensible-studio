/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.dam.cfm;

import java.util.Iterator;

import org.apache.sling.api.adapter.Adaptable;
import org.apache.sling.api.resource.Resource;

/**
 * A content fragment: structured content stored as a DAM asset.
 */
public interface ContentFragment extends Adaptable {

    String getName();

    String getTitle();

    String getDescription();

    Iterator<ContentElement> getElements();

    boolean hasElement(String elementName);

    ContentElement getElement(String elementName);

    FragmentTemplate getTemplate();

    Iterator<VariationDef> listAllVariations();

    Iterator<Resource> getAssociatedContent();
}
