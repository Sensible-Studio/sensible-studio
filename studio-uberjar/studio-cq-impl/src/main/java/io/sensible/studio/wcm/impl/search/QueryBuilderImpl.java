/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.QueryManager;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.eval.PredicateEvaluator;
import com.day.cq.search.result.SearchResult;

/**
 * QueryBuilder over Oak XPath. Results are read with the caller's session, so permissions apply.
 */
@Component(service = QueryBuilder.class)
public class QueryBuilderImpl implements QueryBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(QueryBuilderImpl.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public Query createQuery(Session session) {
        return new QueryImpl(new PredicateGroup("root"), session);
    }

    @Override
    public Query createQuery(PredicateGroup predicates, Session session) {
        return new QueryImpl(predicates == null ? new PredicateGroup("root") : predicates, session);
    }

    /**
     * A new resolver wrapping the caller's session, as with AEM: result resources belong to it
     * and callers are expected to close it ({@code hit.getResourceResolver().close()}). Closing
     * it does not log out the caller's session.
     */
    ResourceResolver resolverFor(Session session) {
        Map<String, Object> auth = new HashMap<>();
        auth.put("user.jcr.session", session);
        try {
            return resolverFactory.getResourceResolver(auth);
        } catch (LoginException e) {
            return null;
        }
    }

    final class QueryImpl implements Query {
        private final PredicateGroup predicates;
        private final Session session;
        private long start = -1;
        private long hitsPerPage = -2;

        QueryImpl(PredicateGroup predicates, Session session) {
            this.predicates = predicates;
            this.session = session;
        }

        @Override
        public PredicateGroup getPredicates() {
            return predicates;
        }

        @Override
        public void setStart(long start) {
            this.start = start;
        }

        @Override
        public void setHitsPerPage(long hitsPerPage) {
            this.hitsPerPage = hitsPerPage;
        }

        @Override
        public void registerPredicateEvaluator(String type, PredicateEvaluator evaluator) {
            // custom evaluators are not supported; their predicates are ignored
        }

        @Override
        public SearchResult getResult() {
            XPathBuilder x = XPathBuilder.build(predicates);
            // what a search finds changes with a publication where it looks: a page that shows it shows that
            io.sensible.studio.wcm.shown.Shown.search(x.path, "a search");
            long limit = hitsPerPage != -2 ? hitsPerPage : x.limit;
            long offset = start >= 0 ? start : x.offset;
            List<String> paths = new ArrayList<>();
            try {
                QueryManager qm = session.getWorkspace().getQueryManager();
                @SuppressWarnings("deprecation")
                javax.jcr.query.Query q = qm.createQuery(x.statement, javax.jcr.query.Query.XPATH);
                if (limit > 0) {
                    q.setLimit(limit);
                }
                if (offset > 0) {
                    q.setOffset(offset);
                }
                NodeIterator it = q.execute().getNodes();
                while (it.hasNext()) {
                    paths.add(it.nextNode().getPath());
                }
            } catch (RepositoryException e) {
                LOG.warn("query failed: {} ({})", x.statement, e.getMessage());
            }
            ResourceResolver rr = resolverFor(session);
            return new SearchResultImpl(x.statement, paths, rr);
        }
    }

    static final class SearchResultImpl implements SearchResult {
        private final String statement;
        private final List<String> paths;
        private final ResourceResolver resolver;

        SearchResultImpl(String statement, List<String> paths, ResourceResolver resolver) {
            this.statement = statement;
            this.paths = paths;
            this.resolver = resolver;
        }

        @Override
        public Iterator<Resource> getResources() {
            if (resolver == null) {
                return Collections.emptyIterator();
            }
            List<Resource> out = new ArrayList<>();
            for (String p : paths) {
                Resource r = resolver.getResource(p);
                if (r != null) {
                    out.add(r);
                }
            }
            return out.iterator();
        }

        @Override
        public long getTotalMatches() {
            return paths.size();
        }

        @Override
        public String getQueryStatement() {
            return statement;
        }
    }
}
