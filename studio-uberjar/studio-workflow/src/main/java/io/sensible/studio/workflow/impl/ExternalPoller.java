package io.sensible.studio.workflow.impl;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Every ten seconds: asks the steps that wait for something outside (a {@code WorkflowExternalProcess})
 * whether it is done. Apart from the minute-by-minute housekeeping, which would make such a step wait a
 * minute for an answer that is there in a second.
 */
@Component(service = Runnable.class, property = {"scheduler.period:Long=10", "scheduler.concurrent:Boolean=false", "scheduler.runOn=SINGLE"})
public class ExternalPoller implements Runnable {

    @Reference
    private WorkflowService service;

    @Override
    public void run() {
        service.pollExternals();
    }
}
