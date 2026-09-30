package ru.somena.core

/**
 * Плашки «Сегодня» (чистая логика без Android): идентификаторы, порядок и скрытость.
 * Порядок и скрытость не зависят друг от друга: скрытая плашка помнит своё место,
 * «показать» возвращает её к нему. Календарь цикла для пола «мужской» недоступен
 * вовсе - он не входит в доступные плашки и не хранится.
 */
enum class TodayPlate(val id: String, val title: String) {
    STEPS("steps", "Шаги"),
    SLEEP("sleep", "Сон"),
    BURNED("burned", "Сожжено"),
    EATEN("eaten", "Съедено"),
    WEIGHT("weight", "Вес"),
    BODY_FAT("body_fat", "Процент жира"),
    BONE("bone", "Костная масса"),
    BMR("bmr", "Базовый расход"),
    WELLBEING("wellbeing", "Самочувствие"),
    CYCLE("cycle", "Цикл");

    companion object {
        fun byId(id: String): TodayPlate? = entries.firstOrNull { it.id == id }
    }
}

/** Метрические плашки одного размера: Самочувствие и Цикл живут своей ширины. */
val TodayPlate.isMetric: Boolean get() = this != TodayPlate.WELLBEING && this != TodayPlate.CYCLE

/** Доступные плашки: у пола «мужской» Календарь цикла скрыт всегда. */
fun todayPlatesFor(male: Boolean): List<TodayPlate> =
    if (male) TodayPlate.entries.filter { it != TodayPlate.CYCLE } else TodayPlate.entries.toList()

/** Метрика ручного ввода плашки; у Самочувствия и Цикла свои редакторы. */
fun TodayPlate.manualMetric(): ManualMetric? = when (this) {
    TodayPlate.STEPS -> ManualMetric.STEPS
    TodayPlate.SLEEP -> ManualMetric.SLEEP
    TodayPlate.BURNED -> ManualMetric.BURNED
    TodayPlate.EATEN -> ManualMetric.EATEN
    TodayPlate.WEIGHT -> ManualMetric.WEIGHT
    TodayPlate.BODY_FAT -> ManualMetric.BODY_FAT
    TodayPlate.BONE -> ManualMetric.BONE
    TodayPlate.BMR -> ManualMetric.BMR
    TodayPlate.WELLBEING -> null
    TodayPlate.CYCLE -> null
}

data class TodayLayout(val order: List<TodayPlate>, val hidden: Set<TodayPlate>) {
    val visible: List<TodayPlate> get() = order.filter { it !in hidden }
}

/**
 * Раскладка из хранимых идентификаторов: незнакомые выбрасываются, забытые
 * дописываются в конец, скрытость вне порядка не учитывается.
 */
fun todayLayout(orderIds: List<String>, hiddenIds: Set<String>, available: List<TodayPlate>): TodayLayout {
    val order = orderIds.mapNotNull(TodayPlate::byId).filter { it in available }.distinct()
    val hidden = hiddenIds.mapNotNull(TodayPlate::byId).filter { it in order }.toSet()
    return TodayLayout(order + available.filter { it !in order }, hidden)
}

/** Перенос элемента списка на новое место: остальные сдвигаются, освободив место. */
fun <T> List<T>.moved(item: T, toIndex: Int): List<T> {
    val from = indexOf(item)
    if (from < 0 || toIndex < 0 || toIndex >= size || from == toIndex) return this
    val list = toMutableList()
    list.add(toIndex, list.removeAt(from))
    return list
}

/** Перенос плашки на новое место: остальные сдвигаются, освободив место. */
fun TodayLayout.moved(plate: TodayPlate, toIndex: Int): TodayLayout =
    copy(order = order.moved(plate, toIndex))

/** Скрыть или вернуть плашку: место в порядке не трогается. */
fun TodayLayout.hiddenAs(plate: TodayPlate, hide: Boolean): TodayLayout =
    copy(hidden = if (hide) hidden + plate else hidden - plate)
