package dev.jtiisto.wellness.core.data.network

import java.net.URI
import java.net.URISyntaxException

/** A profile the user typed, either accepted in stored form or rejected with a reason. */
sealed interface ProfileValidation {
    data class Valid(val nickname: String, val url: String) : ProfileValidation

    data class Invalid(val message: String) : ProfileValidation
}

/**
 * What may be saved as a server profile.
 *
 * The rules are tighter than "is this a URL" because of what the value is used
 * for: it is concatenated with endpoint paths by [ServerConfig.endpoint], and
 * anything after the path — a query string, a fragment — would be silently
 * discarded when the path is appended, producing a base URL that does not
 * behave the way it reads. Rejecting up front is the honest response; accepting
 * and quietly dropping half the input is not.
 *
 * User info is refused for a different reason: `http://user:pass@host` would put
 * a credential in a plain-text column, in a list rendered on screen, and in
 * every debug-log HTTP line.
 *
 * The trailing slash is normalized away before storage so the same address
 * typed two ways is stored one way — [ServerConfig] tolerates either, but the
 * list should not show two entries that differ by a character the app ignores.
 */
object ServerProfileValidation {

    const val MAX_NICKNAME = 40
    const val MAX_URL = 200

    const val NICKNAME_REQUIRED = "Name is required."
    const val NICKNAME_TOO_LONG = "Name must be $MAX_NICKNAME characters or fewer."
    const val URL_REQUIRED = "Address is required."
    const val URL_TOO_LONG = "Address must be $MAX_URL characters or fewer."
    const val URL_NOT_HTTP = "Address must start with http:// or https://"
    const val URL_NO_HOST = "Address must include a host."
    const val URL_HAS_EXTRAS = "Address must not contain a username, query or fragment."
    const val URL_MALFORMED = "Address is not a valid URL."

    fun validate(nickname: String, url: String): ProfileValidation {
        val name = nickname.trim()
        val address = url.trim()

        if (name.isEmpty()) return ProfileValidation.Invalid(NICKNAME_REQUIRED)
        if (name.length > MAX_NICKNAME) return ProfileValidation.Invalid(NICKNAME_TOO_LONG)
        if (address.isEmpty()) return ProfileValidation.Invalid(URL_REQUIRED)
        if (address.length > MAX_URL) return ProfileValidation.Invalid(URL_TOO_LONG)

        val parsed = try {
            URI(address)
        } catch (_: URISyntaxException) {
            return ProfileValidation.Invalid(URL_MALFORMED)
        }

        if (!parsed.isAbsolute) return ProfileValidation.Invalid(URL_NOT_HTTP)
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return ProfileValidation.Invalid(URL_NOT_HTTP)
        if (parsed.host.isNullOrBlank()) return ProfileValidation.Invalid(URL_NO_HOST)
        if (parsed.userInfo != null || parsed.query != null || parsed.fragment != null) {
            return ProfileValidation.Invalid(URL_HAS_EXTRAS)
        }

        return ProfileValidation.Valid(nickname = name, url = normalize(address))
    }

    /**
     * Drop trailing slashes. `https://host/` and `https://host` address the same
     * server, and [ServerConfig] joins paths onto either — but the list would
     * show them as two different entries.
     */
    fun normalize(url: String): String = url.trimEnd('/')
}
