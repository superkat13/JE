package com.pineapple.sageos2.core

enum class SageRuntimeState {
    STOPPED,
    IDLE_WAKE,
    ACKNOWLEDGING_WAKE,
    COMMAND_LISTENING,
    THINKING_FAST,
    THINKING_DEEP,
    SPEAKING,
    ECHO_GUARD,
    FOLLOW_UP_LISTENING,
    ERROR
}
