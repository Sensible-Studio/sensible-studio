package io.sensible.studio.workflow.impl;

import java.util.Map;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rule of an OR branch or a Goto step, as AEM models write it: ECMAScript with a function
 * {@code check()} that says whether the branch is taken, with {@code workflowData},
 * {@code workflowSession}, {@code jcrSession}, {@code workItem} and {@code log} at hand. Until
 * 28.11.2026 such a rule was not run and counted as "no", so a flow that branched on its data took
 * the default branch without a word.
 *
 * <p>Kept apart from the engine: Rhino is an optional import, and this class is the only one that
 * names it.
 */
final class Rules {

    private static final Logger LOG = LoggerFactory.getLogger(Rules.class);

    private Rules() {
    }

    /** What {@code check()} of the script answers; {@code null} when the script has no such function. */
    static Boolean check(String script, Map<String, Object> vars) {
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1); // interpreted: nothing is compiled to classes
            Scriptable scope = cx.initStandardObjects();
            for (Map.Entry<String, Object> e : vars.entrySet()) {
                if (e.getValue() != null) {
                    ScriptableObject.putProperty(scope, e.getKey(), Context.javaToJS(e.getValue(), scope));
                }
            }
            ScriptableObject.putProperty(scope, "log", Context.javaToJS(LOG, scope));
            cx.evaluateString(scope, script, "rule", 1, null);
            Object check = scope.get("check", scope);
            if (!(check instanceof Function)) {
                return null;
            }
            return Context.toBoolean(((Function) check).call(cx, scope, scope, new Object[0]));
        } finally {
            Context.exit();
        }
    }
}
