package br.com.revisa.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.revisa.core.FlashcardCsv
import br.com.revisa.core.Grade
import br.com.revisa.core.ImportedCard
import br.com.revisa.data.Deck
import br.com.revisa.data.DeckDraft
import br.com.revisa.data.Flashcard
import br.com.revisa.data.Library
import br.com.revisa.data.Profile
import br.com.revisa.data.RepositoryFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class EditorState(
    val exam: String = "", val subject: String = "", val topic: String = "",
    val title: String = "", val isPublic: Boolean = false,
    val front: String = "", val back: String = "",
    val cards: List<ImportedCard> = emptyList(),
    val imported: Boolean = false,
    val addToDeckId: String? = null
)

data class StudyState(
    val title: String,
    val cards: List<Flashcard>,
    val index: Int = 0,
    val revealed: Boolean = false,
    val eventId: String = UUID.randomUUID().toString(),
    val pendingGrade: Grade? = null,
    val correct: Int = 0,
    val doubts: Int = 0,
    val wrong: Int = 0,
    val nextReviewAt: Long? = null,
) {
    val complete get() = index >= cards.size
    val current get() = cards.getOrNull(index)
}

class RevisaViewModel(application: Application) : AndroidViewModel(application) {
    private var repository = RepositoryFactory.real(application)
    var busy by mutableStateOf(false); private set
    var initializing by mutableStateOf(true); private set
    var authenticated by mutableStateOf(false); private set
    var profile by mutableStateOf<Profile?>(null); private set
    var demo by mutableStateOf(false); private set
    val configured get() = repository.configured
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var library by mutableStateOf(Library()); private set
    var tab by mutableStateOf(0); private set
    var path by mutableStateOf<List<String>>(emptyList()); private set
    var userQuery by mutableStateOf(""); private set
    var searched by mutableStateOf(false); private set
    var searchResults by mutableStateOf<List<Profile>>(emptyList()); private set
    var communityUser by mutableStateOf<Profile?>(null); private set
    var communityDecks by mutableStateOf<List<Deck>>(emptyList()); private set
    var communityPath by mutableStateOf<List<String>>(emptyList()); private set
    var editor by mutableStateOf<EditorState?>(null); private set
    var study by mutableStateOf<StudyState?>(null); private set

    init {
        viewModelScope.launch {
            try {
                authenticated = repository.restore()
                profile = repository.profile
                if (authenticated) library = repository.library()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = readable(e)
            } finally { initializing = false }
        }
    }

