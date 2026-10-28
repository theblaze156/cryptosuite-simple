package com.cryptosuite.simple

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: Store
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var content: LinearLayout
    private lateinit var nav: BottomNavigationView
    private var tab = 0
    private val TABS = listOf("Баланс", "Адреса", "Аудит", "Статус", "Настройки")

    override fun onCreate(b: Bundle?) {
        setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        super.onCreate(b)
        store = Store(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        swipe = SwipeRefreshLayout(this).apply { addView(ScrollView(this@MainActivity).apply { addView(content) }); setOnRefreshListener { show(tab) } }
        nav = BottomNavigationView(this).apply {
            TABS.forEachIndexed { i, t -> menu.add(0, i, i, t).setIcon(listOf(android.R.drawable.ic_menu_info_details,
                android.R.drawable.ic_menu_agenda, android.R.drawable.ic_menu_search, android.R.drawable.ic_dialog_alert,
                android.R.drawable.ic_menu_preferences)[i]) }
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener { show(it.itemId); true }
        }
        root.addView(swipe, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)); root.addView(nav)
        setContentView(root)
        if (!store.rootWarned && RootCheck.likelyRooted()) {
            MaterialAlertDialogBuilder(this).setTitle("Внимание").setMessage(
                "Похоже, на устройстве есть root-доступ. Другие приложения могут прочитать сохранённый ключ. Приложение продолжит работу.")
                .setPositiveButton("Понятно") { _, _ -> store.rootWarned = true }.show()
        }
        if (!store.configured) { nav.visibility = View.GONE; setup() } else show(0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 18f, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(6), 0, dp(6)); setTextIsSelectable(true) }
    private fun title(s: String) { content.addView(text(s, 26f, true)) }
    private fun button(s: String, f: () -> Unit) = MaterialButton(this).apply { text = s; textSize = 18f; setOnClickListener { f() } }
        .also { content.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    private fun card(vararg lines: Pair<String, String>, onLong: (() -> Unit)? = null) {
        val c = MaterialCardView(this).apply { useCompatPadding = true; radius = dp(12).toFloat() }
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        lines.forEach { (a, b) ->
            val row = LinearLayout(this); row.addView(text(a, 18f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(text(b, 18f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f)); l.addView(row) }
        c.addView(l); onLong?.let { f -> c.setOnLongClickListener { f(); true } }
        content.addView(c)
    }
    private fun field(hint: String, value: String, secret: Boolean): TextInputEditText {
        val til = TextInputLayout(this).apply { this.hint = hint; if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE }
        val e = TextInputEditText(til.context).apply { setText(value); textSize = 18f
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        til.addView(e); content.addView(til); return e
    }
    private fun error(msg: String) { content.addView(text("⚠ $msg", 18f).apply { setTextColor(0xFFC62828.toInt()) }) }
    private fun note(msg: String) { content.addView(text(msg, 15f).apply { alpha = 0.7f }) }

    private fun <T> load(work: (Api) -> T, done: (T) -> Unit) {
        swipe.isRefreshing = true; val api = store.api()
        io.execute {
            val r = runCatching { work(api) }
            ui.post { swipe.isRefreshing = false
                r.onSuccess(done).onFailure { error(if (it is ApiError) it.userMessage else "Не удалось разобрать ответ сервера.") } }
        }
    }

    private fun show(i: Int) { tab = i; content.removeAllViews(); swipe.isRefreshing = false
        when (i) { 0 -> balance(); 1 -> addresses(); 2 -> auditScreen(); 3 -> status(); else -> settings() } }

    private fun setup() { content.removeAllViews(); title("Первый запуск")
        note("Введите адрес вашего сервера и ключи. Они хранятся только в зашифрованном хранилище этого приложения.")
        settingsForm(true) }

    private fun settingsForm(first: Boolean) {
        val u = field("Адрес сервера (например http://1.2.3.4)", store.url, false)
        val e = field("Ключ доступа (X-Crypto-Auth)", store.edgeKey, true)
        val k = field("API-ключ (bsk_..., только чтение)", store.apiKey, true)
        button("Проверить и сохранить") {
            val url = u.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) { error("Адрес должен начинаться с http:// или https://"); return@button }
            store.url = url; store.edgeKey = e.text.toString().trim(); store.apiKey = k.text.toString().trim()
            load({ it.health() }) {
                Toast.makeText(this, "Сервер отвечает: ${it.optString("status", "ok")}", Toast.LENGTH_LONG).show()
                if (first) { nav.visibility = View.VISIBLE; nav.selectedItemId = 0; show(0) }
            }
        }
        if (url().startsWith("http://")) note("Соединение без шифрования (http). Используйте только свою сеть/сервер; лучше включить HTTPS.")
    }
    private fun url() = store.url

    private fun balance() { title("Баланс")
        load({ api -> Pair(runCatching { api.summary() }, runCatching { api.portfolio() }) }) { (s, p) ->
            s.exceptionOrNull()?.let { error((it as? ApiError)?.userMessage ?: "Сводка недоступна") }
            val agg = Balances.aggregate(s.getOrNull(), p.getOrNull())
            agg.forEach { (sym, v) -> if (sym != "BCH" || (v?.signum() ?: 1) != 0) card(sym to Balances.fmt(v)) }
            s.getOrNull()?.optJSONObject("totals")?.let { note("Адресов под наблюдением: ${it.optInt("addresses")}") }
            note("Показаны только реальные данные сервера. «нет данных» — источник недоступен. Потяните вниз для обновления.")
        } }

    private fun addresses() { title("Адреса")
        note("Добавляйте ТОЛЬКО свои адреса. Приватные ключи и seed-фразы здесь не нужны и не вводятся.")
        button("Добавить адрес") { addDialog() }
        load({ it.watchlist() }) { j ->
            val items = j.optJSONArray("items")
            if (items == null || items.length() == 0) note("Список пуст.")
            else for (i in 0 until items.length()) { val it = items.getJSONObject(i)
                card(it.optString("chain").uppercase() to it.optString("address"),
                    *(if (it.optString("label").let { l -> l.isNotEmpty() && l != "null" }) arrayOf("Метка" to it.optString("label")) else emptyArray())) {
                    MaterialAlertDialogBuilder(this).setTitle("Удалить адрес?").setMessage(it.optString("address"))
                        .setPositiveButton("Удалить") { _, _ -> load({ a -> a.delete(it.optString("id")) }) { show(1) } }
                        .setNegativeButton("Отмена", null).show() } }
            note("Всего: ${j.optInt("total")}. Долгое нажатие на карточку — удалить.")
        } }

    private fun addDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val a = EditText(this).apply { hint = "Адрес"; textSize = 18f }
        val chains = arrayOf("Авто", "btc", "ltc", "bch", "evm")
        val sp = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, chains) }
        box.addView(a); box.addView(sp)
        MaterialAlertDialogBuilder(this).setTitle("Новый адрес (только свой)").setView(box)
            .setPositiveButton("Добавить") { _, _ ->
                val addr = a.text.toString().trim(); if (addr.isEmpty()) return@setPositiveButton
                val ch = sp.selectedItemPosition.let { if (it == 0) null else chains[it] }
                load({ it.add(addr, ch) }) { r -> Toast.makeText(this, "Добавлено: ${r.optInt("imported")}, пропущено: ${r.optInt("skipped")}, ошибок: ${r.optInt("invalid") + r.optInt("rejected")}", Toast.LENGTH_LONG).show(); show(1) }
            }.setNegativeButton("Отмена", null).show()
    }

    private fun auditScreen() { title("Аудит")
        note("Проверка балансов ваших адресов из списка наблюдения.")
        button("Запустить аудит") { content.removeAllViews(); title("Аудит"); load({ it.audit() }) { j ->
            note("Проверено адресов: ${j.optInt("scanned")}")
            val items = j.optJSONArray("items")
            if (items == null || items.length() == 0) note("Нет адресов для проверки.")
            else for (i in 0 until items.length()) { val r = items.getJSONObject(i)
                val bal = if (r.optBoolean("balance_available")) r.optString("balance_display") else "нет данных"
                card(r.optString("chain").uppercase() to r.optString("address"), "Баланс" to bal) }
            j.optJSONArray("notes")?.let { n -> for (i in 0 until n.length()) note(n.getString(i)) }
            button("Запустить снова") { show(2) }
        } } }

    private fun status() { title("Статус и алерты")
        load({ api -> Triple(api.health(), runCatching { api.liquidityStatus() }, runCatching { api.signals() } to runCatching { api.dormant() }) }) { (h, ls, sd) ->
            card("Сервер" to h.optString("status", "?"), *(h.keys().asSequence().filter { it != "status" }.take(6)
                .map { it to h.opt(it).toString().take(40) }.toList().toTypedArray()))
            ls.getOrNull()?.let { s -> card("Отслеживается" to s.optInt("tracked").toString(), "Спящие" to s.optInt("dormant").toString(),
                "Недоступно" to s.optInt("unavailable").toString()) } ?: ls.exceptionOrNull()?.let { error((it as? ApiError)?.userMessage ?: "Статус недоступен") }
            content.addView(text("Последние сигналы", 20f, true))
            val sig = sd.first.getOrNull()?.optJSONArray("items")
            if (sig == null || sig.length() == 0) note("Сигналов нет.") else for (i in 0 until minOf(sig.length(), 20)) {
                val o = sig.getJSONObject(i); card((o.optString("chain").uppercase().ifEmpty { "—" }) to o.optString("address").take(14) + "…",
                    "Направление" to (o.optString("direction") + " " + o.optString("asset")).trim(),
                    "Время" to o.optString("created_at").replace('T', ' ').take(16)) }
            content.addView(text("Спящие кошельки", 20f, true))
            val d = sd.second.getOrNull()?.optJSONArray("items")
            if (d == null || d.length() == 0) note("Нет.") else for (i in 0 until minOf(d.length(), 20)) {
                val o = d.getJSONObject(i); card(o.optString("chain").uppercase() to o.optString("address")) }
        } }

    private fun settings() { title("Настройки"); settingsForm(false)
        button("Сбросить все данные") { MaterialAlertDialogBuilder(this).setTitle("Удалить адрес сервера и ключи с телефона?")
            .setPositiveButton("Удалить") { _, _ -> store.clear(); nav.visibility = View.GONE; setup() }.setNegativeButton("Отмена", null).show() }
        note("Приложение только читает данные и управляет списком наблюдения. Никаких подписей, переводов и sweep. Ключи не логируются.")
        note("Версия 1.0") }
}
