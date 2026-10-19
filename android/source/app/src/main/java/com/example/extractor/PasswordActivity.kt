package com.example.extractor

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.extractor.data.PasswordDatabase
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 密码本 / 加密本双本管理：
 * - Tab 切换两个本；多选勾选后可删除、全选、移入另一本（联动）。
 * - 备份/导入密钥：特殊格式(KBX1) AES-GCM 加密文件，主密码保护；导入自动合并两本。
 */
class PasswordActivity : AppCompatActivity() {

    private lateinit var db: PasswordDatabase
    private lateinit var listView: ListView
    private lateinit var tvHint: TextView
    private lateinit var tvBottom: TextView
    private lateinit var btnTabDecrypt: Button
    private lateinit var btnTabEncrypt: Button
    private var currentType = PasswordDatabase.TYPE_DECRYPT
    private var entries: MutableList<PasswordDatabase.Entry> = mutableListOf()

    private val saveKbf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && pendingBackup != null) writeUri(uri, pendingBackup!!); pendingBackup = null
    }
    private val openKbf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readUriAndImport(uri)
    }
    private var pendingBackup: ByteArray? = null
    private var langAtCreate = "zh"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        langAtCreate = getSharedPreferences("settings", 0).getString("lang", "zh") ?: "zh"
        MainActivity.applyAppLocale(this)
        setContentView(R.layout.activity_password)
        MainActivity.applyTheme(this, findViewById<View>(android.R.id.content))
        db = PasswordDatabase(this)
        listView = findViewById(R.id.list)
        tvHint = findViewById(R.id.tvHint)
        tvBottom = findViewById(R.id.tvBottom)
        btnTabDecrypt = findViewById(R.id.btnTabDecrypt)
        btnTabEncrypt = findViewById(R.id.btnTabEncrypt)
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        btnTabDecrypt.setOnClickListener { currentType = PasswordDatabase.TYPE_DECRYPT; refresh() }
        btnTabEncrypt.setOnClickListener { currentType = PasswordDatabase.TYPE_ENCRYPT; refresh() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showEditDialog(null) }
        findViewById<View>(R.id.btnSelectAll).setOnClickListener { selectAll() }
        findViewById<View>(R.id.btnDelSel).setOnClickListener { deleteSelected() }
        findViewById<View>(R.id.btnMove).setOnClickListener { moveSelected() }
        findViewById<View>(R.id.btnBackup).setOnClickListener { exportBackup() }
        findViewById<View>(R.id.btnImport).setOnClickListener { openKbf.launch(arrayOf("application/octet-stream", "*/*")) }

        // 点击=勾选（多选）；长按=编辑
        listView.setOnItemClickListener { _, _, _, _ ->
            listView.invalidateViews()
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            showEditDialog(entries[position]); true
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if ((getSharedPreferences("settings", 0).getString("lang", "zh") ?: "zh") != langAtCreate) recreate()
    }

    private fun refresh() {
        entries = db.all(currentType).toMutableList()
        listView.clearChoices()
        listView.adapter = object : ArrayAdapter<PasswordDatabase.Entry>(
            this, android.R.layout.simple_list_item_checked, android.R.id.text1, entries) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(pos, convertView, parent)
                val e = getItem(pos) ?: return v
                val note = if (e.note.isBlank()) "（无备注）" else e.note
                (v.findViewById<TextView>(android.R.id.text1)).text = "$note  ·  ${mask(e.text)}"
                return v
            }
        }
        val n = entries.size
        val bookName = if (currentType == PasswordDatabase.TYPE_DECRYPT) "解密本" else "加密本"
        tvHint.text = "当前显示：【$bookName】共 $n 条。解密本 ${db.count(PasswordDatabase.TYPE_DECRYPT)} 条 / 加密本 ${db.count(PasswordDatabase.TYPE_ENCRYPT)} 条。长按编辑。"
        tvBottom.text = if (currentType == PasswordDatabase.TYPE_DECRYPT)
            getString(R.string.pw_bottom_decrypt)
        else
            getString(R.string.pw_bottom_encrypt)
        btnTabDecrypt.setBackgroundColor(android.graphics.Color.parseColor(
            if (currentType == PasswordDatabase.TYPE_DECRYPT) "#FF6200EE" else "#FFDDDDDD"))
        btnTabDecrypt.setTextColor(android.graphics.Color.parseColor(
            if (currentType == PasswordDatabase.TYPE_DECRYPT) "#FFFFFF" else "#333333"))
        btnTabEncrypt.setBackgroundColor(android.graphics.Color.parseColor(
            if (currentType == PasswordDatabase.TYPE_ENCRYPT) "#FF00897B" else "#FFDDDDDD"))
        btnTabEncrypt.setTextColor(android.graphics.Color.parseColor(
            if (currentType == PasswordDatabase.TYPE_ENCRYPT) "#FFFFFF" else "#333333"))
        // 移入按钮按当前本显示目标本名称（解密本→"移入加密本"，加密本→"移入解密本"）
        findViewById<android.widget.Button>(R.id.btnMove).text =
            if (currentType == PasswordDatabase.TYPE_DECRYPT)
                getString(R.string.pw_move_to_enc)
            else
                getString(R.string.pw_move_to_dec)
    }

    private fun selectedIds(): List<Long> {
        val sb = listView.checkedItemPositions
        val ids = mutableListOf<Long>()
        for (i in 0 until sb.size()) {
            if (sb.valueAt(i) && sb.keyAt(i) < entries.size) ids.add(entries[sb.keyAt(i)].id)
        }
        return ids
    }

    private fun selectAll() {
        for (i in entries.indices) listView.setItemChecked(i, true)
        listView.invalidateViews()
    }

    private fun deleteSelected() {
        val ids = selectedIds()
        if (ids.isEmpty()) { Toast.makeText(this, "请先勾选要删除的条目", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle("删除所选")
            .setMessage("确定删除选中的 ${ids.size} 条密码吗？")
            .setPositiveButton("删除") { _, _ -> db.delete(ids); refresh() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun moveSelected() {
        val ids = selectedIds()
        if (ids.isEmpty()) { Toast.makeText(this, "请先勾选要移入的条目", Toast.LENGTH_SHORT).show(); return }
        val toType = 1 - currentType
        val target = if (toType == PasswordDatabase.TYPE_DECRYPT) "解密本" else "加密本"
        AlertDialog.Builder(this)
            .setTitle(if (currentType == PasswordDatabase.TYPE_DECRYPT)
                getString(R.string.pw_move_to_enc) else getString(R.string.pw_move_to_dec))
            .setMessage("把选中的 ${ids.size} 条复制到【$target】（源本保留）？")
            .setPositiveButton("复制") { _, _ ->
                val copied = db.copyTo(ids, toType)
                refresh()
                // 复制成功：小型白底提示，短暂展示后自动消失（语义=复制，非剪切）
                val skip = ids.size - copied
                val msg = if (skip > 0)
                    String.format(getString(R.string.moved_ok), target) + "\n（${skip} 条在【$target】已存在，跳过不重复）"
                else
                    String.format(getString(R.string.moved_ok), target)
                whiteToast(msg)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 白底黑字的小型自动消失提示（区别于系统深色 Toast） */
    private fun whiteToast(msg: String) {
        val toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
        toast.view?.let { v ->
            v.setBackgroundColor(android.graphics.Color.WHITE)
            if (v is android.widget.TextView) v.setTextColor(android.graphics.Color.parseColor("#333333"))
        }
        toast.show()
    }

    private fun mask(pwd: String): String {
        return if (pwd.length <= 2) "*".repeat(pwd.length) else pwd.substring(0, 2) + "*".repeat(pwd.length - 2)
    }

    private fun showEditDialog(entry: PasswordDatabase.Entry?) {
        val pwdInput = EditText(this).apply { hint = "密码"; if (entry != null) setText(entry.text) }
        val noteInput = EditText(this).apply { hint = "备注（可选）"; if (entry != null) setText(entry.note) }
        val container = android.widget.LinearLayout(this).apply { addView(pwdInput); addView(noteInput) }
        AlertDialog.Builder(this)
            .setTitle(if (entry == null) "添加" + (if (currentType == PasswordDatabase.TYPE_DECRYPT) "到密码本" else "到加密本") else "编辑密码")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val p = pwdInput.text.toString()
                if (p.isBlank()) return@setPositiveButton
                if (entry == null) db.add(p, noteInput.text.toString(), currentType)
                else db.update(entry.id, p, noteInput.text.toString())
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 备份 / 导入 ----

    private fun exportBackup() {
        val all = db.all(null)
        if (all.isEmpty()) { Toast.makeText(this, "两本都为空，无需备份", Toast.LENGTH_SHORT).show(); return }
        askMaster("设置备份主密码（导入需用它解密）") { master ->
            // 构建 {d:[pwd,note]... 密码本, e:[...] 加密本}
            val dArr = org.json.JSONArray()
            db.all(PasswordDatabase.TYPE_DECRYPT).forEach { dArr.put(org.json.JSONArray().put(it.text).put(it.note)) }
            val eArr = org.json.JSONArray()
            db.all(PasswordDatabase.TYPE_ENCRYPT).forEach { eArr.put(org.json.JSONArray().put(it.text).put(it.note)) }
            val json = JSONObject().put("d", dArr).put("e", eArr).toString()
            pendingBackup = encryptBackup(json, master)
            Toast.makeText(this, "请选择保存位置", Toast.LENGTH_SHORT).show()
            saveKbf.launch("密钥备份_${System.currentTimeMillis()}.kbf")
        }
    }

    private fun readUriAndImport(uri: Uri) {
        val bytes = readUri(uri) ?: run {
            Toast.makeText(this, "读取备份文件失败", Toast.LENGTH_SHORT).show(); return
        }
        val head = "KBX2".toByteArray(Charsets.US_ASCII)
        if (bytes.size < 60 || !bytes.copyOfRange(0, head.size).contentEquals(head)) {
            Toast.makeText(this, "不是有效的密钥备份文件(.kbf)", Toast.LENGTH_SHORT).show(); return
        }
        askMaster("输入备份主密码") { master ->
            val json = decryptBackup(bytes, master)
            if (json == null) { Toast.makeText(this, "主密码错误或文件损坏", Toast.LENGTH_SHORT).show(); return@askMaster }
            try {
                val obj = JSONObject(json)
                val d = obj.optJSONArray("d") ?: org.json.JSONArray()
                val e = obj.optJSONArray("e") ?: org.json.JSONArray()
                var cnt = 0
                for (i in 0 until d.length()) {
                    val a = d.getJSONArray(i)
                    if (a.length() >= 1) { db.add(a.getString(0), if (a.length() > 1) a.optString(1) else "", PasswordDatabase.TYPE_DECRYPT); cnt++ }
                }
                for (i in 0 until e.length()) {
                    val a = e.getJSONArray(i)
                    if (a.length() >= 1) { db.add(a.getString(0), if (a.length() > 1) a.optString(1) else "", PasswordDatabase.TYPE_ENCRYPT); cnt++ }
                }
                Toast.makeText(this, "导入成功，共合并 $cnt 条到两本", Toast.LENGTH_LONG).show()
                refresh()
            } catch (ex: Exception) {
                Toast.makeText(this, "导入解析失败：${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun askMaster(title: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "备份主密码"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val m = input.text.toString()
                if (m.isBlank()) Toast.makeText(this, "主密码不能为空", Toast.LENGTH_SHORT).show()
                else onOk(m)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deriveKey(master: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(master.toCharArray(), salt, 100000, 256)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    /** 多层加密备份：先 Deflate 压缩(体积小) → 两层 AES-GCM(主密码各派生子密钥) → KBX2 文件头 */
    private fun encryptBackup(payload: String, master: String): ByteArray {
        val compressed = deflate(payload)
        // 层1
        val salt1 = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c1 = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(master, salt1))
        }
        val iv1 = c1.iv
        val ct1 = c1.doFinal(compressed)
        // 层2：再加密层1密文
        val salt2 = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c2 = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(master, salt2))
        }
        val iv2 = c2.iv
        val ct2 = c2.doFinal(ct1)
        // 头 + salt1 + salt2 + iv1 + iv2 + ct2
        val out = java.io.ByteArrayOutputStream()
        out.write("KBX2".toByteArray(Charsets.US_ASCII))
        out.write(salt1); out.write(salt2); out.write(iv1); out.write(iv2); out.write(ct2)
        return out.toByteArray()
    }

    private fun decryptBackup(bytes: ByteArray, master: String): String? {
        val head = "KBX2".toByteArray(Charsets.US_ASCII)
        if (bytes.size < 60 || !bytes.copyOfRange(0, 4).contentEquals(head)) return null
        return try {
            val salt1 = bytes.copyOfRange(4, 20)
            val salt2 = bytes.copyOfRange(20, 36)
            val iv1 = bytes.copyOfRange(36, 48)
            val iv2 = bytes.copyOfRange(48, 60)
            val ct2 = bytes.copyOfRange(60, bytes.size)
            // 逆序解层2 → 层1
            val c2 = Cipher.getInstance("AES/GCM/NoPadding")
            c2.init(Cipher.DECRYPT_MODE, deriveKey(master, salt2), GCMParameterSpec(128, iv2))
            val ct1 = c2.doFinal(ct2)
            val c1 = Cipher.getInstance("AES/GCM/NoPadding")
            c1.init(Cipher.DECRYPT_MODE, deriveKey(master, salt1), GCMParameterSpec(128, iv1))
            inflate(c1.doFinal(ct1))
        } catch (e: Exception) { null }
    }

    private fun deflate(s: String): ByteArray {
        val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        d.setInput(s.toByteArray(Charsets.UTF_8)); d.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end(); return out.toByteArray()
    }

    private fun inflate(b: ByteArray): String {
        val i = java.util.zip.Inflater(); i.setInput(b)
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!i.finished()) { val n = i.inflate(buf); out.write(buf, 0, n) }
        i.end(); return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun writeUri(uri: Uri, bytes: ByteArray) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(this, "✅ 密钥备份已保存", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "备份保存失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readUri(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) { null }
}
