const fs = require("fs");
const path = require("path");

const root = process.cwd();

const scanTargets = [
  path.join(root, "src"),
  path.resolve(root, "..", "app", "src", "main"),
];

const sourceExtensions = new Set([".ts", ".tsx", ".kt", ".xml"]);
const forbiddenTokens = [
  "�",
  "锟",
  "鍙栨秷",
  "鎼滅储",
  "鐩爣",
  "闇€瑕",
  "妯″紡",
  "鍔╂墜",
  "璇风◢",
  "锛",
  "馃",
  "鈥?",
];

function walk(fileOrDir, output) {
  if (!fs.existsSync(fileOrDir)) return;
  const stat = fs.statSync(fileOrDir);
  if (stat.isFile()) {
    output.push(fileOrDir);
    return;
  }

  for (const entry of fs.readdirSync(fileOrDir, { withFileTypes: true })) {
    if (entry.name === "node_modules" || entry.name === ".next" || entry.name === "build") {
      continue;
    }
    walk(path.join(fileOrDir, entry.name), output);
  }
}

const files = [];
for (const target of scanTargets) {
  walk(target, files);
}

const violations = [];
for (const file of files) {
  if (!sourceExtensions.has(path.extname(file))) continue;
  const content = fs.readFileSync(file, "utf8");
  for (const token of forbiddenTokens) {
    if (content.includes(token)) {
      violations.push({ file, token });
      break;
    }
  }
}

if (violations.length > 0) {
  console.error("[lint:encoding] Found mojibake/invalid tokens:");
  for (const item of violations) {
    console.error(`- ${path.relative(root, item.file)} -> token "${item.token}"`);
  }
  process.exit(1);
}

console.log("[lint:encoding] OK");
