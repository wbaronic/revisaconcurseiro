package br.com.revisa.data

import android.content.Context
import br.com.revisa.core.FlashcardCsv
import br.com.revisa.core.Grade
import br.com.revisa.core.ImportedCard
import br.com.revisa.core.ReviewRules
import br.com.revisa.core.ReviewSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A separate, local-only demonstration store. No credentials or real account
 * data are saved here, and no operation connects to a remote service.
 */
class DemoRepository(context: Context) : RevisaRepository {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("revisa_demo_v1", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var loaded = false
    private var demoProfile: Profile? = null
    private val decks = linkedMapOf<String, Deck>()
    private val flashcards = linkedMapOf<String, Flashcard>()
    private val schedules = linkedMapOf<String, ReviewSchedule>()
    private val events = linkedMapOf<String, GradeEvent>()
    private val copiedSources = linkedMapOf<String, String>()
    private val simulationTemplates = linkedMapOf<String, SimulationTemplate>()
    private val simulationResults = mutableListOf<SimulationHistory>()
    private data class DemoAttempt(val title: String, val cardIds: List<String>, val answers: MutableMap<String, Pair<Grade, String>> = mutableMapOf())
    private val simulationAttempts = mutableMapOf<String, DemoAttempt>()

    override val isDemo = true
    override val configured = true
    override val profile: Profile? get() = demoProfile

    private data class GradeEvent(val cardId: String, val grade: Grade, val score: Int)

    private val exampleProfile = Profile(
        "00000000-0000-4000-8000-000000000002",
        "professora_ana",
        "Professora Ana · perfil de exemplo"
    )
    private val exampleDecks = listOf(
        Deck(
            "example-portugues", exampleProfile.id, "Concurso de exemplo",
            "Língua Portuguesa", "Sintaxe", "Sintaxe essencial", true, 3
        ),
        Deck(
            "example-constitucional", exampleProfile.id, "Concurso de exemplo",
            "Direito Constitucional", "Princípios fundamentais", "Princípios fundamentais", true, 3
        )
    )
    private val exampleCards = listOf(
        Flashcard("example-p1", "example-portugues", "O que é o sujeito de uma oração?", "É o termo com o qual o verbo estabelece concordância e sobre o qual se faz uma declaração.", 0),
        Flashcard("example-p2", "example-portugues", "Em “Os alunos revisaram a matéria”, qual é o sujeito?", "Os alunos.", 1),
        Flashcard("example-p3", "example-portugues", "Em “Os alunos revisaram a matéria”, qual é o objeto direto?", "A matéria. O termo completa o sentido do verbo sem preposição obrigatória.", 2),
        Flashcard("example-c1", "example-constitucional", "Qual é a forma de Estado adotada pelo Brasil?", "Federação.", 0),
        Flashcard("example-c2", "example-constitucional", "Qual é a forma de governo adotada pelo Brasil?", "República.", 1),
        Flashcard("example-c3", "example-constitucional", "Quais são os Poderes da União?", "Legislativo, Executivo e Judiciário, independentes e harmônicos entre si.", 2)
    )

    override suspend fun restore(): Boolean = store { true }

    override suspend fun login(email: String, password: String) {
        restore()
    }

    override suspend fun register(email: String, password: String, username: String, displayName: String): Boolean =
        restore()

    // Leaving demonstration mode intentionally retains its local progress.
    override suspend fun logout() = Unit

    override suspend fun library(): Library = store {
        Library(
            decks = decks.values.map(::withCardCount),
            reviews = schedules.map { (deckId, schedule) ->
                DeckReview(deckId, schedule.nextReviewAtMillis, schedule.reviewCount)
            }.sortedBy { it.nextReviewAt },
            reviewCards = flashcards.values.filter { it.score > 0 }.sortedWith(
                compareByDescending<Flashcard> { it.score }
                    .thenBy { it.deckId }.thenBy { it.position }.thenBy { it.id }
            )
        )
    }

    override suspend fun cards(deckId: String): List<Flashcard> = store {
        when {
            deckId in decks -> flashcards.values.filter { it.deckId == deckId }.sortedBy { it.position }
            exampleDecks.any { it.id == deckId } -> exampleCards.filter { it.deckId == deckId }
            else -> error("Conjunto não encontrado.")
        }
    }

    override suspend fun beginDeck(deckId: String): DeckReview = store(write = true) {
        ownedDeck(deckId)
        val next = ReviewRules.beginGroup(System.currentTimeMillis(), schedules[deckId])
        schedules[deckId] = next
        DeckReview(deckId, next.nextReviewAtMillis, next.reviewCount)
    }

    override suspend fun grade(cardId: String, grade: Grade, eventId: String): Int = store(write = true) {
        require(eventId.isNotBlank()) { "A resposta precisa de um identificador." }
        val previous = events[eventId]
        if (previous != null) {
            require(previous.cardId == cardId && previous.grade == grade) {
                "Esta resposta já foi registrada com outros dados."
            }
            previous.score
        } else {
            val card = flashcards[cardId] ?: error("Cartão não encontrado na sua biblioteca.")
            ownedDeck(card.deckId)
            val score = ReviewRules.nextScore(card.score, grade)
            flashcards[cardId] = card.copy(score = score)
            events[eventId] = GradeEvent(cardId, grade, score)
            score
        }
    }

    override suspend fun simulations(): List<SimulationTemplate> = store {
        simulationTemplates.values.toList()
    }

    override suspend fun saveSimulation(template: SimulationTemplate): Unit = store(write = true) {
        val id = template.id ?: newId()
        require(template.id == null || id in simulationTemplates) { "Simulado não encontrado." }
        simulationTemplates[id] = template.copy(id = id)
    }

    override suspend fun deleteSimulation(id: String): Unit = store(write = true) {
        require(simulationTemplates.remove(id) != null) { "Simulado não encontrado." }
    }

    override suspend fun startSimulation(id: String): SimulationAttempt = store {
        val template = simulationTemplates[id] ?: error("Simulado não encontrado.")
        val selected = template.packs.flatMap { pack ->
            ownedDeck(pack.deckId)
            val available = flashcards.values.filter { it.deckId == pack.deckId }
            require(pack.count in 1..available.size) { "Um conjunto não possui a quantidade configurada de cartões." }
            available.shuffled().take(pack.count)
        }.shuffled()
        val attemptId = newId()
        simulationAttempts[attemptId] = DemoAttempt(template.title, selected.map { it.id })
        SimulationAttempt(attemptId, template.title, selected)
    }

    override suspend fun gradeSimulation(attemptId: String, cardId: String, grade: Grade, eventId: String): SimulationResult = store(write = true) {
        val attempt = simulationAttempts[attemptId] ?: error("Tentativa não encontrada.")
        require(cardId in attempt.cardIds) { "Cartão fora da tentativa." }
        val prior = attempt.answers[cardId]
        if (prior != null) {
            require(prior.first == grade && prior.second == eventId) { "Cartão já avaliado nesta tentativa." }
        } else {
            val card = flashcards[cardId] ?: error("Cartão não encontrado.")
            val nextScore = ReviewRules.nextScore(card.score, grade)
            flashcards[cardId] = card.copy(score = nextScore)
            events[eventId] = GradeEvent(cardId, grade, nextScore)
            attempt.answers[cardId] = grade to eventId
        }
        val complete = attempt.answers.size == attempt.cardIds.size
        val percentage = if (complete) kotlin.math.round((attempt.answers.values.count { it.first == Grade.CORRECT } * 100.0) /
            attempt.cardIds.size).toInt() else null
        if (complete && simulationResults.none { it.id == attemptId }) {
            simulationResults.add(SimulationHistory(attemptId, attempt.title, percentage!!, java.time.Instant.now().toString()))
        }
        SimulationResult(complete, percentage)
    }

    override suspend fun simulationHistory(): List<SimulationHistory> = store {
        simulationResults.sortedByDescending { it.completedAt }
    }

    override suspend fun saveDeck(draft: DeckDraft): String = store(write = true) {
        val exam = requiredText(draft.exam, "Informe o concurso.")
        val subject = requiredText(draft.subject, "Informe a matéria.")
        val topic = requiredText(draft.topic, "Informe o assunto.")
        val title = requiredText(draft.title, "Informe o nome do conjunto.")
        require(draft.cards.isNotEmpty()) { "Adicione pelo menos um cartão." }
        require(draft.cards.size <= FlashcardCsv.MAX_CARDS) { "O limite é de ${FlashcardCsv.MAX_CARDS} cartões por conjunto." }
        val cleanCards = draft.cards.map { cleanCard(it.front, it.back) }
        val id = draft.id?.also { ownedDeck(it) } ?: newId()
        if (draft.id != null) removeDeckContents(id)
        decks[id] = Deck(id, checkNotNull(demoProfile).id, exam, subject, topic, title, draft.isPublic, cleanCards.size)
        insertCards(id, cleanCards)
        id
    }

    override suspend fun addCard(deckId: String, front: String, back: String) {
        store(write = true) {
            ownedDeck(deckId)
            val clean = cleanCard(front, back)
            val existing = flashcards.values.filter { it.deckId == deckId }
            require(existing.size < FlashcardCsv.MAX_CARDS) { "O limite é de ${FlashcardCsv.MAX_CARDS} cartões por conjunto." }
            val id = newId()
            flashcards[id] = Flashcard(id, deckId, clean.front, clean.back, (existing.maxOfOrNull { it.position } ?: -1) + 1)
        }
    }

    override suspend fun setPublic(deckId: String, isPublic: Boolean) {
        store(write = true) {
            decks[deckId] = ownedDeck(deckId).copy(isPublic = isPublic)
        }
    }

    override suspend fun deleteDeck(deckId: String) {
        store(write = true) {
            ownedDeck(deckId)
            removeDeckContents(deckId)
            decks.remove(deckId)
            copiedSources.entries.removeAll { it.value == deckId }
        }
    }

    override suspend fun searchUsers(query: String): List<Profile> = store {
        val term = query.trim().removePrefix("@")
        if (term.length < 2) emptyList()
        else {
            val candidates = buildList {
                add(exampleProfile)
                if (decks.values.any { it.isPublic }) add(checkNotNull(demoProfile))
            }
            candidates.filter {
                it.username.contains(term, ignoreCase = true) || it.displayName.contains(term, ignoreCase = true)
            }
        }
    }

    override suspend fun publicDecks(ownerId: String): List<Deck> = store {
        when (ownerId) {
            exampleProfile.id -> exampleDecks
            demoProfile?.id -> decks.values.filter { it.isPublic }.map(::withCardCount)
            else -> emptyList()
        }
    }

    override suspend fun copyPublic(ownerId: String, exam: String?, subject: String?, topic: String?): Int =
        store(write = true) {
            if (ownerId != exampleProfile.id) {
                0
            } else {
                val selected = exampleDecks.filter {
                    (exam == null || it.exam == exam) &&
                        (subject == null || it.subject == subject) &&
                        (topic == null || it.topic == topic) &&
                        copiedSources[it.id] !in decks
                }
                selected.forEach { source ->
                    val id = newId()
                    decks[id] = source.copy(id = id, ownerId = checkNotNull(demoProfile).id, isPublic = false)
                    insertCards(id, exampleCards.filter { it.deckId == source.id }.map { ImportedCard(it.front, it.back) })
                    copiedSources[source.id] = id
                }
                selected.size
            }
        }

    private fun ownedDeck(id: String): Deck = decks[id] ?: error("Conjunto não encontrado na sua biblioteca.")

    private fun withCardCount(deck: Deck): Deck =
        deck.copy(cardCount = flashcards.values.count { it.deckId == deck.id })

    private fun requiredText(value: String, message: String): String =
        value.trim().also { require(it.isNotBlank()) { message } }

    private fun cleanCard(front: String, back: String): ImportedCard = ImportedCard(
        requiredText(front, "A pergunta está vazia."),
        requiredText(back, "A resposta está vazia.")
    )

    private fun insertCards(deckId: String, cards: List<ImportedCard>) {
        cards.forEachIndexed { position, card ->
            val id = newId()
            flashcards[id] = Flashcard(id, deckId, card.front, card.back, position)
        }
    }

    private fun removeDeckContents(deckId: String) {
        val ids = flashcards.values.filter { it.deckId == deckId }.map { it.id }.toSet()
        flashcards.entries.removeAll { it.key in ids }
        schedules.remove(deckId)
        events.entries.removeAll { it.value.cardId in ids }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private suspend fun <T> store(write: Boolean = false, block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                val saved = preferences.getString("state", null)
                if (saved == null) {
                    seed()
                    persist()
                } else {
                    try {
                        readState(JSONObject(saved))
                    } catch (failure: Exception) {
                        throw IllegalStateException("Não foi possível ler os dados da demonstração neste aparelho.", failure)
                    }
                }
                loaded = true
            }
            val before = if (write) writeState() else null
            try {
                val result = block()
                if (write) persist()
                result
            } catch (failure: Exception) {
                if (before != null) readState(before)
                throw failure
            }
        }
    }

