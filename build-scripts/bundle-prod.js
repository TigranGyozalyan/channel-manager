import {mkdirSync, readFileSync, writeFileSync, readdirSync, copyFileSync} from "fs";
import path from "path";

const srcDir = "src/main/ui";
const destDir = "target/classes/ui";
const stylesSrcDir = path.join(srcDir, "styles");
import crypto from "crypto";

const pageStyles = {
    "board.html": ["common.css", "board.css", "boardListPopup.css", "SnackBar.css"],
    "boards.html": ["common.css", "boards.css", "boardListPopup.css"],
    "billing.html": ["common.css", "billing.css"],
    "checkout.html": ["common.css", "billing.css", "checkout.css"],
};

const cssCache = new Map();

function getCss(name) {
    if (!cssCache.has(name)) {
        const content = readFileSync(path.join(stylesSrcDir, name), "utf-8");
        cssCache.set(name, content);
    }
    return cssCache.get(name);
}

const jsCache = new Map();

function getJs(name) {
    if (!jsCache.has(name)) {
        const content = readFileSync(path.join(destDir, name), "utf-8");
        jsCache.set(name, content);
    }
    return jsCache.get(name);
}

function ensureDir(dir) {
    mkdirSync(dir, { recursive: true });
}

function processHtml(filePath, destPath) {
    let html = readFileSync(filePath, "utf-8");
    const styles = pageStyles[path.basename(filePath)];

    const hashes = new Set();

    if (styles) {
        const cssContent = styles.map(getCss).join("\n");
        html = html.includes("<style>")
            ? html.replace("<style>", `<style>\n${cssContent}\n`)
            : html.replace(
                "</head>",
                `<style>\n${cssContent}\n</style>\n</head>`
            );
    }

    html = injectScripts(html, hashes);

    /**
     * CSP headers have been added to mitigate certain security issues. These rules tell the browser what website code
     * is and is not allowed to do, and where resources, scripts, styles, etc. can and cannot be loaded from.
     * And since we allow inline scripts, we add a hash code to protect against third-party inline scripts.
     * The hash code is calculated based on our script bundle and is checked by the browser before execution.*/
    const csp = `
     default-src 'none';  
     script-src 'self' ${[...hashes].join(" ")} https://cdn.jsdelivr.net/npm/marked@18.0.3/lib/marked.umd.js https://cdn.paddle.com;
     img-src 'self' https: data: blob:; 
     style-src 'self' 'unsafe-inline' https://sandbox-cdn.paddle.com https://cdn.paddle.com;
     connect-src 'self' https://cdn.paddle.com https://sandbox-cdn.paddle.com https://cdn.jsdelivr.net/npm/marked@18.0.3/lib/marked.umd.js.map;
     frame-src 'self' https://sandbox-buy.paddle.com https://buy.paddle.com;
     media-src *;
    `.replace(/\n/g, " ");

    html = html.replace(
        "<head>",
        `<head><meta http-equiv="Content-Security-Policy" content="${csp}">`
    );

    writeFileSync(destPath, html);
}

function injectScripts(html, hashes) {
    return html.replace(
        /<script[^>]*src="\/ui\/(.+?)"[^>]*><\/script>/g,
        (_, fileName) => {
            const jsContent = ` window.addEventListener("DOMContentLoaded", () => {${getJs(fileName)}});`.trim();

            const hash = getScriptHash(jsContent);
            hashes.add(hash);

            return `<script>${jsContent}</script>`;
        }
    );
}

function processDir(src, dest) {
    ensureDir(dest);

    const files = readdirSync(src, { withFileTypes: true });

    for (const file of files) {
        const srcPath = path.join(src, file.name);
        const destPath = path.join(dest, file.name);

        if (file.isDirectory()) {
            processDir(srcPath, destPath);
            continue;
        }

        if (srcPath.endsWith(".html")) {
            processHtml(srcPath, destPath);
        } else if (srcPath.endsWith(".css") || srcPath.split(path.sep).includes("img")) {
            copyFileSync(srcPath, destPath);
        }
    }
}

function getScriptHash(content) {
    const hash = crypto
        .createHash("sha256")
        .update(content)
        .digest("base64");

    return `'sha256-${hash}'`;
}

processDir(srcDir, destDir);