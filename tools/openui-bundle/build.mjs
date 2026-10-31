import { build } from "esbuild";

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
