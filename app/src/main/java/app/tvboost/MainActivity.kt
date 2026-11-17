package app.tvboost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { TvBoostApp(filesDir) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvBoostApp(dir: File) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { TvClient(dir) }
    val prefs = remember { ctx.getSharedPreferences("tvboost", 0) }

    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var tvName by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(listOf<String>()) }
    var ours by remember { mutableStateOf(prefs.getStringSet("ours", emptySet())!!.toSet()) }
    val selected = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    fun saveOurs(s: Set<String>) {
        ours = s
        prefs.edit().putStringSet("ours", s).apply()
    }

    fun run(block: suspend () -> String) {
        scope.launch {
            busy = true
            msg = try { block() } catch (e: Exception) { "Ошибка: ${e.message ?: e.javaClass.simpleName}" }
            busy = false
        }
    }

    suspend fun refresh() { apps = client.enabledPackages() }

    if (tvName == null) {
        // ---------- Экран подключения ----------
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("TvBoost", style = MaterialTheme.typography.headlineLarge)
            Text("Ускорение Android TV / Google TV с телефона по Wi-Fi. Ничего не удаляется — приложения только отключаются и их можно вернуть.")
            Text("1. На ТВ: Настройки → Устройство → О устройстве → 7 раз нажать «Сборка».\n" +
                "2. Для разработчиков → включить «Отладка по USB» (и «Отладка по сети», если есть).\n" +
                "3. Телефон и ТВ — в одной Wi-Fi сети. IP ТВ: Настройки → Сеть.\n" +
                "4. Нажать «Подключиться» и разрешить отладку на экране ТВ.")
            OutlinedTextField(
                value = host, onValueChange = { host = it.trim() },
                label = { Text("IP-адрес ТВ, например 192.168.1.50") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = host.isNotEmpty() && !busy,
                onClick = {
                    prefs.edit().putString("host", host).apply()
                    run {
                        msg = "Подключаюсь… подтвердите запрос на экране ТВ"
                        tvName = client.connect(host)
                        refresh()
                        "Подключено"
                    }
                }
            ) { Text(if (busy) "Подключение…" else "Подключиться") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (msg.isNotEmpty()) Text(msg)
        }
        return
    }

    // ---------- Основной экран ----------
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("TvBoost"); Text(tvName!!, style = MaterialTheme.typography.labelMedium) } }) }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Приложения", "Ускорение", "Вернуть").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (msg.isNotEmpty()) Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)

            when (tab) {
                0 -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Поиск") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        OutlinedButton(onClick = {
                            selected.clear()
                            selected.addAll(SUGGESTED.filter { it in apps })
                            msg = "Выбрано рекомендуемых: ${selected.size}"
                        }) { Text("Рекомендуемые") }
                        OutlinedButton(onClick = { showImport = true }) { Text("Из списка") }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(apps.filter { it.contains(query, true) }, key = { it }) { pkg ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (pkg in selected) selected.remove(pkg) else selected.add(pkg)
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = pkg in selected, onCheckedChange = {
                                    if (it) selected.add(pkg) else selected.remove(pkg)
                                })
                                Text(pkg, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Button(
                        enabled = selected.isNotEmpty() && !busy,
                        onClick = { confirm = true },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) { Text("Отключить выбранные (${selected.size})") }
                }

                1 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ускорить интерфейс: анимации ×0.5, очистка кэша приложений, остановка фоновых процессов. Безопасно, всё обратимо.")
                    Button(enabled = !busy, onClick = { run { client.boost("0.5") } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Ускорить сейчас")
                    }
                    OutlinedButton(enabled = !busy, onClick = { run { client.boost("0") } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Максимум: без анимаций")
                    }
                    OutlinedButton(enabled = !busy, onClick = {
                        run { client.resetAnimations(); "Анимации возвращены к стандартным" }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Вернуть анимации") }
                }

                2 -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Text("Отключено через TvBoost: ${ours.size}", Modifier.padding(vertical = 8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(ours.sorted(), key = { it }) { pkg ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(pkg, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(enabled = !busy, onClick = {
                                    run {
                                        if (client.enable(pkg)) { saveOurs(ours - pkg); refresh(); "Включено: $pkg" }
                                        else "Не удалось включить $pkg"
                                    }
                                }) { Text("Включить") }
                            }
                        }
                    }
                    Button(
                        enabled = ours.isNotEmpty() && !busy,
                        onClick = {
                            run {
                                var ok = 0
                                for (p in ours.toList()) if (client.enable(p)) { saveOurs(ours - p); ok++ }
                                refresh()
                                "Восстановлено: $ok"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) { Text("Восстановить всё") }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Отключить ${selected.size} приложений?") },
            text = { Text("Приложения будут отключены, не удалены. Вернуть их можно на вкладке «Вернуть». Системные и лаунчер защищены.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val list = selected.toList()
                    run {
                        var ok = 0
                        val done = mutableSetOf<String>()
                        for (p in list) if (client.disable(p)) { ok++; done += p }
                        saveOurs(ours + done)
                        selected.clear()
                        refresh()
                        "Отключено: $ok из ${list.size}. Перезагрузите ТВ для лучшего эффекта."
                    }
                }) { Text("Отключить") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } }
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Список пакетов") },
            text = {
                Column {
                    Text("Вставьте содержимое debloat.txt для вашей модели из репозитория cleanyourtv (папка examples/).")
                    OutlinedTextField(value = importText, onValueChange = { importText = it }, modifier = Modifier.height(200.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val wanted = parsePackageList(importText).filter { it in apps }
                    selected.clear(); selected.addAll(wanted)
                    msg = "Найдено на ТВ и выбрано: ${wanted.size}"
                    showImport = false
                }) { Text("Выбрать") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("Отмена") } }
        )
    }
}
