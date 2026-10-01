package ru.example.childwatch.profile

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import ru.example.childwatch.designsystem.AvatarPresetCatalog
import ru.example.childwatch.designsystem.LetterAvatarFactory
import ru.example.childwatch.R

data class FamilyAvatarPreset(val storageValue: String, @DrawableRes val drawableRes: Int)

/** Renders one stored avatar value consistently in every parent feature. */
object FamilyAvatarRenderer {

    private const val TAG = "FamilyAvatarRenderer"
    private val mapPhotos = object : android.util.LruCache<String, android.graphics.Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
    private val failedMapPhotos = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** IO only; keep a copy because clearing a Glide target can recycle its bitmap. */
    fun preloadMapPhoto(context: Context, value: String?) {
        val url = absoluteUrl(context, value) ?: return
        if (mapPhotos.get(url) != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (failedMapPhotos[url]?.let { now - it < 30_000L } == true) return
        val target = Glide.with(context.applicationContext).asBitmap().load(url)
            .override(256, 256).timeout(5000).submit()
        try {
            val bitmap = target.get(6, java.util.concurrent.TimeUnit.SECONDS)
            val copy = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            if (copy != null) { mapPhotos.put(url, copy); failedMapPhotos.remove(url) }
        } catch (error: Exception) {
            failedMapPhotos[url] = android.os.SystemClock.elapsedRealtime()
        } finally { Glide.with(context.applicationContext).clear(target) }
    }

    /**
     * Where an uploaded picture lives, as the server writes it in a profile.
     *
     * The stored value is a path, not an address, so the address of the server is
     * added when the picture is shown. A path is also the only shape worth asking
     * the server for: a stored profile can never point at somebody else's website.
     */
    private const val UPLOADED_AVATAR_PREFIX = "/avatars/"

    /**
     * Answers with the address of the server this app talks to.
     *
     * Both an uploaded picture and every network call have to agree on which server
     * that is, so this reads the same stored setting through the same resolver the
     * rest of the app uses rather than keeping a second copy of the address.
     */
    @Volatile
    private var serverUrlProvider: (Context) -> String = { context ->
        ParentEffectiveContextResolver(context).resolveServerUrl()
    }

    /** Replaces the source of the server address; the default reads the app's setting. */
    fun setServerUrlProvider(provider: (Context) -> String) {
        serverUrlProvider = provider
    }

    /**
     * The avatars offered for choosing, in the order they are shown.
     *
     * Held as plain values rather than as drawable resources: the artwork comes
     * from the shared sheet through [drawable]. An earlier version associated each
     * value with one of six legacy images, so a picker built from that list showed
     * six repeated pictures instead of the twenty-five actual avatars.
     */
    fun selectableValues(): List<String> = AvatarPresetCatalog.keys()

    private val legacyPresets = listOf(
        FamilyAvatarPreset("preset:sky", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:mint", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:sun", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:coral", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:lilac", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:ocean", R.drawable.avatar_family_ocean)
    )

    val presets = listOf(
        FamilyAvatarPreset("preset:corgi", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:dinosaur", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:robot", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:cactus", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:penguin", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:astronaut", R.drawable.avatar_family_ocean),
        FamilyAvatarPreset("preset:donut", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:cat", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:pizza", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:unicorn", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:monster", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:mug", R.drawable.avatar_family_ocean),
        FamilyAvatarPreset("preset:avocado", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:panda", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:rocket", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:alien", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:shark", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:burger", R.drawable.avatar_family_ocean),
        FamilyAvatarPreset("preset:chick", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:frog", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:llama", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:sloth", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:controller", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:pineapple", R.drawable.avatar_family_ocean),
        FamilyAvatarPreset("preset:cloud", R.drawable.avatar_family_sky)
    )

    /**
     * Renders [avatarValue] when it resolves to a picture, otherwise a letter
     * avatar for [displayName].
     *
     * The letter fallback replaces the previous behaviour of drawing one of the
     * legacy preset images (or a blank silhouette) for every person, which is
     * why chat rows looked like a row of identical figures.
     *
     * A picture the person uploaded lives on the server, so it is fetched rather
     * than opened from this phone. The letter is shown while that fetch runs and
     * if it fails, which is the same thing the rest of the app already did for a
     * person with no picture at all.
     */
    fun bind(
        view: ImageView,
        avatarValue: String?,
        displayName: String? = null,
        @DrawableRes fallbackRes: Int = R.drawable.avatar_family_mint
    ) {
        // A delayed network photo must not replace a newly chosen preset/local crop.
        runCatching { Glide.with(view).clear(view) }
        view.imageTintList = null
        val letter = LetterAvatarFactory.create(view.context, displayName)

        if (isUploadedValue(avatarValue)) {
            bindUploaded(view, avatarValue.orEmpty(), letter)
            return
        }

        drawable(view.context, avatarValue)?.let { view.setImageDrawable(it); return }
        view.setImageDrawable(letter)
    }

    /**
     * Fetches a picture that lives on the server.
     *
     * Glide does the fetching off the main thread and caches the answer, which is
     * what makes this usable from a list: rows are bound as they scroll, and the
     * picture of a person does not have to be downloaded again for each of them.
     * An address that cannot be built, or a screen that has already gone away,
     * falls back to the letter instead of throwing.
     */
    private fun bindUploaded(view: ImageView, avatarValue: String, letter: Drawable) {
        val url = absoluteUrl(view.context, avatarValue)
        if (url == null) {
            view.setImageDrawable(letter)
            return
        }
        try {
            Glide.with(view)
                .load(url)
                .placeholder(letter)
                .error(letter)
                .into(view)
        } catch (error: Exception) {
            Log.w(TAG, "An uploaded picture could not be requested", error)
            view.setImageDrawable(letter)
        }
    }

    /** Whether a stored value names a picture this server holds, rather than a preset. */
    fun isUploadedValue(avatarValue: String?): Boolean =
        avatarValue?.trim()?.startsWith(UPLOADED_AVATAR_PREFIX) == true

    /**
     * The address of a stored picture, or null when there is no server to ask.
     *
     * Only the configured address is prefixed: the stored value is a path, and
     * whatever else it might be is not something this app should fetch.
     */
    fun absoluteUrl(context: Context, avatarValue: String?): String? {
        val value = avatarValue?.trim().orEmpty()
        if (!value.startsWith(UPLOADED_AVATAR_PREFIX)) return null
        val base = runCatching { serverUrlProvider(context).trim() }.getOrNull()
        if (base.isNullOrBlank()) return null
        return base.trimEnd('/') + value
    }

    /** Preset picture for a stored value, or null when the value is not one. */
    fun drawable(context: Context, avatarValue: String?): Drawable? {
        val value = avatarValue?.trim().orEmpty()
        if (value.isBlank()) return null
        AvatarPresetCatalog.createDrawable(context, value)?.let { return it }
        legacyPreset(value)?.let { return ContextCompat.getDrawable(context, it.drawableRes) }
        // Map callers prefetch photos on IO; drawing only reads the bounded cache.
        if (value.startsWith(UPLOADED_AVATAR_PREFIX)) {
            val url = absoluteUrl(context, value) ?: return null
            return mapPhotos.get(url)?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) }
        }
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(value))?.use { stream ->
                Drawable.createFromStream(stream, value)
            }
        }.getOrNull()
    }

    fun isPreset(value: String?): Boolean = AvatarPresetCatalog.isPreset(value) || legacyPreset(value.orEmpty()) != null

    /**
     * Decodes the preset sheet and [avatarValues] before anything is drawn.
     *
     * Cropping a preset needs the whole sheet decoded, and doing that on the main
     * thread cost about a second of skipped frames on the first map that drew
     * avatars. Callers run this on a background thread while the positions are
     * still being fetched, so the drawing itself only crops what is ready.
     */
    fun warmUp(context: Context, avatarValues: Collection<String?>) {
        AvatarPresetCatalog.warmUp(context, avatarValues.filterNotNull())
    }

    private fun preset(value: String): FamilyAvatarPreset? = presets.firstOrNull { it.storageValue == value }

    private fun legacyPreset(value: String): FamilyAvatarPreset? =
        legacyPresets.firstOrNull { it.storageValue == value }
}
