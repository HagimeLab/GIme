/**
 * 用 GitHub Git Data API 一次性提交若干文件到 main。
 * 走 API 而不是 git push：本机 git 走代理连不上 github.com，但 api.github.com 可达。
 *
 * 用法：node tools/push-via-api.mjs <token|-> <owner/repo> <branch> <commitMessage> <file1> <file2> ...
 *   令牌写 "-" 时从环境变量 GITHUB_TOKEN 取（推荐：令牌不进命令行参数，也就不进 shell 历史）。
 * 删除文件：在参数前加 "!"，例如 !release/GIme-v1.0.3.apk（tree 里该路径写 null）。
 */
import fs from "node:fs";
import path from "node:path";

let [token, repo, branch, message, ...files] = process.argv.slice(2);
if (token === "-") token = (process.env.GITHUB_TOKEN || "").trim();
if (!token || !repo || !branch || !message || !files.length) {
  console.error("用法: node push-via-api.mjs <token|-> <owner/repo> <branch> <message> <files...>");
  process.exit(2);
}

const API = "https://api.github.com";
const headers = {
  "User-Agent": "GIme-push",
  "Authorization": `token ${token}`,
  "Accept": "application/vnd.github+json",
  "Content-Type": "application/json",
};

async function apiOnce(method, url, body) {
  const res = await fetch(API + url, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 响应 */ }
  if (!res.ok) {
    throw new Error(`${method} ${url} -> ${res.status} ${json ? JSON.stringify(json).slice(0, 300) : text.slice(0, 200)}`);
  }
  return json;
}

/* 这条网络到 api.github.com 时不时握手就超时（undici 默认 10s），
   单发一次失败不代表令牌或接口有问题，重试 4 次再判死。 */
async function api(method, url, body) {
  let last;
  for (let i = 0; i < 4; i++) {
    try { return await apiOnce(method, url, body); }
    catch (e) {
      last = e;
      const transient = /fetch failed|ConnectTimeout|UND_ERR|socket hang up|ECONNRESET|EAI_AGAIN/i.test(String(e.message));
      if (!transient || i === 3) throw e;
      console.log(`  ~ 重试 ${i + 1}/4（${String(e.cause?.code || e.message).slice(0, 60)}）`);
      await new Promise((r) => setTimeout(r, 2000 + i * 1500));
    }
  }
  throw last;
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
  /* 只按 apk/png/jpg/zip/jar 认二进制，.aar / .so 会被当成 utf8 发出去，GitHub 收到的是
     替换过的乱码（上一版就这么把 JNI 库传坏了）。扩展名不够时再看内容里有没有 NUL。 */
  const isBinary = /\.(apk|aab|aar|so|png|jpg|jpeg|gif|webp|zip|jar|keystore|jks|woff2|ttf|otf|pdf)$/i
      .test(rel) || content.includes(0);
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
