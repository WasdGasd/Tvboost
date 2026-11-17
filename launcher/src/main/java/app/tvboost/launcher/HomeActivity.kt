package app.tvboost.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

/**
 * Лёгкий домашний экран для слабых Android TV / проекторов (Android 5+).
 * Без фоновых служб и сети. Управление пультом: стрелки + OK, долгое OK — меню приложения.
 */
class HomeActivity : Activity() {

    private class Item(val label: String, val pkg: String, val cls: String, val icon: Drawable?, val kind: Int)

    private lateinit var prefs: SharedPreferences
    private lateinit var grid: GridView
    private val ui = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Drawable?>()
    private var items: List<Item> = emptyList()

    // Приложения для просмотра идут первыми, в таком порядке
    private val favoriteKeywords = listOf(
        "youtube", "kinopoisk", "yandex", "okko", "netflix", "ivi", "wink", "kion", "rutube", "amazonvideo"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(48), dp(28), dp(48), dp(12))
        }
        val clock = TextClock(this).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            textSize = 44f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"; format24Hour = "EEEE, d MMMM"
            textSize = 16f; setTextColor(0xFF9A9A9A.toInt())
        }
        root.addView(clock)
        root.addView(date)

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(150)
            verticalSpacing = dp(14)
            horizontalSpacing = dp(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isFocusable = true
            setPadding(0, dp(24), 0, dp(16))
            clipToPadding = false
            selector = GradientDrawable().apply {
                setColor(0x33FFFFFF); cornerRadius = dp(14).toFloat(); setStroke(dp(2), Color.WHITE)
            }
            adapter = object : BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(p: Int) = items[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                    val v = (convert as? LinearLayout) ?: makeCell()
                    val it = items[p]
                    (v.getChildAt(0) as ImageView).setImageDrawable(it.icon)
                    (v.getChildAt(1) as TextView).text = it.label
                    return v
                }
            }
            setOnItemClickListener { _, _, pos, _ -> open(items[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> menu(items[pos]); true }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun makeCell(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(8), dp(12), dp(8), dp(10))
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132))
        addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        })
        addView(TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 14f; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
    }

    override fun onResume() {
        super.onResume()
        Thread {
            val list = loadItems()
            ui.post {
                items = list
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
                grid.requestFocus()
            }
        }.start()
    }

    private fun stringSet(key: String): MutableSet<String> =
        HashSet(prefs.getStringSet(key, emptySet()) ?: emptySet())

    private fun loadItems(): List<Item> {
        val pm = packageManager
        val hidden = stringSet("hidden")
        val pinned = stringSet("pinned")
        val found = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val p = ri.activityInfo.packageName
                if (p != packageName && p !in hidden && !found.containsKey(p)) found[p] = ri
            }
        }
        fun rank(it: Item): Int {
            if (it.pkg in pinned) return 0
            val i = favoriteKeywords.indexOfFirst { k -> it.pkg.contains(k, true) }
            return if (i >= 0) 1 + i else 100
        }
        val apps = found.values.map { ri ->
            val p = ri.activityInfo.packageName
            val icon = iconCache.getOrPut(p) { runCatching { ri.loadIcon(pm) }.getOrNull() }
            Item(ri.loadLabel(pm).toString(), p, ri.activityInfo.name, icon, 0)
        }.sortedWith(compareBy<Item>({ rank(it) }, { it.label.lowercase() }))

        val tail = mutableListOf(
            Item("Настройки", "", "", getDrawable(android.R.drawable.ic_menu_preferences), 1)
        )
        if (hidden.isNotEmpty()) {
            tail += Item("Показать скрытые (${hidden.size})", "", "", getDrawable(android.R.drawable.ic_menu_revert), 2)
        }
        return apps + tail
    }

    private fun open(it: Item) {
        try {
            when (it.kind) {
                0 -> startActivity(
                    Intent(Intent.ACTION_MAIN).setClassName(it.pkg, it.cls)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                )
                1 -> startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                2 -> {
                    prefs.edit().remove("hidden").apply()
                    onResume()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть: ${it.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun menu(it: Item) {
        if (it.kind != 0) return
        val pinned = stringSet("pinned")
        val isPinned = it.pkg in pinned
        AlertDialog.Builder(this)
            .setTitle(it.label)
            .setItems(arrayOf(if (isPinned) "Убрать из избранного" else "В начало списка", "Скрыть с экрана", "О приложении")) { _, which ->
                when (which) {
                    0 -> {
                        if (isPinned) pinned.remove(it.pkg) else pinned.add(it.pkg)
                        prefs.edit().putStringSet("pinned", pinned).apply()
                        onResume()
                    }
                    1 -> {
                        val h = stringSet("hidden"); h.add(it.pkg)
                        prefs.edit().putStringSet("hidden", h).apply()
                        onResume()
                    }
                    2 -> runCatching {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${it.pkg}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }.show()
    }

    // Кнопка «Домой» на самом домашнем экране ничего не делает
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {}
}
