/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import com.day.cq.wcm.api.WCMMode;

/**
 * The {@code wcmmode} script variable: {@code ${wcmmode.edit}}, {@code ${wcmmode.disabled}}, ...
 */
public final class WcmModeBinding {

    private final WCMMode mode;

    public WcmModeBinding(WCMMode mode) {
        this.mode = mode;
    }

    public boolean isEdit() {
        return mode == WCMMode.EDIT;
    }

    public boolean isPreview() {
        return mode == WCMMode.PREVIEW;
    }

    public boolean isDisabled() {
        return mode == WCMMode.DISABLED;
    }

    public boolean isDesign() {
        return mode == WCMMode.DESIGN;
    }

    public boolean isReadOnly() {
        return mode == WCMMode.READ_ONLY;
    }

    public boolean isAnalytics() {
        return mode == WCMMode.ANALYTICS;
    }

    @Override
    public String toString() {
        return mode.name();
    }
}
