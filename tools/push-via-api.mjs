/**
 * 用 GitHub Git Data API 一次性提交若干文件到 main。
 * 走 API 而不是 git push：本机 git 走代理连不上 github.com，但 api.github.com 可达。
 *
 * 用法：node tools/push-via-api.mjs <token> <owner/repo> <branch> <commitMessage> <file1> <file2> ...
 * 删除文件：在参数前加 "!"，例如 !release/GIme-v1.0.3.apk（tree 里该路径写 null）。
 */
import fs from "node:fs";
import path from "node:path";

const [token, repo, branch, message, ...files] = process.argv.slice(2);
if (!token || !repo || !branch || !message || !files.length) {
  console.error("用法: node push-via-api.mjs <token> <owner/repo> <branch> <message> <files...>");
  process.exit(2);
}

const API = "https://api.github.com";
const headers = {
  "User-Agent": "GIme-push",
  "Authorization": `token ${token}`,
  "Accept": "application/vnd.github+json",
  "Content-Type": "application/json",
};

async function api(method, url, body) {
  const res = await fetch(API + url, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 响应 */ }
  if (!res.ok) {
    throw new Error(`${method} ${url} -> ${res.status} ${json ? JSON.stringify(json).slice(0, 300) : text.slice(0, 200)}`);
  }
  return json;
}

const root = path.resolve(process.cwd());

// 1. 当前分支头
const ref = await api("GET", `/repos/${repo}/git/ref/heads/${branch}`);
const baseSha = ref.object.sha;
const baseCommit = await api("GET", `/repos/${repo}/git/commits/${baseSha}`);
console.log(`base: ${baseSha.slice(0, 8)}`);

// 2. 逐个文件建 blob（"!path" 表示删除该路径）
const entries = [];
for (const raw of files) {
  const remove = raw.startsWith("!");
  const rel = remove ? raw.slice(1) : raw;
  const abs = path.isAbsolute(rel) ? rel : path.join(root, rel);
  if (remove) {
    entries.push({ path: rel.replace(/\\/g, "/"), mode: "100644", type: "blob", sha: null });
    console.log(`  del  ${rel}`);
    continue;
  }
  if (!fs.existsSync(abs)) { console.error(`跳过（不存在）: ${rel}`); continue; }
  const content = fs.readFileSync(abs);
  const isBinary = /\.(apk|png|jpg|zip|jar)$/i.test(rel);
  const blob = await api("POST", `/repos/${repo}/git/blobs`, isBinary
    ? { content: content.toString("base64"), encoding: "base64" }
    : { content: content.toString("utf8"), encoding: "utf-8" });
  entries.push({
    path: rel.replace(/\\/g, "/"),
    mode: "100644",
    type: "blob",
    sha: blob.sha,
  });
  console.log(`  blob ${rel} (${content.length}B)`);
}

// 3. 新 tree
const tree = await api("POST", `/repos/${repo}/git/trees`, { base_tree: baseCommit.tree.sha, tree: entries });
console.log(`tree: ${tree.sha.slice(0, 8)}`);

// 4. 新 commit
const commit = await api("POST", `/repos/${repo}/git/commits`, {
  message,
  tree: tree.sha,
  parents: [baseSha],
});
console.log(`commit: ${commit.sha.slice(0, 8)}`);

// 5. 移动分支
await api("PATCH", `/repos/${repo}/git/refs/heads/${branch}`, { sha: commit.sha, force: false });
console.log(`已更新 ${branch} -> ${commit.sha.slice(0, 8)}`);
