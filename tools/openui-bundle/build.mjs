import { build } from "esbuild";
import fs from "node:fs";
/* 同一个入口在 Node 里再跑一遍，只为拿到 library.toSpec() 的签名（下面生成小文件用）。 */
import "./entry.mjs";

await build({
  entryPoints: ["entry.mjs"],
  bundle: true,
  format: "iife",
  platform: "browser",
  target: ["chrome80"],
  minify: true,
  outfile: "../../app/src/main/assets/ui/openui-core.js",
  banner: {
    js: "/*! OpenUI core — @openuidev/lang-core 1.0.2 (MIT), github.com/thesysdev/openui; rebuilt locally with esbuild target=chrome80; library: goal-assistant. */",
  },
});

/* 顺手把 22 个组件的官方签名落成一个小文件（约 2KB）。
   为什么要有它：openui-core.js 已改成按需加载（index.html 不再同步引），
   但系统提示在用户发第一条消息时就要签名——那条路径不能等 533KB 解析完。
   这份文件与 bundle 出自同一次构建，check.mjs 会拿它和 bundle 里的 signatures 逐条比对，
   对不上就报错，所以不会出现「提示里有的组件、渲染器里没有」的漂移。 */
const { OpenUI } = globalThis;
if (!OpenUI || !Array.isArray(OpenUI.signatures)) {
  throw new Error("entry.mjs 没有挂上 globalThis.OpenUI，签名文件无法生成");
}
const header =
  "/* 自动生成，勿手改：由 tools/openui-bundle/build.mjs 从 @openuidev/lang-core 的 library.toSpec() 导出。\n" +
  "   用途：系统提示需要组件签名，但 openui-core.js 已改为按需加载，不能同步依赖它。\n" +
  "   改了 entry.mjs 的组件就要重跑 npm run build，这份文件会跟着更新。 */\n";
fs.writeFileSync(
  "../../app/src/main/assets/ui/openui-signatures.js",
  header + "window.OPENUI_SIGNATURES = " + JSON.stringify(OpenUI.signatures) + ";\n"
);
console.log("signatures: " + OpenUI.signatures.length + " 条 → ui/openui-signatures.js");
