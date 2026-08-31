package com.example.adventdesktop.domain.rag

/**
 * Страновой скоуп базы знаний — защита от ответа по правилам ЧУЖОЙ страны.
 *
 * **Зачем.** Отбор кандидатов в RAG идёт по косинусу ПО ВСЕМУ корпусу и происходит РАНЬШЕ реранка, поэтому
 * страна как обычное слово в тексте запроса не спасает: на кейсе «Испания, туризм» лучший чанк `spain.md`
 * оказывался на 45-м месте из 318 (вне пула `retrieveN`), а выдачу занимал `japan.md` — у него в H1 стоит
 * «туризм», который `ContextualChunker` вклеивает в каждый чанк. Ни реранк (работает только внутри пула),
 * ни порог (косинусы корпуса сжаты в 0.58–0.79) вернуть выпавший документ уже не могут.
 *
 * **Решение.** Страна известна детерминированно (`CaseFile.destination`), поэтому применяем её не как токен
 * запроса, а как ОГРАНИЧЕНИЕ МНОЖЕСТВА кандидатов — до косинуса. Чанк странового документа другой страны
 * физически не попадает ни в пул, ни в промпт.
 *
 * **Правило — чёрный список, а не белый:** документ разрешён, ЕСЛИ он не является страновым документом другой
 * страны. Поэтому страна-агностичные материалы (`work-visa`, `general-process`, `checklists`, `faq`, …) и любые
 * файлы, добавленные пользователем в свою базу, видны по умолчанию — их не нужно перечислять и поддерживать.
 *
 * Чистый Kotlin: домен не знает про файлы/HTTP/SQL. Сопоставление — через существующий стеммер [Ru].
 */
object CountryScope {

    /** Страна корпуса: канонический ключ, синонимы для распознавания и её документы (может быть пусто). */
    private data class Entry(val key: String, val aliases: List<String>, val docs: List<String> = emptyList())

    /**
     * Страны Шенгена — членство, а НЕ наличие документа в корпусе: для Португалии своего файла нет, но
     * `schengen.md` ей подходит и остаётся доступным (честная деградация вместо пустоты).
     */
    private val SCHENGEN = setOf(
        "austria", "belgium", "bulgaria", "croatia", "czechia", "denmark", "estonia", "finland", "france",
        "germany", "greece", "hungary", "iceland", "italy", "latvia", "liechtenstein", "lithuania",
        "luxembourg", "malta", "netherlands", "norway", "poland", "portugal", "romania", "slovakia",
        "slovenia", "spain", "sweden", "switzerland",
    )

    /** Рамочные (региональные) документы: разрешены членам региона, а также пока страна ещё не названа. */
    private val REGION_DOCS: Map<String, Set<String>> = mapOf(
        "schengen.md" to SCHENGEN,
        "eu-etias-ees.md" to SCHENGEN,
    )

