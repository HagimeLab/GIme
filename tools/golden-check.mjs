/**
 * T5 金标 + 上游方言 canary（离线）。
 *
 * 15 条固定题只校验「该调哪个工具 / 不该调什么」的期望与 ToolRegistry 对齐：
 * 期望里的工具必须真实注册、写操作必须标 approval、read 不能点写工具。
 * 三方言样本把 OpenAI SSE / DeepSeek 文本 / DSML 各钉一份；上游一变，先跑这里。
 *
 * 用法：node tools/golden-check.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const GOLD = path.join(ROOT, "tools/golden");
let pass = 0;
const fails = [];
function ok(name, cond, detail) {
  if (cond) { pass++; console.log("  ✓ " + name); }
  else { fails.push(name + (detail ? "：" + detail : "")); console.log("  ✗ " + name + (detail ? "：" + detail : "")); }
}

function registryOrder() {
  const src = fs.readFileSync(path.join(ROOT, "app/src/main/java/com/fan/shuatiku/agent/ToolRegistry.java"), "utf8");
  const m = src.match(/public static final String\[\] ORDER = \{([\s\S]*?)\};/);
  return m ? [...m[1].matchAll(/"([a-z_]+)"/g)].map((x) => x[1]) : [];
}
const registry = registryOrder();
const localTools = ["get_current_question", "import_attachment"];
const known = new Set(registry.concat(localTools));
const writeApproval = new Set(["write_answers", "write_explanations", "delete_plan"]);

console.log("[T5] 金标用例");
const cases = JSON.parse(fs.readFileSync(path.join(GOLD, "cases.json"), "utf8"));
ok("共 15 条", cases.length === 15, String(cases.length));
ok("id 唯一", new Set(cases.map((c) => c.id)).size === cases.length);
ok("prompt 都非空", cases.every((c) => String(c.prompt || "").trim().length > 0));
ok("expect 都是已知工具", cases.every((c) => (c.expect || []).every((n) => known.has(n))),
   cases.filter((c) => (c.expect || []).some((n) => !known.has(n))).map((c) => c.id).join(","));
ok("none 类不期望任何工具", cases.filter((c) => c.kind === "none").every((c) => (c.expect || []).length === 0));
ok("read 类不碰写工具",
   cases.filter((c) => c.kind === "read").every((c) => (c.expect || []).every((n) => !writeApproval.has(n))));
ok("写工具用例都要求 approval",
   cases.filter((c) => (c.expect || []).some((n) => writeApproval.has(n))).every((c) => c.approval === true));
ok("多工具用例至少两步", cases.filter((c) => c.kind === "multi").every((c) => (c.expect || []).length >= 2));
ok("期望工具顺序与 Registry 一致",
   cases.every((c) => (c.expect || []).every((n, i, arr) => i === 0 || registry.indexOf(arr[i - 1]) <= registry.indexOf(n))),
   "multi 用例按 Registry 顺序写");
const perCase = cases.every((c) => {
  const exp = c.expect || [];
  if (c.kind === "none") return exp.length === 0;
  if (!exp.length) return false;
  if (exp.some((n) => !known.has(n))) return false;
  if (exp.some((n) => writeApproval.has(n)) && c.approval !== true) return false;
  return true;
});
ok("15 条逐条通过（15/15）", perCase);

console.log("\n[T5] 上游方言 canary");
const openai = fs.readFileSync(path.join(GOLD, "dialects/openai.sse"), "utf8");
const chunks = openai.split(/\r?\n/).filter((l) => l.startsWith("data: ") && l.slice(6) !== "[DONE]")
  .map((l) => JSON.parse(l.slice(6)));
const calls = [];
for (const ch of chunks) {
  const tc = ch.choices && ch.choices[0] && ch.choices[0].delta && ch.choices[0].delta.tool_calls;
  if (!Array.isArray(tc)) continue;
  for (const one of tc) {
    const i = one.index | 0;
    calls[i] = calls[i] || { name: "", args: "" };
    if (one.function && one.function.name) calls[i].name = one.function.name;
    if (one.function && one.function.arguments) calls[i].args += one.function.arguments;
  }
}
ok("OpenAI SSE：分片参数能合并成一个调用",
   calls.length === 1 && calls[0].name === "create_plan" &&
   JSON.parse(calls[0].args).totalDays === 7, JSON.stringify(calls));

const deepseek = fs.readFileSync(path.join(GOLD, "dialects/deepseek-text.txt"), "utf8");
const dm = deepseek.match(/<tool_call>([\s\S]*?)<\/tool_call>/);
const dcall = dm && JSON.parse(dm[1]);
ok("DeepSeek 文本：<tool_call> 能解析", !!dcall && dcall.name === "list_questions" &&
   dcall.arguments.bank === "b1", dm && dm[1]);

const dsml = fs.readFileSync(path.join(GOLD, "dialects/dsml.txt"), "utf8");
const invoke = dsml.match(/invoke name="([^"]+)"/);
const params = {};
for (const m of dsml.matchAll(/parameter name="([^"]+)"[^>]*>([\s\S]*?)<\/｜｜DSML｜｜parameter>/g)) {
  let v = m[2].trim();
  try { v = JSON.parse(v); } catch (e) { }
  params[m[1]] = v;
}
ok("DSML：工具名与参数能解析", !!invoke && invoke[1] === "list_questions" &&
   params.bank === "b1" && String(params.limit) === "3", JSON.stringify({ invoke: invoke && invoke[1], params }));

console.log("\n" + "=".repeat(46));
if (fails.length) {
  console.log("失败 " + fails.length + " 项：\n  - " + fails.join("\n  - "));
  process.exit(1);
}
console.log("全部通过：" + pass + " 项");
