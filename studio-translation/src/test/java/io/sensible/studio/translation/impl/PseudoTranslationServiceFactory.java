package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationConstants;
import com.adobe.granite.translation.api.TranslationService;
import com.adobe.granite.translation.api.TranslationServiceFactory;
import io.sensible.studio.translation.api.Segment;
import io.sensible.studio.translation.api.SegmentResult;
import io.sensible.studio.translation.api.TranslationRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A connector for the tests only (it is not part of the bundle): prefixes every text with the target language
 * ({@code [fr] ...}), leaving markup, links and placeholders untouched. Deterministic and offline.
 */
public class PseudoTranslationServiceFactory implements TranslationServiceFactory {

    static final String NAME = "studio-pseudo";
    private static final Pattern TEXT = Pattern.compile("(^|>)([^<]+)");

    @Override
    public TranslationService createTranslationService(TranslationConstants.TranslationMethod method, String cloudConfigPath) {
        return new DocumentService(DocumentService.info(NAME, "Pseudo translation (testing)", "Pseudo translated")) {
            @Override
            public List<SegmentResult> translateDocument(TranslationRequest request) {
                String prefix = "[" + request.getTargetLanguage() + "] ";
                List<SegmentResult> out = new ArrayList<>();
                for (Segment s : request.getSegments()) out.add(SegmentResult.translated(s.getId(), pseudo(s, prefix)));
                return out;
            }
        };
    }

    static String pseudo(Segment s, String prefix) {
        if (!s.isHtml()) return s.getText().trim().isEmpty() ? s.getText() : prefix + s.getText();
        Matcher m = TEXT.matcher(s.getText());
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String text = m.group(2);
            String replaced = text.trim().isEmpty() ? text : text.replaceFirst("^(\\s*)", "$1" + Matcher.quoteReplacement(prefix));
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + replaced));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @Override
    public List<TranslationConstants.TranslationMethod> getSupportedTranslationMethods() {
        return Collections.singletonList(TranslationConstants.TranslationMethod.MACHINE_TRANSLATION);
    }

    @Override
    public Class<?> getServiceCloudConfigClass() {
        return null;
    }

    @Override
    public String getServiceFactoryName() {
        return NAME;
    }
}
