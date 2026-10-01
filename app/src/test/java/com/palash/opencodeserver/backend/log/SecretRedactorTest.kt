package com.palash.opencodeserver.backend.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 27/108/82 - the redaction tests that matter most.
 *
 * A leak here is a leaked credential in a bug report, so these assert on the *absence* of secrets
 * rather than the presence of replacements.
 */
class SecretRedactorTest {

    @After
    fun tearDown() {
        SecretRedactor.clearRegisteredSecrets()
    }

    @Test
    fun redactsSkStyleApiKey() {
        val input = "Using key sk-abcdefghijklmnop1234 for provider"
        val output = SecretRedactor.redact(input)

        assertFalse(output.contains("sk-abcdefghijklmnop1234"))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun redactsAnthropicStyleKey() {
        val output = SecretRedactor.redact("ANTHROPIC_API_KEY=sk-ant-api03-AAAABBBBCCCCDDDDEEEEFFFF")
        assertFalse(output.contains("sk-ant-api03"))
    }

    @Test
    fun redactsGithubToken() {
        val output = SecretRedactor.redact("export GITHUB_TOKEN=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123")
        assertFalse(output.contains("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
    }

    @Test
    fun redactsBearerToken() {
        val output = SecretRedactor.redact("Authorization: Bearer abc.def.ghi")
        assertFalse(output.contains("abc.def.ghi"))
    }

    @Test
    fun redactsBasicAuthHeader() {
        val output = SecretRedactor.redact("authorization: Basic b3BlbmNvZGU6c3VwZXJzZWNyZXQ=")
        assertFalse(output.contains("b3BlbmNvZGU6c3VwZXJzZWNyZXQ="))
    }

    @Test
    fun redactsPasswordAssignment() {
        val output = SecretRedactor.redact("OPENCODE_SERVER_PASSWORD=hunter2hunter2")
        assertFalse(output.contains("hunter2hunter2"))
    }

    @Test
    fun redactsCredentialsEmbeddedInUrl() {
        val output = SecretRedactor.redact("connecting to http://opencode:s3cr3tvalue@127.0.0.1:4096/doc")
        assertFalse(output.contains("s3cr3tvalue"))
    }

    @Test
    fun redactsCookieHeader() {
        val output = SecretRedactor.redact("Cookie: session=abc123; theme=dark")
        assertFalse(output.contains("session=abc123"))
    }

    @Test
    fun redactsPrivateKeyBlock() {
        val pem = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAxxxxxxxxxxxxxxxxxxxxxxxx
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val output = SecretRedactor.redact("key material follows:\n$pem")
        assertFalse(output.contains("MIIEowIBAAKCAQEA"))
        assertFalse(output.contains("BEGIN RSA PRIVATE KEY"))
    }

    @Test
    fun registeredSecretIsRedactedEvenWithoutRecognisableShape() {
        // A password with no distinctive prefix would slip past pattern matching alone.
        val secret = "zzq7plaintextvalue"
        SecretRedactor.registerSecret(secret)

        val output = SecretRedactor.redact("connecting with $secret now")
        assertFalse(output.contains(secret))
    }

    @Test
    fun shortRegisteredSecretIsIgnoredToProtectOrdinaryText() {
        SecretRedactor.registerSecret("abc")
        val output = SecretRedactor.redact("abc def")
        assertEquals("abc def", output)
    }

    @Test
    fun forgetsSecretOnRemoval() {
        val secret = "removablesecretvalue"
        SecretRedactor.registerSecret(secret)
        SecretRedactor.forgetSecret(secret)

        val output = SecretRedactor.redact("value is $secret")
        assertTrue(output.contains(secret))
    }

    @Test
    fun identifiesSensitiveKeys() {
        assertTrue(SecretRedactor.isSensitiveKey("OPENCODE_SERVER_PASSWORD"))
        assertTrue(SecretRedactor.isSensitiveKey("ANTHROPIC_API_KEY"))
        assertTrue(SecretRedactor.isSensitiveKey("Authorization"))
        assertTrue(SecretRedactor.isSensitiveKey("some.token"))
        assertFalse(SecretRedactor.isSensitiveKey("PATH"))
        assertFalse(SecretRedactor.isSensitiveKey("HOME"))
    }

    @Test
    fun redactsMapEntriesWithSensitiveKeys() {
        val redacted = SecretRedactor.redactAll(
            mapOf("PATH" to "/usr/bin", "OPENCODE_SERVER_PASSWORD" to "supersecretvalue")
        )
        assertEquals("/usr/bin", redacted["PATH"])
        assertFalse(redacted["OPENCODE_SERVER_PASSWORD"]!!.contains("supersecretvalue"))
    }

    @Test
    fun displayMaskingHidesTheMiddle() {
        val masked = SecretRedactor.maskForDisplay("sk-proj-ABCDEFGHIJKLMNOPQRSTUVWXYZ9X2A")
        assertTrue(masked.startsWith("sk-"))
        assertTrue(masked.endsWith("9X2A"))
        assertFalse(masked.contains("ABCDEFGHIJKLMNOPQRSTUV"))
    }

    @Test
    fun displayMaskingOfShortValueRevealsNothing() {
        val masked = SecretRedactor.maskForDisplay("abc")
        assertFalse(masked.contains("abc"))
    }

    @Test
    fun redactsPersonalPathsInDiagnostics() {
        val output = SecretRedactor.redactPaths(
            "Home is /data/user/0/com.palash.opencodeserver/files/runtime/home",
            "/data/user/0/com.palash.opencodeserver/files/runtime/home"
        )
        assertEquals("Home is ~", output)
    }

    @Test
    fun emptyInputIsHandled() {
        assertEquals("", SecretRedactor.redact(null))
        assertEquals("", SecretRedactor.redact(""))
    }
}