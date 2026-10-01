package com.palash.opencodeserver.data

import com.palash.opencodeserver.model.*

object MockData {

    val mockProjects = listOf(
        Project(
            id = "proj-1",
            name = "demo-project",
            path = "~/projects/demo-project",
            language = "JavaScript / TypeScript",
            gitBranch = "main",
            lastModified = "Just now",
            fileCount = 24,
            isConnected = true,
            isCurrent = true
        ),
        Project(
            id = "proj-2",
            name = "mobile-app",
            path = "~/projects/mobile-app",
            language = "Kotlin / Compose",
            gitBranch = "feature/agent-ui",
            lastModified = "2 hours ago",
            fileCount = 42,
            isConnected = false,
            isCurrent = false
        ),
        Project(
            id = "proj-3",
            name = "website",
            path = "~/projects/website",
            language = "React / Next.js",
            gitBranch = "master",
            lastModified = "Yesterday",
            fileCount = 19,
            isConnected = false,
            isCurrent = false
        ),
        Project(
            id = "proj-4",
            name = "api-microservice",
            path = "~/projects/api-microservice",
            language = "Go / gRPC",
            gitBranch = "dev",
            lastModified = "Sep 26",
            fileCount = 31,
            isConnected = false,
            isCurrent = false
        )
    )

    val mockTerminalOutput = listOf(
        "$ opencodectl status",
        "● opencode-server.service - OpenCode Local Terminal Daemon",
        "     Loaded: loaded (/etc/opencode/server.conf; enabled)",
        "     Active: active (running) since Oct 01 01:10:02 UTC (uptime: 1h 2m)",
        "   Main PID: 18492 (node /opt/opencode/server.js)",
        "     Socket: 127.0.0.1:4096 (Loopback only)",
        "        PTY: /dev/pts/3 (xterm-256color)",
        "     Memory: 48.6M (Limit: 512.0M, Quota: 9.5%)",
        "        CPU: 2.4% (2 active worker threads)",
        "",
        "$ opencodectl health",
        "[✓] HTTP Listener:  127.0.0.1:4096 (latency: 0.8ms)",
        "[✓] PTY Subsystem:  /system/bin/sh (latency: 1.4ms)",
        "[✓] WebSocket PTY:  ws://127.0.0.1:4096/ws/pty (active: 1)",
        "[✓] OS Wakelock:    PARTIAL_WAKE_LOCK held",
        "All subsystems operating normally.",
        "",
        "$ opencodectl endpoints",
        "  GET  /api/status   -> 200 OK",
        "  GET  /api/health   -> 200 OK",
        "  WS   /ws/pty       -> 101 Switching Protocols",
        "  POST /api/restart  -> 200 OK",
        "",
        "$ "
    )

