package com.example.extractor.engine

/**
 * libarchive 原生库 JNI 桥接。
 * 负责 rar(4/5) 分卷、加密 7z、加密 zip 等纯 Java 难以/无法处理的格式。
 * native 返回空字符串表示成功，否则返回错误消息。
 */
object LibArchiveBridge {

    @Volatile private var tried = false
    @Volatile private var available = false

    private const val TAG = "LibArchive"

    /** 是否已成功加载 libarchive 原生库 */
    fun isAvailable(): Boolean {
        if (!tried) {
            tried = true
            try {
                // Android 的 loadLibrary 会自动加 "lib" 前缀并找 .so，
                // 因此 "archive" -> libarchive.so
                System.loadLibrary("archive")
                available = true
                android.util.Log.i(TAG, "libarchive native loaded OK")
            } catch (e: Throwable) {
                available = false
                android.util.Log.e(TAG, "libarchive native load FAILED: " + e.message, e)
            }
        }
        return available
    }

    @JvmStatic external fun nativeExtract(src: String, dest: String, password: String?): String

    fun extract(src: String, dest: String, password: String?): Pair<Boolean, String> {
        if (!isAvailable()) return Pair(false, "libarchive 未加载")
        val msg = nativeExtract(src, dest, password)
        android.util.Log.i(TAG, "extract($src, pwd=${password != null}) -> " +
            (if (msg.isEmpty()) "OK" else msg))
        return if (msg.isEmpty()) Pair(true, "") else Pair(false, msg)
    }
}
