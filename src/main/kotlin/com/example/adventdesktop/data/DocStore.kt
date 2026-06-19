package com.example.adventdesktop.data

import java.io.File

/** Документы аккаунта (День 13): копирует приложенные пользователем файлы в `docs/` каталога аккаунта. */
class DocStore(store: FileStore) {
    private val dir = store.dir("docs")

    /** Скопировать [source] в `docs/`; вернуть сохранённое имя (с защитой от коллизий) или null при ошибке. */
    fun save(source: File): String? = runCatching {
        val safe = source.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "document" }
        var target = File(dir, safe)
        var i = 1
        while (target.exists()) {
            val dot = safe.lastIndexOf('.')
            val name = if (dot > 0) "${safe.substring(0, dot)}_$i${safe.substring(dot)}" else "${safe}_$i"
            target = File(dir, name)
            i++
        }
        source.copyTo(target, overwrite = false)
        target.name
    }.getOrNull()
}
