package com.musicaia

import com.musicaia.modelo.BardoGuidedResponseParser
import org.junit.Assert.*
import org.junit.Test

class BardoGuidedResponseParserTest {
    @Test fun extractsSeveralFieldsFromNonEnglishInputWhenEvidenceMatches() {
        val message = "Je veux une chanson de rock sur la résilience, lente et mélancolique."
        val json = """{
          "fields": {
            "theme": {"value":"résilience","evidence":"sur la résilience"},
            "style": {"value":"rock","evidence":"rock"},
            "mood": {"value":"mélancolique","evidence":"mélancolique"},
            "rhythm": {"value":"slow","evidence":"lente"}
          },
          "reply":"Quel type de voix souhaitez-vous ?"
        }"""
        val parsed = BardoGuidedResponseParser.parse(json, message, emptyMap())!!
        assertEquals("résilience", parsed.updates["tema"])
        assertEquals("rock", parsed.updates["estilo"])
        assertEquals("mélancolique", parsed.updates["ambiente"])
        assertEquals("slow", parsed.updates["ritmo"])
        assertEquals("Quel type de voix souhaitez-vous ?", parsed.reply)
    }

    @Test fun rejectsValuesWithoutVerbatimEvidenceInLatestMessage() {
        val json = """{"fields":{"style":{"value":"metal","evidence":"jazz"}},"reply":"Next?"}"""
        val parsed = BardoGuidedResponseParser.parse(json, "I want a rock song", emptyMap())!!
        assertTrue(parsed.updates.isEmpty())
    }

    @Test
    fun evidenceMustNotMatchOnlyAnAccidentalSubstringInsideAnotherWord() {
        val json = """{"fields":{"theme":{"value":"art","evidence":"art"}},"reply":"¿Qué estilo?"}"""
        val parsed = BardoGuidedResponseParser.parse(json, "I want a party song", emptyMap())!!
        assertTrue(parsed.updates.isEmpty())
    }

    @Test
    fun evidenceAtWholeWordBoundariesIsAccepted() {
        val json = """{"fields":{"theme":{"value":"party","evidence":"party"}},"reply":"¿Qué estilo?"}"""
        val parsed = BardoGuidedResponseParser.parse(json, "I want a party song", emptyMap())!!
        assertEquals("party", parsed.updates["tema"])
    }

    @Test fun negativeSpanishPreferenceIsNotStoredAsANewCreativeValue() {
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock"}},"reply":"Entendido."}"""
        val parsed = BardoGuidedResponseParser.parse(json, "No quiero rock", emptyMap())!!
        assertTrue(parsed.updates.isEmpty())
    }

    @Test fun negativeEnglishPreferenceIsNotStoredAsANewCreativeValue() {
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock"}},"reply":"Understood."}"""
        val parsed = BardoGuidedResponseParser.parse(json, "I don't want rock", emptyMap())!!
        assertTrue(parsed.updates.isEmpty())
    }

    @Test fun commaSeparatedAffirmativePreferenceIsStillAccepted() {
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock"}},"reply":"De acuerdo."}"""
        val parsed = BardoGuidedResponseParser.parse(json, "No, quiero rock", emptyMap())!!
        assertEquals("rock", parsed.updates["estilo"])
    }

    @Test fun doesNotOverwriteKnownFieldUnlessModelMarksExplicitChange() {
        val existing = mapOf("estilo" to "pop")
        val withoutChange = """{"fields":{"style":{"value":"rock","evidence":"rock","change":false}},"reply":"Next?"}"""
        val withChange = """{"fields":{"style":{"value":"rock","evidence":"rock","change":true}},"reply":"Next?"}"""
        assertTrue(BardoGuidedResponseParser.parse(withoutChange, "Actually change it to rock", existing)!!.updates.isEmpty())
        assertEquals("rock", BardoGuidedResponseParser.parse(withChange, "Actually change it to rock", existing)!!.updates["estilo"])
    }

    @Test fun modelCannotInventAChangeFlagToOverwriteAnExistingField() {
        val existing = mapOf("estilo" to "pop")
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock","change":true}},"reply":"Got it."}"""
        val parsed = BardoGuidedResponseParser.parse(
            json, "I want a rock song", existing
        )!!
        assertTrue(parsed.updates.isEmpty())
    }

    @Test fun aStandaloneNegativeDoesNotAuthorizeAnExistingFieldOverwrite() {
        val existing = mapOf("estilo" to "pop")
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock","change":true}},"reply":"Got it."}"""
        val parsed = BardoGuidedResponseParser.parse(
            json, "No thanks, I want a rock song", existing
        )!!
        assertTrue(parsed.updates.isEmpty())
    }

