export const config = (window as any).__OPENCODE_CONFIG__ ?? {
  serverUrl: import.meta.env.VITE_SERVER_URL ?? "http://localhost:4096",
  projectDir: null as string | null,
  projectName: null as string | null,
  target: (import.meta.env.VITE_TARGET as string) ?? "web",
}

export const isJetBrains = config.target === "jetbrains"
