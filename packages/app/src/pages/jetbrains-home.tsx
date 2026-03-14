import { createMemo, onMount } from "solid-js"
import { useNavigate } from "@solidjs/router"
import { base64Encode } from "@opencode-ai/util/encode"
import { useGlobalSDK } from "@/context/global-sdk"
import { useGlobalSync } from "@/context/global-sync"
import { config } from "@/env"

export default function JetBrainsHome() {
  const sdk = useGlobalSDK()
  const sync = useGlobalSync()
  const nav = useNavigate()

  const dir = createMemo(() => config.projectDir ?? "")

  onMount(() => {
    const d = dir()
    if (!d) return

    const slug = base64Encode(d)

    setTimeout(async () => {
      const [store] = sync.child(d, { bootstrap: true })
      await new Promise<void>((resolve) => setTimeout(resolve, 800))

      const live = store.session.filter((s) => !s.time.archived)
      if (live.length > 0) {
        const latest = live
          .slice()
          .sort((a, b) => (b.time.updated ?? b.time.created) - (a.time.updated ?? a.time.created))[0]
        nav(`/${slug}/session/${latest.id}`, { replace: true })
        return
      }

      const res = await sdk.client.session.create({ directory: d })
      const id = res.data?.id
      if (id) nav(`/${slug}/session/${id}`, { replace: true })
    }, 0)
  })

  return <div class="h-full w-full" />
}
