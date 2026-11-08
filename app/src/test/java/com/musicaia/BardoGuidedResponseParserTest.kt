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

    @Test fun doesNotOverwriteKnownFieldUnlessModelMarksExplicitChange() {
        val existing = mapOf("estilo" to "pop")
        val withoutChange = """{"fields":{"style":{"value":"rock","evidence":"rock","change":false}},"reply":"Next?"}"""
        val withChange = """{"fields":{"style":{"value":"rock","evidence":"rock","change":true}},"reply":"Next?"}"""
        assertTrue(BardoGuidedResponseParser.parse(withoutChange, "Actually change it to rock", existing)!!.updates.isEmpty())
        assertEquals("rock", BardoGuidedResponseParser.parse(withChange, "Actually change it to rock", existing)!!.updates["estilo"])
    }

    @Test fun normalizesSongLanguageAndRejectsUnsupportedSongLanguages() {
        val spanish = """{"fields":{"language":{"value":"Spanish","evidence":"Spanish"}},"reply":"¿Qué estilo?"}"""
        val french = """{"fields":{"language":{"value":"French","evidence":"French"}},"reply":"Choose Spanish or English."}"""
        assertEquals("es", BardoGuidedResponseParser.parse(spanish, "Please make it Spanish", emptyMap())!!.updates["idioma"])
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
        assertEquals("ambas", parsed.updates["tipo"])
        assertEquals("propia", parsed.updates["vozFuente"])
    }

    @Test fun rejectsMalformedOrIncompleteStructuredResponses() {
        assertNull(BardoGuidedResponseParser.parse("not JSON", "hello", emptyMap()))
        assertNull(BardoGuidedResponseParser.parse("""{"fields":{},"reply":""}""", "hello", emptyMap()))
    }
}
