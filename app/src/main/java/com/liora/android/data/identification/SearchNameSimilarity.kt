package com.liora.android.data.identification

import java.text.Normalizer
import java.util.Locale

/**
 * Coincidencia de nombres al estilo de Cloud: se normaliza el texto (minúsculas,
 * sin tildes ni signos), se mide su distancia de Levenshtein y la coincidencia
 * se pondera 65 % título / 35 % artista. El umbral de 0,78 evita aceptar una
 * canción que solo comparte una palabra.
 */
internal object SearchNameSimilarity {

    /** Puntuación mínima para dar por válida una coincidencia. */
    const val MatchThreshold = 0.78

    fun score(
        title: String,
        artist: String,
        candidateTitle: String,
        candidateArtist: String,
    ): Double {
        val titleScore = similarity(title, candidateTitle)
        if (artist.isBlank()) return titleScore
        return titleScore * 0.65 + artistSimilarity(artist, candidateArtist) * 0.35
    }

    fun similarity(first: String, second: String): Double {
        val left = normalize(first)
        val right = normalize(second)
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val distance = levenshtein(left, right)
        return 1.0 - distance.toDouble() / maxOf(left.length, right.length)
    }

    /**
     * Los créditos de MusicBrainz suelen incluir invitados ("Cartoon & Jéja
     * feat. Coleman Trapp"). Además del texto completo se prueba cada artista
     * por separado para no perder la coincidencia.
     */
    fun artistSimilarity(localArtist: String, candidateArtist: String): Double {
        val local = normalize(localArtist)
        val candidate = normalize(candidateArtist)
        if (local.isEmpty() || candidate.isEmpty()) return 0.0

        val direct = similarity(local, candidate)
        if (candidate.contains(local) || local.contains(candidate)) {
            return maxOf(direct, 0.92)
        }

        val bestCredit = candidate.split(CreditSeparator)
            .filter { it.isNotBlank() }
            .maxOfOrNull { similarity(local, it) }
            ?: 0.0
        return maxOf(direct, bestCredit)
    }

    /** Minúsculas, sin tildes ni signos, con espacios simples. */
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(CombiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace(NonAlphanumeric, " ")
            .trim()
            .replace(ExtraSpaces, " ")
    }

    /** Distancia de Levenshtein con dos filas para no reservar una matriz entera. */
    private fun levenshtein(first: String, second: String): Int {
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length

        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)

        for (firstIndex in 1..first.length) {
            current[0] = firstIndex
            val firstChar = first[firstIndex - 1]
            for (secondIndex in 1..second.length) {
                val substitution = if (firstChar == second[secondIndex - 1]) 0 else 1
                current[secondIndex] = minOf(
                    current[secondIndex - 1] + 1,
                    previous[secondIndex] + 1,
                    previous[secondIndex - 1] + substitution,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }

    private val CombiningMarks = Regex("\\p{Mn}+")
    private val NonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")
    private val ExtraSpaces = Regex("\\s+")
    private val CreditSeparator = Regex(
        "\\s*(?:&|,|;|/|\\bfeat\\b\\.?|\\bft\\b\\.?|\\bwith\\b|\\band\\b|\\bvs\\b\\.?|\\bx\\b)\\s*",
    )
}
