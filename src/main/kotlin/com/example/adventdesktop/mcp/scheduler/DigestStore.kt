package com.example.adventdesktop.mcp.scheduler

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/** Страна в подписке дайджеста (что периодически перепроверяем). */
data class DigestCountry(
    val id: Long,
    val destination: String,
    val citizenship: String,
    val purpose: String,
)

/** Снимок визовой сводки по стране на момент сбора (накапливаются в истории). */
data class DigestSnapshot(
    val id: Long,
    val countryId: Long,
    val collectedAt: String,
    val summary: String,
    val sources: String,
)

/**
 * Хранилище планировщика (День 18) на **SQLite** (sqlite-jdbc, чистый JDBC — без ORM, KISS).
 *
 * Две таблицы: подписки [DigestCountry] и накапливаемые снимки [DigestSnapshot]. Одно соединение на весь
 * процесс сервера; все операции `@Synchronized` (низкая конкуренция: фон-планировщик + редкие вызовы тулзов).
 * Схема создаётся идемпотентно. Путь к файлу — из вызывающего ([DIGEST_DB] в сервере).
 */
class DigestStore(dbPath: String) {

    private val conn: Connection

    init {
        File(dbPath).absoluteFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA busy_timeout = 5000")
            st.executeUpdate("PRAGMA foreign_keys = ON")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS digest_country (
                       id          INTEGER PRIMARY KEY AUTOINCREMENT,
                       destination TEXT NOT NULL,
                       citizenship TEXT NOT NULL,
                       purpose     TEXT NOT NULL,
                       created_at  TEXT NOT NULL,
                       UNIQUE(destination, citizenship, purpose)
                   )""",
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS digest_snapshot (
                       id           INTEGER PRIMARY KEY AUTOINCREMENT,
                       country_id   INTEGER NOT NULL,
                       collected_at TEXT NOT NULL,
                       summary      TEXT NOT NULL,
                       sources      TEXT NOT NULL DEFAULT '',
                       FOREIGN KEY(country_id) REFERENCES digest_country(id) ON DELETE CASCADE
                   )""",
            )
        }
    }

    /** Добавить страну (идемпотентно по тройке destination+citizenship+purpose) и вернуть её запись. */
    @Synchronized
    fun addCountry(destination: String, citizenship: String, purpose: String): DigestCountry {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO digest_country(destination, citizenship, purpose, created_at) VALUES(?,?,?,?)",
        ).use { ps ->
            ps.setString(1, destination); ps.setString(2, citizenship)
            ps.setString(3, purpose); ps.setString(4, Instant.now().toString())
            ps.executeUpdate()
        }
        return findCountry(destination, citizenship, purpose)
            ?: error("digest_country не сохранилась: $destination/$citizenship/$purpose")
    }

    @Synchronized
    fun listCountries(): List<DigestCountry> = buildList {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id, destination, citizenship, purpose FROM digest_country ORDER BY id").use { rs ->
                while (rs.next()) add(rs.toCountry())
            }
        }
    }

    /** Удалить подписку по названию страны (без учёта регистра). Возвращает число удалённых строк. */
    @Synchronized
    fun removeCountry(destination: String): Int =
        conn.prepareStatement("DELETE FROM digest_country WHERE lower(destination) = lower(?)").use { ps ->
            ps.setString(1, destination)
            ps.executeUpdate()
        }

    @Synchronized
    fun saveSnapshot(countryId: Long, summary: String, sources: String = "") {
        conn.prepareStatement(
            "INSERT INTO digest_snapshot(country_id, collected_at, summary, sources) VALUES(?,?,?,?)",
        ).use { ps ->
            ps.setLong(1, countryId); ps.setString(2, Instant.now().toString())
            ps.setString(3, summary); ps.setString(4, sources)
            ps.executeUpdate()
        }
    }

    /** Последний (самый свежий) снимок по стране или null, если сбора ещё не было. */
    @Synchronized
    fun latestSnapshot(countryId: Long): DigestSnapshot? =
        conn.prepareStatement(
            "SELECT id, country_id, collected_at, summary, sources FROM digest_snapshot " +
                "WHERE country_id = ? ORDER BY id DESC LIMIT 1",
        ).use { ps ->
            ps.setLong(1, countryId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toSnapshot() else null }
        }

    @Synchronized
    fun snapshotCount(countryId: Long): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM digest_snapshot WHERE country_id = ?").use { ps ->
            ps.setLong(1, countryId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    fun close() = runCatching { conn.close() }.let {}

    private fun findCountry(destination: String, citizenship: String, purpose: String): DigestCountry? =
        conn.prepareStatement(
            "SELECT id, destination, citizenship, purpose FROM digest_country " +
                "WHERE destination = ? AND citizenship = ? AND purpose = ?",
        ).use { ps ->
            ps.setString(1, destination); ps.setString(2, citizenship); ps.setString(3, purpose)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toCountry() else null }
        }

    private fun java.sql.ResultSet.toCountry() =
        DigestCountry(getLong("id"), getString("destination"), getString("citizenship"), getString("purpose"))

    private fun java.sql.ResultSet.toSnapshot() =
        DigestSnapshot(getLong("id"), getLong("country_id"), getString("collected_at"), getString("summary"), getString("sources"))
}
