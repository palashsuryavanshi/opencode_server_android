package com.palash.opencodeserver.ui.navigation

sealed class Screen(val route: String) {
    // Primary Server Tabs
    object Server : Screen("server")
    object Logs : Screen("logs")
    object Terminal : Screen("terminal")
    object Endpoints : Screen("endpoints")
    object Config : Screen("config")

    // Subscreens & Workspaces
    object Projects : Screen("projects")
    object Sessions : Screen("sessions")
    data class ProjectDetail(val projectId: String) : Screen("project_detail/$projectId")
    data class FileExplorer(val projectId: String) : Screen("files/$projectId")
    data class CodeEditor(val filePath: String, val fileName: String) : Screen("editor/$filePath")
    data class ChatSession(val sessionId: String) : Screen("session/$sessionId")
    data class GitChanges(val projectId: String) : Screen("changes/$projectId")
    object Settings : Screen("settings")
}

enum class NavigationTab(val label: String, val screen: Screen) {
    SERVER("Server", Screen.Server),
    LOGS("Logs", Screen.Logs),
    TERMINAL("Terminal", Screen.Terminal),
    ENDPOINTS("Endpoints", Screen.Endpoints),
    CONFIG("Config", Screen.Config)
}
