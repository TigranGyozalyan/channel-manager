#!/usr/bin/env node
/**
 * Rank and categorize the leaf (innermost) frame of every jdk.ExecutionSample in a JFR file.
 *
 * Usage:
 *   node jfr-leaf-frames.js <jfr-file> [--top N] [--exclude REGEX ...] [--categorize]
 *
 * Requires the `jfr` CLI (ships with the JDK) on PATH. Runs
 * `jfr print --events jdk.ExecutionSample <file>` and aggregates the first (innermost)
 * frame of each sample's stack trace - i.e. what the CPU was actually executing at the
 * moment it was sampled. This is CPU/execution-time profiling, not allocation or lock
 * profiling; use a different --events value with the `jfr` CLI directly for those.
 *
 * Without --categorize: prints the top N leaf frames by sample count (a ranked hotspot list).
 * With --categorize: additionally buckets every non-excluded frame into a small set of
 * categories (JSON, database, network I/O, idle/blocking, framework dispatch, collections,
 * string ops, application code grouped by package, other JVM internals) so you can see
 * where time went at a glance before drilling into individual frames. Application code
 * (anything not under java./jdk./sun.) is grouped by its own package instead of a fixed
 * list, so a hot spot in this project's own code always surfaces as its own bucket.
 *
 * Use --exclude to drop a frame you've already root-caused (e.g. a known, expected cost
 * like bcrypt password verification) so it doesn't drown out what you're investigating now.
 */
import { execFileSync } from "child_process";

const LEAF_RE = /^\s*([^\s(]+)\(/;

const CATEGORY_RULES = [
    [/^(tools\.jackson|com\.fasterxml\.jackson)/, "json (jackson)"],
    [/^org\.postgresql/, "database (jdbc)"],
    [
        /^(jdk\.internal\.misc\.Unsafe\.(un)?park|java\.lang\.Object\.wait0|sun\.nio\.ch\.KQueue\.(poll|register)|sun\.nio\.ch\.(EPoll|Net)\.poll|java\.util\.concurrent\.ThreadPoolExecutor\.runWorker|org\.apache\.tomcat\.util\.net\.\w+\$Poller|jdk\.jfr\.internal\.PlatformRecorder)/,
        "idle/blocking (thread park, poller)",
    ],
    [/^sun\.nio\.ch\.(SocketDispatcher|IOUtil|NioSocketImpl|SelectionKeyImpl)/, "network io"],
    [/^(org\.springframework|jakarta\.servlet|org\.apache\.catalina|org\.apache\.tomcat)/, "framework dispatch (spring/servlet/tomcat)"],
    [/^java\.util\.(ArrayList|HashMap|Arrays|Collections|Objects|concurrent\.ConcurrentHashMap)/, "collections/array internals"],
    [/^java\.lang\.String/, "string ops"],
];

function leafFrames(jfrFile) {
    const stdout = execFileSync("jfr", ["print", "--events", "jdk.ExecutionSample", jfrFile], {
        encoding: "utf8",
        maxBuffer: 1024 * 1024 * 512,
    });
    const frames = [];
    let takeNext = false;
    for (const line of stdout.split("\n")) {
        if (takeNext) {
            const m = LEAF_RE.exec(line);
            if (m) frames.push(m[1]);
            takeNext = false;
            continue;
        }
        if (line.includes("stackTrace = [")) takeNext = true;
    }
    return frames;
}

function categorize(frame) {
    for (const [pattern, label] of CATEGORY_RULES) {
        if (pattern.test(frame)) return label;
    }
    if (!/^(java|jdk|sun)\./.test(frame)) {
        const parts = frame.split(".");
        const pkg = parts.length > 2 ? parts.slice(0, -2).join(".") : frame;
        return `app: ${pkg}`;
    }
    return "other/jvm internals";
}

function parseArgs(argv) {
    const args = { top: 25, exclude: [], categorize: false, jfrFile: null };
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a === "--top") args.top = parseInt(argv[++i], 10);
        else if (a === "--exclude") args.exclude.push(argv[++i]);
        else if (a === "--categorize") args.categorize = true;
        else if (!args.jfrFile) args.jfrFile = a;
    }
    return args;
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (!args.jfrFile) {
        console.error("Usage: node jfr-leaf-frames.js <jfr-file> [--top N] [--exclude REGEX ...] [--categorize]");
        process.exit(1);
    }

    const excludes = args.exclude.map((e) => new RegExp(e));
    const frames = leafFrames(args.jfrFile).filter((f) => !excludes.some((e) => e.test(f)));
    const total = frames.length;
    if (total === 0) {
        console.log("No matching samples found.");
        return;
    }

    console.log(`Total samples: ${total}\n`);

    const counts = new Map();
    for (const f of frames) counts.set(f, (counts.get(f) || 0) + 1);
    const ranked = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, args.top);

    console.log(`Top ${args.top} leaf frames:`);
    for (const [frame, count] of ranked) {
        console.log(`  ${String(count).padStart(5)}  ${((100 * count) / total).toFixed(1).padStart(5)}%  ${frame}`);
    }

    if (args.categorize) {
        const catCounts = new Map();
        for (const f of frames) {
            const cat = categorize(f);
            catCounts.set(cat, (catCounts.get(cat) || 0) + 1);
        }
        const rankedCats = [...catCounts.entries()].sort((a, b) => b[1] - a[1]);
        console.log("\nCategorized breakdown:");
        for (const [cat, count] of rankedCats) {
            console.log(`  ${String(count).padStart(5)}  ${((100 * count) / total).toFixed(1).padStart(5)}%  ${cat}`);
        }
    }
}

main();
