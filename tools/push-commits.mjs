/**
 * 按提交逐笔重建远端分支：把本地 <base>..<branch-head> 的每一笔提交原样搬到远端。
 *
 * 为什么需要它：这台机器的 git 写路径（github.com 的 git-receive-pack）被网络掐断，
 * push 直接 Connection reset，只能走 api.github.com。而 tools/push-via-api.mjs 是
 * "把若干文件压成一笔提交"，搬不动一段历史。
 *
 * 用法：node tools/push-commits.mjs <owner/repo> <branch> <baseSha>
 *   baseSha = 远端当前头（脚本会先核对，不一致就停手，绝不覆盖别人的提交）。
 *
 * 凭据不落命令行：脚本自己调 git credential fill 取，全程不打印。
 *
 * 每笔提交：diff-tree 取出相对父提交的变更 → 建 blob → 以远端父 tree 为 base_tree 建新 tree
 *   → 建 commit（作者/提交者/日期/正文逐字照抄本地）→ 最后一笔才 PATCH ref。
 * 推完做双向核对：仅本地 / 仅远端 / 内容不同 三个数，任何一个非零都判失败。
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const [repo, branch, base] = process.argv.slice(2);
if (!repo || !branch || !base) {
  console.error("用法: node push-commits.mjs <owner/repo> <branch> <baseSha>");
  process.exit(2);
}
const ROOT = path.resolve(process.cwd());

function git(args, encoding = "utf8") {
  return execFileSync("git", args, { cwd: ROOT, maxBuffer: 64 << 20, encoding });
}
function gitZ(args) {
  return git(args, "buffer").toString("utf8").split("\0").filter((s) => s !== "");
}

/* ---- 凭据 ---- */
function token() {
  const out = execFileSync("git", ["credential", "fill"], {
    input: "protocol=https\nhost=github.com\n\n", cwd: ROOT, encoding: "utf8", timeout: 30000,
  });
  const m = /^password=(.*)$/m.exec(out);
  if (!m) throw new Error("git credential fill 没给出 password");
  return m[1].trim();
}
const AUTH = token();
const API = "https://api.github.com";
const HEADERS = {
  "User-Agent": "GIme-push-commits",
  Authorization: `token ${AUTH}`,
  Accept: "application/vnd.github+json",
  "Content-Type": "application/json",
  "X-GitHub-Api-Version": "2022-11-28",
};
async function api(method, url, body) {
  const res = await fetch(API + url, {
    method, headers: HEADERS, body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 响应 */ }
  if (!res.ok) {
    throw new Error(`${method} ${url} -> ${res.status} ${json ? JSON.stringify(json).slice(0, 300) : text.slice(0, 200)}`);
  }
  return json;
}

/* ---- 远端必须先停在 base 上 ---- */
const remoteRef = await api("GET", `/repos/${repo}/git/ref/heads/${branch}`);
if (remoteRef.object.sha !== base) {
  console.error(`远端 ${branch} 已经在 ${remoteRef.object.sha.slice(0, 8)}，不是本地记录的 ${base.slice(0, 8)}。` +
    ` 先 fetch 对齐再推，别在这里覆盖别人的提交。`);
  process.exit(3);
}
console.log(`远端 ${branch} = ${base.slice(0, 8)}，与本地记录一致`);

/* ---- 逐笔重建 ---- */
/* rev-list 用换行分隔，不是 NUL —— 拿 gitZ 切会把整串 SHA 当成一笔提交（第一版就在这翻车） */
const commits = git(["rev-list", "--reverse", `${base}..HEAD`]).split("\n").filter(Boolean);
let parentSha = base;
if (!commits.length) console.log("没有要推的提交，直接做核对。");
else console.log(`要重建 ${commits.length} 笔提交`);

const blobCache = new Map();   // "<sha1>:<path>" -> blob sha（同一文件在多笔提交里不重复上传）
async function blobSha(sha1, rel) {
  const key = `${sha1}:${rel}`;
  if (blobCache.has(key)) return blobCache.get(key);
  const content = git(["cat-file", "blob", `${sha1}:${rel}`], "buffer");
  const isBin = content.includes(0);
  const b = await api("POST", `/repos/${repo}/git/blobs`, isBin
    ? { content: content.toString("base64"), encoding: "base64" }
    : { content: content.toString("utf8"), encoding: "utf-8" });
  blobCache.set(key, b.sha);
  return b.sha;
}

for (const sha of commits) {
  const meta = git(["show", "-s", "--format=%B%x00%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI", sha]).slice(0, -1);
  const [message, an, ae, aI, cn, ce, cI] = meta.split("\0");
  const entries = [];
  /* --raw -z：字段是 :oldmode newmode oldsha newsha status:\0path\0 —— 按 NUL 切最稳，
     非 ASCII 路径不会被 git 加引号转义（那是上一轮建出错文件名的错因） */
  const raw = git(["diff-tree", "--raw", "-z", "--no-commit-id", "-r", sha], "buffer").toString("utf8");
  const toks = raw.split("\0").filter((s) => s !== "");
  for (let i = 0; i < toks.length; i++) {
    /* 描述行形如 ":100644 100644 <旧sha> <新sha> M"；改名/复制会多带一个路径 */
    const f = toks[i].slice(1).split(/\s+/);
    const [oldMode, newMode, status] = [f[0], f[1], f[4]];
    const p1 = toks[++i];
    const target = /^R|^C/.test(status) ? toks[++i] : p1;
    if (status.startsWith("D")) {
      entries.push({ path: p1, mode: "100644", type: "blob", sha: null });
    } else {
      const mode = newMode === "100755" ? "100755" : "100644";
      entries.push({ path: target, mode, type: "blob", sha: await blobSha(sha, target) });
      if (oldMode === "100755" && mode === "100644") console.log(`  注意：${target} 丢了可执行位`);
    }
  }
  const baseTree = (await api("GET", `/repos/${repo}/git/commits/${parentSha}`)).tree.sha;
  const tree = entries.length
    ? await api("POST", `/repos/${repo}/git/trees`, { base_tree: baseTree, tree: entries })
    : { sha: baseTree };
  const made = await api("POST", `/repos/${repo}/git/commits`, {
    message, tree: tree.sha, parents: [parentSha],
    author: { name: an, email: ae, date: aI },
    committer: { name: cn, email: ce, date: cI },
  });
  parentSha = made.sha;
  console.log(`  ${sha.slice(0, 8)} -> ${made.sha.slice(0, 8)}  (${entries.length} 处变更) ${message.split("\n")[0].slice(0, 40)}`);
}

/* ---- 移分支 ---- */
if (commits.length) {
  await api("PATCH", `/repos/${repo}/git/refs/heads/${branch}`, { sha: parentSha, force: false });
  console.log(`已把 ${branch} 推到 ${parentSha.slice(0, 8)}`);
}

/* ---- 双向核对：tree 逐路径比 ---- */
async function remoteTree(sha) {
  /* recursive 只有 trees 端点支持；打在 commits 上返回里没有 tree 字段（第一版就在这空手而归） */
  const commit = await api("GET", `/repos/${repo}/git/commits/${sha}`);
  const t = await api("GET", `/repos/${repo}/git/trees/${commit.tree.sha}?recursive=1`);
  const map = new Map();
  for (const e of t.tree) if (e.type === "blob") map.set(e.path, e.sha);
  return { map, truncated: t.truncated };
}
const rem = await remoteTree(parentSha);
if (rem.truncated) throw new Error("远端 tree 被截断，核对不完整——改用分页再比一遍");
const loc = new Map();
for (const line of gitZ(["ls-tree", "-r", "--full-tree", "-z", "HEAD"])) {
  const [head, rel] = [line.slice(0, line.indexOf("\t")), line.slice(line.indexOf("\t") + 1)];
  const [, , sha1, mode] = head.split(/[\s]+/);
  loc.set(rel, { sha1, mode });
}
const onlyLocal = [...loc.keys()].filter((p) => !rem.map.has(p));
const onlyRemote = [...rem.map.keys()].filter((p) => !loc.has(p));
let differ = 0;
for (const [p, info] of loc) {
  if (!rem.map.has(p)) continue;
  /* 本地 blob sha 与远端 blob sha 同为 git 算法，可直接比 */
  if (rem.map.get(p) !== info.sha1) differ++;
}
console.log(`核对：本地 ${loc.size} 个路径 / 远端 ${rem.map.size} 个路径`);
console.log(`  仅本地 ${onlyLocal.length}  仅远端 ${onlyRemote.length}  内容不同 ${differ}`);
if (onlyLocal.length || onlyRemote.length || differ) {
  console.error("树不一致，前 10 个：" + JSON.stringify({ onlyLocal: onlyLocal.slice(0, 10), onlyRemote: onlyRemote.slice(0, 10), differ }));
  process.exit(4);
}
console.log("远端 tree 与本地 HEAD 逐 blob 一致");
console.log(`HEAD_SHA ${parentSha}`);
