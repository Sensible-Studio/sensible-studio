/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.HashSet;
import java.util.Set;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;

import com.day.cq.wcm.api.WCMMode;
import com.day.cq.wcm.api.components.Component;

import io.sensible.studio.wcm.impl.msm.MsmState;

/**
 * What the Sensible Studio page editor needs to know about one component instance, written as
 * {@code data-studio-*} attributes on its decoration element when the page renders in
 * {@link WCMMode#EDIT}:
 * <pre>
 * data-studio-path="/content/site/page/jcr:content/root/title"
 * data-studio-type="site/components/title"
 * data-studio-title="Title"
 * data-studio-dialog="true"           the component (or a super type) has a cq:dialog
 * data-studio-design-dialog="true"    ... a cq:design_dialog
 * data-studio-container="true"        a paragraph system: children can be added
 * data-studio-drop="./fileReference"  assets can be dropped on it
 * data-studio-drop-accept="image/.*"  mime type patterns, comma separated
 * data-studio-msm="inherited"         rolled out from a blueprint ("cancelled": inheritance cancelled)
 * </pre>
 * Values are taken from the component and its super types (first definition that sets them).
 */
final class EditMeta {

    static final String GRID_TYPE = "wcm/foundation/components/responsivegrid";

    final String path;
    final String type;
    final String title;
    final boolean dialog;
    final boolean designDialog;
    final boolean container;
    final String dropProperty;
    final String dropAccept;
    /** {@link MsmState#of}: inherited, cancelled or {@code null} (local, or not in a live copy) */
    final String msm;

    private EditMeta(String path, String type, String title, boolean dialog, boolean designDialog,
            boolean container, String dropProperty, String dropAccept, String msm) {
        this.path = path;
        this.type = type;
        this.title = title;
        this.dialog = dialog;
        this.designDialog = designDialog;
        this.container = container;
        this.dropProperty = dropProperty;
        this.dropAccept = dropAccept;
        this.msm = msm;
    }

    /**
     * Only content of the page being edited is editable: components of other pages rendered into it
     * (experience fragments, inherited header/footer) are not.
     *
     * @return {@code true} if {@code path} lies in the content of the outermost page of the request
     */
    static boolean inEditedPage(String path, com.day.cq.wcm.api.components.ComponentContext context) {
        com.day.cq.wcm.api.components.ComponentContext root = context;
        while (root != null && root.getParent() != null) {
            root = root.getParent();
        }
        if (root == null || root.getPage() == null) {
            return false;
        }
        String content = root.getPage().getPath() + "/jcr:content";
        return path.equals(content) || path.startsWith(content + "/");
    }

    /** @return {@code true} if the request renders for the editor */
    static boolean isEdit(SlingHttpServletRequest request) {
        return WCMMode.fromRequest(request) == WCMMode.EDIT;
    }

    static EditMeta of(Resource resource, Component component) {
        String type = resource.getResourceType();
        String title = null;
        boolean dialog = false;
        boolean designDialog = false;
        boolean container = GRID_TYPE.equals(type);
        String dropProperty = null;
        String dropAccept = null;
        Set<String> seen = new HashSet<>();
        for (Component c = component; c != null && seen.add(c.getPath()); c = c.getSuperComponent()) {
            if (title == null) {
                title = c.getTitle();
            }
            container |= c.isContainer() || GRID_TYPE.equals(c.getResourceType());
            if (c instanceof ComponentDefinitions.Snapshot) {
                ComponentDefinitions.Snapshot s = (ComponentDefinitions.Snapshot) c;
                dialog |= s.isEditable();
                designDialog |= s.hasDesignDialog;
                if (dropProperty == null && s.dropTarget != null) {
                    dropProperty = s.dropTarget.propertyName;
                    dropAccept = String.join(",", s.dropTarget.accept);
                }
            } else {
                dialog |= c.isEditable();
                Resource def = c.adaptTo(Resource.class);
                if (def != null) {
                    designDialog |= def.getChild("cq:design_dialog") != null;
                    if (dropProperty == null) {
                        ComponentDefinitions.DropTarget dt = ComponentDefinitions.DropTarget.of(def);
                        if (dt != null) {
                            dropProperty = dt.propertyName;
                            dropAccept = String.join(",", dt.accept);
                        }
                    }
                }
            }
        }
        if (title == null) {
            title = component != null ? component.getName() : type.substring(type.lastIndexOf('/') + 1);
        }
        return new EditMeta(resource.getPath(), type, title, dialog, designDialog, container, dropProperty, dropAccept,
                MsmState.of(resource));
    }

    /** @return the attributes, each preceded by a space */
    String attributes() {
        StringBuilder sb = new StringBuilder();
        attr(sb, "data-studio-path", path);
        attr(sb, "data-studio-type", type);
        attr(sb, "data-studio-title", title);
        if (dialog) {
            attr(sb, "data-studio-dialog", "true");
        }
        if (designDialog) {
            attr(sb, "data-studio-design-dialog", "true");
        }
        if (container) {
            attr(sb, "data-studio-container", "true");
        }
        if (dropProperty != null) {
            attr(sb, "data-studio-drop", dropProperty);
            attr(sb, "data-studio-drop-accept", dropAccept);
        }
        if (msm != null) {
            attr(sb, "data-studio-msm", msm);
        }
        return sb.toString();
    }

    private static void attr(StringBuilder sb, String name, String value) {
        sb.append(' ').append(name).append("=\"").append(Decoration.escape(value == null ? "" : value)).append('"');
    }

    /**
     * The empty slot at the end of an editable paragraph system ("Drag components here"), with the classes of AEM's
     * new-section paragraph (the site's grid CSS clears it; the editor's authoring styles give it its height).
     */
    static String placeholder(String gridPath) {
        return "<div class=\"new section aem-Grid-newComponent\" data-studio-placeholder=\""
                + Decoration.escape(gridPath) + "\"></div>";
    }
}
