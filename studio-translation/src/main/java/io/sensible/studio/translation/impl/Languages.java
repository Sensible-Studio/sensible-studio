package io.sensible.studio.translation.impl;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Language codes as AEM writes them ({@code fr}, {@code de_ch}, {@code pt-br}) and their English names. */
final class Languages {

    private Languages() {
    }

    static Locale locale(String code) {
        return Locale.forLanguageTag(code == null ? "" : code.replace('_', '-'));
    }

    /** e.g. {@code de_ch} → "German (Switzerland)"; the code itself when unknown. */
    static String name(String code) {
        if (code == null || code.isEmpty()) return "the source language";
        Locale l = locale(code);
        String name = l.getDisplayName(Locale.ENGLISH);
        return name.isEmpty() ? code : name;
    }

    /** The languages AI models translate well, by code. */
    static Map<String, String> common() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String c : new String[]{"en", "fr", "de", "es", "it", "pt", "pt_br", "nl", "da", "sv", "no", "fi", "pl",
                "cs", "sk", "hu", "ro", "bg", "el", "hr", "sl", "et", "lv", "lt", "uk", "ru", "tr", "ar", "he", "hi",
                "th", "vi", "id", "ms", "ja", "ko", "zh_cn", "zh_tw"}) {
            m.put(c, name(c));
        }
        return m;
    }
}
