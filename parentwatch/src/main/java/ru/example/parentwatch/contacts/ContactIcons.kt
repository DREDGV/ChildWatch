package ru.example.parentwatch.contacts

import ru.example.parentwatch.R

object ContactIcons {
    const val DEFAULT = 0
    const val CHILD = 1
    const val PARENT = 2
    const val CAMERA = 3
    const val HISTORY = 4
    const val LOCATION = 5
    const val ROUTE_START = 6
    const val ROUTE_END = 7

    data class Option(val id: Int, val label: String)

    fun options(): List<Option> {
        return listOf(
            Option(DEFAULT, "По умолчанию"),
            Option(CHILD, "Ребенок"),
            Option(PARENT, "Родитель"),
            Option(CAMERA, "Камера"),
            Option(HISTORY, "История"),
            Option(LOCATION, "Локация"),
            Option(ROUTE_START, "Старт"),
            Option(ROUTE_END, "Финиш")
        )
    }

    fun isKnown(iconId: Int): Boolean = options().any { it.id == iconId }

    fun labelFor(iconId: Int): String {
        return options().firstOrNull { it.id == iconId }?.label ?: options().first().label
    }

    fun resolve(iconId: Int, role: String): Int {
        return when (iconId) {
            CHILD -> R.drawable.ic_child_marker
            PARENT -> R.drawable.ic_parent_marker
            CAMERA -> R.drawable.ic_camera
            HISTORY -> R.drawable.ic_history
            LOCATION -> R.drawable.ic_distance
            ROUTE_START -> R.drawable.ic_route_start_marker
            ROUTE_END -> R.drawable.ic_route_end_marker
            else -> if (role == ContactRoles.CHILD) {
                R.drawable.ic_child_marker
            } else {
                R.drawable.ic_parent_marker
            }
        }
    }
}
