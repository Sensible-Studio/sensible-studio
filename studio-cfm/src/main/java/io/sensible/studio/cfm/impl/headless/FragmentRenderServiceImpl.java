package io.sensible.studio.cfm.impl.headless;

import com.adobe.cq.dam.cfm.ContentElement;
import com.adobe.cq.dam.cfm.ContentFragment;
import com.adobe.cq.dam.cfm.ContentVariation;
import com.adobe.cq.dam.cfm.FragmentData;
import com.adobe.cq.dam.cfm.content.FragmentRenderService;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;

/**
 * Renders the text element a Content Fragment component shows, as HTML. Core Components' Content Fragment
 * component (v1, display mode "single text") calls this with its own resource and splits the answer into
 * paragraphs, between which authors place in-between content.
 *
 * <p>Reads the component's {@code fragmentPath}, {@code elementNames} (the first one; the fragment's first element
 * when none is set) and {@code variationName} (master when unset or unknown), and converts the stored text from
 * its content type (HTML, Markdown or plain text) to HTML.</p>
 */
@Component(service = FragmentRenderService.class)
public class FragmentRenderServiceImpl implements FragmentRenderService {

    static final String PN_FRAGMENT_PATH = "fragmentPath";
    static final String PN_ELEMENT_NAMES = "elementNames";
    static final String PN_VARIATION = "variationName";

    @Override
    public String render(Resource component) {
        if (component == null) return null;
        ValueMap props = component.getValueMap();
        String path = props.get(PN_FRAGMENT_PATH, String.class);
        if (path == null || path.isEmpty()) return null;
        Resource asset = component.getResourceResolver().getResource(path);
        ContentFragment fragment = asset == null ? null : asset.adaptTo(ContentFragment.class);
        if (fragment == null) return null;

        String[] names = props.get(PN_ELEMENT_NAMES, String[].class);
        String name = names != null && names.length > 0 && !names[0].isEmpty() ? names[0] : null;
        ContentElement element = fragment.getElement(name);
        if (element == null) return null;

        String text;
        String contentType;
        String variationName = props.get(PN_VARIATION, String.class);
        ContentVariation variation = variationName == null || variationName.isEmpty() || "master".equals(variationName)
                ? null : element.getVariation(variationName);
        if (variation != null) {
            text = text(variation.getValue());
            contentType = variation.getContentType();
        } else {
            text = text(element.getValue());
            contentType = element.getContentType();
        }
        return text == null ? "" : new RichText(contentType, text).html();
    }

    private static String text(FragmentData data) {
        if (data == null) return null;
        Object raw = data.getValue();
        if (raw instanceof Object[]) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (Object[]) raw) {
                if (o == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(o);
            }
            return sb.toString();
        }
        return raw == null ? null : raw.toString();
    }
}
