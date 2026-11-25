package ru.gonzellon.moybudget2

import android.app.Activity
import android.app.DatePickerDialog
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.YearMonth
import java.net.URL
import kotlin.concurrent.thread

data class Entry(
    val id: Long,
    val title: String,
    val amount: Double,
    val date: String,
    val type: String,
    val category: String,
    val recurringType: String = "NONE"
)

data class Deposit(
    val id: Long,
    val title: String,
    val currency: String,
    val initialAmount: Double,
    val annualRate: Double,
    val startDate: String,
    val endDate: String,
    val transferredToUnallocated: Boolean = false
)

data class DepositTopUp(
    val id: Long,
    val depositId: Long,
    val amount: Double,
    val date: String
)

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_EXPORT = 1001
        private const val REQUEST_IMPORT = 1002
    }
    private val prefs by lazy { getSharedPreferences("budget_data", Context.MODE_PRIVATE) }
    private val entries = mutableListOf<Entry>()
    private val deposits = mutableListOf<Deposit>()
    private val depositTopUps = mutableListOf<DepositTopUp>()
    private val currencies = listOf("RUB", "KGS")
    private var activeTab = 0 // 0 RUB, 1 KGS, 2 combined
    private var selectedDate = today()
    private var editingEntryId: Long? = null
    private var searchQuery = ""
    private var typeFilter = "ALL"
    private var selectedMonth = YearMonth.now().toString()
    private var currentPage = 0 // 0 — бюджет, 1 — депозиты
    private var depositTab = 2 // 0 RUB, 1 KGS, 2 Общее
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var kgsToRub = 0.93938
    private var rateUpdatedAt = "Не обновлялся"
    private var rateStatus = "Курс сохранён"
    private lateinit var root: LinearLayout
    private val green = Color.rgb(35, 119, 80)
    private val muted = Color.rgb(105, 115, 108)
    private val bg = Color.rgb(246, 248, 246)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kgsToRub = prefs.getString("kgs_to_rub", "0.93938")?.toDoubleOrNull() ?: 0.93938
        rateUpdatedAt = prefs.getString("rate_updated_at", "Не обновлялся") ?: "Не обновлялся"

        try {
            loadEntries()
            render()
            fetchRate(silent = true)
        } catch (e: Throwable) {
            val error = TextView(this).apply {
                text = "ОШИБКА ЗАПУСКА\\n\\n" + android.util.Log.getStackTraceString(e)
                textSize = 14f
                setTextColor(Color.RED)
                setPadding(24, 24, 24, 24)
            }
            setContentView(ScrollView(this).apply { addView(error) })
        }
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun render() {
        if (currentPage == 1) {
            renderDeposits()
            return
        }
        root = object : LinearLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                handleSwipe(event)
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(18), dp(16), dp(12))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        text(content, "Мой бюджет", 26, Color.BLACK, true)
        text(content, "Планирование доходов и расходов", 13, muted, false)
        val rateLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        text(rateLine, "1 сом = ${String.format(Locale.US, "%.5f", kgsToRub)} ₽", 13, green, true)
        val updateRate = Button(this).apply {
            text = "Обновить курс"
            isAllCaps = false
            textSize = 11f
            setOnClickListener { fetchRate(silent = false) }
        }
        rateLine.addView(updateRate)
        content.addView(rateLine)
        text(content, "$rateStatus • $rateUpdatedAt", 11, muted, false)

        val backupButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val exportButton = Button(this).apply {
            text = "Экспорт данных"
            isAllCaps = false
            setOnClickListener { exportData() }
        }
        val importButton = Button(this).apply {
            text = "Импорт данных"
            isAllCaps = false
            setOnClickListener { importData() }
        }
        backupButtons.addView(exportButton, LinearLayout.LayoutParams(0, -2, 1f))
        backupButtons.addView(importButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(backupButtons)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(12))
        }
        val tabNames = listOf("Рубли ₽", "Сомы с", "Общий баланс")
        tabNames.forEachIndexed { index, name ->
            val b = Button(this).apply {
                text = name
                textSize = 12f
                isAllCaps = false
                setPadding(dp(4), 0, dp(4), 0)
                minHeight = 0
                minimumHeight = 0
                elevation = 0f
                setTextColor(if (activeTab == index) Color.WHITE else green)
                background = roundedBackground(if (activeTab == index) green else Color.rgb(230, 238, 232), 14)
                setOnClickListener { activeTab = index; render() }
            }
            tabs.addView(b, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (index > 0) marginStart = dp(5)
            })
        }
        content.addView(tabs)

        if (activeTab == 2) {
            val rubNow = balanceAt("RUB", today())
            val kgsNow = balanceAt("KGS", today())
            val rubFuture = balanceAt("RUB", selectedDate)
            val kgsFuture = balanceAt("KGS", selectedDate)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(15), dp(16), dp(15))
                background = roundedBackground(Color.WHITE, 18, Color.rgb(230, 238, 232), 1)
            }
            text(card, "Общий баланс сейчас", 14, muted, true)
            text(card, money(rubNow + kgsNow * kgsToRub, "₽"), 27, green, true)
            text(card, "Остаток на $selectedDate", 13, muted, false)
            text(card, money(rubFuture + kgsFuture * kgsToRub, "₽"), 23, Color.BLACK, true)
            text(card, "Рубли: ${money(rubFuture, "₽")}", 13, muted, false)
            text(card, "Сомы: ${money(kgsFuture, "сом")} × ${String.format(Locale.US, "%.5f", kgsToRub)} ₽", 13, muted, false)
            text(card, "Курс применяется только для общего отображения. Исходные операции остаются в своей валюте.", 11, muted, false)
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        } else {
            val currency = if (activeTab == 0) "RUB" else "KGS"
            val balance = balanceAt(currency, selectedDate)
            val current = balanceAt(currency, today())
            val symbol = if (currency == "RUB") "₽" else "сом"
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(15), dp(16), dp(15))
                background = roundedBackground(Color.WHITE, 18, Color.rgb(230, 238, 232), 1)
            }
            text(card, "Текущий остаток", 14, muted, true)
            text(card, money(current, symbol), 25, green, true)
            text(card, "Остаток на $selectedDate", 13, muted, false)
            text(card, money(balance, symbol), 22, Color.BLACK, true)
            val inc = sumFor(currency, "INCOME", selectedDate)
            val exp = sumFor(currency, "EXPENSE", selectedDate)
            text(card, "Доходы: ${money(inc, symbol)}   Расходы: ${money(exp, symbol)}", 12, muted, false)
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

        val dateButton = Button(this).apply {
            text = "Рассчитать остаток на дату: $selectedDate"
            isAllCaps = false
            setOnClickListener {
                val c = Calendar.getInstance()
                try {
                    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(selectedDate)
                    if (parsed != null) c.time = parsed
                } catch (_: Exception) {}
                DatePickerDialog(this@MainActivity, { _, y, m, d ->
                    selectedDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                    render()
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        content.addView(dateButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val editingEntry = entries.find { it.id == editingEntryId }
        text(content, if (editingEntry == null) "Добавить операцию" else "Редактировать операцию", 18, Color.BLACK, true)
        val titleInput = EditText(this).apply { hint = "Название операции"; textSize = 15f }
        titleInput.setText(editingEntry?.title ?: "")
        content.addView(titleInput, fieldParams())
        val amountInput = EditText(this).apply {
            hint = "Сумма"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 15f
        }
        if (editingEntry != null) amountInput.setText(editingEntry.amount.toString())
        content.addView(amountInput, fieldParams())
        val currencySpinner = Spinner(this)
        currencySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, currencies)
        currencySpinner.setSelection(if (editingEntry != null) { if (currencyOf(editingEntry) == "KGS") 1 else 0 } else if (activeTab == 1) 1 else 0)
        content.addView(currencySpinner, fieldParams())

        val typeSpinner = Spinner(this)
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Расход", "Доход"))
        if (editingEntry?.type == "INCOME") typeSpinner.setSelection(1)
        content.addView(typeSpinner, fieldParams())

        val categorySpinner = Spinner(this)
        val categories = listOf("Продукты", "Жильё", "Транспорт", "Здоровье", "Связь", "Покупки", "Зарплата", "Прочее")
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        if (editingEntry != null) categorySpinner.setSelection(categories.indexOf(categoryOf(editingEntry)).coerceAtLeast(0))
        content.addView(categorySpinner, fieldParams())

        val operationDate = TextView(this).apply {
            text = "Дата операции: $selectedDate  (нажми, чтобы изменить)"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(dp(4), dp(12), dp(4), dp(12))
            setOnClickListener {
                val c = Calendar.getInstance()
                DatePickerDialog(this@MainActivity, { _, y, m, d ->
                    selectedDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                    render()
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        content.addView(operationDate)

        val recurringSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Без повторения", "Еженедельно", "Ежемесячно"))
        }
        recurringSpinner.setSelection(when (editingEntry?.recurringType) { "WEEKLY" -> 1; "MONTHLY" -> 2; else -> 0 })
        content.addView(recurringSpinner, fieldParams())

        val add = Button(this).apply {
            text = if (editingEntryId != null) "Сохранить изменения" else "Добавить операцию"
            isAllCaps = false
            setBackgroundColor(green)
            setTextColor(Color.WHITE)
            setOnClickListener {
                val name = titleInput.text.toString().trim()
                val amount = amountInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (name.isEmpty() || amount == null || amount <= 0) {
                    Toast.makeText(this@MainActivity, "Укажи название и сумму больше нуля", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val cur = currencySpinner.selectedItem.toString()
                val typ = if (typeSpinner.selectedItemPosition == 0) "EXPENSE" else "INCOME"
                val date = selectedDate
                val recurringType = when (recurringSpinner.selectedItemPosition) {
                    1 -> "WEEKLY"
                    2 -> "MONTHLY"
                    else -> "NONE"
                }
                val category = "${categorySpinner.selectedItem}¦$cur"
                val id = editingEntryId ?: System.currentTimeMillis()
                val updated = Entry(id, name, amount, date, typ, category, recurringType)
                if (editingEntryId != null) {
                    val index = entries.indexOfFirst { it.id == editingEntryId }
                    if (index >= 0) entries[index] = updated else entries.add(updated)
                } else {
                    entries.add(updated)
                }
                saveEntries()
                val wasEditing = editingEntryId != null
                editingEntryId = null
                Toast.makeText(this@MainActivity,
                    if (wasEditing) "Операция обновлена" else "Операция добавлена",
                    Toast.LENGTH_SHORT).show()
                render()
            }
        }
        content.addView(add, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6); bottomMargin = dp(18) })

        val searchField = EditText(this).apply {
            hint = "Поиск по названию операции"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setText(searchQuery)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    render()
                    true
                } else false
            }
        }
        content.addView(searchField, fieldParams())
        val filterTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(10))
        }
        listOf("Все", "Доходы", "Расходы").forEachIndexed { index, label ->
            val value = listOf("ALL", "INCOME", "EXPENSE")[index]
            val button = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setTextColor(if (typeFilter == value) Color.WHITE else green)
                background = roundedBackground(if (typeFilter == value) green else Color.rgb(230, 238, 232), 12)
                setOnClickListener { typeFilter = value; render() }
            }
            filterTabs.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index > 0) marginStart = dp(5)
            })
        }
        val monthButton = Button(this).apply {
            text = "Месяц: $selectedMonth"
            isAllCaps = false
            setOnClickListener {
                val parts = selectedMonth.split("-")
                val year = parts[0].toInt()
                val month = parts[1].toInt() - 1
                val c = Calendar.getInstance()
                c.set(year, month, 1)
                DatePickerDialog(this@MainActivity, { _, y, m, _ ->
                    selectedMonth = String.format(Locale.US, "%04d-%02d", y, m + 1)
                    render()
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), 1).show()
            }
        }
        content.addView(monthButton, fieldParams())
        content.addView(filterTabs)
        text(content, "Операции", 18, Color.BLACK, true)
        val monthEntries = entries.filter {
            it.date.startsWith(selectedMonth) &&
                (activeTab == 2 || currencyOf(it) == if (activeTab == 0) "RUB" else "KGS")
        }
        val monthIncome = monthEntries.filter { it.type == "INCOME" }.sumOf { it.amount }
        val monthExpense = monthEntries.filter { it.type == "EXPENSE" }.sumOf { it.amount }
        val statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(Color.WHITE, 16, Color.rgb(230, 238, 232), 1)
        }
        if (activeTab == 2) {
            for (cur in listOf("RUB", "KGS")) {
                val symbol = if (cur == "RUB") "₽" else "сом"
                val income = monthEntries.filter { currencyOf(it) == cur && it.type == "INCOME" }.sumOf { it.amount }
                val expense = monthEntries.filter { currencyOf(it) == cur && it.type == "EXPENSE" }.sumOf { it.amount }
                text(statsCard, "$cur: доходы ${money(income, symbol)} • расходы ${money(expense, symbol)}", 12, muted, false)
                text(statsCard, "Разница: ${money(income - expense, symbol)}", 13, green, true)
            }
        } else {
            val symbol = if (activeTab == 0) "₽" else "сом"
            text(statsCard, "Доходы: ${money(monthIncome, symbol)}", 13, green, true)
            text(statsCard, "Расходы: ${money(monthExpense, symbol)}", 13, Color.rgb(160, 65, 65), true)
            text(statsCard, "Разница: ${money(monthIncome - monthExpense, symbol)}", 15, Color.BLACK, true)
        }
        content.addView(statsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val filtered = entries.filter {
            val cur = currencyOf(it)
            val matchesCurrency = activeTab == 2 || cur == if (activeTab == 0) "RUB" else "KGS"
            val matchesSearch = it.title.contains(searchQuery.trim(), ignoreCase = true)
            val matchesType = typeFilter == "ALL" || it.type == typeFilter
            val matchesMonth = it.date.startsWith(selectedMonth)
            matchesCurrency && matchesSearch && matchesType && matchesMonth
        }.sortedByDescending { it.date }
        if (filtered.isEmpty()) text(content, "Пока нет записей", 14, muted, false)
        filtered.forEach { e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.WHITE)
            }
            val currency = currencyOf(e)
            val symbol = if (currency == "RUB") "₽" else "сом"
            val label = if (e.type == "INCOME") "＋" else "−"
            text(row, "${e.title}   $label${money(e.amount, symbol)}", 15,
                if (e.type == "INCOME") green else Color.rgb(160, 65, 65), true)
            text(row, "${e.date} • ${categoryOf(e)}${when (e.recurringType) { "WEEKLY" -> " • еженедельно"; "MONTHLY" -> " • ежемесячно"; else -> "" }} • $currency", 12, muted, false)
            row.setOnClickListener {
                editingEntryId = e.id
                selectedDate = e.date
                render()
            }
            val del = Button(this).apply {
                text = "Удалить"
                isAllCaps = false
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Удалить операцию?")
                        .setMessage(e.title)
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить") { _, _ ->
                            entries.removeAll { it.id == e.id }
                            saveEntries()
                            render()
                        }
                        .show()
                }
            }
            row.addView(del)
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }

        setContentView(root)
    }

    private fun handleSwipe(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (kotlin.math.abs(dx) > dp(100) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                    currentPage = if (dx < 0) 1 else 0
                    render()
                    return true
                }
            }
        }
        return true
    }

    private fun renderDeposits() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(18), dp(16), dp(12))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        text(content, "Депозиты", 26, Color.BLACK, true)
        text(content, "Управление вкладами и начисленными процентами", 13, muted, false)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(12))
        }

        listOf("Рубли ₽", "Сомы", "Общее").forEachIndexed { index, name ->
            val button = Button(this).apply {
                text = name
                textSize = 12f
                isAllCaps = false
                setPadding(dp(4), 0, dp(4), 0)
                minHeight = 0
                background = roundedBackground(
                    if (depositTab == index) green else Color.rgb(230, 238, 232), 14
                )
                setTextColor(if (depositTab == index) Color.WHITE else green)
                setOnClickListener {
                    depositTab = index
                    render()
                }
            }
            tabs.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (index > 0) marginStart = dp(5)
            })
        }

        content.addView(tabs)
        text(content, "Пока депозитов нет", 15, muted, false)

        val backButton = Button(this).apply {
            text = "← Вернуться к бюджету"
            isAllCaps = false
            setOnClickListener {
                currentPage = 0
                render()
            }
        }
        content.addView(backButton)

        root.addView(content)
        setContentView(root)
    }

    private fun currencyOf(e: Entry): String = if (e.category.endsWith("¦KGS")) "KGS" else "RUB"
    private fun categoryOf(e: Entry): String = e.category.substringBefore("¦")
    private fun balanceAt(currency: String, date: String): Double {
        val target = try { LocalDate.parse(date) } catch (_: Exception) { LocalDate.now() }
        return entries.filter { currencyOf(it) == currency }.sumOf { entry ->
            val start = try { LocalDate.parse(entry.date) } catch (_: Exception) { return@sumOf 0.0 }
            if (start.isAfter(target)) return@sumOf 0.0
            val sign = if (entry.type == "INCOME") 1.0 else -1.0
            when (entry.recurringType) {
                "WEEKLY" -> {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(start, target)
                    val count = (days / 7) + 1
                    sign * entry.amount * count
                }
                "MONTHLY" -> {
                    var month = YearMonth.from(start)
                    val lastMonth = YearMonth.from(target)
                    var count = 0
                    while (!month.isAfter(lastMonth)) {
                        val occurrence = month.atDay(minOf(start.dayOfMonth, month.lengthOfMonth()))
                        if (!occurrence.isBefore(start) && !occurrence.isAfter(target)) count++
                        month = month.plusMonths(1)
                    }
                    sign * entry.amount * count
                }
                else -> sign * entry.amount
            }
        }
    }
    private fun sumFor(currency: String, type: String, date: String): Double {
        val target = try { LocalDate.parse(date) } catch (_: Exception) { LocalDate.now() }

        return entries.filter {
            currencyOf(it) == currency && it.type == type
        }.sumOf { entry ->
            val start = try { LocalDate.parse(entry.date) } catch (_: Exception) {
                return@sumOf 0.0
            }

            if (start.isAfter(target)) return@sumOf 0.0

            when (entry.recurringType) {
                "WEEKLY" -> {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(start, target)
                    entry.amount * ((days / 7) + 1)
                }
                "MONTHLY" -> {
                    var month = YearMonth.from(start)
                    val lastMonth = YearMonth.from(target)
                    var count = 0
                    while (!month.isAfter(lastMonth)) {
                        val occurrence = month.atDay(
                            minOf(start.dayOfMonth, month.lengthOfMonth())
                        )
                        if (!occurrence.isBefore(start) && !occurrence.isAfter(target)) {
                            count++
                        }
                        month = month.plusMonths(1)
                    }
                    entry.amount * count
                }
                else -> entry.amount
            }
        }
    }

    private fun money(value: Double, symbol: String): String {
        val formatted = String.format(Locale("ru", "RU"), "%,.2f", value).replace('\u00a0', ' ')
        return "$formatted $symbol"
    }
    private fun text(parent: LinearLayout, value: String, size: Int, color: Int, bold: Boolean) {
        val v = TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
        parent.addView(v)
    }
    private fun roundedBackground(color: Int, radius: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
        }
    }

    private fun fieldParams() = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(5) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()


    private fun fetchRate(silent: Boolean) {
        if (!silent) Toast.makeText(this, "Обновляю курс…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                // Public online exchange-rate feed: RUB value for one KGS.
                val connection = URL("https://open.er-api.com/v6/latest/KGS").openConnection()
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val body = connection.getInputStream().bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val rate = json.getJSONObject("rates").getDouble("RUB")
                require(rate > 0.0)
                val updated = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
                kgsToRub = rate
                rateUpdatedAt = updated
                rateStatus = "Курс обновлён онлайн"
                prefs.edit().putString("kgs_to_rub", rate.toString())
                    .putString("rate_updated_at", updated).apply()
                runOnUiThread { render() }
            } catch (_: Exception) {
                rateStatus = "Нет интернета — сохранён предыдущий курс"
                runOnUiThread {
                    if (!silent) Toast.makeText(
                        this, "Не удалось получить курс. Используется сохранённое значение.",
                        Toast.LENGTH_LONG
                    ).show()
                    render()
                }
            }
        }
    }


    private fun exportData() {
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject()
                .put("id", it.id)
                .put("title", it.title)
                .put("amount", it.amount)
                .put("date", it.date)
                .put("type", it.type)
                .put("category", it.category)
                .put("recurringType", it.recurringType))
        }

        val backup = JSONObject()
            .put("format", "MoiBudget2")
            .put("version", 1)
            .put("kgsToRub", kgsToRub)
            .put("rateUpdatedAt", rateUpdatedAt)
            .put("entries", arr)
            .put("deposits", JSONArray().apply {
                deposits.forEach {
                    put(JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("currency", it.currency)
                        .put("initialAmount", it.initialAmount)
                        .put("annualRate", it.annualRate)
                        .put("startDate", it.startDate)
                        .put("endDate", it.endDate)
                        .put("transferredToUnallocated", it.transferredToUnallocated))
                }
            })
            .put("depositTopUps", JSONArray().apply {
                depositTopUps.forEach {
                    put(JSONObject()
                        .put("id", it.id)
                        .put("depositId", it.depositId)
                        .put("amount", it.amount)
                        .put("date", it.date))
                }
            })

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "MoiBudget2-backup.json")
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun importData() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            if (requestCode == REQUEST_EXPORT) {
                val arr = JSONArray()
                entries.forEach {
                    arr.put(JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("amount", it.amount)
                        .put("date", it.date)
                        .put("type", it.type)
                        .put("category", it.category)
                        .put("recurringType", it.recurringType))
                }
                val backup = JSONObject()
                    .put("format", "MoiBudget2")
                    .put("version", 1)
                    .put("kgsToRub", kgsToRub)
                    .put("rateUpdatedAt", rateUpdatedAt)
                    .put("entries", arr)

                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(backup.toString(2))
                } ?: throw Exception("Не удалось открыть файл для записи")

                Toast.makeText(this, "Резервная копия сохранена", Toast.LENGTH_LONG).show()

            } else if (requestCode == REQUEST_IMPORT) {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    it.readText()
                } ?: throw Exception("Не удалось прочитать файл")

                val backup = JSONObject(json)
                if (backup.optString("format") != "MoiBudget2" || !backup.has("entries")) {
                    throw Exception("Это не резервная копия Мой бюджет 2")
                }

                val arr = backup.getJSONArray("entries")
                val restored = mutableListOf<Entry>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    restored.add(Entry(
                        o.getLong("id"),
                        o.getString("title"),
                        o.getDouble("amount"),
                        o.getString("date"),
                        o.getString("type"),
                        o.getString("category"),
                        o.optString("recurringType",
                            if (o.optBoolean("recurring", false)) "MONTHLY" else "NONE")
                    ))
                }

                val restoredDeposits = mutableListOf<Deposit>()
                val depositsArray = backup.optJSONArray("deposits") ?: JSONArray()
                for (i in 0 until depositsArray.length()) {
                    val o = depositsArray.getJSONObject(i)
                    restoredDeposits.add(Deposit(
                        o.getLong("id"),
                        o.getString("title"),
                        o.getString("currency"),
                        o.getDouble("initialAmount"),
                        o.getDouble("annualRate"),
                        o.getString("startDate"),
                        o.getString("endDate"),
                        o.optBoolean("transferredToUnallocated", false)
                    ))
                }

                val restoredTopUps = mutableListOf<DepositTopUp>()
                val topUpsArray = backup.optJSONArray("depositTopUps") ?: JSONArray()
                for (i in 0 until topUpsArray.length()) {
                    val o = topUpsArray.getJSONObject(i)
                    restoredTopUps.add(DepositTopUp(
                        o.getLong("id"),
                        o.getLong("depositId"),
                        o.getDouble("amount"),
                        o.getString("date")
                    ))
                }

                android.app.AlertDialog.Builder(this)
                    .setTitle("Восстановить данные?")
                    .setMessage("Текущие операции будут заменены. В файле найдено записей: ${restored.size}.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Восстановить") { _, _ ->
                        entries.clear()
                        entries.addAll(restored)
                        deposits.clear()
                        deposits.addAll(restoredDeposits)
                        depositTopUps.clear()
                        depositTopUps.addAll(restoredTopUps)
                        kgsToRub = backup.optDouble("kgsToRub", kgsToRub)
                        rateUpdatedAt = backup.optString("rateUpdatedAt", rateUpdatedAt)
                        prefs.edit()
                            .putString("kgs_to_rub", kgsToRub.toString())
                            .putString("rate_updated_at", rateUpdatedAt)
                            .apply()
                        saveEntries()
                        editingEntryId = null
                        render()
                        Toast.makeText(this, "Данные восстановлены", Toast.LENGTH_LONG).show()
                    }
                    .show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveEntries() {
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("amount", it.amount)
                .put("date", it.date).put("type", it.type).put("category", it.category)
                .put("recurringType", it.recurringType))
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }
    private fun loadEntries() {
        try {
            val arr = JSONArray(prefs.getString("entries", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(Entry(o.getLong("id"), o.getString("title"), o.getDouble("amount"),
                    o.getString("date"), o.getString("type"), o.getString("category"),
                    o.optString("recurringType", if (o.optBoolean("recurring", false)) "MONTHLY" else "NONE")))
            }
        } catch (_: Exception) {}
    }
}
