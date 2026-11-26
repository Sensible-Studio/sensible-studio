/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import java.util.Collections;
import java.util.HashMap;

import org.apache.sling.api.wrappers.ValueMapDecorator;

final class FormModelTestSupport {

    private FormModelTestSupport() {
    }

    static FormModel form(String path, String title) {
        return new FormModel(path, title, Collections.emptyList(), new ValueMapDecorator(new HashMap<>()));
    }
}
