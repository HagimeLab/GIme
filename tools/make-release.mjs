/**
 * 建 GitHub Release 并上传两个资产（安装包 + 源码包）。
 *
 * 用法：node tools/make-release.mjs <tag> <版本名> <正文文件> <apk路径> <源码包路径>
 *   例：node tools/make-release.mjs v1.0.3 1.0.3 docs/v1.0.3-release-body.md \
 *         release/GIme-v1.0.3-de132fb4.apk dist/GIme-v1.0.3-source.zip
 *
 * 正文的每一行都加 "- " 前缀，末尾补一句「详细：docs/…」——
 * 这段正文就是手机「检查更新」弹窗里的字，所以必须短（tools/verify-release.ps1 卡 50-100 字）。
 * 凭据自己调 git credential fill 取，不从命令行传、不打印。
 * 已经存在同名 Release 时不重复建，直接往那个 Release 上补传资产（资产同名会先删再传）。
 */
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const [tag, ver, bodyFile, apkPath, srcPath] = process.argv.slice(2);
if (!tag || !ver || !bodyFile || !apkPath || !srcPath) {
  console.error("用法: node make-release.mjs <tag> <版本名> <正文文件> <apk> <源码包>");
  process.exit(2);
}
const REPO = execFileSync("git", ["remote", "get-url", "origin"], { encoding: "utf8" })
  .replace(/\.git\s*$/, "").split("github.com/")[1];
const API = "https://api.github.com";
const credOut = execFileSync("git", ["credential", "fill"], {
  input: "protocol=https\nhost=github.com\n\n", encoding: "utf8", timeout: 30000,
});
const token = /^password=(.*)$/m.exec(credOut)[1].trim();

const HEADERS = { "User-Agent": "GIme-make-release", Authorization: `token ${token}`,
  Accept: "application/vnd.github+json", "Content-Type": "application/json" };

async function api(method, url, body) {
  const res = await fetch(url.startsWith("http") ? url : API + url, {
    method, headers: HEADERS, body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON */ }
  if (!res.ok) throw new Error(`${method} ${url} -> ${res.status} ${(json && JSON.stringify(json).slice(0, 300)) || text.slice(0, 200)}`);
  return json;
}

const bullets = fs.readFileSync(bodyFile, "utf8").split(/\r?\n/).filter((l) => l.trim())
  .map((l) => "- " + l.trim());
const body = bullets.join("\n") + `\n\n详细：docs/${tag}-发布说明.md\n`;
console.log("正文：");
console.log(body);

let rel;
try {
  rel = await api("GET", `${API}/repos/${REPO}/releases/tags/${tag}`);
  console.log(`Release ${tag} 已存在（id ${rel.id}），只补传资产`);
  rel = await api("PATCH", `${API}/repos/${REPO}/releases/${rel.id}`, { body });
} catch {
  rel = await api("POST", `${API}/repos/${REPO}/releases`, {
    tag_name: tag, name: `GIme ${tag}`, body, draft: false, prerelease: false,
  });
  console.log(`已建 Release ${tag}（id ${rel.id}）`);
}

const uploadBase = rel.upload_url.replace(/\{.*\}$/, "");
for (const p of [apkPath, srcPath]) {
  const abs = path.resolve(p);
  const name = path.basename(abs);
  const bytes = fs.readFileSync(abs);
  const same = (rel.assets || []).filter((a) => a.name === name);
  for (const a of same) {
    if (a.size === bytes.length) { console.log(`  资产已存在且字节相同，跳过：${name}`); continue; }
    await api("DELETE", `${API}/repos/${REPO}/releases/assets/${a.id}`);
    console.log(`  旧资产字节不同，已删：${name}`);
  }
  const up = await fetch(`${uploadBase}?name=${encodeURIComponent(name)}`, {
    method: "POST",
    headers: { "User-Agent": "GIme-make-release", Authorization: `token ${token}`,
      "Content-Type": "application/octet-stream", Accept: "application/vnd.github+json" },
    body: bytes,
  });
  const j = await up.json().catch(() => null);
  if (!up.ok) throw new Error(`上传 ${name} -> ${up.status} ${JSON.stringify(j).slice(0, 300)}`);
  console.log(`  已上传 ${name}  ${bytes.length}B  下载数配额=${j.download_count}`);
}

const after = await api("GET", `${API}/repos/${REPO}/releases/tags/${tag}`);
console.log(`\n${after.html_url}`);
console.log("资产：" + after.assets.map((a) => `${a.name}(${a.size}B)`).join(", "));