    @Test fun explicitCorrectionLanguageAllowsAChangeFlagToReplaceAnExistingField() {
        val existing = mapOf("estilo" to "pop")
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock","change":true}},"reply":"Got it."}"""
        val parsed = BardoGuidedResponseParser.parse(
            json, "Actually change it to rock", existing
        )!!
        assertEquals("rock", parsed.updates["estilo"])
    }

    @Test fun rejectsChangeFlagWithoutExplicitCorrectionEvenWhenEvidenceMatches() {
        val existing = mapOf("estilo" to "pop")
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock","change":false}},"reply":"Got it."}"""
        val parsed = BardoGuidedResponseParser.parse(
            json, "No, I prefer rock", existing
        )!!
        assertTrue(parsed.updates.isEmpty())
    }

    @Test fun negatedSpanishChangeDoesNotOverwriteAnExistingPreference() {
        val existing = mapOf("estilo" to "pop")
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock","change":true}},"reply":"Entendido."}"""
        val parsed = BardoGuidedResponseParser.parse(
            json, "No quiero cambiar el estilo a rock", existing
        )!!
        assertTrue(parsed.updates.isEmpty())
    }

    @Test fun negatedEnglishChangeDoesNotOverwriteAnExistingPreference() {
        val existing = mapOf("estilo" to "pop")
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock","change":true}},"reply":"Understood."}"""
        val parsed = BardoGuidedResponseParser.parse(
            json, "I don't want to change the style to rock", existing
        )!!
        assertTrue(parsed.updates.isEmpty())
    }

    @Test fun affirmativeSpanishChangeStillAllowsAnExplicitPreferenceUpdate() {
        val existing = mapOf("estilo" to "pop")
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock","change":true}},"reply":"De acuerdo."}"""
        val parsed = BardoGuidedResponseParser.parse(
            json, "Cambia el estilo a rock", existing
        )!!
        assertEquals("rock", parsed.updates["estilo"])
    }

    @Test fun commaSeparatedNoDoesNotCancelAnAffirmativeSpanishCorrection() {
        val existing = mapOf("estilo" to "pop")
        val json = """{"fields":{"style":{"value":"rock","evidence":"rock","change":true}},"reply":"De acuerdo."}"""
        val parsed = BardoGuidedResponseParser.parse(
            json, "No, quiero cambiar el estilo a rock", existing
        )!!
        assertEquals("rock", parsed.updates["estilo"])
    }

    @Test fun normalizesSongLanguageAndRejectsUnsupportedSongLanguages() {
        val spanish = """{"fields":{"language":{"value":"Spanish","evidence":"Spanish"}},"reply":"¿Qué estilo?"}"""
        val french = """{"fields":{"language":{"value":"French","evidence":"French"}},"reply":"Choose Spanish or English."}"""
        val parsedSpanish = BardoGuidedResponseParser.parse(spanish, "Please make it Spanish", emptyMap())!!
        assertEquals("Español", parsedSpanish.updates["idioma"])
        assertTrue(com.musicaia.modelo.BardoIntentRouter.isSupportedSongLanguage(parsedSpanish.updates["idioma"].orEmpty()))
        assertFalse(BardoGuidedResponseParser.parse(french, "Please make it French", emptyMap())!!.updates.containsKey("idioma"))
    }

    @Test fun normalizesVoiceTypeAndSource() {
        val json = """{"fields":{
          "type":{"value":"both","evidence":"vocals and instrumental"},
          "voice_source":{"value":"own","evidence":"my own voice"}
        },"reply":"What tempo?"}"""
        val parsed = BardoGuidedResponseParser.parse(
            json, "I want vocals and instrumental using my own voice", emptyMap()
        )!!
        assertEquals("ambas (voz e instrumental)", parsed.updates["tipo"])
        assertEquals("grabada", parsed.updates["vozFuente"])
    }

    @Test fun preservesConversationReplyWhenNoCreativeFieldsAreReturned() {
        val json = """{"reply":"La armonía menor aporta un carácter introspectivo."}"""
        val parsed = BardoGuidedResponseParser.parse(json, "Explícame la armonía menor", emptyMap())!!
        assertTrue(parsed.updates.isEmpty())
        assertEquals("La armonía menor aporta un carácter introspectivo.", parsed.reply)
    }

    @Test fun extractsPlainConversationalTextWithoutTreatingItAsCreativeData() {
        val reply = BardoGuidedResponseParser.extractFreeformReply("La síncopa desplaza el acento rítmico esperado.")
        assertEquals("La síncopa desplaza el acento rítmico esperado.", reply)
        assertNull(BardoGuidedResponseParser.extractFreeformReply("{\"reply\":\"texto\"}"))
    }

    @Test fun rejectsMalformedOrIncompleteStructuredResponses() {
        assertNull(BardoGuidedResponseParser.parse("not JSON", "hello", emptyMap()))
        assertNull(BardoGuidedResponseParser.parse("""{"fields":{},"reply":""}""", "hello", emptyMap()))
    }
}
