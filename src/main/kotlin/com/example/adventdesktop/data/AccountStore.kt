package com.example.adventdesktop.data

import com.example.adventdesktop.domain.Account
import com.example.adventdesktop.domain.ConversationRepository
import com.example.adventdesktop.domain.MemoryStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.random.Random

/** Снимок состояния аккаунтов: список + активный. */
data class AccountsState(val accounts: List<Account>, val activeId: String)

/**
 * Локальные аккаунты (Day 12). Список — в `~/.adventai/accounts.json`, данные каждого аккаунта изолированы
 * в `~/.adventai/accounts/<id>/` (профиль, диалоги, память). Ключи API — глобальные (см. [ConfigStore]).
 */
class AccountStore(private val root: FileStore) {
    private val file = File(root.root, "accounts.json")
    private val accountsDir = root.dir("accounts")

    fun state(): AccountsState {
        val dto = raw()
        return AccountsState(dto.accounts.map { it.toDomain() }, dto.activeId)
    }

    fun create(name: String): Account {
        val now = nowMs()
        val id = "acc-${now / 1000}-${Random.nextInt(1000, 9999)}"
        val dto = AccountDto(id, name.ifBlank { "Профиль" }, now)
        val current = raw()
        write(current.copy(accounts = current.accounts + dto, activeId = id))
        accountRoot(id) // создать каталог аккаунта
        return dto.toDomain()
    }

    fun setActive(id: String) {
        val current = raw()
        if (current.activeId != id) write(current.copy(activeId = id))
    }

    fun delete(id: String) {
        val current = raw()
        val rest = current.accounts.filterNot { it.id == id }
        val active = if (current.activeId == id) rest.firstOrNull()?.id.orEmpty() else current.activeId
        write(current.copy(accounts = rest, activeId = active))
        // Удаляем и данные аккаунта с диска (диалоги, память, профиль, документы) — без следов.
        runCatching { File(accountsDir, id).deleteRecursively() }
    }

    fun conversations(accountId: String): ConversationRepository = FileConversationRepository(accountRoot(accountId))
    fun memory(accountId: String): MemoryStore = FileMemoryStore(accountRoot(accountId))
    fun profiles(accountId: String): ProfileStore = ProfileStore(accountRoot(accountId))
    fun docs(accountId: String): DocStore = DocStore(accountRoot(accountId))
    fun invariants(accountId: String): InvariantStore = InvariantStore(accountRoot(accountId))

    private fun accountRoot(id: String) = FileStore(File(accountsDir, id))

    private fun raw(): AccountsFileDto = runCatching {
        root.readText(file).takeIf { it.isNotBlank() }?.let { appJson.decodeFromString<AccountsFileDto>(it) }
    }.getOrNull() ?: AccountsFileDto()

    private fun write(dto: AccountsFileDto) {
        root.writeText(file, appJson.encodeToString(dto))
    }
}
