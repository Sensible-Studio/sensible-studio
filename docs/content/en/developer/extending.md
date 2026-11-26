---
title: Extending
description: Add your own code to Sensible Studio: OSGi bundles, flow steps, translation providers, site configurations, scheduled jobs and endpoints.
order: 110
---

Sensible Studio is an OSGi container. You extend it the way you extend Sling or AEM: with a bundle that registers services. There is no plugin format of its own to learn.

## A bundle of your own

A Maven module with the `bnd-maven-plugin` and OSGi Declarative Services annotations. A project made from the AEM project archetype is already one.

Compile against:

- the Sling and OSGi APIs, from Maven Central;
- `studio-cq-api`, for the `com.day.cq.*` and `com.adobe.cq.*` packages. A project that compiles against Adobe's `aem-sdk-api` runs here without being recompiled; see [Run an existing AEM project](aem-projects.md).

Deploy the jar by putting it in the instance's `install` folder, embedding it in a content package under `/apps/<project>/install`, or uploading it in **Web Console › Bundles**. Remember the publish instances.

## Configuration

OSGi configurations are `.cfg.json` files, named after the service's PID, with `~name` for a factory configuration:

```
/apps/mysite/osgiconfig/config/com.example.mysite.MailService.cfg.json
/apps/mysite/osgiconfig/config.author/…      only on the author
/apps/mysite/osgiconfig/config.publish/…     only on publish instances
```

Secrets do not belong in these files. Read them from the environment or from a file outside the repository, the way the product does for the cache's flush secret and the sign-in key.

**Site configurations** (context-aware configuration) are for settings that differ per site and that people change without a deployment. Read them in code with Sling's `ConfigurationBuilder`. Their values are kept under `/conf/<site>/sling:configs`, inherited down the content tree, and edited per site in **Home › System › Developer › Site configurations**.

## Flow steps

A flow step that runs code is a service implementing `com.adobe.granite.workflow.exec.WorkflowProcess`, as in AEM.

```
@Component(service = WorkflowProcess.class,
           property = "process.label=Notify the newsroom")
public class NotifyNewsroom implements WorkflowProcess {
    @Override
    public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) {
        String path = item.getWorkflowData().getPayload().toString();
        …
    }
}
```

The label is what people choose in the flow editor's **Process** step. **Triggers** start a flow when content is created, changed or removed; **Auto-Assign** starts one on every page made from a template. See [Flows](../author/flows.md).

## Translation providers

Translation jobs are sent to a provider. The built-in ones talk to a language model (Anthropic, any OpenAI-compatible service, OpenRouter) and are configured in **Home › System › Translations › Providers**, with the key kept outside the content.

A provider of your own, for a translation agency's system, is an OSGi service in `studio-translation`'s provider interface: it receives the texts of a job with their source and target languages, and returns translations, at once or later. Look at the built-in providers in `studio-translation` for the contract.

## Servlets and endpoints

Register a servlet by resource type where you can, by path where you must:

```
@Component(service = Servlet.class)
@SlingServletResourceTypes(resourceTypes = "mysite/components/search",
                           selectors = "results", extensions = "json")
public class SearchResults extends SlingSafeMethodsServlet { … }
```

A servlet bound to a resource type is reached through the content (`/content/mysite/en/search.results.json`), so access control and the Live cache's `/content/*` rules already apply to it. A servlet bound to a path under `/bin` needs a filter rule of its own in `live.toml` before visitors can reach it.

Use the request's own resource resolver. A service user is for work nobody asked for (a scheduled job, an event handler): declare it with repoinit and a service user mapping, with rights on its own area only.

## Scheduled work and events

- **Scheduler**: a `Runnable` service with `scheduler.expression` (cron) or `scheduler.period`.
- **Jobs**: Sling jobs, for work that must survive a restart and be retried. **Developer › Jobs** shows them with their queues.
- **Content changes**: a `ResourceChangeListener` for paths you name. Do little in the listener; queue a job.
- **After a publication** on the live side: the Live cache's `[after_invalidation]` hook runs a command or calls a URL with what was removed. See [The Live cache](live-cache.md).

## Telling the cache what a page depends on

The Live cache removes a page when something it shows is published. The Core Components and the product's own components report what they show. A component of yours that reads other content (a list built from a query, a teaser of another page) is covered when it reads through the resource API: the pages and media it resolved during rendering are recorded.

If a component gets content some other way, such as from an index you built or an external service, its pages will not be removed when that content changes. Give those pages a time to live instead (`Cache-Control: s-maxage` with `enable_ttl`), or keep them out of the cache.

## Changing the product itself

The authoring interface is `author-ui` (React, TypeScript): `npm install`, `npm run dev` for a development server against a running author, `npm run package` for the content package. The Java features are the modules under `studio-uberjar`. `bin/studio test` runs the Java tests; the Live cache has its own (`cargo test` in `live-service`).

The project is under the Apache License 2.0.
