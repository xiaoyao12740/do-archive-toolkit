package com.example.extractor

import android.app.Application
import android.os.Process
import java.io.File

/**
 * 全局崩溃捕获：任何未捕获异常写入日志文件，
 * 便于真机排查（getExternalFilesDir 无需权限，总能写）。
 */
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val log = buildString {
                    append("时间: ${System.currentTimeMillis()}\n")
                    append("线程: ${thread.name}\n")
                    append(android.util.Log.getStackTraceString(throwable))
                }
                val dir = getExternalFilesDir(null) ?: cacheDir
                File(dir, "crash_log.txt").writeText(log)
            } catch (_: Exception) {
            }
            // 保持默认行为（终止进程）
            Process.killProcess(Process.myPid())
        }
    }
}