    val mockGitChanges = listOf(
        FileDiff(
            filePath = "src/App.tsx",
            changeType = DiffType.MODIFIED,
            additions = 14,
            deletions = 4,
            lines = listOf(
                DiffLine(DiffLineType.HEADER, "@@ -8,6 +8,16 @@ export default function App() {"),
                DiffLine(DiffLineType.UNCHANGED, "   const [count, setCount] = useState(0);", 8, 8),
                DiffLine(DiffLineType.REMOVE, "-  const theme = \"light\";", 9, null),
                DiffLine(DiffLineType.ADD, "+  const [theme, setTheme] = useState(\"dark\");", null, 9),
                DiffLine(DiffLineType.ADD, "+  const toggleTheme = () => setTheme(t => t === 'dark' ? 'light' : 'dark');", null, 10),
                DiffLine(DiffLineType.UNCHANGED, "   return (", 10, 11),
                DiffLine(DiffLineType.UNCHANGED, "     <div className={`app \${theme}`}>", 11, 12),
                DiffLine(DiffLineType.REMOVE, "-      <header className=\"light-header\">", 12, null),
                DiffLine(DiffLineType.ADD, "+      <header className={`header \${theme}`}>", null, 13),
                DiffLine(DiffLineType.ADD, "+        <ThemeToggle theme={theme} onToggle={toggleTheme} />", null, 14),
                DiffLine(DiffLineType.UNCHANGED, "         <h1>OpenCode Workspace</h1>", 13, 15)
            )
        ),
        FileDiff(
            filePath = "src/components/Settings.tsx",
            changeType = DiffType.MODIFIED,
            additions = 22,
            deletions = 2,
            lines = listOf(
                DiffLine(DiffLineType.HEADER, "@@ -15,4 +15,24 @@ export const SettingsView = () => {"),
                DiffLine(DiffLineType.UNCHANGED, "   return (", 15, 15),
                DiffLine(DiffLineType.UNCHANGED, "     <section className=\"settings-panel\">", 16, 16),
                DiffLine(DiffLineType.ADD, "+      <div className=\"setting-item\">", null, 17),
                DiffLine(DiffLineType.ADD, "+        <span>Dark Theme Preference</span>", null, 18),
                DiffLine(DiffLineType.ADD, "+        <Switch checked={isDarkMode} onChange={toggleTheme} />", null, 19),
                DiffLine(DiffLineType.ADD, "+      </div>", null, 20),
                DiffLine(DiffLineType.UNCHANGED, "     </section>", 17, 21)
            )
        ),
        FileDiff(
            filePath = "src/components/MobileMenu.tsx",
            changeType = DiffType.ADDED,
            additions = 38,
            deletions = 0,
            lines = listOf(
                DiffLine(DiffLineType.HEADER, "@@ -0,0 +1,15 @@"),
                DiffLine(DiffLineType.ADD, "+import React from 'react';", null, 1),
                DiffLine(DiffLineType.ADD, "+export const MobileMenu = ({ isOpen, onClose }: Props) => {", null, 2),
                DiffLine(DiffLineType.ADD, "+  if (!isOpen) return null;", null, 3),
                DiffLine(DiffLineType.ADD, "+  return (", null, 4),
                DiffLine(DiffLineType.ADD, "+    <nav className=\"mobile-drawer\">", null, 5),
                DiffLine(DiffLineType.ADD, "+      <button onClick={onClose}>Close</button>", null, 6),
                DiffLine(DiffLineType.ADD, "+    </nav>", null, 7),
                DiffLine(DiffLineType.ADD, "+  );", null, 8),
                DiffLine(DiffLineType.ADD, "+};", null, 9)
            )
        ),
        FileDiff(
            filePath = "package.json",
            changeType = DiffType.MODIFIED,
            additions = 2,
            deletions = 1,
            lines = listOf(
                DiffLine(DiffLineType.HEADER, "@@ -22,3 +22,4 @@"),
                DiffLine(DiffLineType.UNCHANGED, "   \"dependencies\": {", 22, 22),
                DiffLine(DiffLineType.REMOVE, "-    \"lucide-react\": \"^0.290.0\"", 23, null),
                DiffLine(DiffLineType.ADD, "+    \"lucide-react\": \"^0.400.0\",", null, 23),
                DiffLine(DiffLineType.ADD, "+    \"clsx\": \"^2.1.0\"", null, 24)
            )
        )
    )

    val mockChatMessages = listOf(
        ChatMessage(
            id = "msg-1",
            role = SenderRole.USER,
            timestamp = "14:30",
            text = "Add a dark mode toggle to the settings screen and make sure the preference persists."
        ),
        ChatMessage(
            id = "msg-2",
            role = SenderRole.ASSISTANT,
            timestamp = "14:31",
            text = "I will inspect the project structure and identify the appropriate settings components.",
            toolActivity = ToolActivity(
                title = "Inspecting project",
                targetPath = "src/components/Settings.tsx",
                details = listOf(
                    "Scanned component tree in src/components",
                    "Located theme state provider in src/App.tsx",
                    "Checked localStorage utility helpers"
                ),
                isCompleted = true
            )
        ),
        ChatMessage(
            id = "msg-3",
            role = SenderRole.ASSISTANT,
            timestamp = "14:31",
            text = "I have identified the files and prepared the modifications for your review.",
            toolActivity = ToolActivity(
                title = "Changes proposed",
                targetPath = "src/App.tsx & Settings.tsx",
                details = listOf(
                    "+ ThemeToggle component",
                    "+ theme state & handler",
                    "+ persistent preference in local storage"
                ),
                isCompleted = true
            ),
            proposedDiffs = mockGitChanges.take(2)
        ),
        ChatMessage(
            id = "msg-4",
            role = SenderRole.ASSISTANT,
            timestamp = "14:32",
            text = "I have prepared the changes across 2 files. Review the diffs below before applying them to your terminal workspace."
        )
    )

