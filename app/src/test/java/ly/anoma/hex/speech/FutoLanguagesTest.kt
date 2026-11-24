package ly.anoma.hex.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FutoLanguagesTest {
    @Test fun parsesAllFutoLanguagesAndNames() {
        val mappings = FutoLanguages.parseMappingYaml(File("src/main/assets/layouts/mapping.yaml").readText())
        val names = FutoLanguages.parseNamesYaml(File("src/main/assets/layouts/names.yaml").readText())
        assertTrue("Expected >100 FUTO languages, got ${mappings.size}", mappings.size > 100)
        assertEquals(listOf("qwerty", "qwertz", "dvorak", "azerty", "colemak", "colemak_dh", "colemak_dh_ansi", "bepo", "pcqwerty", "nordic", "workman", "kasroz", "clearflow"), mappings["en_US"])
        assertEquals(listOf("hindi", "hindi_compact", "hindi_devanagari"), mappings["hi"])
        assertEquals("Hinglish", names["hi_Latn"]?.get("en"))
        assertEquals("hi", FutoLanguages.whisperCodeFor("hi_Latn"))
        assertEquals("no", FutoLanguages.whisperCodeFor("nb"))
        assertEquals("he", FutoLanguages.whisperCodeFor("iw"))
    }
}
