/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/**
 * What a form asks for: its fields in page order, read from the form page's content (every component of
 * {@code studio/forms/components/*} under {@code jcr:content/root}), with each field's rules. The submit servlet
 * validates against it on the server, the Submissions view takes its columns from it.
 */
public final class FormModel {

    static final String TYPE_PREFIX = "studio/forms/components/";
    static final String ROOT_TYPE = TYPE_PREFIX + "form";
    /** Letters, digits, _ and -: the keys of a submission, safe as property names and CSV headers. */
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public static final class Field {
        public final String name;
        public final String type;
        public final String label;
        public final boolean required;
        public final Long min;
        public final Long max;
        public final Long minLength;
        public final Long maxLength;
        public final String pattern;
        public final List<String> options;
        public final String checkedValue;
        /** date: the earliest / latest day allowed, ISO yyyy-mm-dd */
        public final String minDate;
        public final String maxDate;
        /** fileupload: what the browser's picker offers (".pdf,image/*") and the largest file, in MB */
        public final String accept;
        public final long maxSizeMb;
        public final boolean multiple;
        /** The rules of the field, and of every panel it sits in (a hidden panel hides its fields). */
        public final List<Rule> rules;

        Field(String name, String type, ValueMap vm, List<Rule> rules) {
            this.name = name;
            this.type = type;
            this.label = vm.get("label", name);
            this.required = vm.get("required", false);
            this.min = vm.get("min", Long.class);
            this.max = vm.get("max", Long.class);
            this.minLength = vm.get("minLength", Long.class);
            this.maxLength = vm.get("maxLength", Long.class);
            this.pattern = blank(vm.get("pattern", String.class));
            String[] o = vm.get("options", String[].class);
            this.options = o == null ? Collections.emptyList() : java.util.Arrays.asList(o);
            this.checkedValue = vm.get("checkedValue", "yes");
            this.minDate = blank(vm.get("minDate", String.class));
            this.maxDate = blank(vm.get("maxDate", String.class));
            this.accept = blank(vm.get("accept", String.class));
            this.maxSizeMb = vm.get("maxSize", 10L);
            this.multiple = vm.get("multiple", false);
            this.rules = rules;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("type", type);
            m.put("label", label);
            m.put("required", required);
            return m;
        }
    }

    /** One rule: {@code action} (show / hide / require / disable) when {@code field} {@code op} {@code value}. */
    public static final class Rule {
        public final String action;
        public final String field;
        public final String op;
        public final String value;

        Rule(ValueMap vm) {
            this.action = vm.get("action", "show");
            this.field = vm.get("field", "");
            this.op = vm.get("op", "is");
            this.value = vm.get("value", "");
        }

        /** Mirrors {@code holds()} in forms.js. */
        boolean holds(Map<String, Object> values) {
            Object raw = values.get(field);
            String v = raw == null ? "" : String.valueOf(raw).trim();
            String want = value == null ? "" : value;
            switch (op) {
                case "is":
                    return v.equals(want);
                case "isnot":
                    return !v.equals(want);
                case "contains":
                    return v.toLowerCase().contains(want.toLowerCase());
                case "empty":
                    return v.isEmpty();
                case "notempty":
                    return !v.isEmpty();
                case "gt":
                    return !v.isEmpty() && number(v) > number(want);
                case "lt":
                    return !v.isEmpty() && number(v) < number(want);
                default:
                    return false;
            }
        }

        private static double number(String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
    }

    public final String path;
    public final String title;
    public final List<Field> fields;
    public final ValueMap settings;

    FormModel(String path, String title, List<Field> fields, ValueMap settings) {
        this.path = path;
        this.title = title;
        this.fields = Collections.unmodifiableList(fields);
        this.settings = settings;
    }

