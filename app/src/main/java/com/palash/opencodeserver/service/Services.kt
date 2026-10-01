package com.palash.opencodeserver.service

import com.palash.opencodeserver.model.*
import kotlinx.coroutines.flow.Flow

interface TerminalService {
    fun executeCommand(command: String): Flow<String>
    fun getInitialOutput(): List<String>
    fun clearHistory()
}

interface OpenCodeService {
    fun sendMessage(sessionId: String, userPrompt: String): Flow<ChatMessage>
    fun getSuggestedActions(): List<String>
}

interface ProjectService {
    fun getProjects(): Flow<List<Project>>
    fun getCurrentProject(): Flow<Project?>
    fun selectProject(projectId: String)
    fun createProject(name: String, path: String, language: String): Project
}

interface SessionService {
    fun getSessions(): Flow<List<Session>>
    fun getSessionById(sessionId: String): Session?
    fun createSession(title: String): Session
}

interface GitService {
    fun getChanges(projectId: String): Flow<List<FileDiff>>
    fun stageFile(filePath: String)
    fun stageAll()
    fun commit(message: String): Boolean
}

interface FileService {
    fun getFileTree(projectId: String): Flow<List<FileNode>>
    fun getFileContent(filePath: String): String
    fun saveFile(filePath: String, content: String): Boolean
}

interface ServerService {
    fun getServerConfig(): Flow<ServerConfig>
    fun startServer(): Flow<ServerStatus>
    fun stopServer(): Flow<ServerStatus>
    fun restartServer(): Flow<ServerStatus>
    fun updateConfig(config: ServerConfig)
}
