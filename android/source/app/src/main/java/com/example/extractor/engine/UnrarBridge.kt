package com.example.extractor.engine

/**
 * RARLAB unrar JNI 桥：完整支持 rar4/rar5（含加密分卷）解压。
 * native 方法返回错误信息字符串，空串表示解压成功。
 */
object UnrarBridge {
    private val loaded: Boolean = try {
        System.loadLibrary("unrar_jni")
        true
    } catch (e: Throwable) { false }

    val isAvailable: Boolean get() = loaded

    /** 解压 arc 到 dest；pwd 可为 null。返回错误信息，空串 = 成功 */
    external fun extract(arc: String, dest: String, pwd: String?): String
}
