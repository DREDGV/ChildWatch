package ru.example.parentwatch.session

import android.content.Context
import ru.childwatch.shared.family.ContextDiagnosticSnapshot
import ru.childwatch.shared.family.ContextDiagnostics

class ChildContextDiagnostics(context: Context) {
    private val provider = ChildEffectiveContextProvider.get(context)

    fun snapshot(): ContextDiagnosticSnapshot = ContextDiagnostics.snapshot(provider.current())
}
