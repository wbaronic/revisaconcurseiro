package br.com.revisa.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlashcardCsvTest {
    @Test
    fun importsHeaderlessCsvLikeTheAttachedFiles() {
        val text = """
            Qual é a função do SAP?,Gerenciar processos.
            "O que significa a sigla SAP?","Sistemas, Aplicações e Produtos."
        """.trimIndent()
        assertEquals(
            listOf(
                ImportedCard("Qual é a função do SAP?", "Gerenciar processos."),
                ImportedCard("O que significa a sigla SAP?", "Sistemas, Aplicações e Produtos.")
            ),
            FlashcardCsv.parse(text)
        )
    }

    @Test
    fun handlesBomHeaderEscapedQuotesAndCrLfInsideFields() {
        val text = "\uFEFFPergunta,Resposta\r\n\"Explique \"\"DEV\"\".\",\"Primeira linha\r\nSegunda linha\"\r\n"
        assertEquals(
            listOf(ImportedCard("Explique \"DEV\".", "Primeira linha\nSegunda linha")),
            FlashcardCsv.parse(text)
        )
    }

    @Test
    fun detectsSemicolonAndTab() {
        assertEquals(
            listOf(ImportedCard("Qual é o módulo, no SAP?", "FI; finanças")),
            FlashcardCsv.parse("Frente;Verso\nQual é o módulo, no SAP?;\"FI; finanças\"")
        )
        assertEquals(
            listOf(ImportedCard("Pergunta?", "Resposta.")),
            FlashcardCsv.parse("Front\tBack\nPergunta?\tResposta.")
        )
    }

    @Test
    fun acceptsAllSupportedHeadersOnlyInTheFirstNonblankRecord() {
        for (header in listOf("Pergunta,Resposta", "Frente,Verso", "Question,Answer", "Front,Back")) {
            assertEquals(
                listOf(ImportedCard("A", "B"), ImportedCard("front", "back")),
                FlashcardCsv.parse("\n  \r\n$header\nA,B\nfront,back\n")
            )
        }
    }

    @Test
    fun preservesDuplicateCardsAndSkipsOnlyBlankLines() {
        assertEquals(
            listOf(ImportedCard("A", "B"), ImportedCard("A", "B")),
            FlashcardCsv.parse("\nA,B\n   \nA,B\n\n")
        )
    }

    @Test
    fun handlesCrOnlyAndWhitespaceAfterClosingQuote() {
        assertEquals(
            listOf(ImportedCard("A", "B"), ImportedCard("C", "D")),
            FlashcardCsv.parse("\"A\" ,\"B\"\rC,D\r")
        )
    }

    @Test
    fun rejectsEmptyFilesHeadersOnlyAndEmptyFields() {
        for (text in listOf("", "\uFEFF \n", "front,back\n", ",B", "A,", "\"\",B", "\"\"")) {
            assertFailsWith<CsvImportException>(text) { FlashcardCsv.parse(text) }
        }
    }

    @Test
    fun rejectsWrongColumnCountAndMalformedQuotes() {
        for (text in listOf("A,B,C", "A", "\"A,B", "\"A\"x,B", "A\"x,B", "A,B\nC,D,E")) {
            assertFailsWith<CsvImportException>(text) { FlashcardCsv.parse(text) }
        }
    }

    @Test
    fun errorReportsPhysicalStartLineOfMultilineRecord() {
        val error = assertFailsWith<CsvImportException> {
            FlashcardCsv.parse("front,back\r\n\"A\r\nB\",C\r\nD,\r\n")
        }
        assertTrue(error.message.orEmpty().startsWith("Linha 4:"), error.message)
    }

    @Test
    fun enforcesLimitWithoutCountingTheHeaderOrBlankLines() {
        val body = List(FlashcardCsv.MAX_CARDS) { "P$it,R$it" }.joinToString("\n")
        assertEquals(FlashcardCsv.MAX_CARDS, FlashcardCsv.parse("front,back\n\n$body").size)
        val error = assertFailsWith<CsvImportException> { FlashcardCsv.parse("$body\nUma a mais,Resposta") }
        assertTrue(error.message.orEmpty().contains("limite"))
    }

    @Test
    fun doesNotReturnPartialImportWhenALaterRecordIsInvalid() {
        assertFailsWith<CsvImportException> { FlashcardCsv.parse("A,B\nC,D\nE,") }
    }
}
