package ru.somena.core

/**
 * Источники Health Connect (ADR-0010): приложение работает с любыми Источниками,
 * а не только с каталогом владелицы. Источники обнаруживаются по факту записей
 * (ADR-0004): скан показывает, кто писал данные за окно. Когда один тип данных
 * пишут несколько Источников, Пользователь выбирает один в настройках;
 * без выбора учитываются все.
 */

/** Найденный Источник: пакет и человекочитаемое имя приложения. */
data class HcSource(val packageName: String, val label: String)

/** Тип данных, который приложение читает из Health Connect. Ключи стабильны. */
enum class SourceKind(val key: String, val title: String) {
    STEPS("steps", "Шаги"),
    SLEEP("sleep", "Сон"),
    BURN("burn", "Расход"),
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
    FOOD("food", "Еда", listOf(SourceKind.FOOD)),
    BODY("body", "Вес и состав тела", listOf(SourceKind.WEIGHT, SourceKind.BODY_FAT, SourceKind.BONE, SourceKind.BMR));

    companion object {
        fun byKind(kind: SourceKind): SourceGroup = entries.first { kind in it.kinds }
    }
}

/** Состояние группы для настроек: найденные Источники и действующий выбор. */
data class SourceGroupChoice(
    val group: SourceGroup,
    val sources: List<HcSource>,
    /** Выбранный пакет; null - учитываются все Источники. */
    val selected: String?,
) {
    /** Выбор нужен: один тип данных пишут несколько Источников. */
    val needsChoice: Boolean get() = sources.size > 1
}

/**
 * Собирает группы для настроек: Источники группы - объединение по её типам без
 * повторов, порядок стабильный (по имени, при равенстве - по пакету). Выбор,
 * ссылающийся на пропавший Источник, сбрасывается: данные снова идут из всех.
 */
fun mergeSources(
    writers: Map<SourceKind, List<HcSource>>,
    choices: Map<String, String>,
): List<SourceGroupChoice> = SourceGroup.entries.map { g ->
    val sources = writers.filterKeys { it in g.kinds }.values.flatten()
        .distinctBy { it.packageName }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
    val selected = choices[g.key]?.takeIf { pkg -> sources.any { it.packageName == pkg } }
    SourceGroupChoice(g, sources, selected)
}