    private val ENTRIES = listOf(
        // --- Шенген: документы есть ---
        Entry("spain", listOf("испания", "spain"), listOf("spain.md")),
        Entry("france", listOf("франция", "france"), listOf("france.md")),
        Entry("germany", listOf("германия", "germany"), listOf("germany.md")),
        Entry("italy", listOf("италия", "italy"), listOf("italy.md")),
        Entry("greece", listOf("греция", "greece"), listOf("greece.md")),
        // --- Шенген: своего документа нет (распознаём, чтобы отдать schengen.md и предупредить) ---
        Entry("austria", listOf("австрия", "austria")),
        Entry("belgium", listOf("бельгия", "belgium")),
        Entry("bulgaria", listOf("болгария", "bulgaria")),
        Entry("croatia", listOf("хорватия", "croatia")),
        Entry("czechia", listOf("чехия", "czech")),
        Entry("denmark", listOf("дания", "denmark")),
        Entry("estonia", listOf("эстония", "estonia")),
        Entry("finland", listOf("финляндия", "finland")),
        Entry("hungary", listOf("венгрия", "hungary")),
        Entry("iceland", listOf("исландия", "iceland")),
        Entry("latvia", listOf("латвия", "latvia")),
        Entry("liechtenstein", listOf("лихтенштейн", "liechtenstein")),
        Entry("lithuania", listOf("литва", "lithuania")),
        Entry("luxembourg", listOf("люксембург", "luxembourg")),
        Entry("malta", listOf("мальта", "malta")),
        Entry("netherlands", listOf("нидерланды", "голландия", "netherlands")),
        Entry("norway", listOf("норвегия", "norway")),
        Entry("poland", listOf("польша", "poland")),
        Entry("portugal", listOf("португалия", "portugal")),
        Entry("romania", listOf("румыния", "romania")),
        Entry("slovakia", listOf("словакия", "slovakia")),
        Entry("slovenia", listOf("словения", "slovenia")),
        Entry("sweden", listOf("швеция", "sweden")),
        Entry("switzerland", listOf("швейцария", "switzerland")),
        // --- Европа вне Шенгена ---
        Entry("uk", listOf("великобритания", "англия", "британия", "соединённое королевство", "uk"), listOf("uk.md", "uk-eta.md")),
        Entry("ireland", listOf("ирландия", "ireland"), listOf("ireland.md")),
        Entry("cyprus", listOf("кипр", "cyprus"), listOf("cyprus.md")),
        Entry("serbia", listOf("сербия", "serbia"), listOf("serbia.md")),
        Entry("montenegro", listOf("черногория", "montenegro"), listOf("montenegro.md")),
        // --- Америка ---
        Entry("usa", listOf("сша", "америка", "штаты", "usa"), listOf("usa.md")),
        Entry("canada", listOf("канада", "canada"), listOf("canada.md")),
        Entry("mexico", listOf("мексика", "mexico"), listOf("mexico.md")),
        Entry("brazil", listOf("бразилия", "brazil"), listOf("brazil.md")),
        Entry("cuba", listOf("куба", "cuba"), listOf("cuba.md")),
        Entry("dominican-republic", listOf("доминикана", "доминиканская республика", "dominican"), listOf("dominican-republic.md")),
        // --- Азия и Ближний Восток ---
        Entry("china", listOf("китай", "china"), listOf("china.md")),
        Entry("japan", listOf("япония", "japan"), listOf("japan.md", "japan.pdf")),
        Entry("south-korea", listOf("южная корея", "корея", "korea"), listOf("south-korea.md")),
        // Ловушка: без неё «Северная Корея» подхватила бы алиас «корея» (побеждает более длинный алиас).
        Entry("north-korea", listOf("северная корея", "кндр")),
        Entry("thailand", listOf("таиланд", "тайланд", "thailand"), listOf("thailand.md")),
        Entry("vietnam", listOf("вьетнам", "vietnam"), listOf("vietnam.md")),
        Entry("india", listOf("индия", "india"), listOf("india.md")),
        Entry("indonesia", listOf("индонезия", "бали", "indonesia", "bali"), listOf("indonesia-bali.md")),
        Entry("singapore", listOf("сингапур", "singapore"), listOf("singapore.md")),
        Entry("malaysia", listOf("малайзия", "malaysia"), listOf("malaysia.md")),
        Entry("sri-lanka", listOf("шри-ланка", "шри ланка", "цейлон", "lanka"), listOf("sri-lanka.md")),
        Entry("turkey", listOf("турция", "turkey"), listOf("turkey.md")),
        Entry("israel", listOf("израиль", "israel"), listOf("israel.md")),
        Entry("saudi-arabia", listOf("саудовская аравия", "саудовская", "saudi"), listOf("saudi-arabia.md")),
        Entry("qatar", listOf("катар", "qatar"), listOf("qatar.md")),
        Entry("oman", listOf("оман", "oman"), listOf("oman.md")),
        Entry("uae", listOf("оаэ", "эмираты", "дубай", "абу-даби", "uae", "dubai"), listOf("uae.md")),
        // --- Океания ---
        Entry("australia", listOf("австралия", "australia"), listOf("australia.md")),
        Entry("new-zealand", listOf("новая зеландия", "zealand"), listOf("new-zealand.md")),
        // --- Африка ---
        Entry("egypt", listOf("египет", "egypt"), listOf("egypt.md")),
        Entry("morocco", listOf("марокко", "morocco"), listOf("morocco.md")),
        Entry("tanzania", listOf("танзания", "занзибар", "tanzania", "zanzibar"), listOf("tanzania-zanzibar.md")),
    )

    /** Имя файла → страна-владелец. Файлы вне карты считаются страна-агностичными (видны всегда). */
    private val DOC_OWNER: Map<String, String> =
        ENTRIES.flatMap { e -> e.docs.map { it.lowercase() to e.key } }.toMap()

    /** Предвычисленные основы алиасов: сопоставляем стеммированное со стеммированным (падежи «в Испанию»). */
    private val ALIAS_STEMS: List<Pair<String, Set<String>>> =
        ENTRIES.flatMap { e -> e.aliases.map { e.key to Ru.terms(it) } }.filter { it.second.isNotEmpty() }

    /** Минимальная длина основы, при которой допускаем сравнение по префиксу (добор огрехов стеммера). */
    private const val PREFIX_MIN = 5