    /** @return the model of the form page at {@code page}, or null when it is no form */
    public static FormModel of(Resource page) {
        if (page == null) {
            return null;
        }
        Resource content = page.getChild("jcr:content");
        if (content == null) {
            return null;
        }
        Resource root = content.getChild("root");
        if (root == null || !root.isResourceType(ROOT_TYPE)) {
            return null;
        }
        List<Field> fields = new ArrayList<>();
        collect(root, fields, Collections.emptyList());
        return new FormModel(page.getPath(), content.getValueMap().get("jcr:title", page.getName()), fields, root.getValueMap());
    }

    private static void collect(Resource r, List<Field> out, List<Rule> inherited) {
        for (Resource child : r.getChildren()) {
            String rt = child.getResourceType();
            List<Rule> rules = inherited;
            if (rt != null && rt.startsWith(TYPE_PREFIX)) {
                String type = rt.substring(TYPE_PREFIX.length());
                String name = child.getValueMap().get("name", String.class);
                List<Rule> own = rulesOf(child);
                if (!own.isEmpty()) {
                    rules = new ArrayList<>(inherited);
                    rules.addAll(own);
                }
                boolean input = !type.equals("panel") && !type.equals("form") && !type.equals("text") && !type.equals("submit") && !type.equals("rules");
                if (input && name != null && NAME.matcher(name).matches()) {
                    out.add(new Field(name, type, child.getValueMap(), rules));
                }
            }
            collect(child, out, rules);
        }
    }

    private static List<Rule> rulesOf(Resource field) {
        Resource rules = field.getChild("rules");
        if (rules == null) {
            return Collections.emptyList();
        }
        List<Rule> out = new ArrayList<>();
        for (Resource r : rules.getChildren()) {
            Rule rule = new Rule(r.getValueMap());
            if (!rule.field.isEmpty()) {
                out.add(rule);
            }
        }
        return out;
    }

    /**
     * The rules' verdict for a field, from the values sent: hidden fields are dropped (not validated, not
     * stored), required / disabled follow the rules. Mirrors {@code applyRules()} in forms.js, in a single
     * pass over the values as sent (rules refer to other fields' values, not to their visibility).
     */
    static final class Verdict {
        boolean visible = true;
        boolean disabled;
        Boolean required;
    }

    static Verdict verdict(Field f, Map<String, Object> values) {
        Verdict v = new Verdict();
        boolean anyShow = false;
        boolean allShow = true;
        boolean anyRequire = false;
        boolean allRequire = true;
        for (Rule r : f.rules) {
            boolean h = r.holds(values);
            switch (r.action) {
                case "show":
                    anyShow = true;
                    allShow &= h;
                    break;
                case "hide":
                    if (h) {
                        v.visible = false;
                    }
                    break;
                case "require":
                    anyRequire = true;
                    allRequire &= h;
                    break;
                case "disable":
                    v.disabled |= h;
                    break;
                default:
                    break;
            }
        }
        if (anyShow && !allShow) {
            v.visible = false;
        }
        if (anyRequire) {
            v.required = allRequire;
        }
        return v;
    }

    public Field field(String name) {
        for (Field f : fields) {
            if (f.name.equals(name)) {
                return f;
            }
        }
        return null;
    }

    /**
     * Checks a submission the way the browser did: required, the type's shape, lengths, range, pattern, the
     * options of a choice. Unknown keys are dropped (the result holds the accepted values only).
     *
     * @return field name → message for every field that fails; empty when the submission is fine
     */
    public Map<String, String> validate(Map<String, Object> data, Map<String, Object> accepted) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (Field f : fields) {
            Verdict verdict = verdict(f, data);
            if (!verdict.visible || verdict.disabled) {
                continue; // not on the visitor's screen: neither checked nor kept
            }
            boolean required = verdict.required != null ? verdict.required : f.required;
            Object raw = data.get(f.name);
            String value = raw == null ? "" : raw instanceof List ? String.join(", ", strings((List<?>) raw)) : String.valueOf(raw).trim();
            if (value.length() > 20000) {
                errors.put(f.name, "Too long.");
                continue;
            }
            if ("checkbox".equals(f.type)) {
                boolean ticked = !value.isEmpty() && !"false".equals(value) && !"off".equals(value);
                if (required && !ticked) {
                    errors.put(f.name, "This field is required.");
                }
                accepted.put(f.name, ticked ? f.checkedValue : "");
                continue;
            }
            if (value.isEmpty()) {
                if (required) {
                    errors.put(f.name, "This field is required.");
                }
                accepted.put(f.name, "");
                continue;
            }
            String err = check(f, value);
            if (err != null) {
                errors.put(f.name, err);
            }
            accepted.put(f.name, value);
        }
        return errors;
    }

