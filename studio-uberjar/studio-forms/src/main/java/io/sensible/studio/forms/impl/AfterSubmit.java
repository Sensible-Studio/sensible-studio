/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.Workflow;
import com.adobe.granite.workflow.model.WorkflowModel;

/**
 * What a form does after a submission is stored, from the form's own settings ({@code jcr:content/root}):
 * <ul>
 *   <li>{@code recordModel} + {@code recordFolder}: create a record of that model in that folder from the values
 *       ({@link RecordWriter}; title from {@code recordTitle}, fields mapped by {@code recordMapping});</li>
 *   <li>{@code flowModel}: start that flow with the submission as payload (right away, it is quick);</li>
 *   <li>{@code webhookUrl}: POST the submission as JSON there;</li>
 *   <li>{@code notifyTo}: send the values by email (the {@code io.sensible.studio.forms.impl.Mail} configuration says how).</li>
 * </ul>
 * Webhook and email run on a background thread so the visitor is not kept waiting. Each action's outcome is
 * kept with the submission ({@code actions/<name>}: status {@code done} / {@code failed} / {@code skipped},
 * detail), where the Submissions view shows it. A failed action never fails the submission.
 */
@Component(service = AfterSubmit.class)
public class AfterSubmit {

    private static final Logger LOG = LoggerFactory.getLogger(AfterSubmit.class);
    static final int TIMEOUT_MS = 10000;

    @Reference
    private SubmissionStore store;

    @Reference
    private Mail mail;

    private ExecutorService later;

