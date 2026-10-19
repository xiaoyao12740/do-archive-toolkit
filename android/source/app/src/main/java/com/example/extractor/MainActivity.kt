package com.example.extractor

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.extractor.data.PasswordDatabase
import com.example.extractor.engine.CompressEngine
import com.example.extractor.service.ExtractService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var tvPath: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var svLog: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var cbDelSource: CheckBox
    private lateinit var prefs: android.content.SharedPreferences
    private var testDelOverride: Boolean? = null
    private val tierButtons = mutableMapOf<String, Button>()
    private var rootPath: String? = null
    private val logBuffer = mutableListOf<String>()
    private val maxLogLines = 500

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            rootPath = uriToPath(uri)
            tvPath.text = rootPath ?: "无法解析路径"
        }
    }

    /** 选择单个压缩包文件（解压目标） */
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            rootPath = uriToPathFile(uri)
            tvPath.text = rootPath ?: "无法解析路径"
        }
    }

    // ---- 压缩视图 ----
    private lateinit var extractPanel: android.widget.LinearLayout
    private lateinit var compressPanel: android.widget.LinearLayout
    private lateinit var btnToggle: Button
    private lateinit var cTvSource: TextView
    private lateinit var cEtLayers: android.widget.EditText
    private lateinit var cSpFormat: Spinner
    private lateinit var cTvPwdCount: TextView
    private lateinit var cEtPwd: android.widget.EditText
    private lateinit var cCbDeleteSrc: CheckBox
    private lateinit var cProgressBar: ProgressBar
    private lateinit var cTvCProgress: TextView
    private lateinit var cTvCLog: TextView
    private lateinit var cSvCLog: ScrollView
    private var cSourcePath: String? = null
    private var compressing = false
    private var showingCompress = false

    private val pickDirC = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            cSourcePath = uriToPath(uri)
            cTvSource.text = cSourcePath ?: "无法解析路径"
        }
    }

    /** 选择单个文件（压缩源） */
    private val pickFileC = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            cSourcePath = uriToPathFile(uri)
            cTvSource.text = cSourcePath ?: "无法解析路径"
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    // 定时轮询服务共享进度，展示总进度% + 当前 m/n + 包内%（500ms）
    private val progressTick = object : Runnable {
        override fun run() {
            val total = ExtractService.shareTotal
            val done = ExtractService.shareDone
            val inner = ExtractService.shareInner
            val ok = ExtractService.shareOk
            val fail = ExtractService.shareFail
            val cur = ExtractService.shareCur
            val finished = ExtractService.shareFinished
            if (total > 0) {
                val totalPct = (done * 100 / total).coerceIn(0, 100)
                progressBar.max = 100
                progressBar.progress = totalPct
                tvProgress.text = if (finished) {
                    "完成：成功 $ok / 失败 $fail（总进度 100%）"
                } else {
                    "总进度 $totalPct% · 第 ${done + 1}/$total 个 · 包内 $inner% · 成功 $ok · 失败 $fail · 正在: $cur"
                }
            } else {
                tvProgress.text = getString(R.string.tv_pending)
            }
            handler.postDelayed(this, 500)
        }
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != ExtractService.BROADCAST_PROGRESS) return
            val line = intent.getStringExtra(ExtractService.EXTRA_LOG_LINE) ?: ""
            val finished = intent.getBooleanExtra(ExtractService.EXTRA_FINISHED, false)
            val logPath = intent.getStringExtra(ExtractService.EXTRA_LOG_PATH) ?: ""
            if (line.isNotEmpty()) {
                logBuffer.add(line)
                while (logBuffer.size > maxLogLines) logBuffer.removeAt(0)
                tvLog.text = logBuffer.joinToString("\n")
                svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
            }
            if (finished && logPath.isNotEmpty()) {
                logBuffer.add("📄 日志已导出：$logPath")
                while (logBuffer.size > maxLogLines) logBuffer.removeAt(0)
                tvLog.text = logBuffer.joinToString("\n")
            }
        }
    }

    private var langAtCreate = "zh"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        langAtCreate = getSharedPreferences("settings", 0).getString("lang", "zh") ?: "zh"
        applyAppLocale(this)
        setContentView(R.layout.activity_main)
        // 应用用户选择的主题（背景/字体色）；作用于页面根，避免被根布局写死的白背景覆盖
        applyTheme(this, findViewById<View>(R.id.mainRoot))
        // 标题栏显示真实版本（读取包内 versionName）
        var vn = ""
        try { vn = packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) {}
        setTitle(getString(R.string.app_name) + " v$vn")

        tvPath = findViewById(R.id.tvPath)
        tvProgress = findViewById(R.id.tvProgress)
        tvLog = findViewById(R.id.tvLog)
        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)
        svLog = findViewById(R.id.svLog)
        progressBar = findViewById(R.id.progressBar)
        tvTitle.text = getString(R.string.app_name)

        // ---- 压缩视图控件绑定 + 主界面右上角切换 ----
        extractPanel = findViewById(R.id.extractPanel)
        compressPanel = findViewById(R.id.compressPanel)
        btnToggle = findViewById(R.id.btnToggle)
        btnToggle.setOnClickListener { togglePanel() }
        cTvSource = findViewById(R.id.c_tvSource)
        cEtLayers = findViewById(R.id.c_etLayers)
        cSpFormat = findViewById(R.id.c_spFormat)
        cTvPwdCount = findViewById(R.id.c_tvPwdCount)
        cEtPwd = findViewById(R.id.c_etPwd)
        cCbDeleteSrc = findViewById(R.id.c_cbDeleteSrc)
        cProgressBar = findViewById(R.id.c_progressBar)
        cTvCProgress = findViewById(R.id.c_tvCProgress)
        cTvCLog = findViewById(R.id.c_tvCLog)
        cSvCLog = findViewById(R.id.c_svCLog)
        cSpFormat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            arrayOf("zip", "7z", "tar", "gz", "lz4", "bz2")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        findViewById<Button>(R.id.c_btnPickSource).setOnClickListener { showPickSourceDialog() }
        findViewById<Button>(R.id.c_btnManagePwd).setOnClickListener {
            startActivity(Intent(this, PasswordActivity::class.java))
        }
        findViewById<Button>(R.id.c_btnCompress).setOnClickListener { startCompressC() }
        refreshPwdCountC()

        // 支持 intent extras 自动化测试（压缩）：--es croot <源> --ei layers N --ei encrypt 0/1/2 --ez random --es pwd --ez del
        val cRoot = intent.getStringExtra("croot")
        if (!cRoot.isNullOrEmpty()) {
            if (!showingCompress) togglePanel()
            cSourcePath = cRoot
            cTvSource.text = cRoot
            cEtLayers.setText(intent.getIntExtra("layers", 3).toString())
            when (intent.getIntExtra("encrypt", 1)) {
                0 -> findViewById<android.widget.RadioButton>(R.id.c_rbNoEnc).isChecked = true
                2 -> findViewById<android.widget.RadioButton>(R.id.c_rbAll).isChecked = true
                else -> findViewById<android.widget.RadioButton>(R.id.c_rbPartial).isChecked = true
            }
            val rnd = intent.getBooleanExtra("random", true)
            findViewById<android.widget.RadioButton>(if (rnd) R.id.c_rbRandom else R.id.c_rbManual).isChecked = true
            val tp = intent.getStringExtra("pwd")
            if (!tp.isNullOrBlank()) cEtPwd.setText(tp)
            cCbDeleteSrc.isChecked = intent.getBooleanExtra("del", false)
            startCompressC()
        }

        // 工作调度：档位 + 成功即删原包（持久化）
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        cbDelSource = findViewById(R.id.cbDelSource)
        cbDelSource.isChecked = prefs.getBoolean("del_source", false)
        cbDelSource.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("del_source", checked).apply()
            refreshStatus()
        }
        tierButtons["low"] = findViewById(R.id.btnTierLow)
        tierButtons["medium"] = findViewById(R.id.btnTierMed)
        tierButtons["high"] = findViewById(R.id.btnTierHigh)
        tierButtons["auto"] = findViewById(R.id.btnTierAuto)
        tierButtons.forEach { (k, v) ->
            v.setOnClickListener {
                prefs.edit().putString("tier", k).apply()
                highlightTier(k)
                refreshStatus()
            }
        }
        highlightTier(prefs.getString("tier", "auto") ?: "auto")
        refreshStatus()

        findViewById<Button>(R.id.btnPickDir).setOnClickListener { showPickTargetDialog() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startExtract() }
        findViewById<Button>(R.id.btnPassword).setOnClickListener {
            startActivity(Intent(this, PasswordActivity::class.java))
        }
        findViewById<Button>(R.id.btnCompressGo).setOnClickListener {
            if (!showingCompress) togglePanel()
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 日志手动导出 / 删除（4 个按钮）
        findViewById<Button>(R.id.btnExportDetail).setOnClickListener { exportLog("last_log_detail.txt", "解压日志_详细") }
        findViewById<Button>(R.id.btnExportSimple).setOnClickListener { exportLog("last_log_simple.txt", "解压日志_简略") }
        findViewById<Button>(R.id.btnDelDetail).setOnClickListener { deleteLogs("解压日志_详细") }
        findViewById<Button>(R.id.btnDelSimple).setOnClickListener { deleteLogs("解压日志_简略") }

        // 支持从 intent 携带 root/pwd 路径自动开始（测试/深链用；服务本身不导出，安全）
        val testRoot = intent.getStringExtra("root")
        if (!testRoot.isNullOrEmpty()) {
            rootPath = testRoot
            tvPath.text = rootPath
            testDelOverride = if (intent.hasExtra("del")) intent.getBooleanExtra("del", false) else null
            startExtract(intent.getStringExtra("pwd"))
        }

        ensureNotificationPermission()
        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(progressReceiver, IntentFilter(ExtractService.BROADCAST_PROGRESS), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(progressReceiver, IntentFilter(ExtractService.BROADCAST_PROGRESS))
        }
        handler.postDelayed(progressTick, 500)
    }

    override fun onResume() {
        super.onResume()
        // 语言在设置页切换后回到本页时若已变则重建，保证全局一致
        if ((getSharedPreferences("settings", 0).getString("lang", "zh") ?: "zh") != langAtCreate) recreate()
        // 每次回到主界面重刷主题（设置页切换主题后本页及时跟随），并刷新档位高亮
        applyTheme(this, findViewById<View>(R.id.mainRoot))
        highlightTier(prefs.getString("tier", "auto") ?: "auto")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(progressTick)
        unregisterReceiver(progressReceiver)
    }

    private fun appendLog(msg: String) {
        logBuffer.add(msg)
        while (logBuffer.size > maxLogLines) logBuffer.removeAt(0)
        tvLog.text = logBuffer.joinToString("\n")
        svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
    }

    /** 从 App 私有目录导出日志 txt 到解压根目录 */
    private fun exportLog(privateName: String, prefix: String) {
        val root = rootPath
        if (root.isNullOrEmpty()) { appendLog("请先选择解压目录再导出"); return }
        val src = File(filesDir, privateName)
        if (!src.exists()) { appendLog("暂无已生成日志，请先解压一次"); return }
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(root, "${prefix}_$ts.txt")
            out.writeText(src.readText(), Charsets.UTF_8)
            appendLog("✅ ${if (prefix.contains("详细")) "详细" else "简略"}日志已导出：$out")
        } catch (e: Exception) {
            appendLog("✘ 导出失败：${e.message}")
        }
    }

    /** 删除解压根目录下已导出的日志 txt */
    private fun deleteLogs(prefix: String) {
        val root = rootPath
        if (root.isNullOrEmpty()) { appendLog("请先选择解压目录再删除"); return }
        val removed = File(root).listFiles()?.filter { it.name.startsWith(prefix) && it.extension == "txt" }
            ?.onEach { it.delete() } ?: emptyList()
        appendLog(if (removed.isEmpty()) "目录下没有 ${prefix} 日志" else "已删除 ${removed.size} 个${if (prefix.contains("详细")) "详细" else "简略"}日志")
    }

    private fun ensureNotificationPermission() {
        // 安卓 13+ 通知权限（不强制，仅请求）
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    /** 检查并引导全盘访问权限；返回是否有权限 */
    private fun ensureStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            val target = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:$packageName"))
            try {
                startActivity(target)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            return false
        }
        return true
    }

    /** 高亮当前选中的档位按钮（随主题配色） */
    private fun highlightTier(tier: String) {
        val dark = (prefs.getString("theme", "purple") ?: "purple") == "night"
        val selected = android.graphics.Color.parseColor(if (dark) "#BB86FC" else "#FF6200EE")
        val normal = android.graphics.Color.parseColor(if (dark) "#2A2A2A" else "#F5F5F5")
        val normalText = if (dark) android.graphics.Color.parseColor("#DDDDDD") else android.graphics.Color.DKGRAY
        val selTint = android.content.res.ColorStateList.valueOf(selected)
        val norTint = android.content.res.ColorStateList.valueOf(normal)
        tierButtons.forEach { (k, v) ->
            v.backgroundTintList = if (k == tier) selTint else norTint
            v.setTextColor(if (k == tier) android.graphics.Color.WHITE else normalText)
        }
    }

    /** 更新调度状态摘要 */
    private fun refreshStatus() {
        val tierName = when (prefs.getString("tier", "auto")) {
            "low" -> getString(R.string.tier_low)
            "medium" -> getString(R.string.tier_mid)
            "high" -> getString(R.string.tier_high)
            "custom" -> getString(R.string.tier_custom) + "×" + prefs.getInt("tier_custom", 2)
            else -> getString(R.string.tier_auto)
        }
        tvStatus.text = String.format(
            getString(R.string.tier_summary),
            tierName,
            getString(if (cbDelSource.isChecked) R.string.status_on else R.string.status_off))
    }

    private fun startExtract(pwd: String? = null) {
        val path = rootPath
        if (path.isNullOrEmpty()) {
            tvLog.text = "请先选择解压目录"
            return
        }
        if (!ensureStoragePermission()) {
            tvLog.text = "需要先授权“所有文件访问权限”，授权后请重新点开始解压"
            return
        }
        android.util.Log.d("XTR", "startExtract path=$path testDel=$testDelOverride")
        val intent = Intent(this, ExtractService::class.java).apply {
            action = ExtractService.ACTION_START
            putExtra(ExtractService.EXTRA_ROOT, path)
            if (!pwd.isNullOrBlank()) putExtra("pwd", pwd)
            if (testDelOverride != null) putExtra("del", testDelOverride!!)
            putExtra("scan_subdir", prefs.getBoolean("scan_subdir", false))
            putExtra("max_depth", prefs.getInt("max_depth", 10))
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    /** SAF 树 Uri 转真实路径（支持内部存储） */
    private fun uriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            val sub = if (split.size > 1) split[1] else split[0]
            val base = if (sub.startsWith("primary")) {
                "/storage/emulated/0/" + sub.removePrefix("primary:")
            } else {
                "/storage/emulated/0/$sub"
            }
            base
        } catch (e: Exception) {
            null
        }
    }

    /** SAF 单文档 Uri 转真实路径（如 primary:Download/a.zip → /storage/emulated/0/Download/a.zip） */
    private fun uriToPathFile(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":")
            val sub = if (split.size > 1) split[1] else split[0]
            if (sub.startsWith("primary")) {
                "/storage/emulated/0/" + sub.removePrefix("primary:")
            } else {
                "/storage/emulated/0/$sub"
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 解压目标：弹窗选择"单个压缩包文件"或"整个目录" */
    private fun showPickTargetDialog() {
        val items = arrayOf(getString(R.string.pick_target_file), getString(R.string.pick_target_dir))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.pick_target_title))
            .setItems(items) { _, which ->
                if (which == 0) pickFile.launch(arrayOf("*/*")) else pickDir.launch(null)
            }
            .show()
    }

    /** 压缩源：弹窗选择"单个文件"或"文件夹" */
    private fun showPickSourceDialog() {
        val items = arrayOf(getString(R.string.pick_target_file_c), getString(R.string.pick_target_dir_c))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.pick_target_title))
            .setItems(items) { _, which ->
                if (which == 0) pickFileC.launch(arrayOf("*/*")) else pickDirC.launch(null)
            }
            .show()
    }

    // ---- 压缩视图（主界面右上角切换） ----
    private fun togglePanel() {
        showingCompress = !showingCompress
        extractPanel.visibility = if (showingCompress) View.GONE else View.VISIBLE
        compressPanel.visibility = if (showingCompress) View.VISIBLE else View.GONE
        btnToggle.text = if (showingCompress) "解压功能" else "压缩功能"
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (showingCompress) "#FF6200EE" else "#FF00897B")
        )
        if (showingCompress) refreshPwdCountC()
    }

    private fun refreshPwdCountC() {
        val n = PasswordDatabase(this).all().size
        cTvPwdCount.text = getString(R.string.pwdbook_count, n)
    }

    private fun appendLogC(msg: String) {
        val cur = cTvCLog.text.toString()
        cTvCLog.text = if (cur.isEmpty()) msg else cur + "\n" + msg
        cSvCLog.post { cSvCLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startCompressC() {
        if (compressing) return
        val path = cSourcePath ?: run { appendLogC("请先选择要压缩的文件或文件夹"); return }
        val src = File(path)
        android.util.Log.d("XTR", "startCompressC path=$path exists=${src.exists()} storagePerm=${Environment.isExternalStorageManager()}")
        if (!src.exists()) { appendLogC("源不存在：$path"); return }
        if (!ensureStoragePermission()) { appendLogC("需要先授权“所有文件访问权限”"); return }
        val n = cEtLayers.text.toString().toIntOrNull()?.coerceIn(1, 20)
            ?: run { appendLogC("层数请输入 1-20 之间的数字"); return }
        val layers = buildLayersC(n)
        if (layers.isEmpty()) { appendLogC("未生成压缩层配置"); return }

        val dbPwds = PasswordDatabase(this).all(PasswordDatabase.TYPE_ENCRYPT).map { it.text }
        val extraPwd = cEtPwd.text.toString().trim()
        val pwdList = if (extraPwd.isNotEmpty()) listOf(extraPwd) + dbPwds else dbPwds
        val destDir = src.parentFile ?: run { appendLogC("无法定位输出目录"); return }
        val del = cCbDeleteSrc.isChecked

        compressing = true
        findViewById<Button>(R.id.c_btnCompress).isEnabled = false
        cTvCLog.text = ""
        val desc = layers.joinToString(" → ") { (if (it.encrypted) "P" else "Q") + it.format.name.lowercase() }
        appendLogC("开始制作 ${n} 层堆叠包（内→外）：$desc")
        appendLogC("密码本 ${pwdList.size} 个密码可用")
        android.util.Log.d("XTR", "compress root=$path layers=$n enc=$desc pwdCount=${pwdList.size}")

        thread {
            val r = CompressEngine.createNested(src, destDir, layers, pwdList, del) { pct ->
                runOnUiThread { cProgressBar.progress = pct; cTvCProgress.text = "压缩总进度 $pct%" }
            }
            runOnUiThread {
                compressing = false
                findViewById<Button>(R.id.c_btnCompress).isEnabled = true
                if (r.ok) {
                    cProgressBar.progress = 100
                    cTvCProgress.text = "完成 100%"
                    appendLogC("✅ 压缩完成，最外层堆叠包：${r.outFile?.absolutePath}")
                    appendLogC("   包大小：${r.outFile?.length() ?: 0} 字节")
                    if (del) appendLogC("   已删除源：$path")
                    android.util.Log.d("XTR", "compress OK out=${r.outFile?.absolutePath}")
                } else {
                    appendLogC("✘ 压缩失败：${r.reason}")
                    android.util.Log.d("XTR", "compress FAIL: ${r.reason}")
                }
            }
        }
    }

    private fun buildLayersC(n: Int): List<CompressEngine.LayerSpec> {
        val encId = findViewById<android.widget.RadioGroup>(R.id.c_rgEncrypt).checkedRadioButtonId
        val fmtId = findViewById<android.widget.RadioGroup>(R.id.c_rgFormat).checkedRadioButtonId
        val manual = fmtId == R.id.c_rbManual
        val manualFmt = when (cSpFormat.selectedItemPosition) {
            0 -> CompressEngine.CFormat.ZIP
            1 -> CompressEngine.CFormat.SEVENZ
            2 -> CompressEngine.CFormat.TAR
            3 -> CompressEngine.CFormat.GZ
            4 -> CompressEngine.CFormat.LZ4
            else -> CompressEngine.CFormat.BZIP2
        }
        val allFormats = CompressEngine.CFormat.values().toList()
        val rnd = Random(System.nanoTime())
        return (1..n).map { _ ->
            val encrypted = when (encId) {
                R.id.c_rbAll -> true
                R.id.c_rbPartial -> rnd.nextBoolean()
                else -> false
            }
            val fmt = if (encrypted) CompressEngine.CFormat.ZIP
            else if (manual) manualFmt
            else allFormats[rnd.nextInt(allFormats.size)]
            CompressEngine.LayerSpec(fmt, encrypted)
        }
    }

    companion object {
        /** 应用界面语言：zh/en（读 settings/lang），供各 Activity 在 setContentView 前调用 */
        fun applyAppLocale(activity: Activity) {
            val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val lang = prefs.getString("lang", "zh") ?: "zh"
            val locale = if (lang == "en") Locale("en") else Locale("zh")
            Locale.setDefault(locale)
            val config = Configuration(activity.resources.configuration)
            config.setLocale(locale)
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        }

        /**
         * 应用用户主题（背景色/背景图/字体色/强调色），供 Main/Settings/Password 三页共用。
         * 主题：purple 默认紫、night 夜间黑、eye 护眼绿、kraft 牛皮纸、custom 自定义(背景图+字体色)。
         */
        fun applyTheme(activity: Activity, root: View) {
            val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val themeKey = prefs.getString("theme", "purple") ?: "purple"
            val bgPath = prefs.getString("theme_bg", "") ?: ""
            val fontHex = prefs.getString("theme_font", "") ?: ""
            // 12 背景色主题 + custom：bg 背景 / fg 主文字 / sub 次要 / accent 强调(CheckBox/Radio/Switch tint) / toolbar 顶栏(同色系过渡) / card 卡片面板
            val themeMap = linkedMapOf<String, IntArray>(
                "purple" to intArrayOf(0xFFECE5F8.toInt(), 0xFF241B32.toInt(), 0xFF7A6AA0.toInt(), 0xFF7B1FA2.toInt(), 0xFFD9CBF0.toInt(), 0xFFFFFFFF.toInt()),
                "night" to intArrayOf(0xFF121212.toInt(), 0xFFCFCFCF.toInt(), 0xFF9E9E9E.toInt(), 0xFFBB86FC.toInt(), 0xFF3A3A3A.toInt(), 0xFF1E1E1E.toInt()),
                "eye" to intArrayOf(0xFFC6E9CC.toInt(), 0xFF17391C.toInt(), 0xFF3E6B3E.toInt(), 0xFF2E7D32.toInt(), 0xFFA7D8B2.toInt(), 0xFFE6F6EA.toInt()),
                "kraft" to intArrayOf(0xFFD9C8A9.toInt(), 0xFF3E2E14.toInt(), 0xFF7A6A45.toInt(), 0xFF8D6E63.toInt(), 0xFFC7B089.toInt(), 0xFFF1E6D2.toInt()),
                "blue" to intArrayOf(0xFFDCE8F9.toInt(), 0xFF12243F.toInt(), 0xFF3E6B9E.toInt(), 0xFF1565C0.toInt(), 0xFFB9D1EF.toInt(), 0xFFEAF3FC.toInt()),
                "teal" to intArrayOf(0xFFD6EFE8.toInt(), 0xFF12332B.toInt(), 0xFF3E7A6B.toInt(), 0xFF00796B.toInt(), 0xFFB2E0D3.toInt(), 0xFFE3F2EC.toInt()),
                "pink" to intArrayOf(0xFFFBE0EA.toInt(), 0xFF3A2030.toInt(), 0xFFA75A7A.toInt(), 0xFFC2185B.toInt(), 0xFFF2C2D6.toInt(), 0xFFFFF0F6.toInt()),
                "orange" to intArrayOf(0xFFFCEAD4.toInt(), 0xFF3A2A12.toInt(), 0xFFA47030.toInt(), 0xFFE65100.toInt(), 0xFFF2D6A8.toInt(), 0xFFFBEEDC.toInt()),
                "red" to intArrayOf(0xFFF7DAD8.toInt(), 0xFF381E1C.toInt(), 0xFFA0554F.toInt(), 0xFFC62828.toInt(), 0xFFECB4B0.toInt(), 0xFFFBEAE8.toInt()),
                "gray" to intArrayOf(0xFFE4E6EA.toInt(), 0xFF23272C.toInt(), 0xFF6A7280.toInt(), 0xFF455A64.toInt(), 0xFFC9CDD4.toInt(), 0xFFEDEFF2.toInt()),
                "mint" to intArrayOf(0xFFE0F3EC.toInt(), 0xFF143529.toInt(), 0xFF3E806A.toInt(), 0xFF00897B.toInt(), 0xFFB9E3D4.toInt(), 0xFFE6F5EF.toInt()),
                "cream" to intArrayOf(0xFFFBF3E0.toInt(), 0xFF3A301C.toInt(), 0xFF94876A.toInt(), 0xFFA1887F.toInt(), 0xFFF1E2BC.toInt(), 0xFFF6F0E0.toInt())
            )
            var bg = 0xFFFFFFFF.toInt()
            var fg = 0xFF1A1A1A.toInt()
            var sub = 0xFF888888.toInt()
            var accent = 0xFF6200EE.toInt()
            var toolbar = 0xFFD9CBF0.toInt()
            var card = 0xFFFFFFFF.toInt()
            if (themeKey == "custom") {
                if (fontHex.isNotEmpty()) {
                    try { fg = android.graphics.Color.parseColor(fontHex); sub = fg } catch (_: Exception) {}
                }
                toolbar = 0xFFECE5F8.toInt()
            } else {
                val t = themeMap[themeKey]
                if (t != null) { bg = t[0]; fg = t[1]; sub = t[2]; accent = t[3]; toolbar = t[4]; card = t[5] }
            }
            // 自定义背景图：用户图直接作背景，不叠加水印
            if (themeKey == "custom" && bgPath.isNotEmpty()) {
                val f = File(bgPath)
                if (f.exists()) {
                    val bmp = android.graphics.BitmapFactory.decodeFile(bgPath)
                    if (bmp != null) {
                        root.setBackground(android.graphics.drawable.BitmapDrawable(activity.resources, bmp))
                        recolorTheme(root, fg, sub, accent, card, themeKey == "night")
                        styleSystemBars(activity, toolbar, true)
                        styleToolbar(activity, toolbar, fg)
                        return
                    }
                }
            }
            // 背景 = 主题色 + 斜向浅灰"do压缩包"水印（LayerDrawable 叠加）
            val dark = themeKey == "night"
            val wmColor = if (dark) 0x33FFFFFF else 0x40A0A0A0
            val ld = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(bg),
                android.graphics.drawable.BitmapDrawable(activity.resources, makeWatermark(activity, wmColor))
            ))
            root.setBackground(ld)
            recolorTheme(root, fg, sub, accent, card, themeKey == "night")
            styleSystemBars(activity, toolbar, !dark)
            styleToolbar(activity, toolbar, fg)
        }

        /** 状态栏/导航栏跟随主题（同色系过渡） */
        private fun styleSystemBars(activity: Activity, color: Int, lightIcons: Boolean) {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                activity.window.statusBarColor = color
                activity.window.navigationBarColor = color
            }
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                activity.window.decorView.systemUiVisibility = if (lightIcons)
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
            }
        }

        /** 顶栏磨砂：同色系半透明过渡色 + 标题字跟随主题 */
        private fun styleToolbar(activity: Activity, toolbarColor: Int, fg: Int) {
            val tb = activity.findViewById<View>(R.id.toolbar) ?: return
            tb.setBackgroundColor((toolbarColor and 0x00FFFFFF) or (0xE6 shl 24))
            if (tb is android.view.ViewGroup) {
                for (i in 0 until tb.childCount) {
                    val c = tb.getChildAt(i)
                    if (c is TextView) c.setTextColor(fg)
                }
            }
        }

        /** 生成斜向水印位图：do压缩包 多个浅灰字样 */
        private fun makeWatermark(activity: Activity, color: Int): android.graphics.Bitmap {
            val metrics = activity.resources.displayMetrics
            val w = metrics.widthPixels; val h = metrics.heightPixels
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = 26f * metrics.density
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            c.save(); c.rotate(-22f, w / 2f, h / 2f)
            val text = "do压缩包"
            for (row in 0 until 4) {
                for (col in 0 until 3) {
                    c.drawText(text, 90f + col * 380f * metrics.density, 220f + row * 500f * metrics.density, p)
                }
            }
            c.restore()
            return bmp
        }

        private val btnOriginalTint = HashMap<View, android.content.res.ColorStateList?>()
        private fun recolorTheme(v: View, fg: Int, sub: Int, accent: Int, card: Int, dark: Boolean) {
            when (v) {
                is android.widget.Button -> {
                    if (!btnOriginalTint.containsKey(v)) btnOriginalTint[v] = v.backgroundTintList
                    if (dark) {
                        v.setTextColor(0xFFCFCFCF.toInt())
                        v.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A2A.toInt())
                    } else {
                        val whiteIds = intArrayOf(R.id.btnStart, R.id.btnCompressGo, R.id.c_btnCompress, R.id.btnIconSave)
                        v.setTextColor(if (whiteIds.contains(v.id)) 0xFFFFFFFF.toInt() else fg)
                        v.backgroundTintList = btnOriginalTint[v]
                    }
                }
                is TextView -> v.setTextColor(fg)
                is android.widget.EditText -> { v.setTextColor(fg); v.setHintTextColor(sub) }
                is CheckBox -> { v.setTextColor(fg); v.buttonTintList = android.content.res.ColorStateList.valueOf(accent) }
                is android.widget.RadioButton -> { v.setTextColor(fg); v.buttonTintList = android.content.res.ColorStateList.valueOf(accent) }
                is android.widget.Switch -> { v.setTextColor(fg); v.thumbTintList = android.content.res.ColorStateList.valueOf(accent) }
                is ProgressBar -> v.progressTintList = android.content.res.ColorStateList.valueOf(accent)
                is android.view.ViewGroup -> {
                    val bg = v.background
                    when (bg) {
                        is android.graphics.drawable.ColorDrawable -> v.setBackgroundColor(card)
                        is android.graphics.drawable.GradientDrawable -> (bg as android.graphics.drawable.GradientDrawable).setColor(card)
                    }
                }
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) recolorTheme(v.getChildAt(i), fg, sub, accent, card, dark)
            }
        }
    }
}
