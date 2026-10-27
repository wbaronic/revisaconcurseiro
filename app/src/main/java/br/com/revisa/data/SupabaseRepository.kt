package br.com.revisa.data

import android.content.Context
import android.util.Base64
import br.com.revisa.BuildConfig
import br.com.revisa.core.Grade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Supabase Auth and PostgREST client. RLS and RPCs enforce access and scoring;
 * the public application key is never used as the user's Bearer token.
 */
class SupabaseRepository(
    context: Context,
    private val projectUrl: String = BuildConfig.SUPABASE_URL,
    private val publicKey: String = BuildConfig.SUPABASE_ANON_KEY,
) : RevisaRepository {
    private val base = projectUrl.trim().trimEnd('/')
    private val store = SecureSessionStore(context, base)
    private val authMutex = Mutex()
    @Volatile private var session: AuthSession? = null
    @Volatile private var currentProfile: Profile? = null
    override val profile get() = currentProfile
    override val isDemo = false
    private val publicKeyAllowed = publicKey.startsWith("sb_publishable_") || runCatching {
        val claims = String(Base64.decode(publicKey.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        JSONObject(claims).optString("role") == "anon"
    }.getOrDefault(false)
    override val configured: Boolean get() = base.startsWith("https://") && publicKeyAllowed
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        if (!configured) return@withContext false
        authMutex.withLock { session = store.load() }
        if (session == null) return@withContext false
        try {
            val current = accessSession()
            currentProfile = loadProfile(current.userId)
            true
        } catch (e: SessionExpired) {
            false
        }
    }

    override suspend fun login(email: String, password: String): Unit = withContext(Dispatchers.IO) {
        requireConfigured()
        authMutex.withLock {
            val result = raw("POST", "auth/v1/token", mapOf("grant_type" to "password"),
                JSONObject().put("email", email).put("password", password))
            acceptSession(JSONObject(result.body))
        }
        currentProfile = loadProfile(requireSession().userId)
    }

    override suspend fun register(email: String, password: String, username: String, displayName: String): Boolean =
        withContext(Dispatchers.IO) {
            requireConfigured()
            val loggedIn = authMutex.withLock {
                val body = JSONObject().put("email", email).put("password", password)
                    .put("data", JSONObject().put("username", username).put("display_name", displayName))
                val result = JSONObject(raw("POST", "auth/v1/signup", body = body).body)
                if (result.optString("access_token").isNotBlank()) {
                    acceptSession(result)
                    true
                } else false
            }
            if (loggedIn) currentProfile = loadProfile(requireSession().userId)
            loggedIn
        }

    override suspend fun logout(): Unit = withContext(Dispatchers.IO) {
        authMutex.withLock {
            val previous = session
            try {
                if (previous != null && configured) {
                    raw("POST", "auth/v1/logout", mapOf("scope" to "local"), token = previous.accessToken)
                }
            } catch (_: IOException) {
                // Device signout is still possible offline. No token is retained locally.
            } finally {
                store.clear()
                session = null
                currentProfile = null
            }
        }
    }

    override suspend fun library(): Library = withContext(Dispatchers.IO) {
        val owner = accessSession().userId
        coroutineScope {
            val decks = async { allRows("decks", mapOf(
                "select" to "id,owner_id,exam,subject,topic,title,is_public,cards(count),deck_links(exam,subject,topic)",
                "owner_id" to "eq.$owner", "order" to "exam.asc,subject.asc,topic.asc,title.asc,id.asc"
            )).flatMap(::linkedDecks) }
            val reviews = async { allRows("deck_reviews", mapOf(
                "select" to "deck_id,next_review_at,review_count", "user_id" to "eq.$owner",
                "order" to "next_review_at.asc,deck_id.asc"
            )).map(::review) }
            val cards = async { allRows("card_progress", mapOf(
                "select" to "card_id,score,last_answered_at,cards!inner(id,deck_id,front,back,position)",
                "user_id" to "eq.$owner", "score" to "gt.0",
                "order" to "score.desc,last_answered_at.asc,card_id.asc"
            )).map { progress -> card(progress.getJSONObject("cards"), progress.getInt("score")) } }
            val refreshedProfile = async { loadProfile(owner) }
            val result = Library(decks.await(), reviews.await(), cards.await())
            currentProfile = refreshedProfile.await()
            result
        }
    }

    override suspend fun cards(deckId: String): List<Flashcard> = withContext(Dispatchers.IO) {
        val owner = accessSession().userId
        allRows("cards", mapOf(
            "select" to "id,deck_id,front,back,position,card_progress(score)",
            "deck_id" to "eq.$deckId", "card_progress.user_id" to "eq.$owner",
            "order" to "position.asc,id.asc"
        )).map { row ->
            val progress = row.optJSONArray("card_progress")?.optJSONObject(0)
                ?: row.optJSONObject("card_progress")
            card(row, progress?.optInt("score") ?: 0)
        }
    }

    override suspend fun beginDeck(deckId: String): DeckReview = withContext(Dispatchers.IO) {
        review(JSONObject(rpc("begin_deck_study", JSONObject().put("p_deck_id", deckId))))
    }

    override suspend fun grade(cardId: String, grade: Grade, eventId: String): Int = withContext(Dispatchers.IO) {
        scalarNumber(rpc("grade_card", JSONObject().put("p_card_id", cardId)
            .put("p_grade", grade.name.lowercase(Locale.ROOT)).put("p_event_id", eventId)))
    }

    override suspend fun saveDeck(draft: DeckDraft): String = withContext(Dispatchers.IO) {
        val cards = JSONArray()
        draft.cards.forEach { cards.put(JSONObject().put("front", it.front).put("back", it.back)) }
        val response = rpc("save_deck", JSONObject()
            .put("p_deck_id", draft.id ?: JSONObject.NULL)
            .put("p_exam", draft.exam).put("p_subject", draft.subject).put("p_topic", draft.topic)
            .put("p_title", draft.title).put("p_is_public", draft.isPublic).put("p_cards", cards))
        JSONTokener(response).nextValue() as? String
            ?: throw IOException("O servidor não confirmou o conjunto salvo.")
    }

    override suspend fun addCard(deckId: String, front: String, back: String): Unit = withContext(Dispatchers.IO) {
        rpc("add_card", JSONObject().put("p_deck_id", deckId).put("p_front", front).put("p_back", back))
        Unit
    }

    override suspend fun setPublic(deckId: String, isPublic: Boolean): Unit = withContext(Dispatchers.IO) {
        val owner = accessSession().userId
        val result = authorized("PATCH", "rest/v1/decks",
            mapOf("id" to "eq.$deckId", "owner_id" to "eq.$owner"),
            JSONObject().put("is_public", isPublic), mapOf("Prefer" to "return=representation"))
        require(JSONArray(result.body).length() == 1) { "O conjunto não está disponível na sua biblioteca." }
    }

    override suspend fun deleteDeck(deckId: String): Unit = withContext(Dispatchers.IO) {
        val owner = accessSession().userId
        val result = authorized("DELETE", "rest/v1/decks",
            mapOf("id" to "eq.$deckId", "owner_id" to "eq.$owner"),
            headers = mapOf("Prefer" to "return=representation"))
        require(JSONArray(result.body).length() == 1) { "O conjunto não está disponível na sua biblioteca." }
    }

    override suspend fun searchUsers(query: String): List<Profile> = withContext(Dispatchers.IO) {
        require(query.trim().length in 2..80) { "Busque por um nome com 2 a 80 caracteres." }
        // Escape SQL LIKE wildcards, then quote the complete PostgREST filter value.
        val literal = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val pattern = "\"%${literal.replace("\\", "\\\\").replace("\"", "\\\"")}%\""
        allRows("profiles", mapOf(
            "select" to "id,username,display_name",
            "or" to "(username.ilike.$pattern,display_name.ilike.$pattern)",
            "order" to "username.asc,id.asc"
        )).map(::profile)
    }

    override suspend fun publicDecks(ownerId: String): List<Deck> = withContext(Dispatchers.IO) {
        allRows("decks", mapOf(
            "select" to "id,owner_id,exam,subject,topic,title,is_public,cards(count),deck_links(exam,subject,topic)",
            "owner_id" to "eq.$ownerId", "is_public" to "eq.true",
            "order" to "exam.asc,subject.asc,topic.asc,title.asc,id.asc"
        )).flatMap(::linkedDecks)
    }

    override suspend fun copyPublic(ownerId: String, exam: String?, subject: String?, topic: String?): Int =
        withContext(Dispatchers.IO) {
            scalarNumber(rpc("copy_public_decks", JSONObject().put("p_owner", ownerId)
                .put("p_exam", exam ?: JSONObject.NULL).put("p_subject", subject ?: JSONObject.NULL)
                .put("p_topic", topic ?: JSONObject.NULL)))
        }

    override suspend fun simulations(): List<SimulationTemplate> = withContext(Dispatchers.IO) {
        val owner = accessSession().userId
        allRows("simulations", mapOf("select" to "id,title,config", "owner_id" to "eq.$owner",
            "order" to "created_at.desc,id.desc")).map { row ->
            val config = row.getJSONArray("config")
            SimulationTemplate(row.getString("id"), row.getString("title"),
                (0 until config.length()).map { index ->
                    config.getJSONObject(index).let { SimulationPack(it.getString("deck_id"), it.getInt("count")) }
                })
        }
    }

    override suspend fun saveSimulation(template: SimulationTemplate): Unit = withContext(Dispatchers.IO) {
        val owner = accessSession().userId
        val config = JSONArray().apply {
            template.packs.forEach { put(JSONObject().put("deck_id", it.deckId).put("count", it.count)) }
        }
        val body = JSONObject().put("title", template.title).put("config", config)
        val result = if (template.id == null) {
            body.put("owner_id", owner)
            authorized("POST", "rest/v1/simulations", body = body,
                headers = mapOf("Prefer" to "return=representation"))
        } else {
            authorized("PATCH", "rest/v1/simulations",
                mapOf("id" to "eq.${template.id}", "owner_id" to "eq.$owner"),
                body, mapOf("Prefer" to "return=representation"))
        }
        require(JSONArray(result.body).length() == 1) { "Não foi possível salvar o simulado." }
    }

    override suspend fun deleteSimulation(id: String): Unit = withContext(Dispatchers.IO) {
        val owner = accessSession().userId
        val response = authorized("DELETE", "rest/v1/simulations",
            mapOf("id" to "eq.$id", "owner_id" to "eq.$owner"),
            headers = mapOf("Prefer" to "return=representation"))
        require(JSONArray(response.body).length() == 1) { "Simulado não encontrado." }
    }

    override suspend fun startSimulation(id: String): SimulationAttempt = withContext(Dispatchers.IO) {
        val template = simulations().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Simulado não encontrado.")
        val answer = JSONObject(rpc("start_simulation", JSONObject().put("p_simulation_id", id)))
        val orderedIds = answer.getJSONArray("card_ids").let { array ->
            (0 until array.length()).map(array::getString)
        }
        val available = template.packs.map { it.deckId }.distinct().flatMap { cards(it) }.associateBy { it.id }
        val selected = orderedIds.map { available[it] ?: throw IOException("Um cartão do simulado não está disponível.") }
        SimulationAttempt(answer.getString("attempt_id"), answer.getString("title"), selected)
    }

    override suspend fun gradeSimulation(attemptId: String, cardId: String, grade: Grade, eventId: String): SimulationResult =
        withContext(Dispatchers.IO) {
            val answer = JSONObject(rpc("grade_simulation_card", JSONObject()
                .put("p_attempt_id", attemptId).put("p_card_id", cardId)
                .put("p_grade", grade.name.lowercase(Locale.ROOT)).put("p_event_id", eventId)))
            SimulationResult(answer.getBoolean("complete"),
                if (answer.isNull("percentage")) null else answer.getInt("percentage"))
        }

    override suspend fun simulationHistory(): List<SimulationHistory> = withContext(Dispatchers.IO) {
        val owner = accessSession().userId
        allRows("simulation_attempts", mapOf("select" to "id,title,percentage,completed_at",
            "owner_id" to "eq.$owner", "completed_at" to "not.is.null",
            "order" to "completed_at.desc,id.desc")).map {
            SimulationHistory(it.getString("id"), it.getString("title"),
                it.getInt("percentage"), it.getString("completed_at"))
        }
    }

    private suspend fun loadProfile(id: String): Profile {
        val rows = allRows("profiles", mapOf("select" to "id,username,display_name", "id" to "eq.$id", "order" to "id.asc"))
        return rows.singleOrNull()?.let(::profile)
            ?: throw IllegalStateException("Seu perfil ainda não está disponível. Confira a configuração do banco de dados.")
    }

    private fun profile(row: JSONObject) = Profile(row.getString("id"), row.getString("username"), row.getString("display_name"))
    private fun linkedDecks(row: JSONObject): List<Deck> {
        val original = deck(row)
        val links = row.optJSONArray("deck_links") ?: JSONArray()
        return (listOf(original) + (0 until links.length()).map { index ->
            val link = links.getJSONObject(index)
            original.copy(exam = link.getString("exam"), subject = link.getString("subject"), topic = link.getString("topic"))
        }).distinctBy { Triple(it.exam, it.subject, it.topic) }
    }

    private fun deck(row: JSONObject): Deck {
        val count = row.optJSONArray("cards")?.optJSONObject(0)?.optInt("count")
            ?: row.optJSONObject("cards")?.optInt("count") ?: 0
        return Deck(row.getString("id"), row.getString("owner_id"), row.getString("exam"),
            row.getString("subject"), row.getString("topic"), row.getString("title"),
            row.getBoolean("is_public"), count)
    }
    private fun card(row: JSONObject, score: Int) = Flashcard(
        row.getString("id"), row.getString("deck_id"), row.getString("front"),
        row.getString("back"), row.getInt("position"), score
    )
    private fun review(row: JSONObject) = DeckReview(
        row.getString("deck_id"), parseTimestamp(row.getString("next_review_at")), row.getInt("review_count")
    )
    private fun parseTimestamp(value: String): Long = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: java.time.format.DateTimeParseException) {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }

    /**
     * Uses an explicit stable order supplied by the caller. Continue based on
     * actual row count, even when a project has a server cap below our page size.
     */
    private suspend fun allRows(table: String, parameters: Map<String, String>): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val result = authorized("GET", "rest/v1/$table", parameters + mapOf("limit" to "1000", "offset" to offset.toString()),
                headers = mapOf("Prefer" to "count=exact"))
            val page = JSONArray(result.body)
            if (page.length() == 0) break
            repeat(page.length()) { rows.add(page.getJSONObject(it)) }
            offset += page.length()
            val total = result.contentRange?.substringAfterLast('/')?.toIntOrNull()
            if (total != null && offset >= total) break
        }
        return rows
    }

    private suspend fun rpc(name: String, body: JSONObject): String =
        authorized("POST", "rest/v1/rpc/$name", body = body).body

    private fun scalarNumber(body: String): Int = (JSONTokener(body).nextValue() as? Number)?.toInt()
        ?: throw IOException("O servidor retornou uma resposta inesperada.")

    private fun requireConfigured() {
        check(configured) { "A conexão com o Supabase ainda não foi configurada nesta instalação." }
    }
    private fun requireSession() = session ?: throw SessionExpired()

    /** Must be called while holding authMutex; persist rotated credentials before profile requests. */
    private fun acceptSession(response: JSONObject) {
        val existing = session
        val seconds = response.optLong("expires_at", 0)
        val expires = if (seconds > 0) seconds * 1000 else System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000
        val next = AuthSession(
            response.getString("access_token"), response.getString("refresh_token"), expires,
            response.optJSONObject("user")?.optString("id")?.takeIf { it.isNotBlank() }
                ?: existing?.userId ?: throw IOException("O servidor não informou o usuário autenticado.")
        )
        store.save(next)
        session = next
    }

    private suspend fun accessSession(failedToken: String? = null): AuthSession = authMutex.withLock {
        requireConfigured()
        val current = requireSession()
        val needsRefresh = if (failedToken != null) current.accessToken == failedToken
            else current.expiresAtMillis <= System.currentTimeMillis() + 60_000
        if (!needsRefresh) return@withLock current
        try {
            val response = raw("POST", "auth/v1/token", mapOf("grant_type" to "refresh_token"),
                JSONObject().put("refresh_token", current.refreshToken))
            acceptSession(JSONObject(response.body))
            requireSession()
        } catch (e: ApiFailure) {
            if (e.status == 400 || e.status == 401 || e.status == 403) {
                store.clear()
                session = null
                currentProfile = null
                throw SessionExpired()
            }
            throw e
        }
    }

    private suspend fun authorized(
        method: String, path: String, parameters: Map<String, String> = emptyMap(),
        body: JSONObject? = null, headers: Map<String, String> = emptyMap()
    ): ApiResult {
        val first = accessSession()
        try {
            return raw(method, path, parameters, body, first.accessToken, headers)
        } catch (e: ApiFailure) {
            if (e.status != 401) throw e
        }
        val refreshed = accessSession(first.accessToken)
        return raw(method, path, parameters, body, refreshed.accessToken, headers)
    }

    private fun raw(
        method: String, path: String, parameters: Map<String, String> = emptyMap(),
        body: JSONObject? = null, token: String? = null, headers: Map<String, String> = emptyMap()
    ): ApiResult {
        val url = "$base/$path".toHttpUrl().newBuilder().apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("apikey", publicKey).header("Accept", "application/json")
        if (token != null) request.header("Authorization", "Bearer $token")
        headers.forEach { (key, value) -> request.header(key, value) }
        val payload = body?.toString()?.toRequestBody(jsonType)
            ?: if (method == "POST" || method == "PATCH") "{}".toRequestBody(jsonType) else null
        request.method(method, payload)
        try {
            return client.newCall(request.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiFailure(response.code, friendlyError(response.code, responseBody))
                ApiResult(responseBody, response.header("Content-Range"))
            }
        } catch (e: SocketTimeoutException) {
            throw IOException("O servidor demorou para responder. Verifique a conexão e tente novamente.", e)
        } catch (e: ApiFailure) {
            throw e
        } catch (e: IOException) {
            throw IOException("Não foi possível conectar. Verifique sua internet e tente novamente.", e)
        }
    }

    private fun friendlyError(status: Int, text: String): String {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val code = json?.optString("error_code").orEmpty().ifBlank { json?.optString("code").orEmpty() }
        val message = listOf("error_description", "msg", "message", "error")
            .firstNotNullOfOrNull { key -> json?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } }.orEmpty()
        return when {
            code == "invalid_credentials" || message.contains("Invalid login credentials", true) -> "E-mail ou senha incorretos."
            code == "email_not_confirmed" -> "Confirme seu e-mail antes de entrar."
            code == "user_already_exists" || code == "email_exists" -> "Este e-mail já está cadastrado. Entre com sua senha."
            code == "weak_password" -> "Escolha uma senha mais forte."
            status == 429 -> "Muitas tentativas em pouco tempo. Aguarde alguns minutos e tente novamente."
            code == "PGRST205" || code == "PGRST202" || code == "42P01" -> "O banco de dados do aplicativo ainda precisa ser configurado."
            status == 401 -> "Sua sessão expirou. Entre novamente."
            status == 403 -> "Você não tem acesso a este conteúdo."
            status >= 500 -> "O serviço está temporariamente indisponível. Tente novamente em instantes."
            message.isNotBlank() && message.length <= 400 -> message
            else -> "Não foi possível concluir a operação (código $status)."
        }
    }

    private data class ApiResult(val body: String, val contentRange: String?)
    private class ApiFailure(val status: Int, message: String) : IOException(message)
    private class SessionExpired : IllegalStateException("Sua sessão expirou. Saia da conta e entre novamente.")
}
