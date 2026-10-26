package br.com.revisa.core

data class ImportedCard(val front: String, val back: String)

class CsvImportException(message: String) : IllegalArgumentException(message)

/**
 * Imports a two-column CSV as front/back cards. The caller must decode the file
 * as UTF-8 before calling [parse]. Duplicate cards are intentionally preserved.
 */
object FlashcardCsv {
    const val MAX_CARDS = 5_000

    fun parse(text: String): List<ImportedCard> {
        val source = text.removePrefix("\uFEFF")
        if (source.isBlank()) {
            throw CsvImportException("O arquivo está vazio. Adicione uma pergunta e uma resposta por linha.")
        }

        // Validate the entire file for each plausible delimiter. This also
        // handles semicolon-delimited exports containing ordinary commas.
        val delimiters = delimiterCandidates(source)
        var mostLikelyError: CsvImportException? = null
        for (delimiter in delimiters) {
            try {
                return parseWithDelimiter(source, delimiter)
            } catch (error: CsvImportException) {
                if (mostLikelyError == null) mostLikelyError = error
            }
        }
        throw checkNotNull(mostLikelyError)
    }

    private fun parseWithDelimiter(source: String, delimiter: Char): List<ImportedCard> {
        val result = mutableListOf<ImportedCard>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var quoteClosed = false
        var recordHasSyntax = false
        var firstRecord = true
        var line = 1
        var recordLine = 1
        var index = 0

        fun fail(reason: String): Nothing =
            throw CsvImportException("Linha $recordLine: $reason")

        fun finishField() {
            fields.add(field.toString().trim())
            field.clear()
            quoteClosed = false
        }

        fun finishRecord() {
            finishField()
            val blankRecord = !recordHasSyntax && fields.size == 1 && fields[0].isBlank()
            if (!blankRecord) {
                if (fields.size != 2) {
                    fail(
                        "esperadas 2 colunas (pergunta e resposta), mas foram encontradas ${fields.size}. " +
                            "Coloque entre aspas os textos que contêm o separador."
                    )
                }
                val front = fields[0]
                val back = fields[1]
                val isHeader = firstRecord && isHeader(front, back)
                firstRecord = false
                if (!isHeader) {
                    if (front.isBlank()) fail("a pergunta está vazia.")
                    if (back.isBlank()) fail("a resposta está vazia.")
                    if (result.size == MAX_CARDS) {
                        fail("o limite é de $MAX_CARDS cartões por arquivo. Divida o arquivo em partes.")
                    }
                    result.add(ImportedCard(front, back))
                }
            }
            fields.clear()
            recordHasSyntax = false
        }

        while (index < source.length) {
            val char = source[index]
            if (quoted) {
                when {
                    char == '"' && source.getOrNull(index + 1) == '"' -> {
                        field.append('"')
                        index += 1
                    }
                    char == '"' -> {
                        quoted = false
                        quoteClosed = true
                    }
                    char == '\r' || char == '\n' -> {
                        field.append('\n')
                        if (char == '\r' && source.getOrNull(index + 1) == '\n') index += 1
                        line += 1
                    }
                    else -> field.append(char)
                }
            } else {
                when {
                    char == delimiter -> {
                        recordHasSyntax = true
                        finishField()
                    }
                    char == '\r' || char == '\n' -> {
                        finishRecord()
                        if (char == '\r' && source.getOrNull(index + 1) == '\n') index += 1
                        line += 1
                        recordLine = line
                    }
                    quoteClosed && (char == ' ' || char == '\t') -> Unit
                    quoteClosed -> fail("há texto depois do fechamento das aspas.")
                    char == '"' -> {
                        if (field.isNotEmpty()) {
                            fail("as aspas devem envolver o campo inteiro. Use duas aspas para representar uma aspa no texto.")
                        }
                        quoted = true
                        recordHasSyntax = true
                    }
                    else -> field.append(char)
                }
            }
            index += 1
        }
        if (quoted) fail("há um campo com aspas abertas e sem fechamento.")
        if (field.isNotEmpty() || fields.isNotEmpty() || recordHasSyntax || quoteClosed) finishRecord()
        if (result.isEmpty()) {
            throw CsvImportException("O arquivo não contém cartões. Adicione dados abaixo do cabeçalho.")
        }
        return result
    }

    private fun isHeader(front: String, back: String): Boolean =
        when (front.lowercase() to back.lowercase()) {
            "pergunta" to "resposta",
            "frente" to "verso",
            "question" to "answer",
            "front" to "back" -> true
            else -> false
        }

    private fun delimiterCandidates(source: String): List<Char> {
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        var quoted = false
        var nonblank = false
        var index = 0
        while (index < source.length) {
            val char = source[index]
            when {
                char == '"' && quoted && source.getOrNull(index + 1) == '"' -> index += 1
                char == '"' -> {
                    quoted = !quoted
                    nonblank = true
                }
                !quoted && (char == '\n' || char == '\r') -> if (nonblank) break
                !quoted && char in counts -> {
                    counts[char] = counts.getValue(char) + 1
                    nonblank = true
                }
                !char.isWhitespace() -> nonblank = true
            }
            index += 1
        }
        // Prefer a separator that produces exactly two fields in the first
        // record. Stable ordering resolves genuinely ambiguous files as CSV.
        return counts.keys.sortedBy { delimiter ->
            val count = counts.getValue(delimiter)
            when {
                count == 1 -> 0
                count > 1 -> 1
                else -> 2
            }
        }
    }
}