    @Activate
    protected void activate() {
        later = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "studio-forms-after-submit");
            t.setDaemon(true);
            return t;
        });
    }

    @Deactivate
    protected void deactivate() {
        later.shutdown();
        try {
            later.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs the form's actions for a stored submission; {@code resolver} is the forms service resolver of the request. */
    public void run(ResourceResolver resolver, FormModel model, String submissionPath, Map<String, Object> values) {
        ValueMap settings = model.settings;
        String flow = blank(settings.get("flowModel", String.class));
        String webhook = blank(settings.get("webhookUrl", String.class));
        String notify = blank(settings.get("notifyTo", String.class));
        String recordModel = blank(settings.get("recordModel", String.class));
        if (recordModel != null) {
            createRecord(resolver, model, recordModel, submissionPath, values);
        }
        if (flow != null) {
            startFlow(resolver, flow, submissionPath);
        }
        if (webhook == null && notify == null) {
            return;
        }
        Map<String, Object> payload = payload(model, submissionPath, values);
        String subject = settings.get("notifySubject", "New submission: " + model.title);
        later.execute(() -> {
            try (ResourceResolver r = store.serviceResolver()) {
                if (webhook != null) {
                    post(r, webhook, submissionPath, payload);
                }
                if (notify != null) {
                    email(r, notify, subject, model, submissionPath, values);
                }
            } catch (Exception e) {
                LOG.warn("After-submit actions of {} failed: {}", submissionPath, e.getMessage());
            }
        });
    }

    static Map<String, Object> payload(FormModel model, String submissionPath, Map<String, Object> values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", model.path);
        p.put("title", model.title);
        p.put("submission", submissionPath);
        p.put("submittedAt", java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(java.time.ZonedDateTime.now()));
        List<Object> fields = new ArrayList<>();
        for (FormModel.Field f : model.fields) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.name);
            fm.put("label", f.label);
            fm.put("value", values.get(f.name));
            fields.add(fm);
        }
        p.put("fields", fields);
        p.put("values", values);
        return p;
    }

    private void createRecord(ResourceResolver resolver, FormModel model, String recordModel, String submissionPath, Map<String, Object> values) {
        String folder = blank(model.settings.get("recordFolder", String.class));
        try {
            if (folder == null) {
                record(resolver, submissionPath, "record", "skipped", "Choose the folder the records go to");
                return;
            }
            if (RecordWriter.disabled(resolver, recordModel)) {
                record(resolver, submissionPath, "record", "skipped", "The model " + recordModel + " is disabled");
                return;
            }
            Map<String, String> mapping = new LinkedHashMap<>();
            org.apache.sling.api.resource.Resource map = resolver.getResource(model.path + "/jcr:content/root/recordMapping");
            if (map != null) {
                for (org.apache.sling.api.resource.Resource item : map.getChildren()) {
                    String field = blank(item.getValueMap().get("field", String.class));
                    if (field != null) {
                        String element = item.getValueMap().get("element", "");
                        mapping.put(field, element.trim());
                    }
                }
            }
            RecordWriter.Result r = RecordWriter.write(resolver, model, recordModel, folder, model.settings.get("recordTitle", String.class),
                    mapping, values, java.util.Calendar.getInstance());
            record(resolver, submissionPath, "record", "done",
                    r.path + (r.skipped.isEmpty() ? "" : " — not written: " + String.join(", ", r.skipped)));
        } catch (Exception e) {
            resolver.revert();
            LOG.warn("Record for {} not created: {}", submissionPath, e.getMessage());
            record(resolver, submissionPath, "record", "failed", e.getMessage());
        }
    }

    private void startFlow(ResourceResolver resolver, String modelId, String submissionPath) {
        try {
            WorkflowSession ws = resolver.adaptTo(WorkflowSession.class);
            if (ws == null) {
                record(resolver, submissionPath, "flow", "skipped", "Flows are not available on this instance");
                return;
            }
            WorkflowModel model = ws.getModel(modelId);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", "forms");
            Workflow wf = ws.startWorkflow(model, ws.newWorkflowData("JCR_PATH", submissionPath), meta);
            record(resolver, submissionPath, "flow", "done", wf.getId());
        } catch (Exception e) {
            LOG.warn("Flow {} for {} did not start: {}", modelId, submissionPath, e.getMessage());
            record(resolver, submissionPath, "flow", "failed", e.getMessage());
        }
    }

    private void post(ResourceResolver resolver, String url, String submissionPath, Map<String, Object> payload) {
        try {
            URL u = new URL(url);
            if (!"http".equals(u.getProtocol()) && !"https".equals(u.getProtocol())) {
                throw new IOException("not an http(s) address");
            }
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("User-Agent", "Sensible Studio Forms");
            byte[] body = Json.write(payload).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int status = c.getResponseCode();
            String reply;
            try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
                reply = in == null ? "" : new String(readAtMost(in, 500), StandardCharsets.UTF_8);
            }
            record(resolver, submissionPath, "webhook", status >= 200 && status < 300 ? "done" : "failed", "HTTP " + status + (reply.isEmpty() ? "" : ": " + reply));
        } catch (Exception e) {
            LOG.warn("Webhook {} for {} failed: {}", url, submissionPath, e.getMessage());
            record(resolver, submissionPath, "webhook", "failed", e.getMessage());
        }
    }

    private void email(ResourceResolver resolver, String to, String subject, FormModel model, String submissionPath, Map<String, Object> values) {
        if (!mail.isConfigured()) {
            record(resolver, submissionPath, "email", "skipped", "No mail server is configured (System › Mail)");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(model.title).append(" — a new submission\n\n");
        for (FormModel.Field f : model.fields) {
            Object v = values.get(f.name);
            text.append(f.label).append(": ").append(v == null ? "" : String.valueOf(v)).append('\n');
        }
        text.append("\nSubmission: ").append(submissionPath).append('\n');
        List<String> recipients = new ArrayList<>();
        for (String r : to.split("[,;\\s]+")) {
            if (!r.isEmpty()) {
                recipients.add(r);
            }
        }
        try {
            mail.send(recipients, subject, text.toString());
            record(resolver, submissionPath, "email", "done", String.join(", ", recipients));
        } catch (Exception e) {
            LOG.warn("Email for {} failed: {}", submissionPath, e.getMessage());
            record(resolver, submissionPath, "email", "failed", e.getMessage());
        }
    }

    private void record(ResourceResolver resolver, String submissionPath, String name, String status, String detail) {
        try {
            store.recordAction(resolver, submissionPath, name, status, detail);
        } catch (Exception e) {
            LOG.warn("Cannot record the {} action of {}: {}", name, submissionPath, e.getMessage());
        }
    }

    static byte[] readAtMost(InputStream in, int max) throws IOException {
        byte[] buf = new byte[max];
        int n = 0;
        for (int r; n < max && (r = in.read(buf, n, max - n)) > 0; ) {
            n += r;
        }
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    static String blank(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
