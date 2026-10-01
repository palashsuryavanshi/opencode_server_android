package com.palash.opencodeserver.model

data class Project(
    val id: String,
    val name: String,
    val path: String,
    val language: String,
    val gitBranch: String = "main",
    val lastModified: String = "Just now",
    val fileCount: Int = 18,
    val isConnected: Boolean = false,
    val isCurrent: Boolean = false
)

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLineNo: Int? = null,
    val newLineNo: Int? = null
)

data class FileDiff(
    val filePath: String,
    val changeType: DiffType,
    val additions: Int,
    val deletions: Int,
    val lines: List<DiffLine>
)

data class ToolActivity(
    val title: String,
    val targetPath: String,
    val details: List<String>,
    val isCompleted: Boolean = true
)

data class ChatMessage(
    val id: String,
    val role: SenderRole,
    val timestamp: String,
    val text: String,
    val toolActivity: ToolActivity? = null,
    val proposedDiffs: List<FileDiff> = emptyList()
)

data class Session(
    val id: String,
    val title: String,
    val timeAgo: String,
    val status: SessionStatus,
    val messageCount: Int,
    val filesChangedCount: Int,
    val messages: List<ChatMessage> = emptyList(),
    val isArchived: Boolean = false
)

data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<FileNode> = emptyList(),
    val content: String? = null,
    val size: String? = null
)

enum class LogLevel {
    INFO,
    DEBUG,
    WARN,
    ERROR
}

data class ServerLogEntry(
    val id: String,
    val timestamp: String,
    val level: LogLevel,
    val subsystem: String,
    val message: String
)

data class ServerEndpoint(
    val id: String,
    val method: String,
    val path: String,
    val description: String,
    val status: Int,
    val latencyMs: Int,
    val responseBody: String
)

data class ServerHealthCheck(
    val name: String,
    val isHealthy: Boolean,
    val latencyMs: Int,
    val details: String,
    val type: String
)

data class ServerConfig(
    val status: ServerStatus = ServerStatus.RUNNING,
    val host: String = "127.0.0.1",
    val port: Int = 4096,
    val autoStart: Boolean = true,
    val shell: String = "bash",
    val runtime: String = "Node.js v20.14.0",
    val packageManager: String = "npm v10.7.0",
    val openCodeVersion: String = "v1.4.2 (Daemon)",
    val workingDir: String = "~/projects/demo-project",
    val pid: Int = 18492,
    val uptimeSeconds: Long = 3742,
    val memoryUsageMb: Float = 48.6f,
    val memoryLimitMb: Float = 512f,
    val cpuUsagePercent: Float = 2.4f,
    val activeConnections: Int = 2,
    val ptySessionsCount: Int = 1,
    val wakeLockActive: Boolean = true,
    val autoRestart: Boolean = true,
    val allowLanConnections: Boolean = false,
    val logLevel: String = "INFO"
)

data class AppSettings(
    val darkMode: Boolean = true,
    val themeMode: String = "Dark", // "Dark", "Light", "System"
    val accentColor: String = "Cyan", // "Cyan", "Emerald", "Indigo", "Amber"
    val compactMode: Boolean = false,
    val fontSize: Int = 14,
    // Terminal
    val defaultShell: String = "bash",
    val terminalFontSize: Int = 13,
    val cursorStyle: String = "Block", // "Block", "Beam", "Underline"
    val confirmDestructiveCommands: Boolean = true,
    // Editor
    val editorFontSize: Int = 13,
    val lineNumbers: Boolean = true,
    val wordWrap: Boolean = false,
    val syntaxHighlighting: Boolean = true,
    val minimap: Boolean = false,
    // AI
    val aiModel: String = "opencode-deep-reasoning",
    val aiProvider: String = "Local Terminal Assistant",
    val temperature: Float = 0.2f,
    val streamResponses: Boolean = true
)
