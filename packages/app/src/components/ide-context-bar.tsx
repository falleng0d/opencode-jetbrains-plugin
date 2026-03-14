import { For, Show } from "solid-js"
import { ideContext } from "@/context/ide-bridge"
import { isJetBrains } from "@/env"
import { getFilename } from "@opencode-ai/util/path"
import { usePrompt } from "@/context/prompt"

export function IdeContextBar() {
  if (!isJetBrains) return null

  const prompt = usePrompt()

  function addFile(path: string) {
    const already = prompt.context.items().some((i) => i.type === "file" && i.path === path && !i.selection)
    if (!already) prompt.context.add({ type: "file", path })
  }

  return (
    <Show when={ideContext.activeFile}>
      {(file) => (
        <div class="flex items-center gap-2 px-2 py-1 text-11-regular text-text-weak border-t border-border-base overflow-x-auto no-scrollbar shrink-0">
          {/* Active file — click to add to context */}
          <button
            class="flex items-center gap-1 shrink-0 text-text-strong hover:text-text-strong hover:bg-surface-interactive-weak rounded px-1 transition-colors"
            title="Add to context"
            onClick={() => addFile(file())}
          >
            <span class="size-1.5 rounded-full bg-icon-success-base inline-block" />
            {getFilename(file())}
            <span class="text-text-weaker">:{ideContext.line}</span>
          </button>

          {/* Other open tabs — click to add to context */}
          <For each={ideContext.tabs.filter((t) => !t.active).slice(0, 5)}>
            {(tab) => (
              <button
                class="shrink-0 px-1.5 py-0.5 rounded hover:bg-surface-interactive-weak transition-colors"
                title="Add to context"
                onClick={() => addFile(tab.path)}
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
