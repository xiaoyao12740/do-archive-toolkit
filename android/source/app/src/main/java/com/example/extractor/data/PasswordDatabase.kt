package com.example.extractor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密码库：SQLite 存储 + AES-GCM 加密（密钥由 Android Keystore 保管）。
 * 支持增删改查，供批量解压时逐条尝试。
 */
class PasswordDatabase(context: Context) {

    private val dbHelper = DbHelper(context)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    data class Entry(val id: Long, val text: String, val note: String)

    init {
        ensureKey()
    }

    private fun ensureKey() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            kg.generateKey()
        }
    }

    private fun key(): SecretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(":", limit = 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    /** 新增一条密码；type：0=密码本(解压)，1=加密本(压缩) */
    fun add(password: String, note: String, type: Int = TYPE_DECRYPT): Long {
        val db = dbHelper.writableDatabase
        val values = android.content.ContentValues().apply {
            put("password", encrypt(password))
            put("note", note)
            put("type", type)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert("passwords", null, values)
    }

    /** 修改一条（保留原 type） */
    fun update(id: Long, password: String, note: String) {
        val db = dbHelper.writableDatabase
        val values = android.content.ContentValues().apply {
            put("password", encrypt(password))
            put("note", note)
        }
        db.update("passwords", values, "id=?", arrayOf(id.toString()))
    }

    /** 删除一条 */
    fun delete(id: Long) = delete(listOf(id))

    /** 批量删除 */
    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        dbHelper.writableDatabase.execSQL(
            "DELETE FROM passwords WHERE id IN (${ids.joinToString(",")})")
    }

    /** 把若干条移入另一本（0 密码本 <-> 1 加密本） */
    fun move(ids: List<Long>, toType: Int) {
        if (ids.isEmpty()) return
        dbHelper.writableDatabase.execSQL(
            "UPDATE passwords SET type=$toType WHERE id IN (${ids.joinToString(",")})")
    }

    /** 把若干条复制到另一本（源本保留；目标本已有同密码则跳过，避免重复；返回实际复制条数） */
    fun copyTo(ids: List<Long>, toType: Int): Int {
        if (ids.isEmpty()) return 0
        val db = dbHelper.writableDatabase
        val src = all(null).filter { it.id in ids }
        val existing = all(toType).map { it.text }.toHashSet()
        var copied = 0
        db.beginTransaction()
        try {
            src.forEach { e ->
                if (e.text in existing) return@forEach   // 目标本已有同密码，跳过不复制
                val values = android.content.ContentValues().apply {
                    put("password", encrypt(e.text))
                    put("note", e.note)
                    put("type", toType)
                    put("created_at", System.currentTimeMillis())
                }
                db.insert("passwords", null, values)
                existing.add(e.text)
                copied++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return copied
    }

    /** 列出全部；type=null 时两本一起返回（备份用） */
    fun all(type: Int? = null): List<Entry> {
        val db = dbHelper.readableDatabase
        val sql = if (type == null) "SELECT id, password, note FROM passwords ORDER BY id"
                  else "SELECT id, password, note FROM passwords WHERE type=? ORDER BY id"
        val args = if (type == null) null else arrayOf(type.toString())
        val list = mutableListOf<Entry>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                list.add(Entry(c.getLong(0), decrypt(c.getString(1)), c.getString(2)))
            }
        }
        return list
    }

    /** 密码总数 */
    fun count(type: Int? = null): Int {
        val db = dbHelper.readableDatabase
        val sql = if (type == null) "SELECT COUNT(*) FROM passwords"
                  else "SELECT COUNT(*) FROM passwords WHERE type=?"
        val args = if (type == null) null else arrayOf(type.toString())
        return db.rawQuery(sql, args).use { it.moveToFirst(); it.getInt(0) }
    }

    private class DbHelper(context: Context) :
        android.database.sqlite.SQLiteOpenHelper(context, "extract_passwords.db", null, 2) {
        override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE passwords (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "password TEXT NOT NULL, " +
                        "note TEXT, " +
                        "type INTEGER NOT NULL DEFAULT 0, " +
                        "created_at INTEGER)"
            )
        }

        override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldV: Int, newV: Int) {
            if (oldV < 2) {
                try {
                    db.execSQL("ALTER TABLE passwords ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val KEY_ALIAS = "extractor_password_key"
        const val TYPE_DECRYPT = 0   // 密码本（解压）
        const val TYPE_ENCRYPT = 1   // 加密本（压缩）
    }
}
