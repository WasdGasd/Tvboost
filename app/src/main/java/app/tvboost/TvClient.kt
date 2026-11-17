package app.tvboost

import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Пакеты, которые приложение не даст отключить никогда. */
private val PROTECTED_PREFIXES = listOf(
    "android",
    "com.android.systemui",
    "com.android.settings",
    "com.android.tv.settings",
    "com.android.providers.",
    "com.android.inputmethod",
    "com.android.packageinstaller",
    "com.android.permissioncontroller",
    "com.android.shell",
    "com.android.vending",                 // Play Маркет
    "com.google.android.gms",              // сервисы Google
    "com.google.android.gsf",
    "com.google.android.tvlauncher",
    "com.google.android.apps.tv.launcherx",
    "com.google.android.leanbacklauncher",
    "com.google.android.tv.provision",
    "com.google.android.tungsten.setupwraith",
    "com.google.android.inputmethod",
    "com.google.android.tv.remote",
    "com.google.android.webview",
    "com.android.webview",
    "com.android.tv",
    "com.google.android.permissioncontroller",
    "com.google.android.packageinstaller",
    "com.android.bluetooth",
    "com.android.keychain",
    "com.android.certinstaller",
)

/** Короткий список того, что почти всегда безопасно отключить (но всё равно спрашиваем). */
val SUGGESTED = listOf(
    "com.google.android.videos",
    "com.google.android.youtube.tvmusic",
    "com.google.android.play.games",
    "com.android.printspooler",
    "com.android.bips",
    "com.google.android.feedback",
    "com.google.android.tv.bugreportsender",
)

class TvClient(private val keyDir: File) {
    private var dadb: Dadb? = null
    private var homePackages: Set<String> = emptySet()

    private fun keyPair(): AdbKeyPair {
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** На ТВ появится запрос «Разрешить отладку по USB?» — нужно нажать «Разрешить». */
    suspend fun connect(host: String, port: Int = 5555): String = withContext(Dispatchers.IO) {
        close()
        val d = Dadb.create(host, port, keyPair())
        dadb = d
        val model = d.shell("getprop ro.product.model").output.trim()
        val release = d.shell("getprop ro.build.version.release").output.trim()
        homePackages = queryHomePackages(d)
        "$model, Android $release"
    }

    private fun queryHomePackages(d: Dadb): Set<String> =
        d.shell("pm query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .allOutput.lineSequence()
            .map { it.trim() }
            .filter { it.contains("/") && !it.contains(" ") }
            .map { it.substringBefore("/") }
            .toSet()

    private fun dd(): Dadb = dadb ?: error("Нет подключения к ТВ")

    fun isProtected(pkg: String): Boolean =
        pkg == "android" ||
            pkg in homePackages ||
            PROTECTED_PREFIXES.any {
                val p = it.trimEnd('.')
                p != "android" && (pkg == p || pkg.startsWith("$p."))
            }

    private fun parsePackages(out: String) =
        out.lineSequence().map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").substringAfter("=", it.removePrefix("package:")) }
            .toList()

    suspend fun enabledPackages(): List<String> = withContext(Dispatchers.IO) {
        parsePackages(dd().shell("pm list packages -e --user 0").allOutput)
            .filter { !isProtected(it) }
            .sorted()
    }

    suspend fun disabledPackages(): Set<String> = withContext(Dispatchers.IO) {
        parsePackages(dd().shell("pm list packages -d --user 0").allOutput).toSet()
    }

    /** Отключает (не удаляет) приложение для основного пользователя. Обратимо. */
    suspend fun disable(pkg: String): Boolean = withContext(Dispatchers.IO) {
        if (isProtected(pkg)) return@withContext false
        dd().shell("am force-stop $pkg")
        val r = dd().shell("pm disable-user --user 0 $pkg")
        r.exitCode == 0 && !r.allOutput.contains("Exception", true)
    }

    suspend fun enable(pkg: String): Boolean = withContext(Dispatchers.IO) {
        val r = dd().shell("pm enable --user 0 $pkg")
        r.exitCode == 0 && !r.allOutput.contains("Exception", true)
    }

    /** Ускорение: быстрее анимации, очистка кэша, остановка фоновых процессов. */
    suspend fun boost(animationScale: String = "0.5"): String = withContext(Dispatchers.IO) {
        val d = dd()
        d.shell("settings put global window_animation_scale $animationScale")
        d.shell("settings put global transition_animation_scale $animationScale")
        d.shell("settings put global animator_duration_scale $animationScale")
        d.shell("pm trim-caches 999999999999")
        d.shell("am kill-all")
        val mem = d.shell("cat /proc/meminfo").allOutput.lineSequence()
            .firstOrNull { it.startsWith("MemAvailable") }?.trim() ?: ""
        "Готово: анимации ×$animationScale, кэш очищен, фоновые процессы остановлены. $mem"
    }

    suspend fun resetAnimations(): Unit = withContext(Dispatchers.IO) {
        val d = dd()
        d.shell("settings put global window_animation_scale 1.0")
        d.shell("settings put global transition_animation_scale 1.0")
        d.shell("settings put global animator_duration_scale 1.0")
    }

    /** Устанавливает APK (например, лаунчер) на ТВ. */
       suspend fun installApk(file: File): Unit = withContext(Dispatchers.IO) {
        val d = dd()
        val remote = "/data/local/tmp/tvboost_install.apk"
        d.push(file, remote)
        val r = d.shell("pm install -r $remote")
        d.shell("rm $remote")
        if (!r.allOutput.contains("Success")) {
            error("Не удалось установить: ${r.allOutput.trim()}")
        }
    }

    /** Пытается назначить лаунчер домашним. Возвращает вывод команды. */
    suspend fun setHome(component: String): String = withContext(Dispatchers.IO) {
        dd().shell("cmd package set-home-activity $component").allOutput.trim()
    }

    suspend fun openHome(): Unit = withContext(Dispatchers.IO) {
        dd().shell("input keyevent KEYCODE_HOME")
    }

    fun close() {
        runCatching { dadb?.close() }
        dadb = null
    }
}

/** Разбор списка из репозитория cleanyourtv (debloat.txt): строки с пакетами, # — комментарии. */
fun parsePackageList(text: String): List<String> {
    val re = Regex("^[a-zA-Z][\\w]*(\\.[\\w]+)+$")
    return text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.substringBefore("#").trim().split(Regex("\\s+")).first().removePrefix("package:") }
        .filter { re.matches(it) }
        .distinct()
        .toList()
}
