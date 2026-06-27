package com.example.adventdesktop.domain

/**
 * «Писарь досье» (День 18, «оркестр») — отдельная служебная роль, которая ДЕТЕРМИНИРОВАННО извлекает факты
 * кейса из слов пользователя и обновляет [CaseFile]. Отделён от интервьюера (тот ВЕДЁТ разговор), поэтому
 * досье заполняется всегда, а не «когда модель вспомнила». Строго: факты только из реплик ПОЛЬЗОВАТЕЛЯ,
 * профиль/фон — НЕ источник. Ошибка/пусто → досье не меняется.
 */
class CaseExtractor(private val gateway: LlmGateway) {

    suspend fun update(recent: List<Message>, current: CaseFile): CaseFile {
        if (recent.none { it.role == Role.User }) return current
        val raw = runCatching {
            gateway.complete(listOf(Message(Role.System, PROMPT), Message(Role.User, buildInput(recent, current)))).text
        }.getOrNull().orEmpty()
        return current.merge(parse(raw))
    }

    private fun buildInput(recent: List<Message>, current: CaseFile): String = buildString {
        append("ТЕКУЩЕЕ ДОСЬЕ:\n").append(current.renderBlock().ifBlank { "(пусто)" }).append("\n\n")
        append("СООБЩЕНИЯ (факты бери ТОЛЬКО из реплик «Пользователь»):\n")
        recent.takeLast(8).forEach {
            append(if (it.role == Role.User) "Пользователь" else "Агент").append(": ").append(it.text.trim()).append('\n')
        }
    }

    private fun parse(raw: String): CaseFile {
        var cf = CaseFile()
        raw.lineSequence().forEach { line ->
            val l = line.trim().removePrefix("-").removePrefix("•").trim()
            val k = l.substringBefore(':', "").trim().lowercase()
            val v = l.substringAfter(':', "").trim()
            if (k.isEmpty() || v.isEmpty()) return@forEach
            cf = when {
                k.startsWith("стран") -> cf.copy(destination = v)
                k.startsWith("граждан") -> cf.copy(citizenship = v)
                k.startsWith("цел") -> cf.copy(purpose = v)
                k.startsWith("дат") || k.startsWith("срок") -> cf.copy(timeframe = v)
                k.startsWith("кто") || k.startsWith("заявит") || k.startsWith("путешеств") -> cf.copy(travelers = v)
                k.startsWith("занят") || k.startsWith("доход") || k.startsWith("работ") -> cf.copy(employment = v)
                k.startsWith("отказ") || k.startsWith("истор") -> cf.copy(history = v)
                k.startsWith("город") || k.startsWith("прожив") -> cf.copy(city = v)
                else -> cf
            }
        }
        return cf
    }

    private companion object {
        val PROMPT = """
            Ты — писарь визового досье. На вход: текущее досье и последние сообщения. Извлеки факты кейса
            ТОЛЬКО из реплик «Пользователь» (реплики «Агент» — лишь контекст). НЕ выдумывай и НЕ домысливай;
            профиль, фон и прошлые диалоги — НЕ источник: бери только то, что пользователь сказал ЗДЕСЬ.
            Верни строки «ключ: значение» ТОЛЬКО для тех полей, что пользователь ЯВНО назвал. Возможные ключи:
            страна, гражданство, цель, даты, кто едет, занятость, отказы, город.
            «страна» — это КОНКРЕТНАЯ страна назначения (Италия, Германия…). Расплывчатое («за границу»,
            «куда-нибудь», «в Европу», регион без страны) страной НЕ считается — такое поле ПРОПУСТИ.
            Поля, которые пользователь НЕ называл, — просто НЕ выводи (не пиши «не указано», прочерк, «…» и подобное).
            Если новых фактов из слов пользователя нет — ответь одним словом: НЕТ.
        """.trimIndent()
    }
}
