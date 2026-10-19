package com.example.extractor

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var rgTierRow1: RadioGroup
    private lateinit var rgTierRow2: RadioGroup
    private lateinit var etCustomTier: EditText
    private lateinit var etMaxDepth: EditText
    private var pendingIcon = "preset_thinker"

    override fun onCreate(savedInstanceState: Bundle?) {
        MainActivity.applyAppLocale(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        MainActivity.applyTheme(this, findViewById<View>(android.R.id.content))

        rgTierRow1 = findViewById(R.id.rgTierRow1)
        rgTierRow2 = findViewById(R.id.rgTierRow2)
        etCustomTier = findViewById(R.id.etCustomTier)
        etMaxDepth = findViewById(R.id.etMaxDepth)

        loadValues()
        bindEvents()
    }

    private fun loadValues() {
        val tier = prefs.getString("tier", "auto") ?: "auto"
        rgTierRow1.clearCheck()
        rgTierRow2.clearCheck()
        if (tier == "custom") rgTierRow2.check(R.id.rbCustom)
        else rgTierRow1.check(
            when (tier) {
                "low" -> R.id.rbLow
                "medium" -> R.id.rbMed
                "high" -> R.id.rbHigh
                else -> R.id.rbAuto
            })
        etCustomTier.setText(prefs.getInt("tier_custom", 2).toString())
        etMaxDepth.setText(prefs.getInt("max_depth", 10).toString())

        findViewById<Switch>(R.id.swLang).isChecked = (prefs.getString("lang", "zh") ?: "zh") == "en"
        findViewById<Switch>(R.id.swNotifDetail).isChecked = prefs.getBoolean("notif_detail", true)
        findViewById<Switch>(R.id.swFinishNotif).isChecked = prefs.getBoolean("finish_notif", true)
        findViewById<Switch>(R.id.swAutoExport).isChecked = prefs.getBoolean("auto_export", false)
        findViewById<Switch>(R.id.swScanSubdir).isChecked = prefs.getBoolean("scan_subdir", false)

        val verName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "1.4.0" }
        findViewById<TextView>(R.id.tvAbout).text = "do压缩包 v$verName"
        pendingIcon = prefs.getString("app_icon", "preset_thinker") ?: "preset_thinker"
        highlightIcon()
        loadCurrentIcon()
    }

    /** 显示当前图标预览（仅内置预设）。 */
    private fun loadCurrentIcon() {
        try {
            val iv = findViewById<ImageView>(R.id.imgCurrentIcon)
            val id = when (pendingIcon) {
                "preset_rock" -> R.drawable.icon_preset_rock
                "preset_pc" -> R.drawable.icon_preset_pc
                "preset_monitor" -> R.drawable.icon_preset_monitor
                "preset_phone" -> R.drawable.icon_preset_phone
                else -> R.drawable.icon_preset_thinker
            }
            iv.setImageResource(id)
        } catch (_: Exception) {}
    }

    private fun bindEvents() {
        rgTierRow1.setOnCheckedChangeListener { _, checkedId ->
            val tier = when (checkedId) {
                R.id.rbLow -> "low"
                R.id.rbMed -> "medium"
                R.id.rbHigh -> "high"
                else -> "auto"
            }
            prefs.edit().putString("tier", tier).apply()
        }
        rgTierRow2.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbCustom) {
                prefs.edit().putString("tier", "custom").apply()
                etCustomTier.requestFocus()
            }
        }

        etCustomTier.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveCustomTier()
        }
        etMaxDepth.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveMaxDepth()
        }

        findViewById<Switch>(R.id.swLang).setOnCheckedChangeListener { _, checked ->
            prefs.edit().putString("lang", if (checked) "en" else "zh").apply()
            recreate()
        }
        findViewById<Switch>(R.id.swNotifDetail).setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("notif_detail", c).apply()
        }
        findViewById<Switch>(R.id.swFinishNotif).setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("finish_notif", c).apply()
        }
        findViewById<Switch>(R.id.swAutoExport).setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("auto_export", c).apply()
        }
        findViewById<Switch>(R.id.swScanSubdir).setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("scan_subdir", c).apply()
        }

        bindTheme("purple", R.id.btnThemePurple)
        bindTheme("night", R.id.btnThemeNight)
        bindTheme("eye", R.id.btnThemeEye)
        bindTheme("kraft", R.id.btnThemeKraft)
        bindTheme("blue", R.id.btnThemeBlue)
        bindTheme("teal", R.id.btnThemeTeal)
        bindTheme("pink", R.id.btnThemePink)
        bindTheme("orange", R.id.btnThemeOrange)
        bindTheme("red", R.id.btnThemeRed)
        bindTheme("gray", R.id.btnThemeGray)
        bindTheme("mint", R.id.btnThemeMint)
        bindTheme("cream", R.id.btnThemeCream)
        findViewById<Button>(R.id.btnThemeCustom).setOnClickListener {
            prefs.edit().putString("theme", "custom").apply()
            recreate()
        }
        findViewById<Button>(R.id.btnThemeBg).setOnClickListener { pickBg.launch(arrayOf("image/*")) }
        findViewById<Button>(R.id.btnThemeFont).setOnClickListener { showFontPalette() }
        bindIcon()
    }

    private fun bindTheme(theme: String, btnId: Int) {
        findViewById<Button>(btnId).setOnClickListener {
            prefs.edit().putString("theme", theme).apply()
            recreate()
        }
    }

    private fun saveCustomTier() {
        val n = etCustomTier.text.toString().toIntOrNull()?.coerceIn(1, 16) ?: 2
        etCustomTier.setText(n.toString())
        prefs.edit().putInt("tier_custom", n).apply()
        Toast.makeText(this, "自定义并发：$n 路", Toast.LENGTH_SHORT).show()
    }

    private fun saveMaxDepth() {
        val n = etMaxDepth.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 10
        etMaxDepth.setText(n.toString())
        prefs.edit().putInt("max_depth", n).apply()
    }

    private val pickBg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val out = File(filesDir, "theme_bg.png")
            contentResolver.openInputStream(uri)?.use { ins ->
                out.outputStream().use { os -> ins.copyTo(os) }
            }
            prefs.edit().putString("theme_bg", out.absolutePath).putString("theme", "custom").apply()
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, "背景图读取失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFontPalette() {
        val colors = arrayOf(
            "#1A1A1A" to "深黑",
            "#FFFFFF" to "纯白",
            "#333333" to "墨灰",
            "#0D47A1" to "深蓝",
            "#1B5E20" to "深绿",
            "#4E342E" to "深棕",
            "#880E4F" to "酒红",
            "#FFB300" to "琥珀"
        )
        val names = colors.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.theme_custom_font))
            .setItems(names) { _, which ->
                prefs.edit()
                    .putString("theme_font", colors[which].first)
                    .putString("theme", "custom")
                    .apply()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 应用图标 DIY =====
    private fun bindIcon() {
        bindIconItem(R.id.iconRock, "preset_rock")
        bindIconItem(R.id.iconPc, "preset_pc")
        bindIconItem(R.id.iconMonitor, "preset_monitor")
        bindIconItem(R.id.iconPhone, "preset_phone")
        bindIconItem(R.id.iconThinker, "preset_thinker")
        findViewById<Button>(R.id.btnIconSave).setOnClickListener {
            prefs.edit().putString("app_icon", pendingIcon).apply()
            applyLauncherAlias(pendingIcon)
            // 广播 PACKAGE_CHANGED，促使桌面启动器重新加载图标
            try {
                sendBroadcast(Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            Toast.makeText(this, "桌面图标已切换，请返回桌面查看（个别手机需重启一次才刷新）", Toast.LENGTH_SHORT).show()
            finishAffinity()
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    private fun bindIconItem(id: Int, key: String) {
        findViewById<ImageView>(id).setOnClickListener {
            pendingIcon = key
            highlightIcon()
            loadCurrentIcon()
        }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun highlightIcon() {
        val pairs = listOf(
            R.id.iconRock to "preset_rock", R.id.iconPc to "preset_pc",
            R.id.iconMonitor to "preset_monitor", R.id.iconPhone to "preset_phone",
            R.id.iconThinker to "preset_thinker")
        val cur = pendingIcon
        pairs.forEach { (id, key) ->
            val v = findViewById<ImageView>(id)
            if (key == cur) {
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 8.dp().toFloat()
                    setStroke(3.dp(), 0xFF6200EE.toInt())
                }
            } else {
                v.setBackgroundColor(0x22000000)
            }
        }
    }

    /** 切换桌面应用图标：启用所选 activity-alias，禁用其余（custom 固定 thinker） */
    private fun applyLauncherAlias(key: String) {
        val pm = packageManager
        val aliases = mapOf(
            "preset_rock" to "AliasRock",
            "preset_pc" to "AliasPc",
            "preset_monitor" to "AliasMonitor",
            "preset_phone" to "AliasPhone",
            "preset_thinker" to "AliasThinker")
        val target = aliases[key] ?: "AliasThinker"
        aliases.values.forEach { alias ->
            pm.setComponentEnabledSetting(
                ComponentName(this, "com.example.extractor.$alias"),
                if (alias == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }
    }
}
