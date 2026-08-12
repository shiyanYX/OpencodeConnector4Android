package com.opencode.remote.data.api.dto

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A registered slash command / skill.
 *
 * Actual API: GET /command → Array<Command>
 * Field names match the OpenCode server v1.14.x schema.
 */
@Immutable
@Serializable
data class CommandInfo(
    val name: String = "",
    val description: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val template: String = "",
    @SerialName("subtask")
    val subtask: Boolean? = null,
)

/**
 * Request body for POST /session/{id}/command — runs a slash command in a session.
 */
@Serializable
data class RunCommandRequest(
    val command: String,
    val arguments: String = "",
    val agent: String? = null,
    val model: CommandModelRef? = null,
    @SerialName("messageID")
    val messageID: String? = null,
)

@Serializable
data class CommandModelRef(
    @SerialName("providerID")
    val providerID: String? = null,
    @SerialName("modelID")
    val modelID: String? = null,
)