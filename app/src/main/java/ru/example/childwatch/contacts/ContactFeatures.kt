package ru.example.childwatch.contacts

object ContactFeatures {
    const val CHAT = 1
    const val MAP = 1 shl 1
    const val AUDIO = 1 shl 2
    const val PHOTO = 1 shl 3

    const val ALL = CHAT or MAP or AUDIO or PHOTO

    fun isAllowed(mask: Int, feature: Int): Boolean {
        return mask and feature == feature
    }
}
