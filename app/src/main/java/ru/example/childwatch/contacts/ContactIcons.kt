package ru.example.childwatch.contacts

import ru.example.childwatch.R

object ContactIcons {
    const val DEFAULT = 0
    const val CHILD = 1
    const val PARENT = 2
    const val CAMERA = 3
    const val HISTORY = 4
    const val LOCATION = 5

    data class Option(val id: Int, val label: String)

    fun options(): List<Option> {
        return listOf(
            Option(DEFAULT, "По умолчанию"),
            Option(CHILD, "Ребенок"),
            Option(PARENT, "Родитель"),
            Option(CAMERA, "Камера"),
            Option(HISTORY, "История"),
            Option(LOCATION, "Локация")
        )
    }

    fun resolve(iconId: Int, role: String): Int {
        return when (iconId) {
            CHILD -> R.drawable.ic_child_marker
            PARENT -> R.drawable.ic_parent_marker
            CAMERA -> R.drawable.ic_camera
            HISTORY -> R.drawable.ic_history
            LOCATION -> R.drawable.ic_distance
            else -> if (role == ContactRoles.CHILD) {
                R.drawable.ic_child_marker
            } else {
                R.drawable.ic_parent_marker
            }
        }
    }
}
