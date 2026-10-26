package com.dndlsxo.speechmemo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class EditorActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private var scriptId: String? = null
    private lateinit var titleEdit: EditText
    private lateinit var bodyEdit: EditText
    private lateinit var info: TextView
    private var savedTitle = ""
    private var savedBody = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scriptId = intent.getStringExtra(EXTRA_ID)
        val existing = scriptId?.let { ScriptStore.get(this, it) }
        title = if (existing != null) "원고 수정" else "새 원고"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        titleEdit = EditText(this).apply {
            hint = "제목 (예: 3학년 탐구 발표)"
            setSingleLine(true)
            textSize = 18f
        }
        bodyEdit = EditText(this).apply {
            hint = "발표 원고를 붙여넣으세요.\n빈 줄로 단락을 나누면 단락 반복 연습에 쓰여요."
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            textSize = 16f
            setLineSpacing(0f, 1.25f)
        }
        info = TextView(this).apply {
            textSize = 13f
            alpha = 0.7f
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val saveBtn = Button(this).apply {
            text = "저장"
            setOnClickListener { save(openAfter = false) }
        }
        val practiceBtn = Button(this).apply {
            text = "저장하고 연습 시작"
            setOnClickListener { save(openAfter = true) }
        }

        root.addView(titleEdit)
        root.addView(bodyEdit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(info)
        root.addView(row(saveBtn, practiceBtn, stretch = setOf(0, 1)))
        setContentView(root)

        if (existing != null) {
            titleEdit.setText(existing.title)
            bodyEdit.setText(existing.body)
            savedTitle = existing.title
            savedBody = existing.body
        } else if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { titleEdit.setText(it) }
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { bodyEdit.setText(it) }
        }
        bodyEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateInfo()
        })
        updateInfo()
    }

    private fun updateInfo() {
        val parsed = TextParser.parse(bodyEdit.text.toString())
        info.text = if (parsed.isEmpty()) "" else "문장 ${parsed.size}개 · 단락 ${TextParser.paragraphCount(parsed)}개로 나눠 읽어요"
    }

    private fun isDirty() = titleEdit.text.toString() != savedTitle || bodyEdit.text.toString() != savedBody

    private fun save(openAfter: Boolean): Boolean {
        val body = bodyEdit.text.toString()
        if (body.isBlank()) {
            toast("원고 내용을 넣어 주세요.")
            return false
        }
        var t = titleEdit.text.toString().trim()
        if (t.isEmpty()) t = body.trim().lineSequence().first().take(24)
        val id = ScriptStore.save(this, scriptId, t, body)
        scriptId = id
        savedTitle = titleEdit.text.toString()
        savedBody = body
        toast("저장했어요.")
        if (openAfter) {
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!isDirty() || bodyEdit.text.isBlank()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("고친 내용을 저장할까요?")
            .setPositiveButton("저장") { _, _ -> save(openAfter = false) }
            .setNegativeButton("버리기") { _, _ -> finish() }
            .setNeutralButton("계속 편집", null)
            .show()
    }
}
