package com.example.adventdesktop.data

/**
 * Загрузчик `SKILL.md` из ресурсов (День 20). Грузится ПО ТРЕБОВАНИЮ — только когда навык включён и нужен,
 * в отличие от MCP-схем, которые идут в каждый sampling call. В этом и состоит экономия токенов.
 */
object SkillDocs {
    fun load(name: String): String =
        SkillDocs::class.java.classLoader
            .getResourceAsStream("skills/$name.md")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            .orEmpty()
}
