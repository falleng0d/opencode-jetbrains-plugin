# JetBrains Plugin for OpenCode — Architecture & Implementation Plan

## Table of Contents

1. [Overview](#overview)
2. [Key Insight: Two Integration Paths](#key-insight-two-integration-paths)
3. [Recommended Approach: ACP + Embedded WebView](#recommended-approach-acp--embedded-webview)
4. [Architecture Diagram](#architecture-diagram)
5. [Phase 1 — ACP Integration (Core Chat)](#phase-1--acp-integration-core-chat)
6. [Phase 2 — Webapp Adaptations](#phase-2--webapp-adaptations)
7. [Phase 3 — IDE-Native Context Features](#phase-3--ide-native-context-features)
8. [Phase 4 — Cursor-like IDE Features](#phase-4--cursor-like-ide-features)
9. [Component Breakdown](#component-breakdown)
10. [File Structure](#file-structure)
11. [Implementation Details](#implementation-details)
12. [What to Strip from the Webapp](#what-to-strip-from-the-webapp)
13. [New Webapp Features for JetBrains](#new-webapp-features-for-jetbrains)
14. [The IDE Context Protocol (Custom Extension)](#the-ide-context-protocol-custom-extension)
15. [Open Questions & Risks](#open-questions--risks)

---

## Overview

The goal is to build a **JetBrains IDE plugin** that embeds OpenCode's chat functionality natively inside any JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, etc.) and extends it with:

- **IDE-native context injection** — current file, cursor position, surrounding code, open tabs
- **Manual code block selection** → add to context (Cursor-style)
- **Single-project focus** — the plugin is locked to the open project directory; no multi-project browser needed
- **Session management** — create, switch, and resume sessions without leaving the IDE
- **Full OpenCode feature parity** — streaming responses, tool calls, MCP, permissions, agents, etc.

The OpenCode backend already does everything we need. The question is purely how the plugin UI communicates with it.

---

## Key Insight: Two Integration Paths

### Path A — ACP (Agent Client Protocol)

JetBrains AI Assistant supports ACP natively in 2025.3+. OpenCode already implements the ACP server via `opencode acp`. This means you can **today** add OpenCode as an ACP agent inside JetBrains AI Chat with zero new code — just an `acp.json` config file.

**The problem:** ACP plugs OpenCode into JetBrains' _own_ AI Chat UI. You get no control over the UI, no custom context injection beyond what the ACP protocol already supports, and you're locked into whatever JetBrains ships. This is good for quick distribution but not for building a Cursor-like experience.

### Path B — Embedded WebView (JCEF)

JetBrains exposes **JCEF** (JetBrains Chromium Embedded Framework), which lets you embed a real Chromium browser panel inside a Tool Window. You host the existing `packages/app` webapp inside this panel, connected to a locally spawned OpenCode HTTP server. This gives you full control over the UI.

**The problem:** The current webapp has features that make no sense in a single-IDE context (multi-project sidebar, server switcher, directory picker, file tree navigator, etc.) and is missing features you need (IDE-native file context, cursor position, tab awareness).

### Decision: Use Both

| Layer                 | Technology                                | What it does                                     |
| --------------------- | ----------------------------------------- | ------------------------------------------------ |
| Chat UI               | JCEF WebView → modified `packages/app`    | Full OpenCode UI embedded as Tool Window panel   |
| Backend               | OpenCode HTTP server (spawned by plugin)  | Handles sessions, LLM calls, tools               |
| IDE Context           | JetBrains Platform SDK → custom JS bridge | Injects editor state into the webapp             |
| Optional ACP fallback | `opencode acp` subprocess                 | For users who want AI Assistant chat integration |

---

## Recommended Approach: ACP + Embedded WebView

```
JetBrains IDE
├── Plugin (Kotlin/Java)
│   ├── Tool Window: "OpenCode"
│   │   └── JCEF panel → packages/app (modified)
│   ├── OpenCode Server Manager
│   │   └── Spawns `opencode serve --port <random>` on project open
│   ├── IDE Context Bridge
│   │   ├── Listens: file open/close, selection change, caret move
│   │   └── Posts messages to webapp via JCEF JS injection
│   ├── Actions
│   │   ├── "Add selection to context" (editor right-click / keybind)
│   │   └── "Open OpenCode" (toolbar / keybind)
│   └── Settings page
│       └── OpenCode server path, API keys, default model
│
└── packages/app (modified fork)
    ├── Strips: multi-project sidebar, directory picker, server switcher
    ├── Adds: IDE context panel, tab list, selection display
    └── Adds: window.ideContext API (consumed from Kotlin bridge)
```

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                      JetBrains IDE                          │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                   OpenCode Tool Window               │   │
│  │  ┌────────────────────────────────────────────────┐  │   │
│  │  │              JCEF WebView                      │  │   │
│  │  │  ┌──────────────────────────────────────────┐  │  │   │
│  │  │  │   packages/app (JetBrains build)          │  │  │   │
│  │  │  │   - No sidebar / project picker           │  │  │   │
│  │  │  │   - Session switcher (compact)            │  │  │   │
│  │  │  │   - IDE context panel (tabs, selection)   │  │  │   │
│  │  │  │   - Prompt input (same as today)          │  │  │   │
│  │  │  └──────────────────────────────────────────┘  │  │   │
│  │  │           ▲ JS postMessage bridge               │  │   │
│  │  └───────────┼────────────────────────────────────┘  │   │
│  │              │ JBCefBrowser.executeJavaScript()        │   │
│  │  ┌───────────┴────────────────────────────────────┐   │   │
│  │  │            IDE Context Bridge (Kotlin)          │   │   │
│  │  │  - FileEditorManagerListener                    │   │   │
│  │  │  - CaretListener                                │   │   │
│  │  │  - SelectionListener                            │   │   │
│  │  │  - VirtualFileListener                          │   │   │
│  │  └────────────────────────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │             OpenCode Server Manager (Kotlin)          │   │
│  │  Spawns: opencode serve --cwd <project.basePath>      │   │
│  │          --port <random-available>                    │   │
│  │  Manages: lifecycle, restart, health check            │   │
│  └──────────────────────────────────────────────────────┘   │
│                           │                                 │
│                    HTTP localhost:<port>                     │
│                           │                                 │
│  ┌────────────────────────▼─────────────────────────────┐   │
│  │               OpenCode HTTP Server                    │   │
│  │  (packages/opencode — existing, unmodified)           │   │
│  │  - /session, /provider, /mcp, /event (SSE), ...       │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## Phase 1 — ACP Integration (Core Chat)

**Goal:** Get OpenCode working inside JetBrains AI Chat via ACP, zero UI work required. Useful as a quick-win release while the proper plugin is built.

### What to do

1. Add `opencode` to the JetBrains ACP registry (submit a PR to `agentclientprotocol.com`).
2. Document the `~/.jetbrains/acp.json` setup in OpenCode docs.
3. JetBrains will auto-detect OpenCode if it's installed — users click "Add to configuration".

### acp.json for JetBrains

```json
{
  "default_mcp_settings": {
    "use_idea_mcp": true,
    "use_custom_mcp": true
  },
  "agent_servers": {
    "OpenCode": {
      "command": "/absolute/path/to/opencode",
      "args": ["acp"]
    }
  }
}
```

### ACP Capabilities Already Supported by OpenCode

Looking at `packages/opencode/src/acp/agent.ts`, OpenCode's ACP implementation already supports:

| Capability               | Status                       |
| ------------------------ | ---------------------------- |
| `loadSession`            | ✅                           |
| `fork`                   | ✅                           |
| `list` sessions          | ✅                           |
| `resume`                 | ✅                           |
| `embeddedContext`        | ✅ (in `promptCapabilities`) |
| `image` attachments      | ✅                           |
| Tool call streaming      | ✅                           |
| Reasoning/thought chunks | ✅                           |
| Permission requests      | ✅                           |
| Mode switching (agents)  | ✅                           |
| Model switching          | ✅                           |
| MCP servers pass-through | ✅                           |

**Limitation of ACP path:** You can pass context as `resource_link` or `text` parts in the prompt, but you cannot push context _proactively_ from the IDE into the agent without user action. For a Cursor-like feel you need Phase 3.

---

## Phase 2 — Webapp Adaptations

The `packages/app` webapp needs a **JetBrains build target** — a compiled variant that removes multi-project features and adds IDE-specific ones.

### Approach

Add a `VITE_TARGET=jetbrains` env flag that conditionally includes/excludes components. No forking of the webapp — just conditional rendering.

```ts
// packages/app/src/env.ts
export const TARGET = import.meta.env.VITE_TARGET ?? "web"
export const isJetBrains = TARGET === "jetbrains"
```

### What to Conditionally Disable

| Feature                                                                       | Why remove                  |
| ----------------------------------------------------------------------------- | --------------------------- |
| Multi-project sidebar (`layout/sidebar-workspace.tsx`, `sidebar-project.tsx`) | Plugin is single-project    |
| Directory picker dialog (`dialog-select-directory.tsx`)                       | IDE handles file system     |
| Server switcher button & dialog (`dialog-select-server.tsx`)                  | Server is managed by plugin |
| File tree (`file-tree.tsx`)                                                   | IDE has its own             |
| Home page with recent projects (`pages/home.tsx`)                             | Not applicable              |
| "Open Project" command                                                        | IDE opens projects          |

### What to Add to Webapp

| Feature                     | Implementation                                        |
| --------------------------- | ----------------------------------------------------- |
| IDE context panel           | New sidebar component showing active file + open tabs |
| Context badge in prompt     | Shows what context is currently attached              |
| Selection highlight display | Shows code snippet from IDE selection                 |
| JetBrains theme bridge      | Reads IDE theme from `window.ideTheme` and applies it |
| Single-project header       | Shows project name + branch, no switcher              |

### Layout for JetBrains Build

```
┌────────────────────────────────────┐
│ OpenCode  [project-name / branch]  │  ← compact header, no project switcher
│ ─────────────────────────────────  │
│ [Session 1]  [Session 2]  [+]      │  ← tab-style session switcher
│ ─────────────────────────────────  │
│                                    │
│   (chat messages)                  │
│                                    │
│ ─────────────────────────────────  │
│ Context: [Main.kt:42] [IdeaTest.kt]│  ← context pills (IDE-injected)
│ ┌──────────────────────────────┐   │
│ │ Ask OpenCode anything...     │   │
│ └──────────────────────────────┘   │
└────────────────────────────────────┘
```

### Routing Changes for JetBrains

The current webapp routes by `/:dir/session/:id`. In the JetBrains build:

- The project directory is fixed to what the IDE provides at startup
- No `/` home route — boot directly into `/:dir/session` or the last active session
- URL base is `http://localhost:<port>` pointing to the managed OpenCode server

---

## Phase 3 — IDE-Native Context Features

This is where the real Cursor-like behaviour lives. The plugin injects IDE state into the webapp via a JavaScript bridge over JCEF.

### The Bridge

The Kotlin plugin calls `JBCefBrowser.executeJavaScript()` to dispatch events into the webapp. The webapp listens on `window` for a custom event namespace.

#### Kotlin side (plugin)

```kotlin
// Dispatch IDE context updates to webapp
fun dispatchToWebapp(browser: JBCefBrowser, event: String, payload: String) {
    browser.executeJavaScript(
        "window.dispatchEvent(new CustomEvent('opencode:ide', { detail: { type: '$event', payload: $payload } }))",
        browser.cefBrowser.url, 0
    )
}
```

#### TypeScript side (webapp)

```ts
// packages/app/src/context/ide-bridge.ts
export type IdeEvent =
  | { type: "activeFile"; path: string; language: string }
  | { type: "caretMoved"; path: string; line: number; col: number; surroundingCode: string }
  | { type: "selectionAdded"; path: string; startLine: number; endLine: number; code: string }
  | { type: "selectionCleared" }
  | { type: "tabsChanged"; tabs: Array<{ path: string; active: boolean }> }
  | { type: "projectInfo"; path: string; name: string; branch?: string }
  | { type: "themeChanged"; isDark: boolean; primaryColor: string }

export function listenIdeEvents(handler: (e: IdeEvent) => void) {
  const fn = (raw: Event) => handler((raw as CustomEvent).detail)
  window.addEventListener("opencode:ide", fn)
  return () => window.removeEventListener("opencode:ide", fn)
}
```

### Feature 1: Active Tab + Cursor Context

#### Kotlin: Listen to editor changes

```kotlin
@Service(Service.Level.PROJECT)
class IdeContextService(private val project: Project) : Disposable {

    fun install(browser: JBCefBrowser) {
        // Listen to file open/focus changes
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile ?: return
                    val editor = event.newEditor as? TextEditor ?: return
                    sendActiveFile(browser, file, editor.editor)
                }
            }
        )

        // Listen to caret moves
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    event.editor.caretModel.addCaretListener(CaretListenerImpl(browser, project))
                }
            }, this
        )
    }

    private fun sendActiveFile(browser: JBCefBrowser, file: VirtualFile, editor: Editor) {
        val path = file.path
        val lang = file.fileType.name
        val line = editor.caretModel.logicalPosition.line
        val col = editor.caretModel.logicalPosition.column
        val surrounding = getSurroundingCode(editor, line, radius = 15)

        val payload = """{"path":"$path","language":"$lang","line":$line,"col":$col,"surroundingCode":${surrounding.toJson()}}"""
        dispatchToWebapp(browser, "caretMoved", payload)
    }

    private fun getSurroundingCode(editor: Editor, line: Int, radius: Int): String {
        val doc = editor.document
        val start = maxOf(0, line - radius)
        val end = minOf(doc.lineCount - 1, line + radius)
        return (start..end).joinToString("\n") { doc.getText(TextRange(doc.getLineStartOffset(it), doc.getLineEndOffset(it))) }
    }
}
```

#### Kotlin: Open tabs list

```kotlin
fun sendOpenTabs(browser: JBCefBrowser) {
    val manager = FileEditorManager.getInstance(project)
    val tabs = manager.openFiles.map { f ->
        """{"path":"${f.path}","active":${f == manager.selectedFiles.firstOrNull()}}"""
    }.joinToString(",")
    dispatchToWebapp(browser, "tabsChanged", "[$tabs]")
}
```

#### Webapp: Context store

```ts
// packages/app/src/context/ide-context.ts (new file)
import { createStore } from "solid-js/store"
import { listenIdeEvents } from "./ide-bridge"

export type IdeTab = { path: string; active: boolean }

export type IdeContext = {
  activeFile: string | null
  language: string | null
  line: number
  col: number
  surroundingCode: string | null
  tabs: IdeTab[]
  attachedSelections: Array<{ path: string; startLine: number; endLine: number; code: string }>
}

const [ctx, setCtx] = createStore<IdeContext>({
  activeFile: null,
  language: null,
  line: 0,
  col: 0,
  surroundingCode: null,
  tabs: [],
  attachedSelections: [],
})

export const ideContext = ctx

export function initIdeBridge() {
  listenIdeEvents((e) => {
    switch (e.type) {
      case "caretMoved":
        setCtx({ activeFile: e.path, line: e.line, col: e.col, surroundingCode: e.surroundingCode })
        break
      case "tabsChanged":
        setCtx("tabs", e.tabs)
        break
      case "selectionAdded":
        setCtx("attachedSelections", (s) => [
          ...s,
          { path: e.path, startLine: e.startLine, endLine: e.endLine, code: e.code },
        ])
        break
      case "selectionCleared":
        setCtx("attachedSelections", [])
        break
      case "projectInfo":
        setCtx({ activeFile: e.path })
        break
    }
  })
}
```

### Feature 2: Manual Code Selection → Add to Context

This is the "Cursor-style" add selection to chat feature.

#### Kotlin: Action + right-click

```kotlin
// Right-click in editor → "Add to OpenCode Context"
class AddToContextAction : AnAction("Add to OpenCode Context") {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val selection = editor.selectionModel

        if (!selection.hasSelection()) return

        val start = editor.offsetToLogicalPosition(selection.selectionStart)
        val end = editor.offsetToLogicalPosition(selection.selectionEnd)
        val code = selection.selectedText ?: return

        val payload = buildJsonPayload(
            "path" to file.path,
            "startLine" to start.line,
            "endLine" to end.line,
            "code" to code
        )

        // Get the tool window browser and dispatch
        val bridge = project.service<IdeContextService>()
        bridge.dispatchSelectionAdded(payload)
    }
}
```

Register the action in `plugin.xml`:

```xml
<actions>
  <action id="opencode.AddToContext"
          class="ai.opencode.plugin.AddToContextAction"
          text="Add to OpenCode Context"
          description="Add the selected code to the OpenCode chat context">
    <add-to-group group-id="EditorPopupMenu" anchor="after" relative-to-action="$Copy"/>
    <keyboard-shortcut keymap="$default" first-keystroke="control alt A"/>
  </action>

  <action id="opencode.OpenPanel"
          class="ai.opencode.plugin.OpenPanelAction"
          text="Open OpenCode"
          description="Open the OpenCode chat panel">
    <add-to-group group-id="MainToolBar" anchor="last"/>
    <keyboard-shortcut keymap="$default" first-keystroke="control BACK_SLASH"/>
  </action>
</actions>
```

#### Webapp: Context pills in prompt input

The attached selections appear as dismissable "pills" above the prompt input, exactly like Cursor's `@file` references. They are appended to the message as `resource_link` parts when the user submits.

When building the request in `packages/app/src/components/prompt-input/build-request-parts.ts`, inject the IDE context:

```ts
// Add IDE active file as implicit context
if (isJetBrains && ideContext.activeFile && ideContext.surroundingCode) {
  parts.push({
    type: "resource_link",
    uri: `file://${ideContext.activeFile}`,
    name: basename(ideContext.activeFile),
    mimeType: "text/plain",
  })
  // Inject surrounding code as synthetic text so the model has it
  parts.push({
    type: "text",
    text: `[Active file: ${ideContext.activeFile} L${ideContext.line}]\n\`\`\`\n${ideContext.surroundingCode}\n\`\`\``,
    annotations: { audience: ["assistant"] }, // synthetic — not shown in user bubble
  })
}

// Add manually pinned selections
for (const sel of ideContext.attachedSelections) {
  parts.push({
    type: "text",
    text: `[Selected code from ${basename(sel.path)} L${sel.startLine}-${sel.endLine}]\n\`\`\`\n${sel.code}\n\`\`\``,
  })
}
```

### Feature 3: Open Tabs Context

Give the model awareness of all currently open tabs — the same way Cursor's "Open Tabs" context works.

In the webapp prompt, when the user types `@tabs` or toggles a "Open tabs" toggle:

```ts
for (const tab of ideContext.tabs) {
  parts.push({
    type: "resource_link",
    uri: `file://${tab.path}`,
    name: basename(tab.path),
    mimeType: "text/plain",
  })
}
```

The OpenCode server will read the files when it processes the `resource_link` parts (the existing file handling code in `acp/agent.ts` already handles `file://` URIs).

---

## Phase 4 — Cursor-like IDE Features

Beyond context injection, here are the Cursor-equivalent features to implement:

### 4.1 Inline Diff / Apply Changes

When OpenCode edits a file, show an inline diff in the JetBrains editor (not just in the chat). Use JetBrains' `DiffManager` to highlight added/removed lines.

**Approach:**

1. Subscribe to `VFS_CHANGES` events (BulkFileListener) to detect when OpenCode writes a file.
2. Before the write, snapshot the file content.
3. After the write, show JetBrains' built-in diff view or use `DiffContentFactory` to display the change inline.

```kotlin
class FileChangeDiffListener(private val project: Project) : BulkFileListener {
    override fun after(events: MutableList<out VFileEvent>) {
        events.filterIsInstance<VFileContentChangeEvent>().forEach { event ->
            val file = event.file
            if (!isInProject(file, project)) return@forEach
            val before = event.oldPath  // or snapshot stored before
            // Show diff notification or inline gutter
            showDiffGutter(project, file, before)
        }
    }
}
```

### 4.2 Ghost Text / Inline Completions (Future)

This is Cursor's inline "Tab to complete" feature. It requires implementing a JetBrains `Inlay` provider and connecting it to OpenCode's streaming API. This is a significant undertaking — phase this for later.

### 4.3 Command-K Style Inline Edit (Future)

Show a floating input bar when the user presses `Ctrl+K` inside the editor. Sends the selected code + instruction to OpenCode and applies the result inline. Also a later phase.

### 4.4 Diff Viewer for Each Tool Call

In the chat panel, each `edit` tool call should show a compact before/after diff (already supported in the ACP protocol via `type: "diff"` in tool call content). The webapp already handles this; no new work needed.

### 4.5 File Gutter Icons

Show a small OpenCode icon in the gutter of files that have been modified by the current session. Implemented via `EditorGutterComponentEx` or `LineMarkerProvider`.

### 4.6 @-mentions for Files (In Chat)

When the user types `@` in the prompt, show a completion popup with files from the project. This is partially already in the webapp via the slash-popover; needs to be extended to use the IDE's file index instead of the server's file API.

---

## Component Breakdown

### Plugin Kotlin Components

| Component                   | Type              | Purpose                                      |
| --------------------------- | ----------------- | -------------------------------------------- |
| `OpenCodeToolWindowFactory` | ToolWindowFactory | Creates the main tool window panel           |
| `OpenCodePanel`             | JPanel            | Contains the JCEF browser                    |
| `ServerManager`             | Project Service   | Spawns/manages `opencode serve` subprocess   |
| `IdeContextService`         | Project Service   | Listens to IDE events, dispatches to webapp  |
| `AddToContextAction`        | AnAction          | Right-click → add selection to chat          |
| `OpenPanelAction`           | AnAction          | Open/focus the OpenCode tool window          |
| `OpenCodeSettings`          | Configurable      | Settings page (server path, API keys)        |
| `BrowserBridge`             | Helper            | Wraps `JBCefBrowser.executeJavaScript` calls |

### Webapp Components (new/modified)

| Component               | Status   | Purpose                                          |
| ----------------------- | -------- | ------------------------------------------------ |
| `IdeContextBar`         | New      | Shows active file, line, open tabs in sidebar    |
| `ContextPill`           | New      | Dismissable pill for attached selections         |
| `IdeBridge`             | New      | `window.opencode:ide` event listener and store   |
| `JetBrainsLayout`       | New      | Replaces `Layout` when `isJetBrains` is true     |
| `SessionTabBar`         | Modified | Compact horizontal session switcher (no sidebar) |
| `PromptInput`           | Modified | Aware of `ideContext.attachedSelections`         |
| `BuildRequestParts`     | Modified | Injects IDE context into request parts           |
| `Home`                  | Removed  | Not needed in JetBrains build                    |
| `SidebarWorkspace`      | Removed  | Not needed in JetBrains build                    |
| `DialogSelectDirectory` | Removed  | IDE handles this                                 |
| `DialogSelectServer`    | Removed  | Server managed by plugin                         |

---

## File Structure

### New Kotlin Plugin Package

```
packages/jetbrains-plugin/
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── ai/opencode/plugin/
│   │   │       ├── OpenCodePlugin.kt          # Plugin startup
│   │   │       ├── toolwindow/
│   │   │       │   ├── OpenCodeToolWindowFactory.kt
│   │   │       │   └── OpenCodePanel.kt
│   │   │       ├── server/
│   │   │       │   ├── ServerManager.kt       # Process lifecycle
│   │   │       │   └── ServerHealthChecker.kt
│   │   │       ├── bridge/
│   │   │       │   ├── IdeContextService.kt   # IDE event → JS dispatch
│   │   │       │   ├── BrowserBridge.kt       # JS execution wrapper
│   │   │       │   └── IdeEvent.kt            # Data classes
│   │   │       ├── actions/
│   │   │       │   ├── AddToContextAction.kt
│   │   │       │   ├── OpenPanelAction.kt
│   │   │       │   └── NewSessionAction.kt
│   │   │       └── settings/
│   │   │           ├── OpenCodeSettings.kt    # Persistent state
│   │   │           └── OpenCodeConfigurable.kt
│   │   └── resources/
│   │       └── META-INF/
│   │           ├── plugin.xml
│   │           └── pluginIcon.svg
│   └── test/
└── webview/                                   # Built webapp output goes here
    └── (built by packages/app jetbrains build)
```

### Webapp Changes

```
packages/app/src/
├── context/
│   └── ide-bridge.ts           # NEW: IDE event listener + context store
├── pages/
│   └── jetbrains-layout.tsx    # NEW: Single-project layout (no sidebar)
├── components/
│   ├── ide-context-bar.tsx     # NEW: Active file + tabs display
│   └── context-pill.tsx        # NEW: Dismissable context attachment
└── env.ts                      # NEW: isJetBrains flag
```

---

## Implementation Details

### Spawning the OpenCode Server

The plugin spawns `opencode serve` as a child process tied to the project's working directory. It picks a random available port, stores it as project-level state, and passes the URL to the JCEF webview.

```kotlin
@Service(Service.Level.PROJECT)
class ServerManager(private val project: Project) : Disposable {
    private var process: Process? = null
    private var port: Int = -1

    fun start(): Int {
        port = findFreePort()
        val bin = settings().opencodeExecutablePath
        val cwd = project.basePath ?: throw IllegalStateException("No project base path")

        process = ProcessBuilder(bin, "serve", "--port", port.toString(), "--cwd", cwd)
            .directory(File(cwd))
            .start()

        waitForHealthy(port)
        return port
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    override fun dispose() {
        process?.destroyForcibly()
    }
}
```

### Loading the Webapp in JCEF

The plugin bundles the built webapp as static resources inside its JAR. At runtime it extracts them to a temp directory (or serves directly from JAR using a custom `CefSchemeHandlerFactory`).

```kotlin
class OpenCodePanel(project: Project) : Disposable {
    private val browser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(false)
        .build()

    init {
        val port = project.service<ServerManager>().start()
        val dir = project.basePath?.let { URLEncoder.encode(it.toBase64(), "UTF-8") }

        // Load the webapp, pointing at the local server
        // The webapp reads VITE_TARGET=jetbrains and VITE_SERVER_URL from a JS global
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                // Inject runtime config
                browser.executeJavaScript("""
                    window.__OPENCODE_CONFIG__ = {
                        serverUrl: 'http://localhost:$port',
                        projectDir: '${project.basePath}',
                        target: 'jetbrains'
                    }
                """, browser.url, 0)
            }
        }, browser.cefBrowser)

        browser.loadURL("http://localhost:$port/index.html")
    }
}
```

### Webapp Runtime Config

The webapp reads `window.__OPENCODE_CONFIG__` to configure itself:

```ts
// packages/app/src/env.ts
declare global {
  interface Window {
    __OPENCODE_CONFIG__?: {
      serverUrl: string
      projectDir: string
      target: "web" | "jetbrains" | "desktop"
    }
  }
}

export const config = window.__OPENCODE_CONFIG__ ?? {
  serverUrl: import.meta.env.VITE_SERVER_URL ?? "http://localhost:4096",
  projectDir: null,
  target: (import.meta.env.VITE_TARGET ?? "web") as "web" | "jetbrains" | "desktop",
}

export const isJetBrains = config.target === "jetbrains"
```

### Theme Synchronization

The plugin reads the current IDE theme and dispatches it to the webapp so the chat panel matches the IDE.

```kotlin
fun syncTheme(browser: JBCefBrowser) {
    val isDark = !JBColor.isBright()
    val bg = JBColor.background()
    val fg = JBColor.foreground()
    val accent = JBColor.namedColor("Button.default.startBackground", JBColor.BLUE)

    dispatchToWebapp(browser, "themeChanged", """
        {
            "isDark": $isDark,
            "background": "${bg.toHex()}",
            "foreground": "${fg.toHex()}",
            "accent": "${accent.toHex()}"
        }
    """.trimIndent())
}
```

The webapp applies a CSS class or custom properties to match:

```ts
listenIdeEvents((e) => {
  if (e.type !== "themeChanged") return
  document.documentElement.style.setProperty("--ide-bg", e.background)
  document.documentElement.style.setProperty("--ide-fg", e.foreground)
  document.documentElement.style.setProperty("--ide-accent", e.primaryColor)
  document.documentElement.classList.toggle("dark", e.isDark)
})
```

---

## What to Strip from the Webapp

These features are controlled by the `isJetBrains` flag and rendered as `null` in the JetBrains build.

### `pages/home.tsx`

The home page shows recent projects and a server status dot. In JetBrains: skip this route entirely, boot directly into the project session.

### `pages/layout.tsx`

The main layout contains a complex multi-project sidebar with workspaces, drag-drop reordering, and project management. In JetBrains:

- Replace the entire sidebar with a compact **session tab bar** at the top
- Remove `DragDropProvider` / `SortableProvider`
- Remove `chooseProject()` / `openProject()` flows
- Remove workspace management

### `components/dialog-select-directory.tsx`

Used to choose a project directory. Not needed — the IDE provides the directory.

### `components/dialog-select-server.tsx`

The server switcher. Not needed — the plugin manages the server.

### `components/titlebar.tsx`

The desktop app titlebar. Not relevant for a JCEF panel.

### `context/server.tsx`

Currently handles connecting to remote servers. In JetBrains the server URL comes from `window.__OPENCODE_CONFIG__` and never changes.

---

## New Webapp Features for JetBrains

### `IdeContextBar` Component

Shown above or below the prompt, displays:

```
┌──────────────────────────────────────────────────────────────┐
│ 📄 Main.kt  L42  |  Tabs: [Build.kt] [Test.kt] [+2 more]    │
│ ✕ [selected: Main.kt L10-25]  ✕ [selected: Utils.kt L5-8]  │
└──────────────────────────────────────────────────────────────┘
```

Clicking a tab appends it as context. The `✕` dismisses a pinned selection.

### `JetBrainsLayout` Component

A stripped-down layout used when `isJetBrains === true`:

```tsx
// pages/jetbrains-layout.tsx
export default function JetBrainsLayout(props: ParentProps) {
  return (
    <div class="flex flex-col h-full">
      <JetBrainsHeader /> {/* project name, branch, session tabs */}
      <div class="flex-1 overflow-hidden">
        {props.children} {/* session content */}
      </div>
    </div>
  )
}
```

### Compact Session Switcher

Replace the sidebar session list with a horizontal tab bar (like browser tabs):

```tsx
function SessionTabBar() {
  const sync = useSync()
  const sessions = () => sync.store.session.filter((s) => !s.time.archived)

  return (
    <div class="flex gap-1 overflow-x-auto px-2 py-1 border-b">
      <For each={sessions()}>
        {(session) => (
          <button
            class="px-3 py-1 text-12 rounded whitespace-nowrap"
            classList={{ "bg-bg-active": session.id === currentSessionId() }}
            onClick={() => navigateToSession(session)}
          >
            {session.title ?? "New Session"}
          </button>
        )}
      </For>
      <button class="px-2 py-1" onClick={newSession}>
        +
      </button>
    </div>
  )
}
```

---

## The IDE Context Protocol (Custom Extension)

Rather than ad-hoc event dispatching, define a formal **typed protocol** for the IDE→webapp bridge. This ensures both sides stay in sync and makes testing possible.

### Message Types (TypeScript canonical definitions)

```ts
// packages/app/src/context/ide-protocol.ts

/** Messages the IDE plugin sends to the webapp */
export type IdeToWebMessage =
  | {
      type: "init"
      projectDir: string
      projectName: string
      branch?: string
      serverUrl: string
    }
  | {
      type: "activeEditor"
      path: string
      language: string
      line: number // 0-indexed
      col: number // 0-indexed
      lineCount: number
    }
  | {
      type: "surroundingCode"
      path: string
      line: number
      before: string // lines before cursor
      after: string // lines after cursor
      radius: number // how many lines were captured each side
    }
  | {
      type: "openTabs"
      tabs: Array<{
        path: string
        name: string
        active: boolean
        modified: boolean
      }>
    }
  | {
      type: "selectionAdded"
      id: string // unique ID so we can dedupe
      path: string
      name: string
      startLine: number
      endLine: number
      content: string
      language: string
    }
  | {
      type: "selectionRemoved"
      id: string
    }
  | {
      type: "theme"
      dark: boolean
      colors: Record<string, string>
    }

/** Messages the webapp sends back to the IDE plugin (via Java → JS query handler) */
export type WebToIdeMessage =
  | { type: "ready" }
  | { type: "openFile"; path: string; line?: number }
  | { type: "requestSurroundingCode"; path: string; line: number; radius: number }
  | { type: "sessionChanged"; sessionId: string }
```

### Bidirectional Communication

For webapp → IDE messages, register a `CefMessageRouter` query handler in JCEF:

```kotlin
val msgRouter = CefMessageRouter.create()
msgRouter.addHandler(object : CefMessageRouterHandlerAdapter() {
    override fun onQuery(browser: CefBrowser, frame: CefFrame, queryId: Long,
                         request: String, persistent: Boolean, callback: CefQueryCallback): Boolean {
        val msg = Json.decodeFromString<WebToIdeMessage>(request)
        when (msg) {
            is WebToIdeMessage.OpenFile -> openFileInEditor(msg.path, msg.line)
            is WebToIdeMessage.SessionChanged -> recordSessionId(msg.sessionId)
            is WebToIdeMessage.Ready -> onWebappReady()
        }
        callback.success("")
        return true
    }
}, false)
browser.jbCefClient.addMessageRouter(msgRouter, browser.cefBrowser)
```

Webapp sends messages via:

```ts
window.cefQuery?.({ request: JSON.stringify(msg), onSuccess: () => {}, onFailure: () => {} })
```

---

## Open Questions & Risks

### 1. JCEF Availability

JCEF is bundled with IntelliJ IDEA but may not be available in all JetBrains IDEs or Remote Development (Rider, Gateway). **Mitigation:** Detect JCEF availability at startup and fall back to opening the webapp in the system browser.

```kotlin
val jcefAvailable = JBCefApp.isSupported()
```

### 2. OpenCode Binary Discovery

The plugin needs to find the `opencode` binary. **Options:**

- Bundle it inside the plugin JAR (large, but zero-config)
- Ask user to configure path in settings
- Auto-detect from PATH

**Recommendation:** Bundle for the first release, add auto-detection from PATH as fallback.

### 3. Server Port Conflicts

If multiple projects are open, each needs its own server instance on a different port. The `ServerManager` uses `ServerSocket(0)` to find a free port, but needs to track per-project state.

### 4. Webapp Build for JetBrains

The webapp is a Vite SPA. For the plugin, it needs to be built into static files bundled inside the plugin JAR. Add a Gradle task that:

1. Runs `bun run build` in `packages/app` with `VITE_TARGET=jetbrains`
2. Copies the output into `packages/jetbrains-plugin/src/main/resources/webview/`

```kotlin
// In build.gradle.kts
val buildWebapp by tasks.registering(Exec::class) {
    workingDir = file("../app")
    commandLine("bun", "run", "build")
    environment("VITE_TARGET", "jetbrains")
}
tasks.named("processResources") { dependsOn(buildWebapp) }
```

### 5. Hot Reload in Dev

During development, instead of serving static files, load the webapp from the Vite dev server (`http://localhost:4444`). Toggle with a system property:

```kotlin
val webappUrl = if (System.getProperty("opencode.dev") != null)
    "http://localhost:4444"
else
    "http://localhost:$serverPort/index.html"
    // or: extract from JAR resources
```

### 6. ACP vs Direct HTTP

Should the webapp talk to OpenCode via:

- **Direct HTTP** to the spawned `opencode serve` server (current approach in desktop app) — simpler, all existing SDK code works as-is
- **ACP** via JCEF — more complex, doesn't buy you anything since you own the webapp

**Recommendation:** Direct HTTP. The ACP path is for third-party editors; since we control the plugin+webapp, direct HTTP is simpler and gives more control.

### 7. Surrounding Code Size

Injecting surrounding code on every caret move could be noisy. **Mitigation:**

- Debounce: only fire after caret is stable for 500ms
- Limit radius to 15 lines by default (configurable)
- Don't inject if the same line hasn't changed

### 8. Content Security Policy

JCEF panels may have CSP restrictions. The webapp uses SSE connections and WebSocket for real-time updates. **Mitigation:** Disable CSP for the JCEF panel (it's a trusted local app) or configure appropriate headers in the OpenCode HTTP server.

---

## Summary: Build Order

| Step | What                                                        | Effort |
| ---- | ----------------------------------------------------------- | ------ |
| 1    | ACP config in JetBrains (zero-code quick win)               | 1 day  |
| 2    | Kotlin plugin skeleton: tool window + JCEF + server spawn   | 3 days |
| 3    | Webapp `isJetBrains` flag + strip irrelevant UI             | 2 days |
| 4    | Compact JetBrains layout + session tab bar                  | 2 days |
| 5    | IDE context bridge (active file, caret, tabs)               | 3 days |
| 6    | "Add selection to context" action + context pills in webapp | 2 days |
| 7    | Theme sync                                                  | 1 day  |
| 8    | Bundling webapp into plugin JAR + Gradle build task         | 1 day  |
| 9    | Settings page (binary path, API keys)                       | 1 day  |
| 10   | Testing, polish, publish to JetBrains Marketplace           | 3 days |

**Total:** ~19 days for a solid v1 with full Cursor-like context injection.

The ACP path (step 1) can ship immediately and gives users a working OpenCode integration while the full plugin is being built.
