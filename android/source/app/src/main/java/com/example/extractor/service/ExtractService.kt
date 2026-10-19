package com.example.extractor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.extractor.MainActivity
import com.example.extractor.R
import com.example.extractor.data.PasswordDatabase
import com.example.extractor.engine.ArchiveEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 单个压缩包的解压日志记录 */
class PkgLog(
    val name: String,          // 包名
    val size: Long,            // 原包总大小（分卷=整组）
    val startMs: Long,         // 开始时间戳
    val endMs: Long,           // 结束时间戳
    val attempts: Int,         // 解压尝试次数（含自动重试）
    val ok: Boolean,           // 是否成功
    val reason: String,        // 失败原因（成功为空）
    val outSize: Long,         // 解压产物大小
    val deleted: Boolean,      // 是否删除原包
    val nestChain: List<String> = emptyList()  // 多层嵌套：逐层格式名（非嵌套=空）
)

/**
 * 后台批量解压前台服务。
 * 扫描选中目录的压缩包，按密码池逐条尝试解压，进度通过广播通知 UI。
 */
class ExtractService : Service() {

    companion object {
        const val CHANNEL_ID = "extract_channel"
        const val NOTIF_ID = 1
        const val NOTIF_FINISH_ID = 2
        const val ACTION_START = "com.example.extractor.START"
        const val ACTION_STOP = "com.example.extractor.STOP"
        const val EXTRA_ROOT = "root"
        const val BROADCAST_PROGRESS = "com.example.extractor.PROGRESS"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_DONE = "done"
        const val EXTRA_OK = "ok"
        const val EXTRA_FAIL = "fail"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_LOG_LINE = "log_line"
        const val EXTRA_FINISHED = "finished"
        const val EXTRA_LOG_PATH = "log_path"

        // 实时进度共享状态（UI 定时轮询读取，避免高频广播）
        @Volatile var shareTotal = 0
        @Volatile var shareDone = 0
        @Volatile var shareOk = 0
        @Volatile var shareFail = 0
        @Volatile var shareInner = 0
        @Volatile var shareCur = ""
        @Volatile var shareFinished = false
    }

