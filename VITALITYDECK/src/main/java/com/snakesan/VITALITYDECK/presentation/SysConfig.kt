package com.snakesan.vitalitysys

import org.json.JSONArray
import org.json.JSONObject

// A single scheduled reminder produced by flattening every medication's
// dose times. Each dose gets a globally-unique, stable key so completion
// state, notifications, and telemetry can all refer to "this exact dose"
// rather than just "this medication".
data class Dose(val key: String, val medId: Int, val medName: String, val time: Int)

data class MedicationConfig(
    val id: Int,
    val name: String,
    val times: List<Int> // minutes since midnight; one entry per daily dose
)

data class HygieneTaskConfig(
    val id: Int,
    val label: String,
    val time: Int // minutes since midnight
)

data class SysConfig(
    val mealTimes: List<Int>, // 1-5 entries, minutes since midnight
    val medications: List<MedicationConfig>,
    val hygieneTasks: List<HygieneTaskConfig>,
    val hydrationTargetMl: Float,
    val activeStartHour: Float,
    val activeEndHour: Float,
    val clinicalOverride: Float = 0f
) {
    fun allDoses(): List<Dose> = medications.flatMap { med ->
        med.times.mapIndexed { index, time ->
            Dose(key = doseKey(med.id, index), medId = med.id, medName = med.name, time = time)
        }
    }

    // The soonest-scheduled dose/hygiene task not yet completed today, if
    // any — used to drive a "one thing at a time" display (watch deck,
    // notifications) instead of listing every pending item at once.
    fun nextPendingDose(completedKeys: Set<String>): Dose? =
        allDoses().filter { it.key !in completedKeys }.minByOrNull { it.time }

    fun nextPendingHygieneTask(completedKeys: Set<String>): HygieneTaskConfig? =
        hygieneTasks.filter { hygieneKey(it.id) !in completedKeys }.minByOrNull { it.time }

    companion object {
        val DEFAULT = SysConfig(
            mealTimes = listOf(540, 780, 1140),
            medications = listOf(MedicationConfig(id = 1, name = "Medication", times = listOf(480))),
            hygieneTasks = listOf(
                HygieneTaskConfig(id = 1, label = "Morning Routine", time = 450),
                HygieneTaskConfig(id = 2, label = "Evening Routine", time = 1320)
            ),
            hydrationTargetMl = 3250f,
            activeStartHour = 8f,
            activeEndHour = 22f,
            clinicalOverride = 0f
        )

        fun doseKey(medId: Int, doseIndex: Int) = "MED:$medId:$doseIndex"
        fun hygieneKey(taskId: Int) = "HYG:$taskId"

        fun fromBytes(bytes: ByteArray): SysConfig {
            if (bytes.isEmpty()) return DEFAULT
            return try {
                fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
            } catch (e: Exception) {
                DEFAULT
            }
        }

        fun fromJson(json: JSONObject): SysConfig {
            val mealTimes = json.optJSONArray("mealTimes")?.let { arr ->
                (0 until arr.length()).map { arr.getInt(it) }
            } ?: DEFAULT.mealTimes

            val medications = json.optJSONArray("medications")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    val times = m.getJSONArray("times").let { t -> (0 until t.length()).map { t.getInt(it) } }
                    MedicationConfig(id = m.getInt("id"), name = m.getString("name"), times = times)
                }
            } ?: DEFAULT.medications

            val hygieneTasks = json.optJSONArray("hygieneTasks")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val h = arr.getJSONObject(i)
                    HygieneTaskConfig(id = h.getInt("id"), label = h.getString("label"), time = h.getInt("time"))
                }
            } ?: DEFAULT.hygieneTasks

            return SysConfig(
                mealTimes = mealTimes,
                medications = medications,
                hygieneTasks = hygieneTasks,
                hydrationTargetMl = json.optDouble("hydrationTargetMl", DEFAULT.hydrationTargetMl.toDouble()).toFloat(),
                activeStartHour = json.optDouble("activeStartHour", DEFAULT.activeStartHour.toDouble()).toFloat(),
                activeEndHour = json.optDouble("activeEndHour", DEFAULT.activeEndHour.toDouble()).toFloat(),
                clinicalOverride = json.optDouble("clinicalOverride", 0.0).toFloat()
            )
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("mealTimes", JSONArray(mealTimes))
        json.put("medications", JSONArray(medications.map { m ->
            JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("times", JSONArray(m.times))
            }
        }))
        json.put("hygieneTasks", JSONArray(hygieneTasks.map { h ->
            JSONObject().apply {
                put("id", h.id)
                put("label", h.label)
                put("time", h.time)
            }
        }))
        json.put("hydrationTargetMl", hydrationTargetMl.toDouble())
        json.put("activeStartHour", activeStartHour.toDouble())
        json.put("activeEndHour", activeEndHour.toDouble())
        json.put("clinicalOverride", clinicalOverride.toDouble())
        return json
    }

    fun toBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)
}
