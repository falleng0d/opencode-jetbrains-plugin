import { For, Show } from "solid-js"
import { ideContext } from "@/context/ide-bridge"
import { isJetBrains } from "@/env"
import { getFilename } from "@opencode-ai/util/path"

export function IdeContextBar() {
  if (!isJetBrains) return null

  return (
    <Show when={ideContext.activeFile}>
      {(file) => (
        <div class="flex items-center gap-2 px-2 py-1 text-11-regular text-text-weak border-t border-border-base overflow-x-auto no-scrollbar shrink-0">
          {/* Active file indicator */}
          <span class="flex items-center gap-1 shrink-0 text-text-strong">
            <span class="size-1.5 rounded-full bg-icon-success-base inline-block" />
            {getFilename(file())}
            <span class="text-text-weaker">:{ideContext.line}</span>
          </span>

          {/* Other open tabs */}
          <For each={ideContext.tabs.filter((t) => !t.active).slice(0, 5)}>
            {(tab) => (
              <button
                class="shrink-0 px-1.5 py-0.5 rounded hover:bg-surface-interactive-weak transition-colors"
                onClick={() => (window as any).__openFileInIDE?.(tab.path)}
              >
                {tab.name}
              </button>
            )}
          </For>
        </div>
      )}
    </Show>
  )
}
