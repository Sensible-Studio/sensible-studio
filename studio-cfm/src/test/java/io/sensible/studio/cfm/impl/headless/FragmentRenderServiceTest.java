package io.sensible.studio.cfm.impl.headless;

import com.adobe.cq.dam.cfm.ContentFragment;
import io.sensible.studio.cfm.impl.ContentFragmentAdapterFactory;
import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The service Core Components' Content Fragment component needs to render a fragment's text (WKND articles). */
@ExtendWith(SlingContextExtension.class)
class FragmentRenderServiceTest {

    private static final String DAM = "/content/dam/wknd-shared";
    private static final String ALASKA = DAM + "/en/magazine/alaska-adventure/alaskan-adventures";
    private static final String BALI = DAM + "/en/adventures/bali-surf-camp/bali-surf-camp";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private final FragmentRenderServiceImpl service = new FragmentRenderServiceImpl();

    @BeforeEach
    void setUp() {
        context.load().json("/wknd-shared-dam.json", DAM);
        context.load().json("/wknd-shared-models.json", "/conf/wknd-shared/settings/dam/cfm/models");
        Map<String, Object> props = new HashMap<>();
        props.put(AdapterFactory.ADAPTABLE_CLASSES, new String[]{Resource.class.getName()});
        props.put(AdapterFactory.ADAPTER_CLASSES, new String[]{ContentFragment.class.getName()});
        context.registerService(AdapterFactory.class, new ContentFragmentAdapterFactory(), props);
    }

    private int count;

    private Resource component(Object... props) {
        return context.create().resource("/content/wknd/us/en/magazine/page/jcr:content/root/contentfragment_" + count++, props);
    }

    @Test
    void rendersTheChosenElementAsHtmlForTheComponentToSplit() {
        String html = service.render(component("sling:resourceType", "wknd/components/contentfragment",
                "fragmentPath", ALASKA, "elementNames", new String[]{"main"}, "displayMode", "singleText"));
        assertTrue(html.startsWith("<p>My brother Neil"), html.substring(0, 40));
        // Core Components splits on block starts; WKND's article has several paragraphs
        assertTrue(html.split("(?=(<p>|<h1>|<h2>|<h3>|<h4>|<h5>|<h6>))").length > 3);
    }

    @Test
    void fallsBackToMasterAndToTheFirstTextElement() {
        String html = service.render(component("fragmentPath", BALI, "elementNames", new String[]{"description"},
                "variationName", "no-such-variation"));
        assertTrue(html.contains("Surfing in Bali"), html);
        assertTrue(service.render(component("fragmentPath", BALI)).contains("Bali Surf Camp"), "first element: title");
    }

    @Test
    void answersNullWithoutAFragment() {
        assertNull(service.render(component("fragmentPath", DAM + "/missing")));
        assertNull(service.render(component("jcr:title", "no path")));
        assertNull(service.render(null));
        assertNull(service.render(component("fragmentPath", BALI, "elementNames", new String[]{"noSuchElement"})));
    }
}