    val mockSessions = listOf(
        Session(
            id = "sess-1",
            title = "Frontend redesign",
            timeAgo = "Today · 14:32",
            status = SessionStatus.IDLE,
            messageCount = 24,
            filesChangedCount = 7,
            messages = mockChatMessages,
            isArchived = false
        ),
        Session(
            id = "sess-2",
            title = "API integration",
            timeAgo = "Yesterday · 18:15",
            status = SessionStatus.COMPLETED,
            messageCount = 18,
            filesChangedCount = 3,
            messages = listOf(
                ChatMessage("m-21", SenderRole.USER, "18:00", "Connect endpoints for project sync"),
                ChatMessage("m-22", SenderRole.ASSISTANT, "18:05", "Created REST clients and DTO models.")
            ),
            isArchived = false
        ),
        Session(
            id = "sess-3",
            title = "Authentication flow",
            timeAgo = "Sep 28 · 11:40",
            status = SessionStatus.WAITING,
            messageCount = 32,
            filesChangedCount = 5,
            messages = listOf(
                ChatMessage("m-31", SenderRole.USER, "11:20", "Implement JWT token refresh mechanism"),
                ChatMessage("m-32", SenderRole.ASSISTANT, "11:25", "Added Axios interceptor for 401 handling.")
            ),
            isArchived = false
        ),
        Session(
            id = "sess-4",
            title = "Performance profiling",
            timeAgo = "Sep 24 · 09:12",
            status = SessionStatus.COMPLETED,
            messageCount = 12,
            filesChangedCount = 2,
            messages = emptyList(),
            isArchived = true
        )
    )

    val mockFileTree = listOf(
        FileNode(
            name = "src",
            path = "src",
            isDirectory = true,
            children = listOf(
                FileNode(
                    name = "App.tsx",
                    path = "src/App.tsx",
                    isDirectory = false,
                    size = "2.4 KB",
                    content = """import React, { useState } from "react";
import { ThemeToggle } from "./components/ThemeToggle";
import { SettingsView } from "./components/Settings";

export default function App() {
  const [count, setCount] = useState(0);
  const [theme, setTheme] = useState<"dark" | "light">("dark");

  const toggleTheme = () => {
    setTheme(t => (t === "dark" ? "light" : "dark"));
  };

  return (
    <main className={`workspace ${"$"}{theme}`}>
      <header className="terminal-header">
        <h1>OpenCode Workspace</h1>
        <ThemeToggle theme={theme} onToggle={toggleTheme} />
      </header>
      <section className="dashboard">
        <p>Active project: demo-project</p>
        <button onClick={() => setCount(c => c + 1)}>
          Count: {count}
        </button>
      </section>
      <SettingsView theme={theme} onToggle={toggleTheme} />
    </main>
  );
}"""
                ),
                FileNode(
                    name = "main.tsx",
                    path = "src/main.tsx",
                    isDirectory = false,
                    size = "840 B",
                    content = """import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./index.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);"""
                ),
                FileNode(
                    name = "index.css",
                    path = "src/index.css",
                    isDirectory = false,
                    size = "1.1 KB",
                    content = """:root {
  --bg-color: #0f172a;
  --text-color: #f8fafc;
  --accent: #38bdf8;
}

body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background-color: var(--bg-color);
  color: var(--text-color);
}"""
                )
            )
        ),
        FileNode(
            name = "components",
            path = "components",
            isDirectory = true,
            children = listOf(
                FileNode(
                    name = "Settings.tsx",
                    path = "components/Settings.tsx",
                    isDirectory = false,
                    size = "3.1 KB",
                    content = """import React from "react";

interface SettingsProps {
  theme: "dark" | "light";
  onToggle: () => void;
}

export const SettingsView: React.FC<SettingsProps> = ({ theme, onToggle }) => {
  return (
    <section className="settings-panel">
      <h2>Editor & Terminal Settings</h2>
      <div className="setting-row">
        <span>Dark Theme Mode</span>
        <button onClick={onToggle}>
          Current: {theme.toUpperCase()}
        </button>
      </div>
      <div className="setting-row">
        <span>Terminal Shell</span>
        <code>/bin/bash</code>
      </div>
    </section>
  );
};"""
                ),
                FileNode(
                    name = "Nav.tsx",
                    path = "components/Nav.tsx",
                    isDirectory = false,
                    size = "1.8 KB",
                    content = """import React from "react";

export const Nav = () => {
  return (
    <nav className="top-navigation">
      <div className="brand">OpenCode Mobile</div>
      <div className="status-indicator">Connected</div>
    </nav>
  );
};"""
                ),
                FileNode(
                    name = "MobileMenu.tsx",
                    path = "components/MobileMenu.tsx",
                    isDirectory = false,
                    size = "1.5 KB",
                    content = """import React from "react";

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

export const MobileMenu: React.FC<Props> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;
  return (
    <div className="drawer-overlay" onClick={onClose}>
      <aside className="drawer-panel" onClick={e => e.stopPropagation()}>
        <h3>Navigation Drawer</h3>
        <ul>
          <li>Dashboard</li>
          <li>Terminal Console</li>
          <li>File Explorer</li>
          <li>Git Diff Viewer</li>
        </ul>
      </aside>
    </div>
  );
};"""
                )
            )
        ),
        FileNode(
            name = "public",
            path = "public",
            isDirectory = true,
            children = listOf(
                FileNode(
                    name = "favicon.svg",
                    path = "public/favicon.svg",
                    isDirectory = false,
                    size = "450 B",
                    content = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\" fill=\"#38bdf8\"/></svg>"
                ),
                FileNode(
                    name = "manifest.json",
                    path = "public/manifest.json",
                    isDirectory = false,
                    size = "320 B",
                    content = "{\n  \"name\": \"OpenCode Workspace\",\n  \"short_name\": \"OpenCode\",\n  \"display\": \"standalone\"\n}"
                )
            )
        ),
        FileNode(
            name = "package.json",
            path = "package.json",
            isDirectory = false,
            size = "1.2 KB",
            content = """{
  "name": "demo-project",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "lint": "eslint . --ext ts,tsx",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "lucide-react": "^0.400.0",
    "clsx": "^2.1.0"
  },
  "devDependencies": {
    "@types/react": "^18.3.3",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.0",
    "typescript": "^5.4.5",
    "vite": "^5.2.11"
  }
}"""
        ),
        FileNode(
            name = "README.md",
            path = "README.md",
            isDirectory = false,
            size = "624 B",
            content = """# Demo Project
Mobile-friendly TypeScript & React project running in OpenCode Android Terminal.

## Getting Started
Run in terminal:
```bash
npm install
npm run dev
```

Server connects to `http://127.0.0.1:4096`."""
        ),
        FileNode(
            name = "vite.config.ts",
            path = "vite.config.ts",
            isDirectory = false,
            size = "385 B",
            content = """import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    host: true
  }
});"""
        )
    )