    private fun seed() {
        demoProfile = Profile(newId(), "aluno_demo", "Aluno de demonstração")
        decks.clear()
        flashcards.clear()
        schedules.clear()
        events.clear()
        copiedSources.clear()
        simulationTemplates.clear()
        simulationResults.clear()
        simulationAttempts.clear()
        val owner = checkNotNull(demoProfile).id
        val coId = newId()
        val fundamentalsId = newId()
        val coCards = application.assets.open("sap_co.csv").bufferedReader(Charsets.UTF_8).use { FlashcardCsv.parse(it.readText()) }
        val fundamentalsCards = application.assets.open("sap_fundamentos.csv").bufferedReader(Charsets.UTF_8).use { FlashcardCsv.parse(it.readText()) }
        decks[coId] = Deck(coId, owner, "Transpetro", "SAP", "Controladoria", "SAP · Controladoria", false, coCards.size)
        decks[fundamentalsId] = Deck(fundamentalsId, owner, "Transpetro", "SAP", "Fundamentos", "SAP · Fundamentos", false, fundamentalsCards.size)
        insertCards(coId, coCards)
        insertCards(fundamentalsId, fundamentalsCards)
        val now = System.currentTimeMillis()
        schedules[coId] = ReviewSchedule(now - 8 * ReviewRules.DAY_MILLIS, now - ReviewRules.DAY_MILLIS, 0)
        schedules[fundamentalsId] = ReviewRules.beginGroup(now, null)
        val initialScores = listOf(7, 4, 2, 1)
        flashcards.values.filter { it.deckId == coId }.take(initialScores.size).forEachIndexed { index, card ->
            flashcards[card.id] = card.copy(score = initialScores[index])
        }
        flashcards.values.filter { it.deckId == fundamentalsId }.take(2).forEachIndexed { index, card ->
            flashcards[card.id] = card.copy(score = if (index == 0) 3 else 1)
        }
    }

