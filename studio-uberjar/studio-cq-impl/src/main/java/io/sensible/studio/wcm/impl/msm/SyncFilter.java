/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a synchronisation action leaves alone, configured like AEM's actions (same property names, so a project's
 * OSGi configuration for them applies unchanged):
 * <ul>
 *   <li>{@value #NODE_TYPES}: nodes of these primary types (or with these names) are not synchronised;</li>
 *   <li>{@value #PROPS}: regular expressions for properties of the page content node;</li>
 *   <li>{@value #PARAGRAPH_ITEMS}: regular expressions for properties and child nodes below the page content node.</li>
 * </ul>
 * Protected JCR properties and the MSM bookkeeping itself are never copied, whatever the configuration.
 */
public final class SyncFilter {

    public static final String NODE_TYPES = "cq.wcm.msm.action.excludednodetypes";
    public static final String PARAGRAPH_ITEMS = "cq.wcm.msm.action.excludedparagraphitems";
    public static final String PROPS = "cq.wcm.msm.action.excludedprops";

    private static final Logger LOG = LoggerFactory.getLogger(SyncFilter.class);

    /** Never synchronised: maintained by the repository, by MSM, by replication or by the page lock. */
    static final Set<String> INTERNAL = new HashSet<>(Arrays.asList(
            "jcr:primaryType", "jcr:mixinTypes", "jcr:uuid", "jcr:created", "jcr:createdBy", "jcr:baseVersion",
            "jcr:predecessors", "jcr:versionHistory", "jcr:isCheckedOut", "jcr:lockOwner", "jcr:lockIsDeep",
            "cq:lastRolledout", "cq:lastRolledoutBy", "cq:isCancelledForChildren", "cq:propertyInheritanceCancelled",
            "cq:lastReplicated", "cq:lastReplicatedBy", "cq:lastReplicationAction", "studio:lockOwner"));
    private static final Pattern INTERNAL_PATTERN = Pattern.compile("(cq:lastReplicat.*|cq:lastPublished.*|jcr:frozen.*)");
    /** Child nodes never synchronised: live copy configurations and access control lists. */
    static final Set<String> INTERNAL_NODES = new HashSet<>(Arrays.asList(
            "cq:LiveSyncConfig", "cq:BlueprintSyncConfig", "rep:policy", "rep:repoPolicy"));

    private final Set<String> nodeTypes;
    private final List<Pattern> paragraphItems;
    private final List<Pattern> props;

    SyncFilter(String[] nodeTypes, String[] paragraphItems, String[] props) {
        this.nodeTypes = new HashSet<>(Arrays.asList(nodeTypes));
        this.paragraphItems = compile(paragraphItems);
        this.props = compile(props);
    }

    static final SyncFilter NONE = new SyncFilter(new String[0], new String[0], new String[0]);

    /** @return the filter from an OSGi configuration, with {@code defaults} for missing properties */
    static SyncFilter of(Map<String, Object> config, SyncFilter defaults) {
        if (config == null) {
            return defaults;
        }
        String[] nt = strings(config.get(NODE_TYPES));
        String[] pi = strings(config.get(PARAGRAPH_ITEMS));
        String[] pr = strings(config.get(PROPS));
        return new SyncFilter(nt == null ? defaults.nodeTypes.toArray(new String[0]) : nt,
                pi == null ? patterns(defaults.paragraphItems) : pi,
                pr == null ? patterns(defaults.props) : pr);
    }

    private static String[] patterns(List<Pattern> list) {
        return list.stream().map(Pattern::pattern).toArray(String[]::new);
    }

    private static String[] strings(Object v) {
        if (v instanceof String[]) {
            return (String[]) v;
        }
        if (v instanceof String) {
            return new String[] {(String) v};
        }
        return null;
    }

    private static List<Pattern> compile(String[] regexes) {
        List<Pattern> out = new ArrayList<>();
        for (String r : regexes) {
            if (r == null || r.trim().isEmpty()) {
                continue;
            }
            try {
                out.add(Pattern.compile(r.trim()));
            } catch (PatternSyntaxException e) {
                LOG.warn("Ignoring invalid MSM exclusion pattern {}", r);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** @return the page property patterns */
    List<String> propPatterns() {
        return Arrays.asList(patterns(props));
    }

    /** @return {@code true} if the property is never touched by MSM */
    public static boolean isInternal(String name) {
        return INTERNAL.contains(name) || INTERNAL_PATTERN.matcher(name).matches();
    }

    /** @return {@code true} if the node (by primary type or name) is not synchronised */
    boolean isNodeExcluded(Resource node) {
        if (node == null) {
            return false;
        }
        String name = node.getName();
        if (INTERNAL_NODES.contains(name)) {
            return true;
        }
        String type = node.getValueMap().get(Msm.PRIMARY, String.class);
        return nodeTypes.contains(name) || (type != null && nodeTypes.contains(type));
    }

    /** @return {@code true} if the child node name below the page content is excluded as a paragraph item */
    boolean isChildExcluded(String name) {
        return INTERNAL_NODES.contains(name) || matches(paragraphItems, name);
    }

    /**
     * @param pageContent the property belongs to the page content node (else to a node below it)
     * @return {@code true} if the property is not synchronised
     */
    boolean isPropertyExcluded(String name, boolean pageContent) {
        return isInternal(name) || matches(pageContent ? props : paragraphItems, name);
    }

    private static boolean matches(List<Pattern> patterns, String name) {
        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }
}
