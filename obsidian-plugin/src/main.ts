import { createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { loadPdfJs, Notice, Plugin, PluginSettingTab, Setting, TextFileView, TFile, type WorkspaceLeaf } from "obsidian";
import { InkScene, parseNote } from "./scene";

const VIEW_TYPE = "inkcalidraw-view";

interface Settings { claimExtension: boolean }
const DEFAULTS: Settings = { claimExtension: true };

/** A file view for .excalidraw notes (Obsidian handles reading/writing; we render Excalidraw). */
class InkView extends TextFileView {
  private root: Root | null = null;
  private text = "";

  constructor(leaf: WorkspaceLeaf, private plugin: InkcalidrawPlugin) { super(leaf); }

  getViewType() { return VIEW_TYPE; }
  getDisplayText() { return this.file?.basename ?? "Inkcalidraw"; }
  getIcon() { return "pen-tool"; }

  getViewData() { return this.text; }

  setViewData(data: string, clear: boolean) {
    // Remount when the file changed outside this view (e.g. git pull), otherwise the next edit would
    // overwrite it. Our own saves come back identical to this.text and are ignored.
    const changed = data !== this.text;
    this.text = data;
    if (clear || !this.root || changed) this.mount();
  }

  clear() { this.root?.unmount(); this.root = null; }

  private mount() {
    this.root?.unmount();
    this.contentEl.empty();
    this.contentEl.addClass("inkcalidraw-view");
    try { parseNote(this.text); } catch (e) {
      this.contentEl.createEl("p", { text: `Can't open this file: ${(e as Error).message}` });
      return;
    }
    const file = this.file;
    const host = {
      dark: document.body.classList.contains("theme-dark"),
      pdfjs: () => loadPdfJs(),
      readSibling: async (name: string) => {
        const dir = file?.parent?.path ?? "";
        const f = this.app.vault.getAbstractFileByPath(dir && dir !== "/" ? `${dir}/${name}` : name);
        return f instanceof TFile ? this.app.vault.readBinary(f) : null;
      },
      save: (json: string) => { this.text = json; this.requestSave(); },
    };
    const holder = this.contentEl.createDiv({ cls: "inkcalidraw-host" });
    this.root = createRoot(holder);
    this.root.render(createElement(InkScene, { key: file?.path ?? "x", text: this.text, host }));
  }

  async onClose() { this.clear(); }
}

export default class InkcalidrawPlugin extends Plugin {
  settings: Settings = DEFAULTS;

  async onload() {
    this.settings = Object.assign({}, DEFAULTS, await this.loadData());
    this.registerView(VIEW_TYPE, (leaf) => new InkView(leaf, this));

    if (this.settings.claimExtension) {
      try {
        this.registerExtensions(["excalidraw"], VIEW_TYPE);
      } catch {
        // Another plugin (e.g. Excalidraw) already handles .excalidraw: stay available via the menu.
        new Notice("Inkcalidraw: .excalidraw is already handled by another plugin. Use “Open in Inkcalidraw” from the file menu.");
      }
    }

    this.registerEvent(this.app.workspace.on("file-menu", (menu, file) => {
      if (file instanceof TFile && file.extension === "excalidraw") {
        menu.addItem((i) => i.setTitle("Open in Inkcalidraw").setIcon("pen-tool").onClick(() => this.openIn(file)));
      }
    }));
    this.addCommand({
      id: "open-current-in-inkcalidraw",
      name: "Open current .excalidraw file in Inkcalidraw",
      checkCallback: (checking) => {
        const f = this.app.workspace.getActiveFile();
        if (!f || f.extension !== "excalidraw") return false;
        if (!checking) void this.openIn(f);
        return true;
      },
    });
    this.addSettingTab(new InkSettings(this));
  }

  async openIn(file: TFile) {
    const leaf = this.app.workspace.getLeaf(false);
    await leaf.setViewState({ type: VIEW_TYPE, state: { file: file.path }, active: true });
  }

  async saveSettings() { await this.saveData(this.settings); }
}

class InkSettings extends PluginSettingTab {
  constructor(private plugin: InkcalidrawPlugin) { super(plugin.app, plugin); }
  display() {
    this.containerEl.empty();
    new Setting(this.containerEl)
      .setName("Open .excalidraw files with Inkcalidraw")
      .setDesc("Turn off if you prefer the Excalidraw plugin for these files (you can still use “Open in Inkcalidraw”). Restart Obsidian after changing.")
      .addToggle((t) => t.setValue(this.plugin.settings.claimExtension).onChange(async (v) => { this.plugin.settings.claimExtension = v; await this.plugin.saveSettings(); }));
  }
}
