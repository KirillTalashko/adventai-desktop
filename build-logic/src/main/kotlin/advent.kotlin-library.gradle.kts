// Модуль-библиотека: то же, что advent.kotlin-jvm, плюс java-library ради конфигурации `api`.
// api нужна там, где типы модуля торчат в сигнатурах для потребителей (:core:domain, :core:data).
plugins {
    id("advent.kotlin-jvm")
    `java-library`
}
