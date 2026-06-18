package com.example.adventdesktop.data

import java.io.File

/** Тонкая обёртка над файловой системой (DRY): репозитории читают/пишут через неё. */
class FileStore(val root: File) {
    init {
        root.mkdirs()
    }

    fun dir(name: String): File = File(root, name).apply { mkdirs() }

    fun exists(file: File): Boolean = file.exists()

    fun readText(file: File): String = if (file.exists()) file.readText() else ""

    fun writeText(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun list(dir: File): List<File> = dir.listFiles()?.toList() ?: emptyList()

    fun delete(file: File) {
        if (file.exists()) file.delete()
    }
}

/** Каталог данных приложения: `<home>/.adventai`. */
internal fun appHomeDir(): File {
    val home = System.getProperty("user.home") ?: "."
    return File(home, ".adventai")
}
