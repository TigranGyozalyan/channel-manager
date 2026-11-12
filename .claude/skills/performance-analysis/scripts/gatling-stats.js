#!/usr/bin/env node
/**
 * Summarize a Gatling simulation report's latency/throughput/error-rate stats.
 *
 * Usage:
 *   node gatling-stats.js <path-to-report-dir-or-index.html-or-stats.json>
 *
 * Accepts a report directory (target/gatling/<sim>-<timestamp>/), its index.html, or
 * js/stats.json directly. Prints one line per request group (and the aggregate "All
 * Requests" group) with total/ok/ko counts, mean/min/max response time, the configured
 * percentiles (Gatling's default is p50/p75/p95/p99 - see charting.indicators.percentileN
 * in gatling.conf if this project ever overrides that), and mean requests/sec.
 */
import fs from "fs";
import path from "path";

function resolveStatsJson(inputPath) {
    const resolved = path.resolve(inputPath);
    if (path.basename(resolved) === "stats.json") return resolved;
    const stat = fs.existsSync(resolved) ? fs.statSync(resolved) : null;
    if (stat && stat.isDirectory()) {
        const candidate = path.join(resolved, "js", "stats.json");
        if (fs.existsSync(candidate)) return candidate;
        throw new Error(`No js/stats.json found under ${resolved}`);
    }
    if (resolved.endsWith(".html")) {
        const candidate = path.join(path.dirname(resolved), "js", "stats.json");
        if (fs.existsSync(candidate)) return candidate;
        throw new Error(`No js/stats.json found next to ${resolved}`);
    }
    throw new Error(`Don't know how to resolve stats.json from ${resolved}`);
}

function fmtRow(name, stats, indent) {
    const n = stats.numberOfRequests;
    const rt = (k) => stats[k].total;
    const prefix = "  ".repeat(indent) + name;
    return (
        `${prefix.padEnd(34)} total=${String(n.total).padEnd(6)} ok=${String(n.ok).padEnd(6)} ko=${String(n.ko).padEnd(6)} ` +
        `mean=${String(rt("meanResponseTime")).padStart(5)}ms min=${String(rt("minResponseTime")).padStart(4)}ms max=${String(rt("maxResponseTime")).padStart(5)}ms ` +
        `p50=${String(rt("percentiles1")).padStart(4)}ms p75=${String(rt("percentiles2")).padStart(4)}ms p95=${String(rt("percentiles3")).padStart(5)}ms p99=${String(rt("percentiles4")).padStart(5)}ms ` +
        `rps=${stats.meanNumberOfRequestsPerSecond.total.toFixed(2)}`
    );
}

function walk(node, indent, out) {
    if (node.stats) out.push(fmtRow(node.name || "?", node.stats, indent));
    for (const child of Object.values(node.contents || {})) walk(child, indent + 1, out);
}

function main() {
    const arg = process.argv[2];
    if (!arg) {
        console.error("Usage: node gatling-stats.js <path-to-report-dir-or-index.html-or-stats.json>");
        process.exit(1);
    }
    const statsPath = resolveStatsJson(arg);
    const data = JSON.parse(fs.readFileSync(statsPath, "utf8"));
    const out = [];
    walk(data, 0, out);
    out.forEach((line) => console.log(line));
}

main();