    private fun persist() {
        check(preferences.edit().putString("state", writeState().toString()).commit()) {
            "Não foi possível salvar a demonstração. Verifique o espaço disponível no aparelho."
        }
    }

    private fun writeState(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("profile", checkNotNull(demoProfile).let {
            JSONObject().put("id", it.id).put("username", it.username).put("displayName", it.displayName)
        })
        put("decks", jsonArray(decks.values) {
            JSONObject().put("id", it.id).put("ownerId", it.ownerId).put("exam", it.exam)
                .put("subject", it.subject).put("topic", it.topic).put("title", it.title).put("isPublic", it.isPublic)
        })
        put("cards", jsonArray(flashcards.values) {
            JSONObject().put("id", it.id).put("deckId", it.deckId).put("front", it.front)
                .put("back", it.back).put("position", it.position).put("score", it.score)
        })
        put("schedules", jsonArray(schedules.entries) {
            JSONObject().put("deckId", it.key).put("startedAt", it.value.startedAtMillis)
                .put("nextReviewAt", it.value.nextReviewAtMillis).put("reviewCount", it.value.reviewCount)
        })
        put("events", jsonArray(events.entries) {
            JSONObject().put("id", it.key).put("cardId", it.value.cardId)
                .put("grade", it.value.grade.name).put("score", it.value.score)
        })
        put("copies", jsonArray(copiedSources.entries) {
            JSONObject().put("sourceId", it.key).put("deckId", it.value)
        })
        put("simulations", jsonArray(simulationTemplates.values) { simulation ->
            JSONObject().put("id", simulation.id).put("title", simulation.title)
                .put("config", jsonArray(simulation.packs) { pack ->
                    JSONObject().put("deck_id", pack.deckId).put("count", pack.count)
                })
        })
        put("simulationResults", jsonArray(simulationResults) {
            JSONObject().put("id", it.id).put("title", it.title)
                .put("percentage", it.percentage).put("completedAt", it.completedAt)
        })
        put("simulationAttempts", jsonArray(simulationAttempts.entries) { entry ->
            JSONObject().put("id", entry.key).put("title", entry.value.title)
                .put("cardIds", JSONArray(entry.value.cardIds))
                .put("answers", jsonArray(entry.value.answers.entries) { answer ->
                    JSONObject().put("cardId", answer.key).put("grade", answer.value.first.name)
                        .put("eventId", answer.value.second)
                })
        })
    }

