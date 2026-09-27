package com.calgrid.data.tasks

import com.calgrid.auth.GoogleAuthManager
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class TaskListDto(val id: String, val title: String = "", val updated: String? = null)

@Serializable
data class TaskListsResponse(val items: List<TaskListDto> = emptyList(), val nextPageToken: String? = null)

@Serializable
data class TaskDto(
    val id: String,
    val title: String = "",
    val notes: String? = null,
    val status: String = STATUS_NEEDS_ACTION,
    val due: String? = null,
    val completed: String? = null,
    val parent: String? = null,
    val position: String? = null,
    val updated: String? = null,
    val deleted: Boolean = false,
    val hidden: Boolean = false,
) {
    companion object {
        const val STATUS_NEEDS_ACTION = "needsAction"
        const val STATUS_COMPLETED = "completed"
    }
}

@Serializable
data class TasksResponse(val items: List<TaskDto> = emptyList(), val nextPageToken: String? = null)

/** Fields we write; [due] is a yyyy-MM-dd date (the Tasks API ignores the time part). */
data class TaskFields(
    val title: String,
    val notes: String?,
    val due: String?,
    val completed: Boolean,
)

/** Thin client for the Google Tasks REST API v1. */
class TasksApi(private val auth: GoogleAuthManager) {

    private val client = HttpClient(Android) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        defaultRequest { url("https://tasks.googleapis.com/tasks/v1/") }
    }

    suspend fun lists(): List<TaskListDto> = authed { token ->
        val all = mutableListOf<TaskListDto>()
        var page: String? = null
        do {
            val resp: TaskListsResponse = client.get("users/@me/lists") {
                bearerAuth(token)
                parameter("maxResults", 100)
                page?.let { parameter("pageToken", it) }
            }.body()
            all += resp.items
            page = resp.nextPageToken
        } while (page != null)
        all
    }

    suspend fun createList(title: String): TaskListDto = authed { token ->
        client.post("users/@me/lists") { jsonBody(token, titleJson(title)) }.body()
    }

    suspend fun renameList(listId: String, title: String): TaskListDto = authed { token ->
        client.patch("users/@me/lists/$listId") { jsonBody(token, titleJson(title)) }.body()
    }

    suspend fun deleteList(listId: String) = authed { token ->
        ignoreNotFound { client.delete("users/@me/lists/$listId") { bearerAuth(token) } }
    }

    /**
     * Open tasks plus tasks completed after [completedMin] (RFC 3339).
     * Hidden tasks are included because tasks completed in Google's apps become hidden.
     */
    suspend fun tasks(listId: String, completedMin: String): List<TaskDto> = authed { token ->
        val open = taskPages(listId, token) { parameter("showCompleted", false) }
        val done = taskPages(listId, token) {
            parameter("showCompleted", true)
            parameter("showHidden", true)
            parameter("completedMin", completedMin)
        }
        (open + done.filter { it.status == TaskDto.STATUS_COMPLETED })
            .distinctBy { it.id }
            .filterNot { it.deleted }
    }

    suspend fun createTask(listId: String, fields: TaskFields): TaskDto = authed { token ->
        client.post("lists/$listId/tasks") { jsonBody(token, fields.toJson()) }.body()
    }

    suspend fun updateTask(listId: String, taskId: String, fields: TaskFields): TaskDto = authed { token ->
        client.patch("lists/$listId/tasks/$taskId") { jsonBody(token, fields.toJson()) }.body()
    }

    suspend fun deleteTask(listId: String, taskId: String) = authed { token ->
        ignoreNotFound { client.delete("lists/$listId/tasks/$taskId") { bearerAuth(token) } }
    }

    private suspend fun taskPages(
        listId: String,
        token: String,
        filters: HttpRequestBuilder.() -> Unit,
    ): List<TaskDto> {
        val all = mutableListOf<TaskDto>()
        var page: String? = null
        do {
            val resp: TasksResponse = client.get("lists/$listId/tasks") {
                bearerAuth(token)
                parameter("maxResults", 100)
                filters()
                page?.let { parameter("pageToken", it) }
            }.body()
            all += resp.items
            page = resp.nextPageToken
        } while (page != null)
        return all
    }

    private fun HttpRequestBuilder.jsonBody(token: String, body: JsonObject) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun titleJson(title: String) = buildJsonObject { put("title", JsonPrimitive(title)) }

    private fun TaskFields.toJson(): JsonObject = buildJsonObject {
        put("title", JsonPrimitive(title))
        put("notes", notes?.let(::JsonPrimitive) ?: JsonNull)
        put("due", due?.let { JsonPrimitive("${it}T00:00:00.000Z") } ?: JsonNull)
        if (completed) {
            put("status", JsonPrimitive(TaskDto.STATUS_COMPLETED))
        } else {
            put("status", JsonPrimitive(TaskDto.STATUS_NEEDS_ACTION))
            put("completed", JsonNull)
        }
    }

    /** Runs [block] with an access token; retries once with a fresh token on 401. */
    private suspend fun <T> authed(block: suspend (token: String) -> T): T {
        val token = auth.accessToken()
        return try {
            block(token)
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.Unauthorized) throw e
            auth.invalidate(token)
            block(auth.accessToken())
        }
    }

    private suspend fun ignoreNotFound(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.NotFound && e.response.status != HttpStatusCode.Gone) throw e
        }
    }
}
