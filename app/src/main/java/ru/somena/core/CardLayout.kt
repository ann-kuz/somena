package ru.somena.core

/**
 * Раскладка карточек (чистая логика без Android): порядок и скрытость любых
 * карточек с идентификатором и названием - плашек «Сегодня» и графиков «Графиков».
 * Порядок и скрытость не зависят друг от друга: скрытая карточка помнит своё
 * место, «показать» возвращает её к нему.
 */
interface LayoutCard {
    val id: String
    val title: String
}

data class CardLayout<T : LayoutCard>(val order: List<T>, val hidden: Set<T>) {
    val visible: List<T> get() = order.filter { it !in hidden }
}

/** Перенос элемента списка на новое место: остальные сдвигаются, освободив место. */
fun <T> List<T>.moved(item: T, toIndex: Int): List<T> {
    val from = indexOf(item)
    if (from < 0 || toIndex < 0 || toIndex >= size || from == toIndex) return this
    val list = toMutableList()
    list.add(toIndex, list.removeAt(from))
    return list
}

/**
 * Раскладка из хранимых идентификаторов: незнакомые выбрасываются, забытые
 * дописываются в конец, скрытость вне порядка не учитывается.
 */
fun <T : LayoutCard> cardLayout(
    orderIds: List<String>,
    hiddenIds: Set<String>,
    available: List<T>,
    byId: (String) -> T?,
): CardLayout<T> {
    val order = orderIds.mapNotNull(byId).filter { it in available }.distinct()
    val hidden = hiddenIds.mapNotNull(byId).filter { it in order }.toSet()
    return CardLayout(order + available.filter { it !in order }, hidden)
}

/** Перенос карточки на новое место: остальные сдвигаются, освободив место. */
fun <T : LayoutCard> CardLayout<T>.moved(card: T, toIndex: Int): CardLayout<T> =
    copy(order = order.moved(card, toIndex))

/** Скрыть или вернуть карточку: место в порядке не трогается. */
fun <T : LayoutCard> CardLayout<T>.hiddenAs(card: T, hide: Boolean): CardLayout<T> =
    copy(hidden = if (hide) hidden + card else hidden - card)

// ---- Плашки «Сегодня» ----

/**
 * Плашки «Сегодня». Календарь цикла для пола «мужской» недоступен вовсе:
 * он не входит в доступные плашки и не хранится.
 */
enum class TodayPlate(override val id: String, override val title: String) : LayoutCard {
    STEPS("steps", "Шаги"),
    SLEEP("sleep", "Сон"),
    BURNED("burned", "Сожжено"),
    PULSE("pulse", "Пульс"),
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

typealias TodayLayout = CardLayout<TodayPlate>

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
    TodayPlate.PULSE -> ManualMetric.PULSE
    TodayPlate.EATEN -> ManualMetric.EATEN
    TodayPlate.WEIGHT -> ManualMetric.WEIGHT
    TodayPlate.BODY_FAT -> ManualMetric.BODY_FAT
    TodayPlate.BONE -> ManualMetric.BONE
    TodayPlate.BMR -> ManualMetric.BMR
    TodayPlate.WELLBEING -> null
    TodayPlate.CYCLE -> null
}

fun todayLayout(orderIds: List<String>, hiddenIds: Set<String>, available: List<TodayPlate>): TodayLayout =
    cardLayout(orderIds, hiddenIds, available, TodayPlate::byId)

// ---- Графики «Графиков» ----

/**
 * Графики «Графиков»: порядок по умолчанию - спека 0005 (от главного к деталям).
 * Составные графики (Дефицит, Калории, БЖУ) показывают производные значения -
 * ручного ввода у них нет.
 */
enum class ChartCard(override val id: String, override val title: String) : LayoutCard {
    DEFICIT("deficit", "Дефицит"),
    CALORIES("calories", "Калории"),
    WEIGHT("weight_trend", "Вес и тренд"),
    MACROS("macros", "БЖУ"),
    STEPS("steps", "Шаги"),
    SLEEP("sleep", "Сон"),
    PULSE("pulse", "Пульс"),
    WELLBEING("wellbeing", "Самочувствие"),
    BODY_FAT("body_fat", "Процент жира"),
    BONE("bone", "Костная масса"),
    BMR("bmr", "Базовый расход");

    companion object {
        fun byId(id: String): ChartCard? = entries.firstOrNull { it.id == id }
    }
}

typealias ChartLayout = CardLayout<ChartCard>

fun chartLayout(
    orderIds: List<String>,
    hiddenIds: Set<String>,
    available: List<ChartCard> = ChartCard.entries.toList(),
): ChartLayout = cardLayout(orderIds, hiddenIds, available, ChartCard::byId)

/**
 * Категория ручного ввода графика: у составных (Дефицит, Калории, БЖУ) значения
 * производные - категории нет; Самочувствие правится своим редактором.
 */
fun ChartCard.manualMetric(): ManualMetric? = when (this) {
    ChartCard.WEIGHT -> ManualMetric.WEIGHT
    ChartCard.STEPS -> ManualMetric.STEPS
    ChartCard.SLEEP -> ManualMetric.SLEEP
    ChartCard.PULSE -> ManualMetric.PULSE
    ChartCard.BODY_FAT -> ManualMetric.BODY_FAT
    ChartCard.BONE -> ManualMetric.BONE
    ChartCard.BMR -> ManualMetric.BMR
    else -> null
}
