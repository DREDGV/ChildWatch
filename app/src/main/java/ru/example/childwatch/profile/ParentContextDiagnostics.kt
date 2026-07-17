package ru.example.childwatch.profile

import android.content.Context
import ru.childwatch.shared.family.ContextDiagnosticSnapshot
import ru.childwatch.shared.family.ContextDiagnostics

class ParentContextDiagnostics(context: Context) {
    private val provider = ParentEffectiveContextProvider.get(context)

    fun snapshot(): ContextDiagnosticSnapshot = ContextDiagnostics.snapshot(provider.current())
}
