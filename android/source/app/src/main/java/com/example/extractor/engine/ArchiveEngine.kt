package com.example.extractor.engine

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import net.jpountz.lz4.LZ4FrameInputStream
import java.io.File
import java.io.FileInputStream
import java.io.BufferedInputStream

/**
 * 解压引擎。
 * - zip / tar / lz4 / 无密码 7z / rar4：纯 Java 实现
 * - rar5 分卷 / 加密 7z / 加密 zip：走 libarchive JNI（LibArchiveBridge）
 */
object ArchiveEngine {

    enum class Format { ZIP, RAR, SEVENZ, TAR, LZ4, BZIP2, UNKNOWN }

    /** 按文件头 magic 检测格式 */
    fun detectFormat(file: File): Format {
        if (!file.isFile) return Format.UNKNOWN
        return try {
            val buf = ByteArray(512)
            BufferedInputStream(FileInputStream(file)).use { it.read(buf) }
            // rar4: 52 61 72 21 1A 07 00 ; rar5: 52 61 72 21 1A 07 01 00
            if (buf[0] == 'R'.code.toByte() && buf[1] == 'a'.code.toByte() &&
                buf[2] == 'r'.code.toByte() && buf[3] == '!'.code.toByte()) return Format.RAR
            // zip: PK\x03\x04 或 PK\x05\x06 或 PK\x07\x08
            if (buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte()) return Format.ZIP
            // 7z: 37 7A BC AF 27 1C
            if (buf[0] == 0x37.toByte() && buf[1] == 0x7A.toByte() && buf[2] == 0xBC.toByte() && buf[3] == 0xAF.toByte()) return Format.SEVENZ
            // tar: offset 257 处 "ustar"（纯 tar）
            if (buf.size > 262 &&
                buf[257] == 'u'.code.toByte() && buf[258] == 's'.code.toByte() &&
                buf[259] == 't'.code.toByte() && buf[260] == 'a'.code.toByte() && buf[261] == 'r'.code.toByte()) return Format.TAR
            // gzip 头（tar.gz 或纯 gz）
            if (buf[0] == 0x1F.toByte() && buf[1] == 0x8B.toByte()) return Format.TAR
            // bzip2 头: BZh
            if (buf[0] == 'B'.code.toByte() && buf[1] == 'Z'.code.toByte() && buf[2] == 'h'.code.toByte()) return Format.BZIP2
            // lz4 帧 magic：标准 04 22 4D 18；legacy 02 21 4C 18
            if (buf[0] == 0x04.toByte() && buf[1] == 0x22.toByte() && buf[2] == 0x4D.toByte() && buf[3] == 0x18.toByte()) return Format.LZ4
            if (buf[0] == 0x02.toByte() && buf[1] == 0x21.toByte() && buf[2] == 0x4C.toByte() && buf[3] == 0x18.toByte()) return Format.LZ4
            Format.UNKNOWN
        } catch (e: Exception) {
            Format.UNKNOWN
        }
    }

    data class Result(val ok: Boolean, val reason: String = "")