    /**
     * Скоуп по стране кейса. [destination] — `CaseFile.destination` (на исполнении гарантированно непуст);
     * [fallbackText] — последнее сообщение пользователя, нужно только на первом ходу, пока досье пустое.
     * Если в тексте упомянуто НЕСКОЛЬКО стран («был в Японии, теперь хочу в Испанию») — считаем страну
     * неизвестной (fail-safe: лучше общие материалы, чем угаданная не та страна).
     */
    fun scopeFor(destination: String, fallbackText: String = ""): KnowledgeScope {
        val given = destination.isNotBlank()
        if (given) {
            val key = matchOne(destination)
            return if (key != null) scope(key, true) else KnowledgeScope("", destinationGiven = true, hasCountryDoc = false)
        }
        val key = matchAll(fallbackText).singleOrNull()   // ровно одна страна в тексте — иначе не гадаем
        return if (key != null) scope(key, true) else KnowledgeScope("", destinationGiven = false, hasCountryDoc = false)
    }

    /** Страна-владелец документа (пусто — документ страна-агностичный или рамочный). Для стража выдачи. */
    fun countryOfSource(source: String): String = DOC_OWNER[basename(source)].orEmpty()

    /** Все страновые/рамочные имена файлов — для приёмочной проверки покрытия манифеста. */
    fun knownDocs(): Set<String> = DOC_OWNER.keys + REGION_DOCS.keys

    /** Канонические ключи словаря — для приёмочной проверки обратимости алиасов. */
    fun keys(): List<String> = ENTRIES.map { it.key }

    internal fun regionDocsOf(key: String): Set<String> =
        REGION_DOCS.filterValues { key in it }.keys

    private fun scope(key: String, given: Boolean) =
        KnowledgeScope(key, destinationGiven = given, hasCountryDoc = ENTRIES.any { it.key == key && it.docs.isNotEmpty() })

    /** Совпавшая страна с приоритетом более длинного (более специфичного) алиаса: «южная корея» > «корея». */
    private fun matchOne(text: String): String? {
        val terms = Ru.terms(text)
        if (terms.isEmpty()) return null
        var best: String? = null
        var bestSize = 0
        for ((key, alias) in ALIAS_STEMS) {
            if (alias.size > bestSize && alias.all { a -> terms.any { same(it, a) } }) {
                best = key
                bestSize = alias.size
            }
        }
        return best
    }

    /** Все страны, упомянутые в тексте (для fail-safe при неоднозначности). */
    private fun matchAll(text: String): Set<String> {
        val terms = Ru.terms(text)
        if (terms.isEmpty()) return emptySet()
        return ALIAS_STEMS.filter { (_, alias) -> alias.all { a -> terms.any { same(it, a) } } }
            .map { it.first }.toSet()
    }

    /** Равенство основ с поблажкой на префикс: стеммер даёт «испани» и «испание» для разных падежей. */
    private fun same(a: String, b: String): Boolean = when {
        a == b -> true
        a.length >= PREFIX_MIN && b.startsWith(a) -> true
        b.length >= PREFIX_MIN && a.startsWith(b) -> true
        else -> false
    }

    private fun basename(source: String): String =
        source.substringAfterLast('/').substringAfterLast('\\').lowercase()

    /**
     * Решение «пускать ли документ в поиск» для конкретного кейса. Вынесено в [CountryScope], т.к. правило —
     * доменное знание о корпусе, а не свойство самого скоупа.
     */
    internal fun allows(scope: KnowledgeScope, source: String): Boolean {
        val name = basename(source)
        REGION_DOCS[name]?.let { members ->
            return when {
                scope.country.isNotBlank() -> scope.country in members
                // Страна названа, но не распознана — рамочный документ может увести не туда, не рискуем.
                scope.destinationGiven -> false
                // Страна ещё не названа (первый ход): рамочные материалы уместны («сколько дней в Шенгене»).
                else -> true
            }
        }
        val owner = DOC_OWNER[name] ?: return true          // агностичный или пользовательский файл
        return scope.country.isNotBlank() && owner == scope.country
    }
}

/**
 * Ограничение области поиска по стране кейса. [country] — канонический ключ (пусто = не определена);
 * [destinationGiven] — страна в досье названа (пусть и не распознана); [hasCountryDoc] — в корпусе есть
 * документ этой страны (иначе агент обязан честно предупредить, а не переносить правила соседей).
 */
data class KnowledgeScope(
    val country: String,
    val destinationGiven: Boolean,
    val hasCountryDoc: Boolean,
) {
    /** Пускать ли документ [source] в поиск. Страна-агностичные и пользовательские файлы — всегда да. */
    fun allows(source: String): Boolean = CountryScope.allows(this, source)

    /** Страна определена — можно применять ограничение и предупреждать об отсутствии документа. */
    val isKnown: Boolean get() = country.isNotBlank()
}