    private var notifManager: NotificationManager? = null
    // 本次运行设置（来自 MainActivity intent + 持久化 prefs）
    private var scanSubdir = false
    private var maxDepth = 10
    private var notifDetail = true
    private var finishNotif = true
    private var autoExport = false
    private var lastIntentDel: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // 读取本次运行设置：优先 intent 传入，否则取持久化设置
        scanSubdir = intent?.getBooleanExtra("scan_subdir", false)
            ?: prefs.getBoolean("scan_subdir", false)
        maxDepth = intent?.getIntExtra("max_depth", 0)?.takeIf { it > 0 }
            ?: prefs.getInt("max_depth", 10)
        notifDetail = prefs.getBoolean("notif_detail", true)
        finishNotif = prefs.getBoolean("finish_notif", true)
        autoExport = prefs.getBoolean("auto_export", false)
        startForeground(NOTIF_ID, buildNotification("准备解压…", 0, 0, ""))
        val root = intent?.getStringExtra(EXTRA_ROOT)
        if (root == null) {
            broadcastFinished("未指定解压目录")
            stopSelf()
            return START_NOT_STICKY
        }
        // 测试/诊断：可从 intent 直接传"是否删除原包"（--es del true/false），优先于设置开关
        lastIntentDel = if (intent?.hasExtra("del") == true) intent.getBooleanExtra("del", false) else null
        // 后台线程执行，避免阻塞主线程
        Thread {
            val pwdDb = PasswordDatabase(this)
            val passwords = mutableListOf<String>()
            passwords.addAll(pwdDb.all(PasswordDatabase.TYPE_DECRYPT).map { it.text })
            // 测试/诊断：可从 intent 直接传密码（--es pwd xxx / --es pwds "a|b|c" 多密码）
            val extraPwd = intent?.getStringExtra("pwd")
            if (!extraPwd.isNullOrBlank()) passwords.add(extraPwd)
            val extraPwds = intent?.getStringExtra("pwds")
            if (!extraPwds.isNullOrBlank()) passwords.addAll(extraPwds.split('|').filter { it.isNotBlank() })
            runExtract(File(root), passwords)
        }.start()
        return START_NOT_STICKY
    }

    private fun runExtract(root: File, passwords: List<String>) {
        val archives = scanArchives(root, scanSubdir)
        val total = archives.size
        // 重置并发布共享进度状态（供 UI 轮询 + 通知）
        ExtractService.shareTotal = total
        ExtractService.shareDone = 0
        ExtractService.shareOk = 0
        ExtractService.shareFail = 0
        ExtractService.shareInner = 0
        ExtractService.shareCur = ""
        ExtractService.shareFinished = false

        // 工作调度：读取档位与"成功即删原包"设置
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val tier = prefs.getString("tier", "auto") ?: "auto"
        val delSource = lastIntentDel ?: prefs.getBoolean("del_source", false)
        val concurrency = resolveConcurrency(tier)
        android.util.Log.d("XTR", "runExtract total=$total tier=$tier del=$delSource concurrency=$concurrency lastIntentDel=$lastIntentDel")

        val done = AtomicInteger(0)
        val ok = AtomicInteger(0)
        val fail = AtomicInteger(0)
        val startAll = System.currentTimeMillis()

        broadcast(total, 0, 0, 0, "扫描完成，共 $total 个压缩包 · 档位=$tier · 并发=$concurrency", "", false)

        // 每包独立解压目录，可安全并行；线程用大栈，避免 unrar/libarchive 深度递归栈溢出
        val executor = Executors.newFixedThreadPool(concurrency) { r ->
            Thread(null, r, "extract-thread", 16L * 1024 * 1024)
        }
        val logs = arrayOfNulls<PkgLog>(total)
        try {
            val futures = archives.mapIndexed { idx, a ->
                executor.submit {
                    logs[idx] = processOne(a, passwords, done, ok, fail, total, delSource)
                }
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
        }
        val endAll = System.currentTimeMillis()

        // 保存日志数据到 App 私有目录（不自动导出；由 UI 按钮手动导出/删除，或按设置自动导出）
        saveLogs(root, logs, tier, concurrency, delSource, ok.get(), fail.get(), startAll, endAll)
        if (autoExport) exportLogsTo(root)
        ExtractService.shareFinished = true
        ExtractService.shareDone = total
        ExtractService.shareInner = 100
        if (finishNotif) {
            val finish = buildNotification("全部完成：成功 ${ok.get()} / 失败 ${fail.get()}", 100, 100, "", false)
            notifManager?.notify(NOTIF_FINISH_ID, finish)
        } else {
            notifManager?.cancel(NOTIF_ID)
        }
        broadcast(total, done.get(), ok.get(), fail.get(),
                  "全部完成：成功 ${ok.get()} / 失败 ${fail.get()}\n日志已生成，可点下方按钮导出",
                  "", true, "")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 按设置自动导出详细+简略日志到解压根目录 */
    private fun exportLogsTo(root: File) {
        try {
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val d = File(filesDir, "last_log_detail.txt")
            val s = File(filesDir, "last_log_simple.txt")
            if (d.exists()) File(root, "解压日志_详细_$ts.txt").writeText(d.readText(), Charsets.UTF_8)
            if (s.exists()) File(root, "解压日志_简略_$ts.txt").writeText(s.readText(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    /** 解压单个压缩包并广播结果；成功后按设置决定是否删除原包；返回该包日志 */
    private fun processOne(a: File, passwords: List<String>,
                           done: AtomicInteger, ok: AtomicInteger, fail: AtomicInteger,
                           total: Int, delSource: Boolean): PkgLog {
        val current = a.name
        val dest = destDirFor(a)
        dest.mkdirs()

        // rar 先试密码（避免无密码加密包在 unrar 里卡住），最后才无密码；
        // 其他格式保持先无密码再试密码。
        val fmt = ArchiveEngine.detectFormat(a)
        val startMs = System.currentTimeMillis()
        val origSize = archiveFullSize(a) // 解压前记录原包总大小（分卷整组）

        // 包内进度回调：写入共享状态供 UI 轮询；通知按 400ms 节流，避免高频刷通知
        val innerRef = AtomicInteger(0)
        var lastNotif = 0L
        val onProg: (Int) -> Unit = { pct ->
            if (pct != innerRef.get()) {
                innerRef.set(pct)
                ExtractService.shareInner = pct
                ExtractService.shareCur = current
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastNotif > 400) {
                    lastNotif = nowMs
                    val tp = if (total > 0) (done.get() * 100 / total).coerceIn(0, 100) else 0
                    notifManager?.notify(NOTIF_ID, buildNotification(
                        "第 ${done.get() + 1}/$total 个 · 包内 $pct% · 成功${ok.get()}/失败${fail.get()}",
                        tp, 100, current))
                }
            }
        }
        var result = tryExtract(a, dest, passwords, fmt, onProg)

        // 偶发失败自动重试 2 次（应对 arm64/系统瞬时资源问题）
        var attempt = 1
        while (!result.ok && attempt < 3) {
            attempt++
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            result = tryExtract(a, dest, passwords, fmt, onProg)
        }
        val endMs = System.currentTimeMillis()

        val line = if (result.ok) "✔ 成功: $current"
        else "✘ 失败: $current — ${result.reason}"
        var deleted = false
        val chain = mutableListOf<String>()
        if (result.ok) {
            ok.incrementAndGet()
            // 递归解内层嵌套压缩包（多格式多层，最多 maxDepth 层），收集逐层格式链
            nestedExtract(dest, passwords, delSource, 0, chain)
            // 扁平化：提升最内层内容到包名目录根，删除全部中间层文件夹壳（不删内容）
            flattenDest(dest)
            if (delSource) { deleteArchive(a); deleted = true }
        } else fail.incrementAndGet()
        val d = done.incrementAndGet()
        ExtractService.shareOk = ok.get()
        ExtractService.shareFail = fail.get()
        ExtractService.shareDone = d
        ExtractService.shareInner = 100
        val totalPct = if (total > 0) (d * 100 / total).coerceIn(0, 100) else 100
        val outSize = if (result.ok) dirSize(dest) else 0L
        updateNotification("第 $d/$total 个完成 · 成功${ok.get()}/失败${fail.get()}", totalPct, 100, current)
        broadcast(total, d, ok.get(), fail.get(), line, current, false)
        return PkgLog(current, origSize, startMs, endMs, attempt, result.ok,
                      result.reason ?: "", outSize, deleted, chain.toList())
    }

    /** 递归解压嵌套层：扫描 dir 内所有压缩文件(按内容 magic 识别)，逐层解到唯一子目录，最多 maxDepth 层 */
    private fun nestedExtract(dir: File, passwords: List<String>, delSource: Boolean, depth: Int,
                              chain: MutableList<String>) {
        if (depth >= maxDepth) return
        val subs = dir.listFiles()
            ?.filter { !it.isDirectory && ArchiveEngine.detectFormat(it) != ArchiveEngine.Format.UNKNOWN }
            ?: return
        for (a in subs) {
            val fmt = ArchiveEngine.detectFormat(a)
            chain.add(layerLabel(a, fmt))
            // 用唯一子目录承载内层, 避免流格式解出的无扩展名文件与 destDirFor 目录命名冲突
            val d = File(dir, a.name + "_L" + depth)
            d.mkdirs()
            val r = tryExtract(a, d, passwords, fmt)
            if (r.ok) {
                // 中间层压缩包：内容已提取，无论删源开关都删除（只留最外层包名目录+内容）
                deleteArchive(a)
                nestedExtract(d, passwords, delSource, depth + 1, chain)
            }
        }
    }

    /**
     * 解压扁平化：把解压出的所有文件（任意深度）提升到包名目录根，
     * 删除全部中间层文件夹壳（含 src 等），同名内容去重保留先到者，不删内容。
     */
    private fun flattenDest(dest: File) {
        if (!dest.isDirectory) return
        val files = mutableListOf<File>()
        fun collect(f: File) {
            if (f.isDirectory) f.listFiles()?.forEach { collect(it) }
            else if (f.parentFile != dest) files.add(f)
        }
        dest.listFiles()?.forEach { collect(it) }
        for (f in files) {
            val target = File(dest, f.name)
            if (target.exists()) {
                f.delete()
            } else {
                try { f.renameTo(target) } catch (_: Exception) { f.delete() }
            }
        }
        dest.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
    }

    /** 嵌套层的格式名（detectFormat 将 gz/tar 都归为 TAR，这里用扩展名区分） */
    private fun layerLabel(a: File, fmt: ArchiveEngine.Format): String {
        val n = a.name.lowercase()
        return when (fmt) {
            ArchiveEngine.Format.ZIP -> "zip"
            ArchiveEngine.Format.SEVENZ -> "7z"
            ArchiveEngine.Format.RAR -> "rar"
            ArchiveEngine.Format.LZ4 -> "lz4"
            ArchiveEngine.Format.BZIP2 -> "bz2"
            ArchiveEngine.Format.TAR -> when {
                n.endsWith(".gz") -> "gz"
                n.endsWith(".tar") -> "tar"
                n.endsWith(".tar.gz") -> "tar.gz"
                else -> "tar"
            }
            else -> "?"
        }
    }

    private fun tryExtract(a: File, dest: File, passwords: List<String>,
                           fmt: ArchiveEngine.Format, onProgress: ((Int) -> Unit)? = null): ArchiveEngine.Result {
        if (fmt == ArchiveEngine.Format.RAR) {
            var r = ArchiveEngine.Result(false, "start")
            if (passwords.isNotEmpty()) {
                for (p in passwords) {
                    r = ArchiveEngine.extract(a, dest, p, onProgress)
                    if (r.ok) break
                }
            }
            if (!r.ok) r = ArchiveEngine.extract(a, dest, null, onProgress)
            return r
        }
        var r = ArchiveEngine.extract(a, dest, null, onProgress)
        if (!r.ok && passwords.isNotEmpty()) {
            for (p in passwords) {
                r = ArchiveEngine.extract(a, dest, p, onProgress)
                if (r.ok) break
            }
        }
        return r
    }

    /** 根据档位决定并发数；auto 按设备核数自适应(2-4)，避免 arm64 并发内存竞争 */
    private fun resolveConcurrency(tier: String): Int = when (tier) {
        "low" -> 1
        "medium" -> 2
        "high" -> 4
        "custom" -> getSharedPreferences("settings", MODE_PRIVATE).getInt("tier_custom", 2).coerceIn(1, 16)
        else -> minOf(Runtime.getRuntime().availableProcessors(), 4).coerceAtLeast(2) // auto 自适应
    }

    /** 删除原压缩包；分卷/数字卷删除整组，防止残留；删除失败自动重试 */
    private fun deleteArchive(a: File) {
        val toDelete = mutableListOf<File>()
        if (Regex("\\.part\\d+\\.").containsMatchIn(a.name)) {
            // 基底 = ".part" 之前部分，如 rar4_plain；分卷名为 rar4_plain.part1.rar
            val base = a.name.substringBefore(".part")
            a.parentFile?.listFiles()
                ?.filter { it.name.startsWith(base + ".part") && Regex("\\.part\\d+\\.").containsMatchIn(it.name) }
                ?.forEach { toDelete.add(it) }
        } else if (Regex("\\.\\d{3}$").containsMatchIn(a.name)) {
            val base = a.name.replace(Regex("\\.\\d{3}$"), "")
            a.parentFile?.listFiles()
                ?.filter { it.name.startsWith(base) && Regex("\\.\\d{3}$").containsMatchIn(it.name) }
                ?.forEach { toDelete.add(it) }
        } else {
            toDelete.add(a)
        }
        for (f in toDelete) deleteWithRetry(f)
    }

    /** 删除单个文件，失败重试 3 次（应对瞬时句柄占用/系统忙碌） */
    private fun deleteWithRetry(f: File) {
        var i = 0
        while (i < 3 && f.exists()) {
            if (f.delete()) return
            i++
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }
    }

    // ---------- 日志导出 ----------

    /** 生成详细 + 简略日志文本并保存到 App 私有目录，供 UI 按钮手动导出 */
    private fun saveLogs(root: File, logs: Array<PkgLog?>, tier: String, concurrency: Int,
                         delSource: Boolean, okCount: Int, failCount: Int,
                         startAll: Long, endAll: Long) {
        val valid = logs.filterNotNull()
        val detail = buildDetailLog(root, valid, tier, concurrency, delSource, okCount, failCount, startAll, endAll)
        val simple = buildSimpleLog(valid, okCount, failCount, startAll, endAll)
        try { File(filesDir, "last_log_detail.txt").writeText(detail, Charsets.UTF_8) } catch (_: Exception) {}
        try { File(filesDir, "last_log_simple.txt").writeText(simple, Charsets.UTF_8) } catch (_: Exception) {}
    }

    private fun buildDetailLog(root: File, logs: List<PkgLog>, tier: String, concurrency: Int,
                               delSource: Boolean, okCount: Int, failCount: Int,
                               startAll: Long, endAll: Long): String {
        val sb = StringBuilder()
        sb.appendLine("批量解压 · 详细日志")
        sb.appendLine("生成时间：${now()}")
        sb.appendLine("解压根目录：${root.absolutePath}")
        sb.appendLine("档位：$tier · 并发：$concurrency · 成功即删原包：${if (delSource) "开" else "关"}")
        sb.appendLine("")
        var idx = 1
        var sumSize = 0L; var sumOut = 0L; var sumAttempts = 0; var sumDel = 0L
        for (p in logs) {
            val dur = (p.endMs - p.startMs).coerceAtLeast(0)
            val delText = when {
                !p.ok -> "未删除（解压失败）"
                p.deleted -> "已删除 → 释放 ${fmtSize(p.size)}"
                else -> "未删除（开关关闭）"
            }
            val net = if (p.deleted) p.outSize - p.size else if (p.ok) p.outSize else 0L
            val netText = when {
                !p.ok -> "无（失败）"
                net >= 0 -> "占用增加 ${fmtSize(net)}（产物大于原包）"
                else -> "占用减少 ${fmtSize(-net)}（原包已删，净省空间）"
            }
            sb.appendLine("── 第 $idx 个：${p.name}")
            if (p.nestChain.isNotEmpty()) {
                sb.appendLine("    嵌套解压：${p.nestChain.size} 层（${p.nestChain.joinToString(" → ")}，逐层解到最内层内容）")
            }
            sb.appendLine("    原包总大小：${fmtBytes(p.size)} (${p.size} 字节)")
            sb.appendLine("    时间：${fmtTime(p.startMs)} → ${fmtTime(p.endMs)}（耗时 ${fmtSec(dur)}）")
            sb.appendLine("    解压尝试：${p.attempts} 次")
            sb.appendLine("    结果：${if (p.ok) "成功" else "失败 — ${p.reason}"}")
            sb.appendLine("    解压产物：${p.outSize} 字节 (${fmtSize(p.outSize)})")
            sb.appendLine("    大小对比：原 ${fmtSize(p.size)} → 产物 ${fmtSize(p.outSize)}")
            sb.appendLine("    原包处理：$delText")
            sb.appendLine("    空间净变化：$netText")
            idx++
            sumSize += p.size; sumOut += p.outSize; sumAttempts += p.attempts
            if (p.deleted) sumDel += p.size
        }
        val durAll = (endAll - startAll).coerceAtLeast(0)
        sb.appendLine("")
        sb.appendLine("════════ 总结 ════════")
        sb.appendLine("共处理：${logs.size} 个压缩包")
        sb.appendLine("成功 $okCount · 失败 $failCount · 解压尝试总次数 ${sumAttempts} 次")
        sb.appendLine("总耗时：${fmtSec(durAll)}")
        sb.appendLine("解压前原包总占用：${fmtBytes(sumSize)} (${fmtSize(sumSize)})")
        sb.appendLine("解压后产物总占用：${fmtBytes(sumOut)} (${fmtSize(sumOut)})")
        sb.appendLine("删除原包释放空间：${fmtBytes(sumDel)} (${fmtSize(sumDel)})")
        val netAll = if (delSource) sumOut - sumSize else sumOut
        sb.appendLine("占用变化（解压后 vs 解压前）：${if (netAll >= 0) "增加" else "减少"} ${fmtSize(if (netAll >= 0) netAll else -netAll)}")
        return sb.toString()
    }

    private fun buildSimpleLog(logs: List<PkgLog>, okCount: Int, failCount: Int,
                               startAll: Long, endAll: Long): String {
        val sb = StringBuilder()
        sb.appendLine("批量解压 · 简略日志")
        sb.appendLine("生成时间：${now()}")
        sb.appendLine("")
        for (p in logs) {
            val dur = (p.endMs - p.startMs).coerceAtLeast(0)
            val tag = if (p.ok) "成功" else "失败"
            val nest = if (p.nestChain.isNotEmpty()) " | 嵌套${p.nestChain.size}层(${p.nestChain.joinToString("→")})" else ""
            sb.appendLine("[$tag] ${p.name}$nest | 原大小 ${fmtSize(p.size)} | 解出内容 ${fmtSize(p.outSize)} | 用时 ${fmtSec(dur)}")
        }
        sb.appendLine("")
        val durAll = (endAll - startAll).coerceAtLeast(0)
        sb.appendLine("总计：${logs.size} 个包 · 成功 $okCount · 失败 $failCount · 总用时 ${fmtSec(durAll)}")
        return sb.toString()
    }

    /** 递归计算目录/文件大小 */
    private fun dirSize(f: File): Long {
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    /** 原包总大小：分卷时按整组累计（解压前调用） */
    private fun archiveFullSize(a: File): Long {
        var sum = a.length()
        val parent = a.parentFile ?: return sum
        if (Regex("\\.part\\d+\\.").containsMatchIn(a.name)) {
            val base = a.name.substringBefore(".part")
            parent.listFiles()
                ?.filter { it.name.startsWith(base + ".part") && it.name != a.name }
                ?.forEach { sum += it.length() }
        } else if (Regex("\\.\\d{3}$").containsMatchIn(a.name)) {
            val base = a.name.replace(Regex("\\.\\d{3}$"), "")
            parent.listFiles()
                ?.filter { it.name.startsWith(base) && Regex("\\.\\d{3}$").containsMatchIn(it.name) && it.name != a.name }
                ?.forEach { sum += it.length() }
        }
        return sum
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun fmtSec(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        val s = ms / 1000.0
        return if (s >= 60) { val m = s / 60; "${m}分${(s % 60).toInt()}秒" }
        else "${String.format(Locale.US, "%.2f", s)}秒"
    }

    private fun fmtBytes(n: Long): String = String.format(Locale.US, "%,d", n)

    private fun fmtSize(n: Long): String {
        if (n < 1024) return "${n}B"
        val kb = n / 1024.0
        if (kb < 1024) return "${String.format(Locale.US, "%.1f", kb)}KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${String.format(Locale.US, "%.2f", mb)}MB"
        return "${String.format(Locale.US, "%.2f", mb / 1024)}GB"
    }

    /** 扫描所选目标内的压缩包：目标是文件则直接解该文件；是目录则扫目录(可选递归)；分卷只保留 part1 */
    private fun scanArchives(root: File, scanSubdir: Boolean): List<File> {
        val result = mutableListOf<File>()
        if (root.isFile) {
            if (!isVolumePartNotFirst(root.name) && ArchiveEngine.detectFormat(root) != ArchiveEngine.Format.UNKNOWN) {
                result.add(root)
            }
            return result
        }
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isDirectory) { if (scanSubdir) stack.add(f); continue }
                if (isVolumePartNotFirst(f.name)) continue
                if (ArchiveEngine.detectFormat(f) == ArchiveEngine.Format.UNKNOWN) continue
                result.add(f)
            }
        }
        return result
    }

    /** 判断是否为分卷且非 part1（part1 才是入口） */
    private fun isVolumePartNotFirst(name: String): Boolean {
        val m = Regex("\\.part(\\d+)\\.").find(name) ?: return false
        return m.groupValues[1].toIntOrNull()?.let { it != 1 } ?: false
    }

    /** 解压目标目录名：去掉 .partN 与扩展名；放在压缩包所在目录内（保持原结构） */
    private fun destDirFor(file: File): File {
        var name = file.name
        name = name.replace(Regex("\\.part\\d+"), "")
        name = name.substringBeforeLast(".")
        val parent = file.parentFile ?: return File(name)
        return File(parent, name)
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "do压缩包", NotificationManager.IMPORTANCE_LOW)
            notifManager?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, progress: Int, total: Int, current: String = "", ongoing: Boolean = true): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else Notification.Builder(this)
        val body = if (notifDetail) {
            val cur = if (current.isNotEmpty()) " · 正在: $current" else ""
            "$text$cur · 总${progress}% · 包内${ExtractService.shareInner}%"
        } else text
        builder.setContentTitle("do压缩包")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(ongoing)
        if (total > 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int, total: Int, current: String = "") {
        notifManager?.notify(NOTIF_ID, buildNotification(text, progress, total, current))
    }

    // ---------- 广播 ----------

    private fun broadcast(total: Int, done: Int, ok: Int, fail: Int, logLine: String, current: String, finished: Boolean, logPath: String = "") {
        val i = Intent(BROADCAST_PROGRESS).apply {
            setPackage(packageName) // 显式广播：API34+ 的 RECEIVER_NOT_EXPORTED 只收同 UID 显式广播
            putExtra(EXTRA_TOTAL, total)
            putExtra(EXTRA_DONE, done)
            putExtra(EXTRA_OK, ok)
            putExtra(EXTRA_FAIL, fail)
            putExtra(EXTRA_LOG_LINE, logLine)
            putExtra(EXTRA_CURRENT, current)
            putExtra(EXTRA_FINISHED, finished)
            putExtra(EXTRA_LOG_PATH, logPath)
        }
        sendBroadcast(i)
    }

    private fun broadcastFinished(msg: String) {
        val i = Intent(BROADCAST_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_TOTAL, 0)
            putExtra(EXTRA_DONE, 0)
            putExtra(EXTRA_OK, 0)
            putExtra(EXTRA_FAIL, 0)
            putExtra(EXTRA_LOG_LINE, msg)
            putExtra(EXTRA_CURRENT, "")
            putExtra(EXTRA_FINISHED, true)
        }
        sendBroadcast(i)
    }
}
