#!/usr/bin/env node
/**
 * Find clusters ("bursts") of jdk.ExecutionSample events whose block matches a pattern.
 *
 * Usage:
 *   node jfr-burst-timing.js <jfr-file> <frame-regex> [--gap-ms N]
 *
 * Useful for answering "is this expensive call happening once per session/login, or once
 * per request?" - e.g. confirming that a slow authentication step (bcrypt) is amortized
 * across many requests instead of repeated on every one.
 *
 * Requires the `jfr` CLI (ships with the JDK) on PATH. Matches <frame-regex> against every
 * line of each jdk.ExecutionSample event (stack frames, thread name, state - not leaf-only),
 * groups consecutive matching samples into clusters where consecutive samples are within
 * --gap-ms of each other (default 500ms), and prints each cluster's sample count, time
 * range, and span.
 *
 * A handful of short, widely-spaced clusters (roughly one per new session/login) usually
 * means the cost is being cached correctly. Many clusters that line up with the request
 * count instead means it's very likely NOT being cached - being paid on every request.
 */
import { execFileSync } from "child_process";

const TIME_RE = /startTime = (\d\d):(\d\d):(\d\d)\.(\d+) \(\d{4}-\d\d-\d\d\)/;

// Parsed as milliseconds-since-midnight (local wall clock, exactly as jfr print shows it) -
// avoids any UTC/local conversion that a Date object would silently apply on display.
function parseTimestamp(m) {
    const [, hh, mm, ss, frac] = m;
    const millis = Math.round(Number(`0.${frac}`) * 1000);
    return { ms: ((+hh * 60 + +mm) * 60 + +ss) * 1000 + millis, raw: `${hh}:${mm}:${ss}.${frac}` };
}

function matchingSampleTimes(jfrFile, frameRegex) {
    const stdout = execFileSync("jfr", ["print", "--events", "jdk.ExecutionSample", jfrFile], {
        encoding: "utf8",
        maxBuffer: 1024 * 1024 * 512,
    });
    const times = [];
    let curTime = null;
    let matched = false;
    for (const line of stdout.split("\n")) {
        if (line.includes("jdk.ExecutionSample {")) {
            curTime = null;
            matched = false;
            continue;
        }
        const m = TIME_RE.exec(line);
        if (m) {
            curTime = parseTimestamp(m);
            continue;
        }
        if (frameRegex.test(line)) {
            matched = true;
            continue;
        }
        if (line.trim() === "}" && matched && curTime !== null) {
            times.push(curTime);
        }
    }
    return times.sort((a, b) => a.ms - b.ms);
}

function cluster(times, gapMs) {
    if (times.length === 0) return [];
    const clusters = [[times[0]]];
    for (let i = 1; i < times.length; i++) {
        const t = times[i];
        const last = clusters[clusters.length - 1];
        if (t.ms - last[last.length - 1].ms <= gapMs) last.push(t);
        else clusters.push([t]);
    }
    return clusters;
}

function parseArgs(argv) {
    const args = { jfrFile: null, frameRegex: null, gapMs: 500 };
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a === "--gap-ms") args.gapMs = parseInt(argv[++i], 10);
        else if (!args.jfrFile) args.jfrFile = a;
        else if (!args.frameRegex) args.frameRegex = a;
    }
    return args;
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (!args.jfrFile || !args.frameRegex) {
        console.error("Usage: node jfr-burst-timing.js <jfr-file> <frame-regex> [--gap-ms N]");
        process.exit(1);
    }

    const times = matchingSampleTimes(args.jfrFile, new RegExp(args.frameRegex));
    if (times.length === 0) {
        console.log(`No samples matched /${args.frameRegex}/`);
        return;
    }

    const clusters = cluster(times, args.gapMs);
    console.log(`${times.length} matching samples across ${clusters.length} cluster(s) (gap threshold ${args.gapMs}ms)\n`);
    clusters.forEach((c, i) => {
        const durMs = c[c.length - 1].ms - c[0].ms;
        console.log(
            `  cluster ${String(i + 1).padStart(3)}: ${String(c.length).padStart(3)} samples  ${c[0].raw} -> ${c[c.length - 1].raw}  (~${durMs}ms span)`
        );
    });

    if (clusters.length <= 10) {
        console.log("\nFew, short, separated clusters -> looks amortized (paid once per session/login), not per-request.");
    } else {
        console.log(
            `\n${clusters.length} clusters -> check whether this lines up with the request count; may indicate the cost is NOT being cached.`
        );
    }
}

main();
