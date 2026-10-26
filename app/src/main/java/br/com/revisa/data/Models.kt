package br.com.revisa.data

import br.com.revisa.core.Grade
import br.com.revisa.core.ImportedCard

data class Profile(val id: String, val username: String, val displayName: String)
data class Deck(
    val id: String, val ownerId: String, val exam: String, val subject: String,
    val topic: String, val title: String, val isPublic: Boolean, val cardCount: Int = 0
)
data class Flashcard(
    val id: String, val deckId: String, val front: String, val back: String,
    val position: Int = 0, val score: Int = 0
)
data class DeckReview(val deckId: String, val nextReviewAt: Long, val reviewCount: Int)
data class Library(
    val decks: List<Deck> = emptyList(), val reviews: List<DeckReview> = emptyList(),
    val reviewCards: List<Flashcard> = emptyList()
)
data class DeckDraft(
    val exam: String, val subject: String, val topic: String, val title: String,
    val isPublic: Boolean, val cards: List<ImportedCard>, val id: String? = null
)

interface RevisaRepository {
    val isDemo: Boolean
    val configured: Boolean
    val profile: Profile?
    suspend fun restore(): Boolean
    suspend fun login(email: String, password: String)
    /** True if signed in; false if email confirmation is required. */
    suspend fun register(email: String, password: String, username: String, displayName: String): Boolean
    suspend fun logout()
    suspend fun library(): Library
    suspend fun cards(deckId: String): List<Flashcard>
    suspend fun beginDeck(deckId: String): DeckReview
    suspend fun grade(cardId: String, grade: Grade, eventId: String): Int
    suspend fun saveDeck(draft: DeckDraft): String
    suspend fun addCard(deckId: String, front: String, back: String)
    suspend fun setPublic(deckId: String, isPublic: Boolean)
    suspend fun deleteDeck(deckId: String)
    suspend fun searchUsers(query: String): List<Profile>
    suspend fun publicDecks(ownerId: String): List<Deck>
    suspend fun copyPublic(ownerId: String, exam: String? = null, subject: String? = null, topic: String? = null): Int
}