    /** 解压入口；密码可为空。onProgress 可选：回调包内解压进度百分比 0-100（仅 zip/7z 字节级、流式近似）。 */
    fun extract(file: File, dest: File, password: String?, onProgress: ((Int) -> Unit)? = null): Result {
        val fmt = detectFormat(file)
        return try {
            when (fmt) {
                Format.ZIP -> extractZip(file, dest, password, onProgress)
                Format.TAR -> extractTar(file, dest, onProgress)
                Format.LZ4 -> extractLz4Chain(file, dest, password, onProgress)
                Format.BZIP2 -> extractBzip2(file, dest, onProgress)
                Format.RAR -> extractRar(file, dest, password)
                Format.SEVENZ -> extractSevenz(file, dest, password, onProgress)
                else -> Result(false, "无法识别的压缩格式: ${file.name}")
            }
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 每层解压结束后补一次 100，确保包内进度收敛到满 */
    private fun finishProgress(onProgress: ((Int) -> Unit)?) {
        onProgress?.invoke(100)
    }

    /** unrar 内部有非线程安全全局状态，必须串行调用 */
    private val unrarLock = Any()

    /** rar 用 RARLAB unrar（JNI，完整支持 rar4/rar5 加密分卷）；失败回退 libarchive */
    private fun extractRar(file: File, dest: File, password: String?, onProgress: ((Int) -> Unit)? = null): Result {
        if (UnrarBridge.isAvailable) {
            return synchronized(unrarLock) {
                try {
                    dest.mkdirs()
                    val err = UnrarBridge.extract(file.absolutePath, dest.absolutePath, password)
                    if (err.isEmpty()) { finishProgress(onProgress); Result(true) }
                    else extractNative(file, dest, password, onProgress)
                } catch (e: Exception) {
                    extractNative(file, dest, password, onProgress)
                }
            }
        }
        return extractNative(file, dest, password, onProgress)
    }

    private fun extractZip(file: File, dest: File, password: String?, onProgress: ((Int) -> Unit)? = null): Result {
        // zip 分卷：先按序拼接 .001~.NNN 成完整 zip（WinZip spanning，libarchive 不认多卷 zip）
        val merged = mergeZipVolumes(file)
        val work = merged ?: file
        // 加密 zip：用 zip4j（支持 zipcrypto + AES，完整解出所有文件）；zip4j 无字节回调，包内进度由调用方按"解压中"处理
        if (password != null) {
            try {
                net.lingala.zip4j.ZipFile(work.absolutePath, password.toCharArray()).use { zf ->
                    zf.extractAll(dest.absolutePath)
                }
                merged?.delete()
                finishProgress(onProgress)
                return Result(true)
            } catch (e: Exception) {
                merged?.delete()
                if (!LibArchiveBridge.isAvailable()) {
                    return Result(false, e.message ?: "加密 zip 解压失败")
                }
                // 否则回退 libarchive（可能密码不对，保留错误上报）
                return extractNative(file, dest, password)
            }
        }
        return try {
            ZipFile(work, Charsets.UTF_8.name()).use { zip ->
                val entries = zip.entries.toList()
                val totalBytes = entries.filter { !it.isDirectory }.sumOf { it.size }
                var written = 0L; var lastPct = -1
                for (e in entries) {
                    val out = safeResolve(dest, e.name)
                    if (e.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        zip.getInputStream(e).use { input ->
                            out.outputStream().use { output ->
                                val buf = ByteArray(65536); var n: Int
                                while (input.read(buf).also { n = it } > 0) {
                                    output.write(buf, 0, n); written += n
                                    if (totalBytes > 0) {
                                        val pct = (written * 100 / totalBytes).toInt()
                                        if (pct != lastPct) { lastPct = pct; onProgress?.invoke(pct.coerceIn(0, 100)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            finishProgress(onProgress)
            Result(true)
        } catch (e: Exception) {
            if (LibArchiveBridge.isAvailable()) extractNative(file, dest, password)
            else Result(false, e.message ?: "zip 解压失败")
        }.also { merged?.delete() }
    }

    /** 若文件是 .NNN 结尾的 zip 分卷（xxx.zip.001 / xxx.001），按序拼接成完整 zip 临时文件 */
    private fun mergeZipVolumes(file: File): File? {
        val m = Regex("""^(.*)\.(\d{3})$""").find(file.name) ?: return null
        val base = m.groupValues[1]
        val dir = file.parentFile ?: return null
        val vols = (dir.listFiles { f ->
            f.isFile && Regex("""^${Regex.escape(base)}\.\d{3}$""").matches(f.name)
        } ?: return null).sortedBy { it.name.substringAfterLast('.').toInt() }
        if (vols.isEmpty()) return null
        // 完整拼接 .001~N（连续分卷的字节按序拼即完整 zip）
        val merged = File(dir, "$base.merged.zip")
        vols.forEach { f -> merged.appendBytes(f.readBytes()) }
        return merged
    }

    private fun extractTar(file: File, dest: File, onProgress: ((Int) -> Unit)? = null): Result {
        val packSize = file.length()
        try {
            return java.io.BufferedInputStream(java.io.FileInputStream(file)).use { base ->
                if (isGzip(base)) {
                    val gz = org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(base)
                    val gzBuf = java.io.BufferedInputStream(gz)
                    if (isUstar(gzBuf)) {
                        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzBuf).use { tar ->
                            var entry = tar.nextEntry
                            while (entry != null) {
                                val out = safeResolve(dest, entry.name)
                                if (entry.isDirectory) out.mkdirs()
                                else {
                                    out.parentFile?.mkdirs()
                                    copyCounted(tar, out.outputStream(), packSize, onProgress)
                                }
                                entry = tar.nextEntry
                            }
                        }
                        Result(true)
                    } else {
                        // 纯 gzip：优先用 gzip 头里的原始文件名，否则用包名去 .gz
                        val meta = gz.getMetaData()
                        val innerName = meta?.getFilename()?.takeIf { it.isNotBlank() }
                            ?: file.name.removeSuffix(".gz").removeSuffix(".GZ")
                        val out = File(dest, innerName)
                        out.parentFile?.mkdirs()
                        copyCounted(gzBuf, out.outputStream(), packSize, onProgress)
                        Result(true)
                    }
                } else {
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(base).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            val out = safeResolve(dest, entry.name)
                            if (entry.isDirectory) out.mkdirs()
                            else {
                                out.parentFile?.mkdirs()
                                tar.copyTo(out.outputStream())
                            }
                            entry = tar.nextEntry
                        }
                    }
                    Result(true)
                }
            }
        } catch (e: Exception) {
            return Result(false, e.message ?: "tar 解压失败")
        }
    }

    /** 按解压流已读字节 / 压缩包大小近似回调进度（流式格式无法预知解出总量，取 min(100)） */
    private fun copyCounted(input: java.io.InputStream, output: java.io.OutputStream,
                            packSize: Long, onProgress: ((Int) -> Unit)?): Long {
        val buf = ByteArray(65536); var n: Int; var read = 0L; var lastPct = -1
        while (input.read(buf).also { n = it } > 0) {
            output.write(buf, 0, n); read += n
            if (packSize > 0) {
                val pct = (read * 100 / packSize).toInt()
                if (pct != lastPct) { lastPct = pct; onProgress?.invoke(pct.coerceIn(0, 100)) }
            }
        }
        return read
    }

    private fun isGzip(stream: java.io.BufferedInputStream): Boolean {
        stream.mark(2)
        val b1 = stream.read()
        val b2 = stream.read()
        stream.reset()
        return b1 == 0x1F && b2 == 0x8B
    }

    /** 判断流解压后是否为 tar（offset 257 处有 "ustar" 头） */
    private fun isUstar(stream: java.io.BufferedInputStream): Boolean {
        return try {
            val b = ByteArray(512)
            stream.mark(512)
            var off = 0
            while (off < b.size) { val n = stream.read(b, off, b.size - off); if (n < 0) break; off += n }
            stream.reset()
            b.size > 262 &&
                b[257] == 'u'.code.toByte() && b[258] == 's'.code.toByte() &&
                b[259] == 't'.code.toByte() && b[260] == 'a'.code.toByte() && b[261] == 'r'.code.toByte()
        } catch (e: Exception) { false }
    }

    /** lz4 解出内层文件，再继续按内容递归解压（对应 PC 端套娃逻辑） */
    private fun extractLz4Chain(file: File, dest: File, password: String?, onProgress: ((Int) -> Unit)? = null): Result {
        val inner = File(dest, file.name.removeSuffix(".lz4").removeSuffix(".LZ4"))
        try {
            inner.parentFile?.mkdirs()
            LZ4FrameInputStream(BufferedInputStream(FileInputStream(file))).use { lz4 ->
                inner.outputStream().use { output -> copyCounted(lz4, output, file.length(), onProgress) }
            }
            if (!inner.isFile) return Result(false, "lz4 解压产物缺失")
            // 内层若是压缩包则继续套娃
            val innerFmt = detectFormat(inner)
            return if (innerFmt != Format.UNKNOWN) extract(inner, dest, password)
            else Result(true)
        } catch (e: Exception) {
            return Result(false, e.message ?: "lz4 解压失败")
        }
    }

    /** bzip2：流压缩（压单个文件），解出内层文件；若内层仍是压缩包由调用方(nestedExtract)续解 */
    private fun extractBzip2(file: File, dest: File, onProgress: ((Int) -> Unit)? = null): Result {
        val inner = File(dest, file.name.removeSuffix(".bz2").removeSuffix(".bzip2").removeSuffix(".BZ2"))
        try {
            inner.parentFile?.mkdirs()
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(file))).use { bz ->
                inner.outputStream().use { o -> copyCounted(bz, o, file.length(), onProgress) }
            }
            if (!inner.isFile) return Result(false, "bzip2 解压产物缺失")
            return Result(true)
        } catch (e: Exception) {
            return Result(false, e.message ?: "bzip2 解压失败")
        }
    }

    /** 7z：加密走 sevenzipjbinding（官方 7-Zip，AES 加密完整解压）；无加密走 libarchive/纯 Java */
    private fun extractSevenz(file: File, dest: File, password: String?, onProgress: ((Int) -> Unit)? = null): Result {
        if (password != null) {
            val r = extract7zSevenZip(file, dest, password, onProgress)
            if (r.ok) return r
            // sevenzip 失败（可能密码错）回退 libarchive 上报原因
            return extractNative(file, dest, password, onProgress)
        }
        return extractNative(file, dest, password, onProgress)
    }

    /** 用 commons-compress SevenZFile 解加密 7z（纯 Java，支持 AES-256） */
    private fun extract7zSevenZip(file: File, dest: File, password: String, onProgress: ((Int) -> Unit)? = null): Result {
        return try {
            val sevenZ = org.apache.commons.compress.archivers.sevenz.SevenZFile(file, password.toCharArray())
            try {
                val packSize = file.length()
                var written = 0L; var lastPct = -1
                var entry = sevenZ.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (entry.isDirectory) {
                        safeResolve(dest, name).mkdirs()
                    } else {
                        val out = safeResolve(dest, name)
                        out.parentFile?.mkdirs()
                        java.io.FileOutputStream(out).use { fos ->
                            val buf = ByteArray(8192)
                            var n: Int
                            while (sevenZ.read(buf).also { n = it } > 0) {
                                fos.write(buf, 0, n); written += n
                                if (packSize > 0) {
                                    val pct = (written * 100 / packSize).toInt()
                                    if (pct != lastPct) { lastPct = pct; onProgress?.invoke(pct.coerceIn(0, 100)) }
                                }
                            }
                        }
                    }
                    entry = sevenZ.nextEntry
                }
            } finally {
                sevenZ.close()
            }
            finishProgress(onProgress)
            Result(true)
        } catch (e: Exception) {
            android.util.Log.e("SevenZip", "加密7z解压失败: ${e.message}")
            Result(false, e.message ?: "加密 7z 解压失败")
        }
    }

    /** rar / 7z：优先 libarchive（rar5/分卷/加密 7z），rar4 降级 junrar */
    private fun extractNative(file: File, dest: File, password: String?, onProgress: ((Int) -> Unit)? = null): Result {
        if (LibArchiveBridge.isAvailable()) {
            val r = LibArchiveBridge.extract(file.absolutePath, dest.absolutePath, password)
            if (r.first) { finishProgress(onProgress); return Result(true) }
            // rar4 兜底：libarchive 失败且非加密时尝试 junrar
            if (detectFormat(file) == Format.RAR && password == null) {
                return extractRar4(file, dest)
            }
            return Result(false, r.second)
        }
        // libarchive 未就绪：rar4 用 junrar，7z 用 commons-compress
        return when (detectFormat(file)) {
            Format.RAR -> extractRar4(file, dest)
            Format.SEVENZ -> extract7zPlain(file, dest, password, onProgress)
            else -> Result(false, "原生库不可用")
        }
    }

    /** 纯 Java 解 7z（无加密） */
    private fun extract7zPlain(file: File, dest: File, password: String?, onProgress: ((Int) -> Unit)? = null): Result {
        return try {
            val szf = if (password != null && LibArchiveBridge.isAvailable()) {
                val r = LibArchiveBridge.extract(file.absolutePath, dest.absolutePath, password)
                if (r.first) { finishProgress(onProgress); return Result(true) }
                return Result(false, r.second)
            } else {
                SevenZFile(file).use { sz ->
                    val packSize = file.length()
                    var written = 0L; var lastPct = -1
                    var entry = sz.nextEntry
                    while (entry != null) {
                        val out = safeResolve(dest, entry.name)
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            val buf = ByteArray(65536)
                            out.outputStream().use { output ->
                                var n = sz.read(buf)
                                while (n != -1) {
                                    output.write(buf, 0, n); written += n
                                    if (packSize > 0) {
                                        val pct = (written * 100 / packSize).toInt()
                                        if (pct != lastPct) { lastPct = pct; onProgress?.invoke(pct.coerceIn(0, 100)) }
                                    }
                                    n = sz.read(buf)
                                }
                            }
                        }
                        entry = sz.nextEntry
                    }
                }
                finishProgress(onProgress)
            }
            Result(true)
        } catch (e: Exception) {
            Result(false, e.message ?: "7z 解压失败")
        }
    }

    private fun extractRar4(file: File, dest: File): Result {
        return try {
            dest.mkdirs()
            com.github.junrar.Junrar.extract(file, dest)
            Result(true)
        } catch (e: Exception) {
            Result(false, e.message ?: "rar4 解压失败")
        }
    }

    /** 防路径穿越：把 entry 名称安全映射到 dest 内 */
    private fun safeResolve(dest: File, name: String): File {
        val cleaned = name.replace('\\', '/').trimStart('/')
        return File(dest, cleaned).canonicalFile.let {
            if (!it.path.startsWith(dest.canonicalFile.path + File.separator)) File(dest, "extracted_" + cleaned.replace('/', '_'))
            else it
        }
    }
}
