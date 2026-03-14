import { ParentProps, createMemo, For, Show } from "solid-js"
import { useNavigate, useParams } from "@solidjs/router"
import { useGlobalSync } from "@/context/global-sync"
import { ideContext } from "@/context/ide-bridge"
import { base64Encode } from "@opencode-ai/util/encode"
import { decode64 } from "@/utils/base64"
import { config } from "@/env"

export default function JetBrainsLayout(props: ParentProps) {
  const globalSync = useGlobalSync()
  const navigate = useNavigate()
  const params = useParams()

  const dir = createMemo(() => config.projectDir ?? decode64(params.dir) ?? "")

  const sessions = createMemo(() => {
    const d = dir()
    if (!d) return []
    const [store] = globalSync.child(d, { bootstrap: true })
    return store.session.filter((s) => !s.time.archived).slice(0, 20)
  })

  function newSession() {
    const d = dir()
    if (!d) return
    navigate(`/${base64Encode(d)}/session`)
  }

  return (
    <div class="flex flex-col h-screen overflow-hidden bg-background-base">
      {/* Compact header */}
      <div class="flex items-center gap-2 px-3 h-9 border-b border-border-base shrink-0">
        <span class="text-12-medium text-text-strong truncate">
          {ideContext.projectName ?? config.projectName ?? "OpenCode"}
        </span>
        <Show when={ideContext.branch}>
          <span class="text-12-regular text-text-weak">{ideContext.branch}</span>
        </Show>
      </div>

      {/* Session tab bar */}
      <div class="flex items-center gap-1 px-2 h-8 border-b border-border-base shrink-0 overflow-x-auto no-scrollbar">
        <For each={sessions()}>
          {(session) => {
            const slug = base64Encode(session.directory)
            const active = () => session.id === params.id
            return (
              <button
                class="shrink-0 px-2 py-0.5 rounded text-11-regular whitespace-nowrap transition-colors"
                classList={{
                  "bg-surface-interactive-hover text-text-strong": active(),
                  "text-text-weak hover:text-text-base hover:bg-surface-interactive-weak": !active(),
                }}
                onClick={() => navigate(`/${slug}/session/${session.id}`)}
              >
                {session.title ?? "New Session"}
              </button>
            )
          }}
        </For>
        <button class="shrink-0 px-2 py-0.5 text-11-regular text-text-weak hover:text-text-base" onClick={newSession}>
          +
        </button>
      </div>

      {/* Page content */}
      <div class="flex-1 min-h-0 overflow-hidden">{props.children}</div>
    </div>
  )
}