    val mockServerLogs = listOf(
        ServerLogEntry("log-1", "01:10:02", LogLevel.INFO, "daemon", "OpenCode daemon v1.4.2 starting on Android aarch64 (Linux 5.15)"),
        ServerLogEntry("log-2", "01:10:02", LogLevel.INFO, "pty", "Spawning PTY pseudo-terminal process via /bin/sh (PID: 18492)"),
        ServerLogEntry("log-3", "01:10:03", LogLevel.DEBUG, "bridge", "Android termios flags configured: ECHO=1, ICANON=0, OPOST=1"),
        ServerLogEntry("log-4", "01:10:03", LogLevel.INFO, "http", "HTTP server listening on http://127.0.0.1:4096"),
        ServerLogEntry("log-5", "01:10:03", LogLevel.INFO, "ws", "WebSocket terminal bridge bound to ws://127.0.0.1:4096/ws/pty"),
        ServerLogEntry("log-6", "01:10:04", LogLevel.INFO, "daemon", "Acquired Android PARTIAL_WAKE_LOCK (tag: opencode:daemon_lock)"),
        ServerLogEntry("log-7", "01:10:04", LogLevel.INFO, "daemon", "Local workspace registered: ~/projects/demo-project"),
        ServerLogEntry("log-8", "01:10:05", LogLevel.INFO, "daemon", "Server daemon ready. Memory: 48.6 MB | CPU: 1.2%"),
        ServerLogEntry("log-9", "01:12:14", LogLevel.DEBUG, "http", "GET /api/health HTTP/1.1 from 127.0.0.1 -> 200 OK (0.8ms)"),
        ServerLogEntry("log-10", "01:12:30", LogLevel.INFO, "ws", "Client handshake accepted on /ws/pty from 127.0.0.1:49210"),
        ServerLogEntry("log-11", "01:13:02", LogLevel.DEBUG, "pty", "PTY stream buffer write: 256 bytes dispatched"),
        ServerLogEntry("log-12", "01:14:10", LogLevel.WARN, "daemon", "Memory pressure warning: high cache usage in AST index (cleared 12 MB)"),
        ServerLogEntry("log-13", "01:14:25", LogLevel.INFO, "http", "POST /api/session/inspect -> 200 OK (3.2ms)"),
        ServerLogEntry("log-14", "01:14:36", LogLevel.INFO, "daemon", "Health check OK: loopback:4096 latency 1ms, active sockets: 2")
    )