    private fun readState(json: JSONObject) {
        require(json.getInt("version") == 1) { "Versão da demonstração não reconhecida." }
        val user = json.getJSONObject("profile")
        demoProfile = Profile(user.getString("id"), user.getString("username"), user.getString("displayName"))
        decks.clear()
        flashcards.clear()
        schedules.clear()
        events.clear()
        copiedSources.clear()
        simulationTemplates.clear()
        simulationResults.clear()
        simulationAttempts.clear()
        json.getJSONArray("decks").forEachObject {
            val deck = Deck(
                it.getString("id"), it.getString("ownerId"), it.getString("exam"), it.getString("subject"),
                it.getString("topic"), it.getString("title"), it.getBoolean("isPublic")
            )
            decks[deck.id] = deck
        }
        json.getJSONArray("cards").forEachObject {
            val card = Flashcard(
                it.getString("id"), it.getString("deckId"), it.getString("front"), it.getString("back"),
                it.getInt("position"), it.getInt("score")
            )
            require(card.deckId in decks && card.score >= 0) { "Cartão de demonstração inválido." }
            flashcards[card.id] = card
        }
        json.getJSONArray("schedules").forEachObject {
            val id = it.getString("deckId")
            require(id in decks) { "Revisão de demonstração inválida." }
            schedules[id] = ReviewSchedule(it.getLong("startedAt"), it.getLong("nextReviewAt"), it.getInt("reviewCount"))
        }
        json.getJSONArray("events").forEachObject {
            events[it.getString("id")] = GradeEvent(it.getString("cardId"), Grade.valueOf(it.getString("grade")), it.getInt("score"))
        }
        json.getJSONArray("copies").forEachObject {
            copiedSources[it.getString("sourceId")] = it.getString("deckId")
        }
        json.optJSONArray("simulations")?.forEachObject { row ->
            val packs = mutableListOf<SimulationPack>()
            row.getJSONArray("config").forEachObject { packs.add(SimulationPack(it.getString("deck_id"), it.getInt("count"))) }
            val template = SimulationTemplate(row.getString("id"), row.getString("title"), packs)
            simulationTemplates[template.id!!] = template
        }
        json.optJSONArray("simulationResults")?.forEachObject {
            simulationResults.add(SimulationHistory(it.getString("id"), it.getString("title"),
                it.getInt("percentage"), it.getString("completedAt")))
        }
        json.optJSONArray("simulationAttempts")?.forEachObject { row ->
            val cardIds = row.getJSONArray("cardIds").let { ids ->
                (0 until ids.length()).map(ids::getString)
            }
            val attempt = DemoAttempt(row.getString("title"), cardIds)
            row.getJSONArray("answers").forEachObject { answer ->
                attempt.answers[answer.getString("cardId")] =
                    Grade.valueOf(answer.getString("grade")) to answer.getString("eventId")
            }
            simulationAttempts[row.getString("id")] = attempt
        }
    }

    private fun <T> jsonArray(values: Iterable<T>, convert: (T) -> JSONObject): JSONArray =
        JSONArray().apply { values.forEach { put(convert(it)) } }

    private fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (index in 0 until length()) block(getJSONObject(index))
    }
}
