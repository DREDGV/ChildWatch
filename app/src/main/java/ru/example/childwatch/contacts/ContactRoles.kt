package ru.example.childwatch.contacts

object ContactRoles {
    const val CHILD = "child"
    const val PARENT = "parent"
    const val RELATIVE = "relative"

    fun label(role: String): String {
        return when (role) {
            PARENT -> "Родитель"
            RELATIVE -> "Родственник"
            else -> "Ребенок"
        }
    }

    fun fromLabel(label: String): String {
        return when (label.trim()) {
            "Родитель" -> PARENT
            "Родственник" -> RELATIVE
            else -> CHILD
        }
    }
}
