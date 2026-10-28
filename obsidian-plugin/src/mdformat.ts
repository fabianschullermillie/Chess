/**
 * The Obsidian Excalidraw plugin's file format (".excalidraw.md"): a Markdown note with
 *   ## Text Elements   – one "<text> ^<id>" paragraph per text element (searchable, linkable)
 *   ## Embedded Files  – "<fileId>: [[image.png]]" (images live as vault files, not in the JSON)
 *   ## Drawing         – the scene JSON, in a ```json or ```compressed-json (LZString base64) fence
 * Decoding gives us the plain scene JSON; encoding writes it back into the same note, in the same
 * fence style, regenerating the Text Elements section so the Excalidraw plugin stays in sync.
 */
import LZString from "lz-string";

export interface MdNote {
  text: string;                 // the whole original note
  fence: "json" | "compressed-json";
  embeds: Map<string, string>;  // fileId -> link text inside [[ ]]
}

const DRAWING = /(^#{1,6} Drawing[ \t]*\r?\n```(compressed-json|json)[ \t]*\r?\n)([\s\S]*?)(\r?\n```)/m;

export function isExcalidrawMd(path: string, text?: string): boolean {
  if (!path.toLowerCase().endsWith(".md")) return false;
  if (path.toLowerCase().endsWith(".excalidraw.md")) return true;
  return !!text && /^excalidraw-plugin:/m.test(frontmatter(text));
}

function frontmatter(text: string) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---/.exec(text);
  return m ? m[1] : "";
}

function section(text: string, name: string): { start: number; end: number } | null {
  const h = new RegExp(`^#{1,6} ${name}[ \\t]*\\r?\\n`, "m").exec(text);
  if (!h) return null;
  const start = h.index + h[0].length;
  const rest = text.slice(start);
  const next = /^(#{1,6} |%%)/m.exec(rest);
  return { start, end: next ? start + next.index : text.length };
}

export function decodeMd(text: string): { json: string; note: MdNote } {
  const m = DRAWING.exec(text);
  if (!m) throw new Error("No drawing found in this note (expected a “## Drawing” section)");
  const fence = m[2] as MdNote["fence"];
  const json = fence === "compressed-json" ? LZString.decompressFromBase64(m[3].replace(/\s+/g, "")) : m[3];
  if (!json) throw new Error("Couldn't decompress the drawing");
  const embeds = new Map<string, string>();
  const sec = section(text, "Embedded Files");
  if (sec) {
    for (const line of text.slice(sec.start, sec.end).split(/\r?\n/)) {
      const e = /^\^?([\w-]+):\s*\[\[([^\]]+)\]\]/.exec(line.trim());
      if (e) embeds.set(e[1], e[2]);
    }
  }
  return { json, note: { text, fence, embeds } };
}

function compress(json: string) {
  const c = LZString.compressToBase64(json);
  const out: string[] = [];
  for (let i = 0; i < c.length; i += 256) out.push(c.slice(i, i + 256));
  return out.join("\n\n");
}

type Json = Record<string, unknown>;

/** Writes `json` (a full scene file) back into the note. Images must already be in note.embeds. */
export function encodeMd(note: MdNote, json: string): string {
  const scene = JSON.parse(json) as Json;
  const els = ((scene.elements as Json[]) ?? []).filter((e) => !e.isDeleted);
  // images are referenced from "## Embedded Files", never stored inline
  scene.files = {};
  const body = JSON.stringify(scene, null, note.fence === "json" ? "\t" : undefined);
  const payload = note.fence === "compressed-json" ? compress(body) : body;

  let text = note.text;
  const m = DRAWING.exec(text)!;
  text = text.slice(0, m.index) + m[1] + payload + m[4] + text.slice(m.index + m[0].length);

  const texts = els.filter((e) => e.type === "text").map((e) => `${String(e.originalText ?? e.rawText ?? e.text ?? "")} ^${e.id}\n\n`).join("");
  text = replaceSection(text, "Text Elements", texts, "# Excalidraw Data", "## Drawing");

  const used = new Set(els.filter((e) => e.type === "image").map((e) => String(e.fileId)));
  const embedLines = [...note.embeds].filter(([id]) => used.has(id)).map(([id, link]) => `${id}: [[${link}]]\n\n`).join("");
  text = replaceSection(text, "Embedded Files", embedLines, "## Text Elements", "## Drawing");
  return text;
}

/** Replaces a section's body; creates the section before `beforeHeading` if it doesn't exist (and isn't empty). */
function replaceSection(text: string, name: string, body: string, _after: string, beforeHeading: string) {
  const sec = section(text, name);
  if (sec) return text.slice(0, sec.start) + body + text.slice(sec.end);
  if (!body) return text;
  const at = text.search(new RegExp(`^(%%\\r?\\n)?#{1,6} ${beforeHeading.replace(/^#+ /, "")}`, "m"));
  if (at < 0) return text;
  return text.slice(0, at) + `## ${name}\n${body}` + text.slice(at);
}
