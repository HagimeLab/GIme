/**
 * 重算并回写 `develop/依赖清单.md` 里三份产物的指纹（§1 一览的字节数 + §2 的字节数/MD5/SHA-256）。
 *
 * 用法：node tools/openui-fingerprints.mjs [--check]
 *   --check  只比对不写盘：不一致时以非零退出码结束（check.mjs 的 [19b] 是同一件事的断言版）
 *
 * 为什么要有这个脚本：改完 bundle / 渲染层之后，要手工把三个文件的「字节数 + MD5 + SHA-256」
 * 抄进文档，六处数字手抄极容易错位，错了只会在 check.mjs 里报一行红字。这里一次算完写回。
 */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

const root = path.resolve(import.meta.dirname, "..");
const docPath = path.join(root, "develop", "依赖清单.md");
const files = [
  "app/src/main/assets/ui/openui-core.js",
  "app/src/main/assets/ui/openui-ui.js",
  "app/src/main/assets/ui/openui-signatures.js",
];

const onlyCheck = process.argv.includes("--check");
const digest = (buf, alg) => crypto.createHash(alg).update(buf).digest("hex");

const info = new Map();
for (const rel of files) {
  const buf = fs.readFileSync(path.join(root, rel));
  info.set(rel, { size: String(buf.length), md5: digest(buf, "md5"), sha256: digest(buf, "sha256") });
}

const lines = fs.readFileSync(docPath, "utf8").split(/\r?\n/);
let changed = 0;
let drift = 0;

for (let i = 0; i < lines.length; i++) {
  const line = lines[i];
  const rel = files.find((f) => line.includes(f));
  if (!rel) continue;
  const want = info.get(rel);

  /* §2：| `path` | size | `md5` | `sha256` | */
  const full = /^(\| `[^`]+` \| )(\d+)( \| `)([0-9a-f]{32})(` \| `)([0-9a-f]{64})(` \|)\s*$/.exec(line);
  if (full) {
    const next = full[1] + want.size + full[3] + want.md5 + full[5] + want.sha256 + full[7];
    if (next !== line) { drift++; lines[i] = next; changed++; }
    continue;
  }
  /* §1 一览：行尾是字节数 */
  const sizeOnly = /^(.*\| )(\d+)( \|)\s*$/.exec(line);
  if (sizeOnly) {
    const next = sizeOnly[1] + want.size + sizeOnly[3];
    if (next !== line) { drift++; lines[i] = next; changed++; }
  }
}

for (const [rel, v] of info) console.log(`${path.basename(rel)}  ${v.size}  ${v.md5}  ${v.sha256}`);
if (!changed) { console.log("依赖清单里的指纹已是最新"); process.exit(0); }
if (onlyCheck) { console.log(`依赖清单有 ${changed} 行指纹过期`); process.exit(1); }
fs.writeFileSync(docPath, lines.join("\n"), "utf8");
console.log(`已回写 ${changed} 行`);
