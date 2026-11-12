---
name: wallclock-burst-cluster-per-thread-not-globally
description: When burst-timing profiler.WallClockSleeping (or any off-CPU wait) across concurrent worker threads, cluster per-thread - pooling all threads' timestamps together fuses unrelated concurrent requests into one meaningless mega-cluster
metadata:
  type: feedback
---

The bundled `jfr-burst-timing.js` only reads `jdk.ExecutionSample` and is fine as-is (each
sample already belongs to one thread's stack at that instant, and its typical use case -
BCrypt, a single auth cost - doesn't have this issue at the volumes tested so far). But for
an ad-hoc off-CPU check against `profiler.WallClockSleeping` under concurrent load (multiple
`http-8080-N` worker threads all hitting the DB during overlapping time windows), clustering
by wall-clock time across *all* threads pooled together produces a misleading result: with
8 threads each issuing DB calls every ~100ms, gaps between different threads' events are
almost always under the default 500ms threshold, so nearly the whole steady-state load window
collapses into one giant "cluster" (e.g. 307 events summing to 20+ seconds) that says nothing
useful about per-request burst size.

**Why**: caught while running the WallClockSleeping burst check for the
`feature/events_blobs` huge-body-patch run in
[[events-schema-rework-perf-investigation]] - pooled clustering gave a nonsensical
20.8-second "burst," while clustering the same data per-thread gave a sane, comparable-to-baseline
result (239 bursts total, max single burst 1740ms, evenly spread ~26-34 bursts/thread).

**How to apply**: always group matching wall-clock-wait events by `eventThread` first, cluster
within each thread's own timeline, then sum/report across threads. Total wait time and burst
*count* are still meaningful pooled; burst *duration*/max-burst is only meaningful per-thread.
