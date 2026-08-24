plugins {
    id("advent.kotlin-library")
}

dependencies {
    // Единственная зависимость домена, и это не случайность: ни Ktor, ни файлов, ни Compose здесь
    // быть не должно — теперь это ошибка компиляции, а не договорённость на словах.
    // api, а не implementation: доменные порты — suspend-функции, и типы корутин видны потребителям
    // в их сигнатурах (например, CoroutineScope в конструкторе ChatState).
    api(libs.coroutines.core)
}
