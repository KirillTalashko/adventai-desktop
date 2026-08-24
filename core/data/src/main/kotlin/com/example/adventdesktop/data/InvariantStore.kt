package com.example.adventdesktop.data

import com.example.adventdesktop.domain.Invariant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/** Пользовательские инварианты аккаунта: `invariants.json` (отдельно от диалога; встроенные — в коде). */
class InvariantStore(private val store: FileStore) {
    private val file = File(store.root, "invariants.json")

    fun load(): List<Invariant> = runCatching {
        store.readText(file).takeIf { it.isNotBlank() }
            ?.let { appJson.decodeFromString<List<InvariantDto>>(it).map { d -> d.toDomain() } }
    }.getOrNull() ?: emptyList()

    fun save(invariants: List<Invariant>) {
        store.writeText(file, appJson.encodeToString(invariants.map { it.toDto() }))
    }
}
