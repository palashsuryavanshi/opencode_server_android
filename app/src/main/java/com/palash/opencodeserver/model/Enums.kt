package com.palash.opencodeserver.model

enum class ServerStatus(val label: String) {
    STOPPED("Stopped"),
    STARTING("Starting..."),
    RUNNING("Running"),
    DEMO("Demo Mode"),
    ERROR("Error")
}

enum class ConnectionStatus(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting..."),
    CONNECTED("Connected"),
    DEMO("Demo Environment"),
    ERROR("Connection Error")
}

enum class SessionStatus(val label: String) {
    IDLE("Idle"),
    THINKING("Thinking..."),
    WORKING("Working"),
    WAITING("Waiting for user"),
    COMPLETED("Completed"),
    ERROR("Error")
}

enum class DiffType(val symbol: String) {
    MODIFIED("M"),
    ADDED("A"),
    DELETED("D")
}

enum class DiffLineType {
    HEADER,
    UNCHANGED,
    ADD,
    REMOVE
}

enum class SenderRole {
    USER,
    ASSISTANT,
    SYSTEM
}
