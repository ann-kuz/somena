package ru.somena.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextTest {

    private val today = LocalDate.of(2026, 9, 25)
    private val yesterday = today.minusDays(1)

    private val slice = DaySlice(
        date = yesterday,
        steps = 8432,
        sleepMinutes = 440,
        burnedKcal = 2100.0,
        eatenKcal = 1800.0,
        proteinG = 90.0,
        fatG = 70.0,
        carbsG = 180.0,
        weightKg = 62.4,
        bodyFatPct = 28.1,
    )
    private val wellbeing = Wellbeing(yesterday, energy = 7, mood = 8, sleepQuality = 6, note = "устала")

    private fun context(
        slices: Map<LocalDate, DaySlice> = mapOf(yesterday to slice),
        wellbeing: Map<LocalDate, List<Wellbeing>> = mapOf(yesterday to listOf(this.wellbeing)),
        profile: Profile? = null,
        cycle: List<CycleDay> = emptyList(),
        medcard: List<MedRecord> = emptyList(),
    ) = buildChatContext(
        today, daysBack = 3, data = DayData(slices, wellbeing),
        profile = profile, cycle = cycle, medcard = medcard,
    )

    @Test
    fun `контекст содержит показатели среза`() {
        val text = context()
        assertTrue(text.contains("шаги 8432"))
        assertTrue(text.contains("сон 7 ч 20 м"))
        assertTrue(text.contains("сожжено 2100 ккал"))
        assertTrue(text.contains("съедено 1800 ккал"))
        assertTrue(text.contains("Б 90 / Ж 70 / У 180 г"))
        // Дефицит: разница сожжённых и съеденных калорий (спека 0005).
        assertTrue(text.contains("дефицит 300 ккал"))
        assertTrue(text.contains("вес 62.4 кг"))
        assertTrue(text.contains("жир 28.1%"))
    }

    @Test
    fun `контекст учитывает Самочувствие и заметку`() {
        val text = context()
        assertTrue(text.contains("энергия 7/10"))
        assertTrue(text.contains("настроение 8/10"))
        assertTrue(text.contains("сон 6/10"))
        assertTrue(text.contains("заметка: «устала»"))
    }

    @Test
    fun `две отметки Самочувствия попадают в контекст обе`() {
        val evening = wellbeing.copy(slot = Wellbeing.SLOT_SECOND, energy = 4, mood = 5, note = "полегче")
        val text = context(wellbeing = mapOf(yesterday to listOf(wellbeing, evening)))
        assertTrue(text.contains("самочувствие (первая и вторая отметки): энергия 7 и 4 из 10, настроение 8 и 5 из 10, сон 6 и 6 из 10"))
        assertTrue(text.contains("заметки: «устала», «полегче»"))
    }

    @Test
    fun `дефицит в контексте прибавляет ручной обмен из профиля`() {
        val text = context(profile = Profile(bmrKcal = 1200.0))
        assertTrue(text.contains("дефицит 1500 ккал"))
        assertTrue(text.contains("обмен 1200 ккал/дн"))
    }

    @Test
    fun `обмен с весов сильнее ручного в дефиците`() {
        val text = context(
            slices = mapOf(yesterday to slice.copy(bmrKcal = 1300.0)),
            profile = Profile(bmrKcal = 1200.0),
        )
        assertTrue(text.contains("дефицит 1600 ккал"))
        assertTrue(text.contains("обмен 1300 ккал/дн"))
    }

    @Test
    fun `дни без данных перечисляются отдельно и не превращаются в нули`() {
        val text = context(slices = emptyMap(), wellbeing = emptyMap())
        assertTrue(text.contains("Дней без данных: 3"))
        assertTrue(text.contains("Это пропуски, а не нули"))
        assertFalse(text.contains("шаги 0"))
    }

    @Test
    fun `профиль попадает в контекст с вычисленным возрастом`() {
        val text = context(
            profile = Profile(heightCm = 168, birthDateIso = "1990-05-14", goalWeightKg = 60.0)
        )
        assertTrue(text.contains("рост 168 см"))
        assertTrue(text.contains("полных лет 36"))
        assertTrue(text.contains("цель по весу 60 кг"))
    }

    @Test
    fun `пустой профиль помечен честно`() {
        val text = context(profile = Profile())
        assertTrue(text.contains("Профиль: не заполнен"))
    }

    @Test
    fun `явная нет боли отличается от неотмеченного`() {
        val text = context(
            cycle = listOf(
                CycleDay(yesterday, menstruation = true, pain = CycleDay.PAIN_NONE),
                CycleDay(today.minusDays(2), menstruation = true, flow = 1),
            )
        )
        assertTrue(text.contains("менструация, боли нет"))
        assertTrue(text.contains("менструация, выделения скудные"))
        assertFalse(text.contains("выделения скудные, боль"))
    }

    @Test
    fun `видимые строки без типографских тире`() {
        val text = context(
            profile = Profile(heightCm = 168, birthDateIso = "1990-05-14", goalWeightKg = 60.0)
        )
        assertFalse(text.contains("—"))
        assertFalse(text.contains("–"))
        assertFalse(CHAT_SYSTEM_PROMPT.contains("—"))
    }

    @Test
    fun `менструация с интенсивностью и болью попадает в строку дня`() {
        val text = context(
            cycle = listOf(
                CycleDay(yesterday, menstruation = true, flow = 3, pain = 1),
                CycleDay(today.minusDays(2), menstruation = false, pain = 2),
            )
        )
        assertTrue(text.contains("менструация, выделения обильные, боль слабая"))
        assertTrue(text.contains("боль средняя"))
    }

    @Test
    fun `строка Цикл содержит последний период и прогноз начала`() {
        val text = context(
            cycle = listOf(CycleDay(LocalDate.of(2026, 9, 23), menstruation = true, flow = 2))
        )
        assertTrue(text.contains("Цикл: последний период 23.09 - 23.09"))
        assertTrue(text.contains("прогноз начала 21.10"))
        assertTrue(text.contains("приблизительно"))
    }

    @Test
    fun `фертильное окно и овуляция отмечаются в днях как прогноз`() {
        // Один период 11.09: прогноз следующего начала 09.10, овуляция 09.10 - 14 = 25.09,
        // фертильное окно 20.09 - 25.09 - попадает в окно контекста 23-25.09.
        val text = context(cycle = listOf(CycleDay(LocalDate.of(2026, 9, 11), menstruation = true)))
        assertTrue(text.contains("фертильное окно (прогноз)"))
        assertTrue(text.contains("25.09: овуляция (прогноз)"))
    }

    @Test
    fun `без записей цикл помечен честно`() {
        assertTrue(context().contains("Цикл: записей нет"))
    }

    // ---- Медкарта в контексте (спека 0010, тикет 06) ----

    private fun analysisAt(date: LocalDate, vararg rows: AnalyteRow) =
        MedRecord(kind = MedKind.ANALYSIS, date = date, items = rows.toList())

    private val hemoglobin = AnalyteRow("Гемоглобин", 134.0, "г/л", 120.0, 150.0)
    private val ferritin = AnalyteRow("Ферритин", 8.0, "нг/мл", 13.0, 150.0)

    @Test
    fun `пустая Медкарта помечена честно`() {
        val text = context(medcard = emptyList())
        assertTrue(text.contains("Медкарта: записей нет"))
    }

    @Test
    fun `список всех записей и все диагнозы за всё время в любом возрасте`() {
        val old = MedRecord(
            kind = MedKind.PROTOCOL,
            date = today.minusDays(400),
            specialty = "Эндокринолог",
            diagnoses = listOf("Гипотиреоз"),
        )
        val text = context(medcard = listOf(analysisAt(today.minusDays(10), hemoglobin), old))
        assertTrue(text.contains("записи: ${today.minusDays(10).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} анализ"))
        assertTrue(text.contains("диагнозы за всё время: Гипотиреоз"))
    }

    @Test
    fun `полные записи за полгода со значениями референсами и флагом`() {
        val text = context(medcard = listOf(analysisAt(today.minusDays(30), hemoglobin, ferritin)))
        assertTrue(text.contains("Гемоглобин 134 г/л (реф 120.0-150.0)"))
        assertTrue(text.contains("Ферритин 8 нг/мл (реф 13.0-150.0) ВНЕ РЕФЕРЕНСА"))
    }

    @Test
    fun `старше окна - только дата и вид с пометкой сказать об этом прямо`() {
        val old = analysisAt(today.minusDays(200), hemoglobin)
        val text = context(medcard = listOf(old))
        // В списке запись есть, подробностей её значений нет.
        assertTrue(text.contains("записи: ${today.minusDays(200).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} анализ"))
        assertFalse(text.contains("Гемоглобин 134"))
        assertTrue(text.contains("содержимого в этом запросе нет"))
    }

    @Test
    fun `заключение обследования и рекомендации протокола попадают в подробности`() {
        val exam = MedRecord(
            kind = MedKind.EXAM,
            date = today.minusDays(20),
            examType = "УЗИ щитовидной железы",
            conclusion = "Без особенностей.",
        )
        val protocol = MedRecord(
            kind = MedKind.PROTOCOL,
            date = today.minusDays(15),
            specialty = "Эндокринолог",
            diagnoses = listOf("Гипотиреоз"),
            recommendations = "Контроль ТТГ через 3 месяца.",
        )
        val text = context(medcard = listOf(exam, protocol))
        assertTrue(text.contains("УЗИ щитовидной железы: Без особенностей."))
        assertTrue(text.contains("диагнозы Гипотиреоз"))
        assertTrue(text.contains("рекомендации: Контроль ТТГ через 3 месяца."))
    }

    @Test
    fun `рамка промпта дает медицинскую свободу до дозировок с оговоркой про врача`() {
        assertTrue(CHAT_SYSTEM_PROMPT.contains("дозировок"))
        assertTrue(CHAT_SYSTEM_PROMPT.contains("окончательное решение за врачом"))
        assertFalse(CHAT_SYSTEM_PROMPT.contains("не ставишь диагнозов"))
    }

    @Test
    fun `промпт учит заносить данные блоком данные без выдуманных значений`() {
        assertTrue(CHAT_SYSTEM_PROMPT.contains("```данные"))
        assertTrue(CHAT_SYSTEM_PROMPT.contains("burned_kcal"))
        assertTrue(CHAT_SYSTEM_PROMPT.contains("которых не хватало - спроси"))
        assertTrue("лимит Бэкенда на system - 4000 символов", CHAT_SYSTEM_PROMPT.length < 4000)
    }

    @Test
    fun `сводка Медкарты без типографских тире`() {
        val text = context(
            medcard = listOf(
                analysisAt(today.minusDays(30), hemoglobin),
                MedRecord(kind = MedKind.EXAM, date = today.minusDays(20), examType = "ЭКГ", conclusion = "Норма."),
            )
        )
        assertFalse(medCardContextLine(listOf(analysisAt(today.minusDays(30), hemoglobin)), today).contains("—"))
        assertFalse(text.contains("—"))
    }
}