    val mockEndpoints = listOf(
        ServerEndpoint(
            id = "ep-1",
            method = "GET",
            path = "/api/status",
            description = "Returns current server daemon status, PID, uptime and metrics",
            status = 200,
            latencyMs = 1,
            responseBody = """{
  "status": "running",
  "version": "v1.4.2",
  "pid": 18492,
  "uptime": 3742,
  "bind": "127.0.0.1:4096",
  "clients": 2,
  "ptySessions": 1,
  "memory": {
    "rss": "48.6 MB",
    "heapTotal": "32.1 MB",
    "heapUsed": "24.5 MB"
  }
}"""
        ),
        ServerEndpoint(
            id = "ep-2",
            method = "GET",
            path = "/api/health",
            description = "Liveness probe for local terminal emulator and UI bridge",
            status = 200,
            latencyMs = 1,
            responseBody = """{
  "healthy": true,
  "timestamp": "2026-10-01T01:14:36Z",
  "subsystems": {
    "pty": "OK",
    "http": "OK",
    "loopback": "OK",
    "wakelock": "ACTIVE"
  }
}"""
        ),
        ServerEndpoint(
            id = "ep-3",
            method = "WS",
            path = "/ws/pty",
            description = "Bidirectional WebSocket terminal emulator stream bridge",
            status = 101,
            latencyMs = 2,
            responseBody = """{
  "protocol": "websocket",
  "encoding": "utf-8",
  "terminal": "xterm-256color",
  "cols": 80,
  "rows": 24,
  "buffer": "active"
}"""
        ),
        ServerEndpoint(
            id = "ep-4",
            method = "GET",
            path = "/api/metrics",
            description = "Detailed process telemetry, CPU ticks and memory gauges",
            status = 200,
            latencyMs = 3,
            responseBody = """{
  "cpuPercent": 2.4,
  "memoryMb": 48.6,
  "maxMemoryMb": 512.0,
  "threadCount": 6,
  "eventLoopLagMs": 0.4,
  "activeHandles": 14
}"""
        ),
        ServerEndpoint(
            id = "ep-5",
            method = "POST",
            path = "/api/server/restart",
            description = "Trigger hot restart of OpenCode daemon worker threads",
            status = 200,
            latencyMs = 12,
            responseBody = """{
  "success": true,
  "message": "Daemon restarted in 280ms",
  "newPid": 18510
}"""
        ),
        ServerEndpoint(
            id = "ep-6",
            method = "GET",
            path = "/api/config",
            description = "Retrieve active server daemon network configuration",
            status = 200,
            latencyMs = 2,
            responseBody = """{
  "host": "127.0.0.1",
  "port": 4096,
  "autoStart": true,
  "shell": "bash",
  "allowLan": false,
  "logLevel": "INFO",
  "wakeLock": true
}"""
        )
    )

    val mockHealthChecks = listOf(
        ServerHealthCheck("HTTP Daemon Loopback", true, 1, "Bound to 127.0.0.1:4096", "Network"),
        ServerHealthCheck("PTY Terminal Bridge", true, 2, "pts/3 pseudo-terminal alive", "Process"),
        ServerHealthCheck("Subshell Execution", true, 1, "/bin/bash response in 1.4ms", "Shell"),
        ServerHealthCheck("Android Wakelock", true, 0, "PARTIAL_WAKE_LOCK held", "System"),
        ServerHealthCheck("Memory Governor", true, 3, "48.6MB / 512MB (9.5% quota)", "Resource")
    )

    val mockServerConfig = ServerConfig(
        status = ServerStatus.RUNNING,
        host = "127.0.0.1",
        port = 4096,
        autoStart = true,
        shell = "bash",
        runtime = "Node.js v20.14.0",
        packageManager = "npm v10.7.0",
        openCodeVersion = "v1.4.2 (Local Daemon)",
        workingDir = "~/projects/demo-project",
        pid = 18492,
        uptimeSeconds = 3742,
        memoryUsageMb = 48.6f,
        memoryLimitMb = 512f,
        cpuUsagePercent = 2.4f,
        activeConnections = 2,
        ptySessionsCount = 1,
        wakeLockActive = true,
        autoRestart = true,
        allowLanConnections = false,
        logLevel = "INFO"
    )

    val mockSettings = AppSettings()
}
