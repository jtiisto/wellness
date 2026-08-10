package dev.jtiisto.wellness.core.data.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What may be saved as a server profile.
 *
 * The rejections matter more than the acceptances: a stored URL is concatenated
 * with endpoint paths, so anything that would be silently discarded when the
 * path is appended has to be refused up front rather than accepted and quietly
 * mangled.
 */
class ServerProfileValidationTest {

    private fun valid(nickname: String, url: String): ProfileValidation.Valid =
        ServerProfileValidation.validate(nickname, url) as ProfileValidation.Valid

    private fun invalid(nickname: String, url: String): String =
        (ServerProfileValidation.validate(nickname, url) as ProfileValidation.Invalid).message

    @Test
    @DisplayName("an ordinary tailnet address is accepted with both fields trimmed")
    fun happyPath() {
        val result = valid("  Laptop  ", "  https://pop-os.tailexample.ts.net:9443/wellness  ")

        assertEquals("Laptop", result.nickname)
        assertEquals("https://pop-os.tailexample.ts.net:9443/wellness", result.url)
    }

    @Test
    @DisplayName("http is accepted too — the dev server is plain HTTP inside the tailnet")
    fun httpIsAccepted() {
        assertEquals("http://localhost:9000/wellness", valid("Dev", "http://localhost:9000/wellness").url)
    }

    @Test
    @DisplayName("the scheme is matched case-insensitively")
    fun schemeIsCaseInsensitive() {
        assertEquals("HTTPS://host/wellness", valid("Up", "HTTPS://host/wellness").url)
    }

    @Test
    @DisplayName("a trailing slash is normalized away before storage")
    fun trailingSlashNormalized() {
        // ServerConfig tolerates either, but the list should not show two rows
        // that differ by a character the app ignores.
        assertEquals("https://host/wellness", valid("A", "https://host/wellness/").url)
        assertEquals("https://host", valid("A", "https://host///").url)
    }

    @Test
    @DisplayName("a blank nickname is refused")
    fun blankNickname() {
        assertEquals(ServerProfileValidation.NICKNAME_REQUIRED, invalid("   ", "https://host"))
    }

    @Test
    @DisplayName("the nickname cap is applied after trimming")
    fun nicknameCap() {
        val fits = "n".repeat(ServerProfileValidation.MAX_NICKNAME)
        assertEquals(fits, valid("  $fits  ", "https://host").nickname)
        assertEquals(ServerProfileValidation.NICKNAME_TOO_LONG, invalid("n".repeat(41), "https://host"))
    }

    @Test
    @DisplayName("a blank address is refused")
    fun blankUrl() {
        assertEquals(ServerProfileValidation.URL_REQUIRED, invalid("A", "  "))
    }

    @Test
    @DisplayName("the address cap is applied after trimming")
    fun urlCap() {
        val long = "https://host/" + "p".repeat(200)
        assertEquals(ServerProfileValidation.URL_TOO_LONG, invalid("A", long))
    }

    @Test
    @DisplayName("a relative address is refused: there is no host to send anything to")
    fun relativeUrlRefused() {
        assertEquals(ServerProfileValidation.URL_NOT_HTTP, invalid("A", "/wellness"))
        assertEquals(ServerProfileValidation.URL_NOT_HTTP, invalid("A", "host:9443/wellness"))
    }

    @Test
    @DisplayName("a non-http scheme is refused, opaque URIs included")
    fun nonHttpSchemeRefused() {
        assertEquals(ServerProfileValidation.URL_NOT_HTTP, invalid("A", "ftp://host/wellness"))
        assertEquals(ServerProfileValidation.URL_NOT_HTTP, invalid("A", "mailto:someone@example.com"))
    }

    @Test
    @DisplayName("a http(s) address with no authority at all is refused")
    fun emptyHostRefused() {
        // One slash rather than two: parseable, absolute, correct scheme, and no
        // host to send anything to.
        assertEquals(ServerProfileValidation.URL_NO_HOST, invalid("A", "https:/wellness"))
    }

    @Test
    @DisplayName("a bare scheme with an empty authority does not parse at all")
    fun bareSchemeIsMalformed() {
        assertEquals(ServerProfileValidation.URL_MALFORMED, invalid("A", "https://"))
    }

    @Test
    @DisplayName("user info is refused: it would put a credential in a plain-text column and every log line")
    fun userInfoRefused() {
        assertEquals(ServerProfileValidation.URL_HAS_EXTRAS, invalid("A", "https://user:pass@host/wellness"))
    }

    @Test
    @DisplayName("a query string is refused rather than silently dropped when a path is appended")
    fun queryRefused() {
        assertEquals(ServerProfileValidation.URL_HAS_EXTRAS, invalid("A", "https://host/wellness?debug=1"))
    }

    @Test
    @DisplayName("a fragment is refused for the same reason")
    fun fragmentRefused() {
        assertEquals(ServerProfileValidation.URL_HAS_EXTRAS, invalid("A", "https://host/wellness#here"))
    }

    @Test
    @DisplayName("an unparseable address is refused rather than throwing")
    fun malformedUrlRefused() {
        assertEquals(ServerProfileValidation.URL_MALFORMED, invalid("A", "https://ho st/wellness"))
    }

    @Test
    @DisplayName("an address with a port and a deep path is fine")
    fun portAndPathAccepted() {
        assertEquals("https://host:9443/a/b/c", valid("A", "https://host:9443/a/b/c").url)
    }
}
