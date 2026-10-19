package com.example.extractor.engine

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import net.jpountz.lz4.LZ4FrameOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 压缩引擎：创建多格式、多层堆叠、部分加密(zip/AES)的压缩包。
 * 可压：zip(可加密) / 7z / tar / gz / lz4 / bz2；rar 因 RARLAB 版权无法创建（只能解）。
 */
object CompressEngine {

    enum class CFormat { ZIP, SEVENZ, TAR, GZ, LZ4, BZIP2 }

    data class LayerSpec(val format: CFormat, val encrypted: Boolean)
    data class CResult(val ok: Boolean, val reason: String = "", val outFile: File? = null)

    /** 多层堆叠压缩：source -> L1(格式1, 可选加密) -> L2 -> ... -> 最外层产物 */
    fun createNested(source: File, destDir: File, layers: List<LayerSpec>,
                     passwords: List<String>, deleteSource: Boolean,
                     onProgress: ((Int) -> Unit)? = null): CResult {
        if (layers.isEmpty()) return CResult(false, "至少选择 1 层")
        try { destDir.mkdirs() } catch (_: Exception) {}
        var current = source
        val base = source.name
        val total = layers.size
        val intermediates = mutableListOf<File>()
        for ((idx, spec) in layers.withIndex()) {
            val layer = idx + 1
            var eff = spec
            // 流式格式(gz/lz4/bz2)只能压单个文件：源是文件夹且该层是流式 → 自动改为 zip（保留加密意图）
            if (current.isDirectory &&
                (spec.format == CFormat.GZ || spec.format == CFormat.LZ4 || spec.format == CFormat.BZIP2)) {
                eff = LayerSpec(CFormat.ZIP, spec.encrypted)
            }
            val out = File(destDir, "${base}_L$layer.${extFor(eff.format)}")
            // 加密层密码：从密码本按索引轮换取
            val pwd = if (eff.encrypted) {
                if (passwords.isEmpty()) return CResult(false, "第 $layer 层要加密，但密码本为空")
                passwords[idx % passwords.size]
            } else null
            val r = compressOne(current, out, eff, pwd)
            if (!r.ok) return r
            if (layer < total) intermediates.add(out)
            current = out
            onProgress?.invoke((layer * 100 / total).coerceIn(1, 100))
        }
        // 删除中间构造层（只留最外层堆叠包）；源文件/夹按开关删除
        intermediates.forEach { deleteRecursive(it) }
        if (deleteSource) deleteRecursive(source)
        return CResult(true, outFile = current)
    }

    private fun compressOne(src: File, out: File, spec: LayerSpec, pwd: String?): CResult {
        // 流式格式(gz/lz4/bz2)只能压单个文件
        if ((spec.format == CFormat.GZ || spec.format == CFormat.LZ4 || spec.format == CFormat.BZIP2) && src.isDirectory) {
            return CResult(false, "${spec.format} 只能压单个文件，文件夹请先选 zip/tar/7z 层")
        }
        return when (spec.format) {
            CFormat.ZIP -> if (pwd != null) compressZipEncrypted(src, out, pwd) else compressZipPlain(src, out)
            CFormat.SEVENZ -> compress7z(src, out)
            CFormat.TAR -> compressTar(src, out)
            CFormat.GZ -> compressStream(src, out, CFormat.GZ)
            CFormat.LZ4 -> compressStream(src, out, CFormat.LZ4)
            CFormat.BZIP2 -> compressStream(src, out, CFormat.BZIP2)
        }
    }

    /** 加密 zip：zip4j AES-256 */
    private fun compressZipEncrypted(src: File, out: File, pwd: String): CResult {
        return try {
            val zf = net.lingala.zip4j.ZipFile(out, pwd.toCharArray())
            try {
                if (src.isDirectory) zf.addFolder(src) else zf.addFile(src)
            } finally { zf.close() }
            CResult(true, outFile = out)
        } catch (e: Exception) {
            CResult(false, "zip 加密创建失败: ${e.message}")
        }
    }

