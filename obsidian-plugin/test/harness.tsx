// Mounts the plugin's editor (InkScene) outside Obsidian with a fake vault, for automated tests.
import { createRoot } from "react-dom/client";
import { createElement } from "react";
import * as pdfjs from "pdfjs-dist";
import { InkScene } from "../src/scene";

declare global { interface Window { __note: string; __pdf: string; __saved: string[]; __pdfWorker: string } }
pdfjs.GlobalWorkerOptions.workerSrc = window.__pdfWorker;
window.__saved = [];
const host = {
  dark: false,
  pdfjs: async () => pdfjs as never,
  readSibling: async (name: string) => (name === "Lecture 3.pdf" ? Uint8Array.from(atob(window.__pdf), (c) => c.charCodeAt(0)).buffer : null),
  save: (json: string) => { window.__saved.push(json); },
};
const el = document.getElementById("root")!;
createRoot(el).render(createElement(InkScene, { text: window.__note, host }));
