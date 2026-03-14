import { ParentProps, createMemo, For, Show, onMount, createEffect, on } from "solid-js"
import { useNavigate, useParams, useLocation } from "@solidjs/router"
import { useGlobalSync } from "@/context/global-sync"
import { useGlobalSDK } from "@/context/global-sdk"
import { ideContext } from "@/context/ide-bridge"
import { base64Encode } from "@opencode-ai/util/encode"
import { decode64 } from "@/utils/base64"
import { config } from "@/env"

export default function JetBrainsLayout(props: ParentProps) {
  const globalSync = useGlobalSync()
  const globalSDK = useGlobalSDK()
  const navigate = useNavigate()
  const params = useParams()
  const location = useLocation()

  const dir = createMemo(() => config.projectDir ?? decode64(params.dir) ?? "")

  const sessions = createMemo(() => {
    const d = dir()
    if (!d) return []
    const [store] = globalSync.child(d, { bootstrap: true })
    return store.session.filter((s) => !s.time.archived).slice(0, 20)
  })

  async function newSession() {
    const d = dir()
    if (!d) return
    const res = await globalSDK.client.session.create({ directory: d })
    const id = res.data?.id
    if (!id) return
    navigate(`/${base64Encode(d)}/session/${id}`)
  }

  // Auto-navigate to the project dir on mount, then into the most recent
  // session (or create one if none exist). Skip if already on a session route.
  onMount(() => {
    const d = config.projectDir
    if (!d) return
    if (location.pathname !== "/") return

    const slug = base64Encode(d)

    // Wait a tick for globalSync to bootstrap the directory store
    setTimeout(async () => {
      const [store] = globalSync.child(d, { bootstrap: true })

      // Give the store a moment to load sessions from the server
      await new Promise<void>((resolve) => setTimeout(resolve, 800))

      const live = store.session.filter((s) => !s.time.archived)
      if (live.length > 0) {
        // Navigate to the most recent session
        const latest = live
          .slice()
          .sort((a, b) => (b.time.updated ?? b.time.created) - (a.time.updated ?? a.time.created))[0]
        navigate(`/${slug}/session/${latest.id}`, { replace: true })
      } else {
        // No sessions yet — create one
        const res = await globalSDK.client.session.create({ directory: d })
        const id = res.data?.id
        if (id) navigate(`/${slug}/session/${id}`, { replace: true })
      }
    }, 0)
  })

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
