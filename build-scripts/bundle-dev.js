import {mkdirSync, readFileSync, writeFileSync, readdirSync, copyFileSync} from "fs";
import path from "path";

const srcDir = "src/main/ui";
const destDir = "target/classes/ui";
const stylesSrcDir = path.join(srcDir, "styles");

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

function ensureDir(dir) {
    mkdirSync(dir, { recursive: true });
}

function injectStyles(filePath, destPath) {
    let html = readFileSync(filePath, "utf-8");
    const styles = pageStyles[path.basename(filePath)];

    if (styles) {
        const cssContent = styles.map(getCss).join("\n");

        html = html.includes("<style>")
            ? html.replace("<style>", `<style>\n${cssContent}\n`)
            : html.replace(
                "</head>",
                `<style>\n${cssContent}\n</style>\n</head>`
            );
    }

    writeFileSync(destPath, html);
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
            injectStyles(srcPath, destPath);
        } else if (srcPath.endsWith(".css") || srcPath.split(path.sep).includes("img")) {
            copyFileSync(srcPath, destPath);
        }
    }
}

processDir(srcDir, destDir);