    private static String check(Field f, String value) {
        switch (f.type) {
            case "email":
                if (!value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                    return "Enter a valid email address.";
                }
                break;
            case "number": {
                double n;
                try {
                    n = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    return "Enter a number.";
                }
                if (f.min != null && n < f.min) {
                    return "Enter a number of at least " + f.min + ".";
                }
                if (f.max != null && n > f.max) {
                    return "Enter a number of at most " + f.max + ".";
                }
                break;
            }
            case "dropdown":
            case "radiogroup":
                if (!f.options.isEmpty() && !f.options.contains(value)) {
                    return "Choose one of the options.";
                }
                break;
            case "checkboxgroup":
                if (!f.options.isEmpty()) {
                    for (String part : value.split(", ")) {
                        if (!f.options.contains(part)) {
                            return "Choose from the options.";
                        }
                    }
                }
                return null;
            case "hidden":
                return null;
            case "date": {
                java.time.LocalDate d;
                try {
                    d = java.time.LocalDate.parse(value);
                } catch (java.time.format.DateTimeParseException e) {
                    return "Enter a date.";
                }
                if (f.minDate != null && d.isBefore(java.time.LocalDate.parse(f.minDate))) {
                    return "Enter a date from " + f.minDate + " on.";
                }
                if (f.maxDate != null && d.isAfter(java.time.LocalDate.parse(f.maxDate))) {
                    return "Enter a date up to " + f.maxDate + ".";
                }
                return null; // lengths and patterns do not apply to a date
            }
            case "fileupload":
                return null; // the files themselves are checked by checkFile
            default:
                break;
        }
        if (f.minLength != null && value.length() < f.minLength) {
            return "Enter at least " + f.minLength + " characters.";
        }
        if (f.maxLength != null && value.length() > f.maxLength) {
            return "Enter at most " + f.maxLength + " characters.";
        }
        if (f.pattern != null) {
            try {
                if (!Pattern.compile("^(?:" + f.pattern + ")$").matcher(value).matches()) {
                    return "This value does not have the expected format.";
                }
            } catch (PatternSyntaxException e) {
                // a broken pattern never blocks the visitor, as in the browser
            }
        }
        return null;
    }

    /**
     * Checks one uploaded file against a fileupload field: size and the accept list (extensions like
     * {@code .pdf}, types like {@code image/*} or {@code application/pdf}).
     *
     * @return a message, or null when the file is fine
     */
    public static String checkFile(Field f, String fileName, String contentType, long size) {
        if (size > f.maxSizeMb * 1024L * 1024L) {
            return "The file is larger than " + f.maxSizeMb + " MB.";
        }
        if (f.accept == null) {
            return null;
        }
        String name = fileName == null ? "" : fileName.toLowerCase();
        String type = contentType == null ? "" : contentType.toLowerCase();
        for (String a : f.accept.split(",")) {
            String want = a.trim().toLowerCase();
            if (want.isEmpty()) {
                continue;
            }
            if (want.startsWith(".") && name.endsWith(want)) {
                return null;
            }
            if (want.endsWith("/*") && type.startsWith(want.substring(0, want.length() - 1))) {
                return null;
            }
            if (want.equals(type)) {
                return null;
            }
        }
        return "This kind of file is not accepted (" + f.accept + ").";
    }

    private static List<String> strings(List<?> l) {
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            out.add(String.valueOf(o).trim());
        }
        return out;
    }

    private static String blank(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }
}
