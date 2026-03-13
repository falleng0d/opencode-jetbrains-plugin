import { createStore } from "solid-js/store"

// ── Types ─────────────────────────────────────────────────────────────────────

export type IdeTab = {
  path: string
  name: string
  active: boolean
  modified: boolean
}

export type IdeSelection = {
  id: string
  path: string
  name: string
  startLine: number
  endLine: number
  code: string
  language: string
}

export type IdeContext = {
  ready: boolean
  projectDir: string | null
  projectName: string | null
  branch: string | null
  activeFile: string | null
  language: string | null
  line: number
  col: number
  lineCount: number
  surroundingCode: string | null
  tabs: IdeTab[]
  selections: IdeSelection[]
  theme: {
    dark: boolean
    bg: string
    fg: string
    accent: string
    border: string
  } | null
}

// ── Store ─────────────────────────────────────────────────────────────────────

const [ctx, setCtx] = createStore<IdeContext>({
  ready: false,
  projectDir: null,
  projectName: null,
  branch: null,
  activeFile: null,
  language: null,
  line: 0,
  col: 0,
  lineCount: 0,
  surroundingCode: null,
  tabs: [],
  selections: [],
  theme: null,
})

export const ideContext = ctx

export function removeSelection(id: string) {
  setCtx("selections", (s) => s.filter((x) => x.id !== id))
}

export function clearSelections() {
  setCtx("selections", [])
}

// ── Init ──────────────────────────────────────────────────────────────────────

export function initIdeBridge() {
  const cfg = (window as any).__OPENCODE_CONFIG__
  if (cfg?.target !== "jetbrains") return

  if (cfg.projectDir) setCtx("projectDir", cfg.projectDir)
  if (cfg.projectName) setCtx("projectName", cfg.projectName)

  // Tell the IDE we're ready (triggers sendCurrentState)
  setTimeout(() => {
    ;(window as any).__sendToIDE?.(JSON.stringify({ type: "ready" }))
    setCtx("ready", true)
  }, 100)

  window.addEventListener("opencode:ide", (raw) => {
    const e = (raw as CustomEvent).detail
    switch (e.type) {
      case "activeEditor":
        setCtx({
          activeFile: e.path,
          language: e.language,
          line: e.line,
          col: e.col,
          lineCount: e.lineCount,
          surroundingCode: e.surroundingCode,
        })
        break

      case "openTabs":
        setCtx("tabs", e.tabs)
        break

      case "selectionAdded":
        setCtx("selections", (s) => {
          if (s.some((x) => x.id === e.id)) return s
          return [
            ...s,
            {
              id: e.id,
              path: e.path,
              name: e.name,
              startLine: e.startLine,
              endLine: e.endLine,
              code: e.code,
              language: e.language,
            },
          ]
        })
        break

      case "theme":
        setCtx("theme", { dark: e.dark, bg: e.bg, fg: e.fg, accent: e.accent, border: e.border })
        applyTheme(e)
        break

      case "projectInfo":
        setCtx({ projectDir: e.path, projectName: e.name, branch: e.branch ?? null })
        break
    }
  })
}

function applyTheme(t: { dark: boolean; bg: string; fg: string; accent: string; border: string }) {
  const root = document.documentElement
  root.classList.toggle("dark", t.dark)
  root.style.setProperty("--ide-bg", t.bg)
  root.style.setProperty("--ide-fg", t.fg)
  root.style.setProperty("--ide-accent", t.accent)
  root.style.setProperty("--ide-border", t.border)
}

// Expose for webapp to call (e.g., open file click)
;(window as any).__openFileInIDE = (path: string, line?: number) => {
  ;(window as any).__sendToIDE?.(JSON.stringify({ type: "openFile", path, line }))
}
