import esbuild from "esbuild";
import fs from "node:fs";

// Obsidian plugin bundle: main.js (CommonJS, obsidian/electron external) + styles.css (Excalidraw's CSS).
await esbuild.build({
  entryPoints: ["src/main.ts"],
  bundle: true,
  format: "cjs",
  target: "es2020",
  platform: "browser",
  external: ["obsidian", "electron", "@codemirror/*", "@lezer/*"],
  outfile: "dist/main.js",
  minify: true,
  loader: { ".woff2": "dataurl", ".ttf": "dataurl" },
  define: { "process.env.NODE_ENV": '"production"', "process.env.IS_PREACT": '"false"', "import.meta.env": '{"DEV":false,"PROD":true,"MODE":"production"}' },
  conditions: ["production"],
  logLevel: "warning",
});
fs.renameSync("dist/main.css", "dist/styles.css");
fs.appendFileSync("dist/styles.css", "\n.inkcalidraw-view{padding:0!important;overflow:hidden}.inkcalidraw-host{position:absolute;inset:0}\n");
fs.copyFileSync("manifest.json", "dist/manifest.json");
console.log("built", Object.fromEntries(["main.js", "styles.css"].map((f) => [f, (fs.statSync("dist/" + f).size / 1024).toFixed(0) + " KB"])));
