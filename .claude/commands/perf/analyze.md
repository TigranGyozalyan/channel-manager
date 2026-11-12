---
description: Full performance analysis of a Gatling report and/or JFR recording via the performance-analyzer agent
argument-hint: <jfr-file-and/or-gatling-report-path>
---

Dispatch the **`performance-analyzer`** agent (via the Agent tool) to produce a full
performance report for the file(s) given below: `$ARGUMENTS`

Pass along both paths if given (a Gatling report and a JFR file are often shared
together as a pair). Once the agent reports back, relay its findings to the user.
