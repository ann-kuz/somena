package ru.somena.core

import java.time.Instant
import java.time.ZoneId

/**
 * Источники Health Connect (ADR-0010): приложение работает с любыми Источниками,
 * а не только с каталогом владелицы. Источники обнаруживаются по факту записей
 * (ADR-0004): скан показывает, кто писал данные за окно. Когда один тип данных
 * пишут несколько Источников, Пользователь задаёт их порядок - приоритет:
 * за каждый день данные берутся у первого Источника порядка, у которого они
 * в этот день есть; без порядка учитываются все.
 */

/** Найденный Источник: пакет и человекочитаемое имя приложения. */
data class HcSource(val packageName: String, val label: String)

/** Тип данных, который приложение читает из Health Connect. Ключи стабильны. */
enum class SourceKind(val key: String, val title: String) {
    STEPS("steps", "Шаги"),
    SLEEP("sleep", "Сон"),
    BURN("burn", "Расход"),
    PULSE("pulse", "Пульс"),
    FOOD("food", "Еда"),
    WEIGHT("weight", "Вес"),
    BODY_FAT("body_fat", "Процент жира"),
    BONE("bone", "Костная масса"),
    BMR("bmr", "Базовый расход"),
}

/**
 * Группа выбора в настройках: показатели состава тела приходят с одних весов
 * и выбираются одним Источником; браслет и телефон выбираются по своим типам.
 */
enum class SourceGroup(val key: String, val title: String, val kinds: List<SourceKind>) {
    ACTIVITY("steps", "Шаги", listOf(SourceKind.STEPS)),
    SLEEP("sleep", "Сон", listOf(SourceKind.SLEEP)),
    BURN("burn", "Расход", listOf(SourceKind.BURN)),
    PULSE("pulse", "Пульс", listOf(SourceKind.PULSE)),
    FOOD("food", "Еда", listOf(SourceKind.FOOD)),
    BODY("body", "Вес и состав тела", listOf(SourceKind.WEIGHT, SourceKind.BODY_FAT, SourceKind.BONE, SourceKind.BMR));

    companion object {
        fun byKind(kind: SourceKind): SourceGroup = entries.first { kind in it.kinds }
    }
}

/** Состояние группы для настроек: найденные Источники и порядок приоритета. */
data class SourceGroupChoice(
    val group: SourceGroup,
    val sources: List<HcSource>,
    /** Порядок приоритета пакетами; пустой - учитываются все Источники. */
    val priority: List<String>,
) {
    /** Выбор нужен: один тип данных пишут несколько Источников. */
    val needsChoice: Boolean get() = sources.size > 1
}

/**
 * Собирает группы для настроек: Источники группы - объединение по её типам без
 * повторов, порядок стабильный (по имени, при равенстве - по пакету). Приоритет,
 * ссылающийся на пропавшие Источники, теряет их: данные снова идут из всех.
 */
fun mergeSources(
    writers: Map<SourceKind, List<HcSource>>,
    choices: Map<String, List<String>>,
): List<SourceGroupChoice> = SourceGroup.entries.map { g ->
    val sources = writers.filterKeys { it in g.kinds }.values.flatten()
        .distinctBy { it.packageName }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
    val priority = choices[g.key].orEmpty().filter { pkg -> sources.any { it.packageName == pkg } }
    SourceGroupChoice(g, sources, priority)
}

/**
 * Приоритет Источников при чтении (ADR-0010): записи делятся по дням своей даты
 * (в зоне Пользователя), и за каждый день берутся записи первого Источника
 * порядка, у которого они в этот день есть. Пустой порядок - все записи как есть.
 * Источники вне порядка - запас с конца: они читаются только в дни, когда у
 * поставленных в порядок записей нет. Так браслет главный, а день без браслета
 * честно берётся с телефонного шагомера.
 */
fun <T> applySourcePriority(
    entries: List<T>,
    priority: List<String>,
    zone: ZoneId,
    sourceOf: (T) -> String,
    timeOf: (T) -> Instant,
): List<T> {
    if (priority.isEmpty()) return entries
    return entries.groupBy { timeOf(it).atZone(zone).toLocalDate() }.flatMap { (_, dayEntries) ->
        val bySource = dayEntries.groupBy { sourceOf(it) }
        val winner = priority.firstOrNull { it in bySource.keys }
        when (winner) {
            null -> dayEntries.filter { sourceOf(it) !in priority }
            else -> bySource.getValue(winner)
        }
    }
}
