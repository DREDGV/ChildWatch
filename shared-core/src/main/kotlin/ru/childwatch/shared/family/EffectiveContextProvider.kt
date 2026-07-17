package ru.childwatch.shared.family

import kotlinx.coroutines.flow.StateFlow

interface EffectiveContextProvider {
    fun current(): ActiveContext?
    fun observe(): StateFlow<ActiveContext?>
    fun refresh(): ActiveContext?
}
