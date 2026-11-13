package com.musicaia.modulos.voice

/**
 * Une la inspección de una voicebank con G2P español y el mapeo fonético
 * específico de esa voz. No crea una puntuación musical ni sintetiza WAV.
 */
object BardoDiffSingerSpanishG2pResolver {

    fun resolveLyrics(
        lyrics: String,
        inspection: DiffSingerVoicebankInspector.Inspection,
        predictWord: (String) -> BardoSpanishG2pOnnxEngine.Prediction
    ): BardoDiffSingerSpanishDictionary.Resolution {
        if (!inspection.readyForRuntimeProbe) {
            return incomplete("La voicebank no superó la inspección DiffSinger.")
        }
        val dictionary = inspection.spanishDictionary
            ?: return incomplete("La inspección no proporcionó un diccionario español validado.")

        return BardoDiffSingerSpanishDictionary.resolveLyricsWithG2pFallback(
            lyrics,
            dictionary,
            inspection.phonemeIds
        ) { word ->
            val prediction = try {
                predictWord(word)
            } catch (error: Exception) {
                return@resolveLyricsWithG2pFallback BardoDiffSingerSpanishDictionary.G2pFallback(
                    emptyList(),
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                )
            }
            when {
                prediction.isComplete -> BardoDiffSingerSpanishDictionary.G2pFallback(prediction.phonemes)
                prediction.unsupportedGraphemes.isNotEmpty() ->
                    BardoDiffSingerSpanishDictionary.G2pFallback(
                        emptyList(),
                        "Grafemas no admitidos: ${prediction.unsupportedGraphemes.joinToString()}."
                    )
                prediction.errors.isNotEmpty() ->
                    BardoDiffSingerSpanishDictionary.G2pFallback(
                        emptyList(),
                        prediction.errors.joinToString("; ")
                    )
                else -> BardoDiffSingerSpanishDictionary.G2pFallback(
                    emptyList(),
                    "El G2P no devolvió fonemas."
                )
            }
        }
    }

    private fun incomplete(message: String) =
        BardoDiffSingerSpanishDictionary.Resolution(
            words = emptyList(),
            unknownWords = emptyList(),
            unsupportedPhonemes = listOf(message)
        )
}
