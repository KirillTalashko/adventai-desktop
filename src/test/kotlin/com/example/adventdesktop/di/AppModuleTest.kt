package com.example.adventdesktop.di

import com.example.adventdesktop.ui.ChatState
import kotlinx.coroutines.CoroutineScope
import org.koin.dsl.koinApplication
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import org.koin.test.verify.verifyAll
import java.io.File
import kotlin.test.Test

/**
 * День 32 — страховка от главного минуса Koin: граф резолвится в рантайме, поэтому незакрытая
 * зависимость всплыла бы только у пользователя при открытии панели. Оба теста роняют сборку раньше.
 *
 * Ни один тест не создаёт объектов графа — ни `~/.adventai`, ни сеть, ни Ollama не трогаются.
 */
class AppModuleTest {

    /**
     * Статическая проверка: рефлексией обходит конструкторы всех определений и требует, чтобы
     * у каждого параметра был провайдер.
     */
    @Test
    fun `koin graph is complete`() {
        appModules.verifyAll(
            // Типы, которых в графе нет намеренно:
            //  File           — FileStore берёт путь из appHomeDir(), а не из контейнера;
            //  CoroutineScope — принадлежит композиции окна и приходит через parametersOf(scope).
            extraTypes = listOf(File::class, CoroutineScope::class),
            injections = injectedParameters(
                definition<ChatState>(CoroutineScope::class),
            ),
        )
    }

    /**
     * Динамическая проверка: verifyAll() модули НЕ загружает, поэтому конфликт определений ему не
     * виден — а он возможен, так как dataModule и gatewayModule и перечислены в appModules, и
     * приезжают через includes() из stateModule. Здесь граф загружается по-настоящему.
     *
     * `koinApplication` (в отличие от `startKoin`) не трогает глобальный контекст, а `single`
     * ленивы — определения регистрируются, но не инстанцируются.
     */
    @Test
    fun `koin modules load without definition conflicts`() {
        koinApplication { modules(appModules) }.close()
    }
}
