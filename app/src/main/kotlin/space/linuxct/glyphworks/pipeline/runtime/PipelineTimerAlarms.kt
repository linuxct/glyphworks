package space.linuxct.glyphworks.pipeline.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.TimerSignalPort
import space.linuxct.glyphworks.toy.TimerAlarmReceiver
import space.linuxct.pipeline.Value

/** Independent, durable deadlines. The receiver never renders or overrides a live preview. */
class PipelineTimerAlarms(private val app: Context, private val prefs: Prefs, private val signal: TimerSignalPort) {
    @Serializable private data class Alarm(val document: String, val program: String, val slot: String, val deadline: Long, val native: Boolean, val statePrefix: String = "")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun key(document: String, program: String, slot: String, native: Boolean) = "$document|$program|$slot|$native"
    fun action(document: String, action: String, values: Map<String, Value>) {
        val program = values["owner"]?.text().orEmpty()
        val slot = values["instance"]?.text().orEmpty().ifBlank { "root:main" }
        if (program.isEmpty()) return
        val identity = key(document, program, slot, true)
        val statePrefix = (values["statePrefix"]?.text() ?: "") + "native:${values["slot"]?.text() ?: slot.substringAfter(':', "main")}:"
        when (action) {
            "native.timer.schedule" -> schedule(identity, Alarm(document, program, slot, values["deadline"]?.number()?.toLong() ?: 0L, true, statePrefix))
            "native.timer.cancel" -> cancel(identity)
            "native.timer.chime" -> signal.chime()
        }
    }
    fun persistent(document: String, program: String, name: String, deadline: Long) {
        val identity = key(document, program, name, false)
        if (deadline > 0) schedule(identity, Alarm(document, program, name, deadline, false)) else cancel(identity)
    }
    private fun pending(key: String, deadline: Long): PendingIntent = PendingIntent.getBroadcast(app, 0,
        Intent(app, TimerAlarmReceiver::class.java).setData(Uri.Builder().scheme("glyphworks-pipeline").authority("timer").appendPath(key).build())
            .putExtra(EXTRA_KEY, key).putExtra(EXTRA_DEADLINE, deadline), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @Synchronized private fun schedule(key: String, alarm: Alarm) {
        if (alarm.deadline <= 0) return
        prefs.putString(PREFIX + key, json.encodeToString(Alarm.serializer(), alarm))
        remember(keys() + key)
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = pending(key, alarm.deadline)
        val at = alarm.deadline + if (alarm.native) 3000 else 0
        try { if (manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) else manager.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, intent) }
        catch (_: SecurityException) { manager.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, intent) }
    }
    @Synchronized private fun cancel(key: String) { app.getSystemService(AlarmManager::class.java)?.cancel(pending(key, 0)); prefs.remove(PREFIX + key); remember(keys() - key) }
    private fun keys(): Set<String> = runCatching { (json.decodeFromString(Value.serializer(), prefs.getString(INDEX, "")) as? Value.Items)?.values?.map { it.text() }?.toSet() }.getOrNull().orEmpty()
    private fun remember(keys: Set<String>) = prefs.putString(INDEX, json.encodeToString(Value.serializer(), Value.Items(keys.map { Value.Text(it) })))
    @Synchronized fun cancelDocument(document: String) { keys().filter { it.substringBefore('|').substringBefore('@') == document }.forEach(::cancel) }
    @Synchronized fun cancelAll() { keys().toList().forEach(::cancel) }

    @Synchronized fun fire(key: String, deadline: Long) {
        val alarm = prefs.getString(PREFIX + key, "").takeIf(String::isNotBlank)?.let { runCatching { json.decodeFromString(Alarm.serializer(), it) }.getOrNull() } ?: return
        if (alarm.deadline != deadline || System.currentTimeMillis() < deadline) return
        if (alarm.native) {
            fun state(name: String) = PipelineController.stateKey(alarm.document, alarm.program, (alarm.statePrefix.ifBlank { "native:${alarm.slot.substringAfter(':')}:" } + name))
            fun read(name: String) = runCatching { json.decodeFromString(Value.serializer(), prefs.getString(state(name), "")) }.getOrNull()?.number()?.toLong() ?: 0L
            val start = read(PrefKeys.TIMER_START)
            if (start <= 0 || read(PrefKeys.TIMER_PAUSED_ELAPSED) > 0 || read(PrefKeys.TIMER_CHIMED_FOR) == start) { cancel(key); return }
            prefs.putString(state(PrefKeys.TIMER_CHIMED_FOR), json.encodeToString(Value.serializer(), Value.Number(start.toDouble())))
            signal.chime()
        }
        prefs.remove(PREFIX + key)
        remember(keys() - key)
    }
    companion object {
        const val EXTRA_KEY = "pipelineTimerKey"
        const val EXTRA_DEADLINE = "pipelineTimerDeadline"
        private const val PREFIX = "pipelineAlarm:"
        private const val INDEX = "pipelineAlarmIndex"
    }
}
