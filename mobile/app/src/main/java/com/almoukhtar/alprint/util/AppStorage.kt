package com.almoukhtar.alprint.util

import android.content.Context
import com.almoukhtar.alprint.model.ActivityEntry
import com.almoukhtar.alprint.model.ScanInputSource
import com.almoukhtar.alprint.model.ScanPreset
import com.almoukhtar.alprint.model.ShortcutConfig
import org.json.JSONArray
import org.json.JSONObject

class AppStorage(context: Context) {
    private val prefs = context.getSharedPreferences("alprint_mobile_prefs", Context.MODE_PRIVATE)

    fun isDarkMode(): Boolean = prefs.getBoolean("dark_mode", true)

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    fun getPrinterIp(): String = prefs.getString("printer_ip", "192.168.0.58") ?: "192.168.0.58"

    fun setPrinterIp(ip: String) {
        prefs.edit().putString("printer_ip", ip.trim()).apply()
    }

    fun loadShortcuts(): List<ShortcutConfig> {
        val raw = prefs.getString("shortcuts_json", null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultShortcuts()
            saveShortcuts(defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ShortcutConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ShortcutConfig(
                        id = obj.optString("id", "sc_$i"),
                        name = obj.optString("name", "Quick Scan"),
                        subtitle = obj.optString("subtitle", ""),
                        source = runCatching {
                            ScanInputSource.valueOf(obj.optString("source", "CAMERA"))
                        }.getOrDefault(ScanInputSource.CAMERA),
                        preset = runCatching {
                            ScanPreset.valueOf(obj.optString("preset", "DOCUMENT"))
                        }.getOrDefault(ScanPreset.DOCUMENT),
                        dpi = obj.optInt("dpi", 300),
                        colorMode = obj.optString("colorMode", "Color"),
                        filter = obj.optString("filter", "auto_enhance"),
                        outputFormat = obj.optString("outputFormat", "PDF"),
                        filenameTemplate = obj.optString("filenameTemplate", "Scan_{date}_{time}"),
                        autoOcr = obj.optBoolean("autoOcr", false)
                    )
                )
            }
            list.ifEmpty { defaultShortcuts() }
        } catch (_: Exception) {
            defaultShortcuts()
        }
    }

    fun saveShortcuts(shortcuts: List<ShortcutConfig>) {
        val arr = JSONArray()
        for (sc in shortcuts) {
            val obj = JSONObject().apply {
                put("id", sc.id)
                put("name", sc.name)
                put("subtitle", sc.subtitle)
                put("source", sc.source.name)
                put("preset", sc.preset.name)
                put("dpi", sc.dpi)
                put("colorMode", sc.colorMode)
                put("filter", sc.filter)
                put("outputFormat", sc.outputFormat)
                put("filenameTemplate", sc.filenameTemplate)
                put("autoOcr", sc.autoOcr)
            }
            arr.put(obj)
        }
        prefs.edit().putString("shortcuts_json", arr.toString()).apply()
    }

    fun loadActivities(): List<ActivityEntry> {
        val raw = prefs.getString("activity_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ActivityEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ActivityEntry(
                        id = obj.optString("id", "act_$i"),
                        type = obj.optString("type", "scan"),
                        title = obj.optString("title", ""),
                        subtitle = obj.optString("subtitle", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addActivity(type: String, title: String, subtitle: String): List<ActivityEntry> {
        val current = loadActivities().toMutableList()
        current.add(
            0,
            ActivityEntry(
                id = "act_${System.currentTimeMillis()}",
                type = type,
                title = title,
                subtitle = subtitle,
                timestamp = System.currentTimeMillis()
            )
        )
        val trimmed = current.take(25)
        val arr = JSONArray()
        for (item in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("type", item.type)
                    put("title", item.title)
                    put("subtitle", item.subtitle)
                    put("timestamp", item.timestamp)
                }
            )
        }
        prefs.edit().putString("activity_json", arr.toString()).apply()
        return trimmed
    }

    fun clearActivities(): List<ActivityEntry> {
        prefs.edit().remove("activity_json").apply()
        return emptyList()
    }

    private fun defaultShortcuts(): List<ShortcutConfig> = listOf(
        ShortcutConfig(
            id = "sc_cam_pdf",
            name = "Camera Scan to Searchable PDF",
            subtitle = "Phone Camera • Document • Auto-Enhance + Offline OCR",
            source = ScanInputSource.CAMERA,
            preset = ScanPreset.DOCUMENT,
            dpi = 300,
            colorMode = "Color",
            filter = "auto_enhance",
            outputFormat = "PDF",
            filenameTemplate = "Doc_{date}_{time}",
            autoOcr = true
        ),
        ShortcutConfig(
            id = "sc_wifi_flatbed",
            name = "Wi-Fi Flatbed to PDF (300 DPI)",
            subtitle = "HP Smart Tank eSCL • 300 DPI Color • PDF",
            source = ScanInputSource.WIFI_ESCL,
            preset = ScanPreset.DOCUMENT,
            dpi = 300,
            colorMode = "Color",
            filter = "auto_enhance",
            outputFormat = "PDF",
            filenameTemplate = "Flatbed_{date}_{time}",
            autoOcr = false
        ),
        ShortcutConfig(
            id = "sc_id_copy",
            name = "ID Card Front + Back Copy",
            subtitle = "2-Step Guided Capture • Stitched A4 • Direct Print",
            source = ScanInputSource.CAMERA,
            preset = ScanPreset.ID_CARD,
            dpi = 300,
            colorMode = "Color",
            filter = "auto_enhance",
            outputFormat = "PRINT",
            filenameTemplate = "ID_Card_{date}",
            autoOcr = false
        ),
        ShortcutConfig(
            id = "sc_receipt_bw",
            name = "Receipt / Invoice B&W Archive",
            subtitle = "Phone Camera • B&W Otsu Threshold • Offline OCR",
            source = ScanInputSource.CAMERA,
            preset = ScanPreset.DOCUMENT,
            dpi = 300,
            colorMode = "Black & White",
            filter = "bw_document",
            outputFormat = "PDF",
            filenameTemplate = "Receipt_{date}_{time}",
            autoOcr = true
        )
    )
}
