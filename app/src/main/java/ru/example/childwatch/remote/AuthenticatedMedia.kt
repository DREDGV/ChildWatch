package ru.example.childwatch.remote

import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaderFactory
import com.bumptech.glide.load.model.LazyHeaders

/**
 * A picture that lives behind the server's authentication.
 *
 * The media routes answer `401 MISSING_TOKEN` to a request without a token, and
 * a plain `Glide.with(...).load(url)` sends none. Every thumbnail therefore
 * failed while the server held the files, so the screen showed placeholders for
 * photographs that existed: the gallery looked empty and saving a picture had
 * nothing to save.
 *
 * The token is read for each request rather than captured once, so a token
 * refreshed after a list was built is still the one that is sent.
 */
object AuthenticatedMedia {

    private const val AUTHORIZATION = "Authorization"

    fun url(rawUrl: String, tokenProvider: () -> String?): GlideUrl = GlideUrl(
        rawUrl,
        LazyHeaders.Builder()
            .addHeader(
                AUTHORIZATION,
                LazyHeaderFactory {
                    val token = tokenProvider()?.trim().orEmpty()
                    if (token.isEmpty()) "" else "Bearer $token"
                }
            )
            .build()
    )
}
