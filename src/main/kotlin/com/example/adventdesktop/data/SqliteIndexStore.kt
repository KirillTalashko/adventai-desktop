package com.example.adventdesktop.data

import com.example.adventdesktop.domain.rag.Chunk
import com.example.adventdesktop.domain.rag.ChunkMetadata
import com.example.adventdesktop.domain.rag.IndexStats
import com.example.adventdesktop.domain.rag.IndexStore
import com.example.adventdesktop.domain.rag.IndexedChunk
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager

/**
 * Локальный индекс RAG на **SQLite** (sqlite-jdbc, чистый JDBC — как [com.example.adventdesktop.mcp.scheduler.DigestStore], KISS).
 *
 * Две таблицы: `rag_meta` (статистика на стратегию) и `rag_chunk` (чанки + метаданные + вектор BLOB).
 * Вектор храним как сырые float32 little-endian ([floatsToBlob]) — компактно и без потерь. Обе стратегии
 * (`fixed`/`structural`) живут в одной БД, адресуются колонкой `strategy` → легко сравнивать и
 * переключать. Файл общий для приложения (визовая база знаний не персональная): `~/.adventai/rag/index.db`.
 */
class SqliteIndexStore(dbPath: String) : IndexStore {

    private val conn: Connection

    init {
        File(dbPath).absoluteFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA busy_timeout = 5000")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS rag_meta (
                       strategy     TEXT PRIMARY KEY,
                       embedder_id  TEXT NOT NULL,
                       dimension    INTEGER NOT NULL,
                       doc_count    INTEGER NOT NULL,
                       chunk_count  INTEGER NOT NULL,
                       avg_chars    INTEGER NOT NULL,
                       min_chars    INTEGER NOT NULL,
                       max_chars    INTEGER NOT NULL,
                       avg_tokens   INTEGER NOT NULL,
                       section_count INTEGER NOT NULL,
                       built_at     TEXT NOT NULL,
                       build_ms     INTEGER NOT NULL
                   )""",
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS rag_chunk (
                       id          INTEGER PRIMARY KEY AUTOINCREMENT,
                       strategy    TEXT NOT NULL,
                       chunk_id    TEXT NOT NULL,
                       source      TEXT NOT NULL,
                       title       TEXT NOT NULL,
                       section     TEXT NOT NULL,
                       ordinal     INTEGER NOT NULL,
                       char_start  INTEGER NOT NULL,
                       char_end    INTEGER NOT NULL,
                       approx_tokens INTEGER NOT NULL,
                       text        TEXT NOT NULL,
                       embedding   BLOB NOT NULL
                   )""",
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rag_chunk_strategy ON rag_chunk(strategy)")
        }
    }

    @Synchronized
    override fun save(stats: IndexStats, chunks: List<IndexedChunk>) {
        val strategy = stats.strategy
        conn.autoCommit = false
        try {
            conn.prepareStatement("DELETE FROM rag_chunk WHERE strategy = ?").use { it.setString(1, strategy); it.executeUpdate() }
            conn.prepareStatement("DELETE FROM rag_meta WHERE strategy = ?").use { it.setString(1, strategy); it.executeUpdate() }
            conn.prepareStatement(
                """INSERT INTO rag_meta(strategy, embedder_id, dimension, doc_count, chunk_count,
                       avg_chars, min_chars, max_chars, avg_tokens, section_count, built_at, build_ms)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
            ).use { ps ->
                ps.setString(1, strategy); ps.setString(2, stats.embedderId); ps.setInt(3, stats.dimension)
                ps.setInt(4, stats.docCount); ps.setInt(5, stats.chunkCount); ps.setInt(6, stats.avgChars)
                ps.setInt(7, stats.minChars); ps.setInt(8, stats.maxChars); ps.setInt(9, stats.avgTokens)
                ps.setInt(10, stats.sectionCount); ps.setString(11, stats.builtAt); ps.setLong(12, stats.buildMs)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """INSERT INTO rag_chunk(strategy, chunk_id, source, title, section, ordinal,
                       char_start, char_end, approx_tokens, text, embedding) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            ).use { ps ->
                for (ic in chunks) {
                    val m = ic.chunk.meta
                    ps.setString(1, strategy); ps.setString(2, m.chunkId); ps.setString(3, m.source)
                    ps.setString(4, m.title); ps.setString(5, m.section); ps.setInt(6, m.ordinal)
                    ps.setInt(7, m.charStart); ps.setInt(8, m.charEnd); ps.setInt(9, m.approxTokens)
                    ps.setString(10, ic.chunk.text); ps.setBytes(11, floatsToBlob(ic.embedding))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    @Synchronized
    override fun load(strategy: String): List<IndexedChunk> = buildList {
        conn.prepareStatement(
            """SELECT chunk_id, source, title, section, ordinal, char_start, char_end, approx_tokens, text, embedding
                   FROM rag_chunk WHERE strategy = ? ORDER BY id""",
        ).use { ps ->
            ps.setString(1, strategy)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val meta = ChunkMetadata(
                        source = rs.getString("source"), title = rs.getString("title"),
                        section = rs.getString("section"), chunkId = rs.getString("chunk_id"),
                        strategy = strategy, ordinal = rs.getInt("ordinal"),
                        charStart = rs.getInt("char_start"), charEnd = rs.getInt("char_end"),
                        approxTokens = rs.getInt("approx_tokens"),
                    )
                    add(IndexedChunk(Chunk(rs.getString("text"), meta), blobToFloats(rs.getBytes("embedding"))))
                }
            }
        }
    }

    @Synchronized
    override fun stats(strategy: String): IndexStats? =
        conn.prepareStatement("SELECT * FROM rag_meta WHERE strategy = ?").use { ps ->
            ps.setString(1, strategy)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else IndexStats(
                    strategy = rs.getString("strategy"), embedderId = rs.getString("embedder_id"),
                    dimension = rs.getInt("dimension"), docCount = rs.getInt("doc_count"),
                    chunkCount = rs.getInt("chunk_count"), avgChars = rs.getInt("avg_chars"),
                    minChars = rs.getInt("min_chars"), maxChars = rs.getInt("max_chars"),
                    avgTokens = rs.getInt("avg_tokens"), sectionCount = rs.getInt("section_count"),
                    builtAt = rs.getString("built_at"), buildMs = rs.getLong("build_ms"),
                )
            }
        }

    @Synchronized
    override fun strategies(): List<String> = buildList {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT strategy FROM rag_meta ORDER BY strategy").use { rs ->
                while (rs.next()) add(rs.getString(1))
            }
        }
    }

    @Synchronized
    override fun clear(strategy: String) {
        conn.prepareStatement("DELETE FROM rag_chunk WHERE strategy = ?").use { it.setString(1, strategy); it.executeUpdate() }
        conn.prepareStatement("DELETE FROM rag_meta WHERE strategy = ?").use { it.setString(1, strategy); it.executeUpdate() }
    }

    fun close() = runCatching { conn.close() }.let {}

    private companion object {
        /** float32 → сырой BLOB (little-endian): 4 байта на число. */
        fun floatsToBlob(v: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in v) buf.putFloat(x)
            return buf.array()
        }

        fun blobToFloats(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.float }
        }
    }
}
