package io.sensible.studio.translation.impl;

import io.sensible.studio.translation.api.ReferenceTranslator;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Works a translation project's job on the server (Translation Projects → Run on the server): its items to do, one at
 * a time, with the translation worker's session (studio-translation-worker) on behalf of the user who started it, until
 * the job is done, stopped or the connector fails. A job interrupted by a restart runs again and resumes.
 */
@Component(service = JobConsumer.class, property = JobConsumer.PROPERTY_TOPICS + "=" + TranslationProjectJob.TOPIC)
public class TranslationProjectJob implements JobConsumer {

    static final String TOPIC = "studio/translation/project";
    static final String PROP_PROJECT = "project";
    static final String PROP_LANGUAGE = "language";
    static final String PROP_USER = "user";

    private static final Logger LOG = LoggerFactory.getLogger(TranslationProjectJob.class);

    @Reference
    private volatile Translator translator;

    @Reference
    private volatile PageTranslationRules rules;

    @Reference
    private volatile ResourceResolverFactory resolverFactory;

    private final List<ReferenceTranslator> referenceTranslators = new CopyOnWriteArrayList<>();

    @Reference(service = ReferenceTranslator.class, cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    void bindReferenceTranslator(ReferenceTranslator t) {
        referenceTranslators.add(t);
    }

    void unbindReferenceTranslator(ReferenceTranslator t) {
        referenceTranslators.remove(t);
    }

    @Override
    public JobResult process(Job job) {
        String project = job.getProperty(PROP_PROJECT, String.class);
        String language = job.getProperty(PROP_LANGUAGE, String.class);
        String user = job.getProperty(PROP_USER, String.class);
        try (ResourceResolver worker = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) "worker"))) {
            new TranslationProjects(worker, worker, translator, rules, referenceTranslators).as(user).runBackground(project, language);
            return JobResult.OK;
        } catch (IllegalArgumentException e) {
            LOG.info("Translation project {} ({}): {}", project, language, e.getMessage());
            return JobResult.CANCEL;
        } catch (Exception e) {
            LOG.warn("Translation project {} ({}) stopped", project, language, e);
            return JobResult.FAILED;
        }
    }
}