    /** 无加密 zip（commons-compress） */
    private fun compressZipPlain(src: File, out: File): CResult {
        return try {
            ZipArchiveOutputStream(BufferedOutputStream(FileOutputStream(out))).use { z ->
                addToZip(z, src, "")
            }
            CResult(true, outFile = out)
        } catch (e: Exception) {
            CResult(false, "zip 创建失败: ${e.message}")
        }
    }

    private fun addToZip(z: ZipArchiveOutputStream, f: File, entryPath: String) {
        if (f.isDirectory) {
            val name = if (entryPath.isEmpty()) f.name else "$entryPath/${f.name}"
            val e = ZipArchiveEntry("$name/"); e.time = f.lastModified()
            z.putArchiveEntry(e); z.closeArchiveEntry()
            f.listFiles()?.forEach { addToZip(z, it, name) }
        } else {
            val name = if (entryPath.isEmpty()) f.name else "$entryPath/${f.name}"
            val e = ZipArchiveEntry(name); e.time = f.lastModified()
            z.putArchiveEntry(e)
            FileInputStream(f).use { it.copyTo(z) }
            z.closeArchiveEntry()
        }
    }

    /** 7z（LZMA，无加密） */
    private fun compress7z(src: File, out: File): CResult {
        return try {
            SevenZOutputFile(out).use { z -> addTo7z(z, src, "") }
            CResult(true, outFile = out)
        } catch (e: Exception) {
            CResult(false, "7z 创建失败: ${e.message}")
        }
    }

    private fun addTo7z(z: SevenZOutputFile, f: File, entryPath: String) {
        val name = if (entryPath.isEmpty()) f.name else "$entryPath/${f.name}"
        val entry = z.createArchiveEntry(f, name)
        z.putArchiveEntry(entry)
        if (f.isFile) {
            FileInputStream(f).use { input ->
                val buf = ByteArray(65536); var n: Int
                while (input.read(buf).also { n = it } > 0) z.write(buf, 0, n)
            }
        }
        z.closeArchiveEntry()
        if (f.isDirectory) f.listFiles()?.forEach { addTo7z(z, it, name) }
    }

    /** tar */
    private fun compressTar(src: File, out: File): CResult {
        return try {
            TarArchiveOutputStream(BufferedOutputStream(FileOutputStream(out))).use { t ->
                t.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                addToTar(t, src, "")
            }
            CResult(true, outFile = out)
        } catch (e: Exception) {
            CResult(false, "tar 创建失败: ${e.message}")
        }
    }

    private fun addToTar(t: TarArchiveOutputStream, f: File, entryPath: String) {
        if (f.isDirectory) {
            val name = if (entryPath.isEmpty()) f.name else "$entryPath/${f.name}"
            val e = TarArchiveEntry(f, name); t.putArchiveEntry(e); t.closeArchiveEntry()
            f.listFiles()?.forEach { addToTar(t, it, name) }
        } else {
            val name = if (entryPath.isEmpty()) f.name else "$entryPath/${f.name}"
            val e = TarArchiveEntry(f, name); t.putArchiveEntry(e)
            FileInputStream(f).use { it.copyTo(t) }
            t.closeArchiveEntry()
        }
    }

    /** 流式单文件压缩（gz/lz4/bz2） */
    private fun compressStream(src: File, out: File, fmt: CFormat): CResult {
        return try {
            val os: OutputStream = when (fmt) {
                CFormat.GZ -> GzipCompressorOutputStream(FileOutputStream(out))
                CFormat.BZIP2 -> BZip2CompressorOutputStream(FileOutputStream(out))
                else -> LZ4FrameOutputStream(FileOutputStream(out))
            }
            os.use { s -> FileInputStream(src).use { input -> input.copyTo(s) } }
            CResult(true, outFile = out)
        } catch (e: Exception) {
            CResult(false, "${fmt.name.lowercase()} 创建失败: ${e.message}")
        }
    }

    private fun extFor(f: CFormat): String = when (f) {
        CFormat.ZIP -> "zip"; CFormat.SEVENZ -> "7z"; CFormat.TAR -> "tar"
        CFormat.GZ -> "gz"; CFormat.LZ4 -> "lz4"; CFormat.BZIP2 -> "bz2"
    }

    /** 递归删除（夹整组删除） */
    private fun deleteRecursive(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        if (f.exists()) f.delete()
    }
}
