# Memory index

- [events-schema-rework-perf-investigation](project_events_schema_rework.md) — events_alt drops valprev/refprev; events_blobs content-addressed body blobs leaked storage + regressed @1M+; mutable-slot fix (2026-09-25, uncommitted on 190aaca) resolves both
- [jfr-leaf-frame-categorization-gaps](feedback_jfr_leaf_categorization_gaps.md) — categorized script buckets by leaf frame only; drill into full stacks by function name (e.g. CardDao.patch) to see framework/pool overhead the categorizer alone would miss
- [rtk-bash-hook-use-proxy-for-exact-output](reference_rtk_bash_hook.md) — this user's global rtk Bash hook can silently filter output; use `rtk proxy <cmd>` for exact/complete results in data-sensitive commands
- [verify-provenance-before-trusting-run-label](feedback_verify_provenance_before_trusting_run_label.md) — a Gatling+JFR pair's name/description isn't proof of what code it reflects; verify given code facts before analyzing
- [wallclock-burst-cluster-per-thread](feedback_wallclock_burst_cluster_per_thread.md) — cluster profiler.WallClockSleeping bursts per eventThread, not pooled across concurrent workers, or you get one meaningless mega-cluster
