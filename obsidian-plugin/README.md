# Inkcalidraw for Obsidian

Opens `.excalidraw` notes from the Inkcalidraw tablet app in Obsidian (desktop and mobile), using the Excalidraw editor.

- **PDF annotations:** pages of an annotated PDF show under your ink. The PDF is read from the same folder as the note, so keep the note and its PDF together, as the app does.
- **Editing:** edits are saved back into the same file. The app's own data (pens, page layout, PDF page pointers) is kept. The rendered PDF pages are never written into the note.
- **Changes from outside:** if the file changes on disk while it's open (e.g. after `git pull`), the view reloads it.

## Install

1. Download `inkcalidraw-obsidian-<version>.zip` from the CI artifacts (or build it, see below).
2. Unzip it into `<your vault>/.obsidian/plugins/inkcalidraw/`. That folder should then contain `main.js`, `manifest.json` and `styles.css`.
3. In Obsidian, go to Settings → Community plugins, then enable **Inkcalidraw**.

**Excalidraw plugin conflict:** if the Excalidraw plugin is also installed, it keeps the `.excalidraw` extension. In that case, right-click a note and choose **Open in Inkcalidraw**, or use the command "Open current .excalidraw file in Inkcalidraw". The setting "Open .excalidraw files with Inkcalidraw" turns off claiming the extension.

## Build and test

```sh
npm install
npm run build      # outputs dist/main.js, dist/styles.css, dist/manifest.json
npm test           # headless-Chromium test against a real PDF-annotation note
```