    private fun readable(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Não foi possível concluir. Verifique sua conexão e tente novamente."

    private fun task(block: suspend () -> Unit) {
        if (busy || initializing) return
        busy = true
        error = null
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                error = readable(e)
            }
            finally { busy = false }
        }
    }

    fun dismissError() { error = null }
    fun dismissNotice() { notice = null }
    fun showError(text: String) { error = text }
    fun login(email: String, password: String) = task {
        require(email.isNotBlank() && password.isNotBlank()) { "Informe seu e-mail e sua senha." }
        repository.login(email.trim(), password)
        authenticated = true
        profile = repository.profile
        library = repository.library()
    }
    fun register(email: String, password: String, username: String) = task {
        require(email.isNotBlank() && password.length >= 8) {
            "Preencha os campos e use uma senha com pelo menos 8 caracteres."
        }
        require(username.trim().matches(Regex("[a-z0-9_]{3,30}"))) {
            "O nome de usuário deve ter de 3 a 30 letras minúsculas, números ou _."
        }
        val normalizedUsername = username.trim()
        if (repository.register(email.trim(), password, normalizedUsername, normalizedUsername)) {
            authenticated = true
            profile = repository.profile
            library = repository.library()
        } else notice = "Conta criada! Confirme seu e-mail e depois entre com sua senha."
    }
    fun enterDemo() = task {
        repository = RepositoryFactory.demo(getApplication())
        repository.restore()
        demo = repository.isDemo
        authenticated = true
        profile = repository.profile
        library = repository.library()
        notice = "Demonstração local: seus testes ficam salvos neste aparelho."
    }
    fun logout() = task {
        repository.logout()
        repository = RepositoryFactory.real(getApplication())
        authenticated = false
        profile = null
        demo = false
        library = Library()
        tab = 0
        path = emptyList()
        communityUser = null
        communityDecks = emptyList()
        communityPath = emptyList()
        searchResults = emptyList()
        userQuery = ""
        searched = false
        editor = null
        study = null
        notice = null
    }
    fun refresh() = task { library = repository.library(); profile = repository.profile }
    fun selectTab(value: Int) {
        if (busy) return
        tab = value
        error = null
    }
    fun enterPath(value: String) { path = path + value }
    fun setPathDepth(depth: Int) { path = path.take(depth) }
    fun enterCommunityPath(value: String) { communityPath = communityPath + value }
    fun setCommunityPathDepth(depth: Int) { communityPath = communityPath.take(depth) }
    fun leaveCommunityUser() { communityUser = null; communityDecks = emptyList(); communityPath = emptyList() }
    fun updateQuery(value: String) { userQuery = value }
    fun search() = task {
        require(userQuery.trim().length >= 2) { "Digite pelo menos 2 caracteres para buscar um usuário." }
        searchResults = repository.searchUsers(userQuery.trim())
        searched = true
    }
    fun openUser(user: Profile) = task {
        val decks = repository.publicDecks(user.id)
        communityUser = user
        communityDecks = decks
        communityPath = emptyList()
    }
    fun copyPublic() = task {
        val owner = communityUser ?: return@task
        val count = repository.copyPublic(owner.id, communityPath.getOrNull(0), communityPath.getOrNull(1), communityPath.getOrNull(2))
        library = repository.library()
        notice = if (count == 0) "Nenhum conjunto novo foi adicionado." else "$count conjunto(s) adicionado(s) à sua biblioteca como cópia particular."
    }
    fun togglePublic(deck: Deck) = task {
        repository.setPublic(deck.id, !deck.isPublic)
        library = repository.library()
        notice = if (deck.isPublic) "Conjunto alterado para particular." else "Conjunto disponível no seu perfil público."
    }
    fun delete(deck: Deck) = task {
        repository.deleteDeck(deck.id)
        library = repository.library()
        notice = "Conjunto excluído."
    }
    fun newDeck() {
        editor = EditorState(exam = path.getOrNull(0).orEmpty(), subject = path.getOrNull(1).orEmpty(), topic = path.getOrNull(2).orEmpty())
        error = null
    }
    fun addCard(deck: Deck) {
        editor = EditorState(exam = deck.exam, subject = deck.subject, topic = deck.topic, title = deck.title, addToDeckId = deck.id)
        error = null
    }
    fun edit(value: EditorState) { editor = value }
    fun closeEditor() { if (!busy) { editor = null; error = null } }
    fun appendManualCard() {
        val current = editor ?: return
        if (current.front.isBlank() || current.back.isBlank()) {
            error = "Preencha a pergunta e a resposta do cartão."; return
        }
        editor = current.copy(cards = current.cards + ImportedCard(current.front.trim(), current.back.trim()), front = "", back = "")
        error = null
    }
    fun removeDraftCard(index: Int) {
        editor = editor?.let { it.copy(cards = it.cards.filterIndexed { i, _ -> i != index }) }
    }
    fun importCsv(uri: Uri) = task {
        val current = editor ?: return@task
        require(current.addToDeckId == null) { "A importação cria um novo conjunto." }
        val imported = withContext(Dispatchers.IO) {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 5 * 1024 * 1024) { "O arquivo deve ter no máximo 5 MB." }
                    out.write(buffer, 0, count)
                }
                try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(out.toByteArray())).toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    throw IllegalArgumentException("Salve o CSV em UTF-8 antes de importar.")
                }
            } ?: throw IllegalArgumentException("Não foi possível ler o arquivo escolhido.")
            FlashcardCsv.parse(text)
        }
        require(imported.isNotEmpty()) { "O arquivo não contém cartões." }
        editor = current.copy(cards = imported, imported = true, front = "", back = "")
        notice = "${imported.size} cartões lidos. Confira a prévia antes de salvar."
    }
    fun saveEditor() = task {
        val current = editor ?: return@task
        if (current.addToDeckId != null) {
            require(current.front.isNotBlank() && current.back.isNotBlank()) { "Preencha a pergunta e a resposta." }
            repository.addCard(current.addToDeckId, current.front.trim(), current.back.trim())
            notice = "Cartão adicionado."
        } else {
            require(listOf(current.exam, current.subject, current.topic, current.title).all { it.isNotBlank() }) {
                "Preencha concurso, matéria, assunto e nome do conjunto."
            }
            var cards = current.cards
            if (current.front.isNotBlank() || current.back.isNotBlank()) {
                require(current.front.isNotBlank() && current.back.isNotBlank()) { "Complete a pergunta e a resposta do cartão em edição." }
                cards = cards + ImportedCard(current.front.trim(), current.back.trim())
            }
            require(cards.isNotEmpty()) { "Adicione pelo menos um cartão ou importe um arquivo CSV." }
            repository.saveDeck(DeckDraft(current.exam.trim(), current.subject.trim(), current.topic.trim(), current.title.trim(), current.isPublic, cards))
            path = listOf(current.exam.trim(), current.subject.trim(), current.topic.trim())
            notice = "Conjunto salvo com ${cards.size} cartão(ões)."
        }
        editor = null
        tab = 1
        library = repository.library()
    }
    fun startDeck(deck: Deck) = task {
        val cards = repository.cards(deck.id)
        require(cards.isNotEmpty()) { "Este conjunto ainda não tem cartões." }
        val schedule = repository.beginDeck(deck.id)
        study = StudyState(deck.title, cards, nextReviewAt = schedule.nextReviewAt)
        library = library.copy(reviews = library.reviews.filterNot { it.deckId == deck.id } + schedule)
    }
    fun startGlobal() = task {
        val fresh = repository.library()
        library = fresh
        val queue = fresh.reviewCards.filter { it.score > 0 }.sortedWith(compareByDescending<Flashcard> { it.score }.thenBy { it.id })
        require(queue.isNotEmpty()) { "Nenhum cartão com pontuação positiva para revisar." }
        study = StudyState("Revisão dos cartões", queue)
    }
    fun reveal() { study = study?.copy(revealed = true) }
    fun grade(grade: Grade) = task {
        val current = study ?: return@task
        val card = current.current ?: return@task
        require(current.revealed) { "Veja a resposta antes de avaliar." }
        require(current.pendingGrade == null || current.pendingGrade == grade) { "Tente novamente a avaliação que ficou pendente." }
        study = current.copy(pendingGrade = grade)
        repository.grade(card.id, grade, current.eventId)
        study = current.copy(
            index = current.index + 1, revealed = false,
            eventId = UUID.randomUUID().toString(), pendingGrade = null,
            correct = current.correct + if (grade == Grade.CORRECT) 1 else 0,
            doubts = current.doubts + if (grade == Grade.DOUBT) 1 else 0,
            wrong = current.wrong + if (grade == Grade.WRONG) 1 else 0
        )
        if (study?.complete == true) library = repository.library()
    }
    fun closeStudy() = task {
        study = null
        library = repository.library()
    }
}
