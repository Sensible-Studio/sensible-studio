# Flows: a customer's process steps, and every kind of step

A bundle of process steps and participant choosers written the way an AEM project writes them — against
`com.adobe.granite.workflow` (`Steps.java`) and against the older `com.day.cq.workflow` (`OldSteps.java`,
`OldStartServlet.java`) — and a script that builds flow models with every kind of step on a running author
and walks each branch.

It is not part of the product and not in `bin/studio`'s lists. Use it after changing the workflow engine or
the workflow API:

    (cd studio-uberjar && mvn -o -q -pl studio-workflow -am package)       # the API jar this builds against
    (cd qa/workflow-steps && mvn -o -q package)
    cp qa/workflow-steps/target/zz-wfqa-1.0.0.jar instances/author/install/
    python3 qa/workflow-steps/run.py
    rm instances/author/install/zz-wfqa-1.0.0.jar

The script needs the WKND site and `admin:admin` on `http://localhost:4502` (`AUTHOR=` for another). All it
makes is named `zz-…` / `qa-…` and removed when it ends, also when it is run again after a broken run. It
takes about four minutes; the last check waits for a timeout.

What a step may do here, and what is tested, is described in the project document
`claude/workflows-custom-steps.md`.
