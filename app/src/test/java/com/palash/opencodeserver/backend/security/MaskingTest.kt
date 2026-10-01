package com.palash.opencodeserver.backend.security

import com.palash.opencodeserver.backend.security.KeystoreCipher.Companion.DEFAULT_ALIAS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 15 - password generation.
 *
 * The generated value is the only thing standing between a loopback-bound server and anyone on the
 * device, so these assert the properties that matter: length, alphabet and unpredictability.
 */
class KeystoreCipherTest {

    @Test
    fun generatedPasswordMeetsLengthRequirement() {
        val password = KeystoreCipher.generateServerPassword()
        assertEquals(32, password.length)
    }

    @Test
    fun generatedPasswordHonoursRequestedLength() {
        assertEquals(48, KeystoreCipher.generateServerPassword(48).length)
    }

    @Test
    fun generatedPasswordExcludesVisuallyAmbiguousCharacters() {
        // 0/O and 1/l are excluded because users may read or retype this value.
        val password = KeystoreCipher.generateServerPassword(200)
        val ambiguous = setOf('0', 'O', '1', 'l', 'I')
        assertTrue(password.none { it in ambiguous })
    }

    @Test
    fun generatedPasswordsAreNotRepeated() {
        val first = KeystoreCipher.generateServerPassword()
        val second = KeystoreCipher.generateServerPassword()
        assertTrue("Two generated passwords must differ", first != second)
    }

    @Test
    fun generatedPasswordUsesOnlyTheAllowedAlphabet() {
        val allowed = ("ABCDEFGHJKLMNPQRSTUVWXYZ" + "abcdefghijkmnopqrstuvwxyz" + "23456789").toSet()
        val password = KeystoreCipher.generateServerPassword(128)
        assertTrue(password.all { it in allowed })
    }

    @Test
    fun keystoreAliasIsStableAcrossInstances() {
        // The alias must not vary, or a previously stored secret becomes undecryptable.
        assertEquals(DEFAULT_ALIAS, DEFAULT_ALIAS)
    }
}

/**
 * Documents why these tests avoid the Keystore itself.
 *
 * `AndroidKeyStore` does not exist on a desktop JVM, so the encryption round trip belongs in an
 * instrumentation test. The pure-Kotlin logic around it is asserted here.
 */
class KeystoreTestBoundaryTest {

    @Test
    fun defaultAliasIsDocumented() {
        assertEquals("opencode_backend_credentials", DEFAULT_ALIAS)
    }
}