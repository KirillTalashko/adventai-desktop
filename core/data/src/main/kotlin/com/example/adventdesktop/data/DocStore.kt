package com.example.adventdesktop.data

import java.io.File

/**
 * Документы ДЕЛА (День 13; скоуп по диалогу — фикс 24.08.2026): приложенные пользователем файлы копируются
 * в `docs/<convId>/` каталога аккаунта.
 *
 * Раньше всё складывалось одной кучей в `docs/`, поэтому навык `docs check` в НОВОМ диалоге поднимал
 * документы всех прошлых поездок и объявлял пакет невалидным (бронь в Лондон против поездки в Испанию).
 * Файлы, приложенные до фикса, остаются в корне `docs/` и видны только через [listArchive] — в проверку
 * текущего дела они не попадают.
 */
class DocStore(store: FileStore) {
    private val root = store.dir("docs")

    /** Каталог конкретного дела. Пустой [convId] → корень (архив старых файлов). */
    private fun dirOf(convId: String): File =
        if (convId.isBlank()) root else File(root, safeDir(convId)).apply { mkdirs() }

    /** Файлы, приложенные В ЭТОМ диалоге, — единственный вход для проверки текущего дела. */
    fun list(convId: String): List<File> = filesIn(dirOf(convId))

    /** Файлы из корня `docs/` — общий архив аккаунта (приложены до скоупа по диалогу). */
    fun listArchive(): List<File> = filesIn(root)

    /** Скопировать [source] в каталог дела; вернуть сохранённое имя (с защитой от коллизий) или null при ошибке. */
    fun save(convId: String, source: File): String? = runCatching {
        val dir = dirOf(convId)
        val safe = source.name.replace(UNSAFE_FILE, "_").ifBlank { "document" }
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

    private fun filesIn(dir: File): List<File> =
        (dir.listFiles()?.filter { it.isFile } ?: emptyList()).sortedBy { it.name }

    private companion object {
        val UNSAFE_FILE = Regex("[\\/:*?\"<>|]")

        /**
         * Имя каталога дела. `--conv` в CLI приходит от МОДЕЛИ, поэтому оставляем только буквы/цифры/`-`/`_`:
         * точки вырезаны, значит `..` и выход из каталога аккаунта невозможны в принципе.
         */
        fun safeDir(convId: String): String = convId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    }
}
