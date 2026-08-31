package com.example.adventdesktop.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Скоуп документов по ДЕЛУ (фикс 24.08.2026). Регрессия здесь не видна в UI и стоит дорого: навык
 * `docs check` начинает сверять текущую поездку с файлами прошлых дел и объявляет пакет невалидным
 * (бронь в Лондон против поездки в Испанию — реальный случай из диалога пользователя).
 *
 * Тесты работают во временном каталоге; `~/.adventai` не трогается.
 */
class DocStoreTest {

    private fun newStore(): Pair<DocStore, File> {
        val root = File.createTempFile("docstore_", "").apply { delete(); mkdirs(); deleteOnExit() }
        return DocStore(FileStore(root)) to root
    }

    private fun sourceFile(dirPrefix: String, name: String, text: String): File {
        val dir = File.createTempFile(dirPrefix, "").apply { delete(); mkdirs(); deleteOnExit() }
        return File(dir, name).apply { writeText(text); deleteOnExit() }
    }

    @Test
    fun `документы одного дела не видны в другом`() {
        val (store, _) = newStore()
        store.save("conv-a", sourceFile("caseA_", "passport.pdf", "дело A"))
        store.save("conv-b", sourceFile("caseB_", "booking.pdf", "дело B"))

        assertEquals(listOf("passport.pdf"), store.list("conv-a").map { it.name })
        assertEquals(listOf("booking.pdf"), store.list("conv-b").map { it.name })
        assertTrue(store.list("conv-c").isEmpty(), "в новом деле документов быть не должно")
    }

    @Test
    fun `файлы прошлых дел лежат в архиве и в дело не попадают`() {
        val (store, root) = newStore()
        File(root, "docs").mkdirs()
        File(root, "docs/legacy.pdf").writeText("файл из общей кучи до фикса")

        assertTrue(store.list("conv-new").isEmpty(), "архив не должен попадать в текущее дело")
        assertEquals(listOf("legacy.pdf"), store.listArchive().map { it.name })
    }

    @Test
    fun `id дела из CLI не выводит за каталог документов`() {
        val (store, root) = newStore()
        // `--conv` приходит от модели, поэтому проверяем именно попытку обхода каталога.
        assertTrue(store.save("../../escape", sourceFile("evil_", "evil.pdf", "x")) != null)

        val docsRoot = File(root, "docs")
        val saved = docsRoot.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, saved.size, "файл обязан остаться внутри docs/")
        assertTrue(store.listArchive().isEmpty(), "и не должен лечь в корень архива")
    }

    @Test
    fun `коллизия имён не перетирает уже приложенный файл`() {
        val (store, _) = newStore()
        val first = store.save("conv-a", sourceFile("first_", "passport.pdf", "первый"))
        val second = store.save("conv-a", sourceFile("second_", "passport.pdf", "второй"))

        assertEquals("passport.pdf", first)
        assertEquals("passport_1.pdf", second)
        assertEquals(2, store.list("conv-a").size)
    }
}
