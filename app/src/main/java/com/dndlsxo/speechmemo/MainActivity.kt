package com.dndlsxo.speechmemo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private var scripts: List<Script> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "발표 암기"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyText = TextView(this).apply {
            textSize = 16f
            setPadding(dp(24), dp(32), dp(24), dp(16))
            setLineSpacing(0f, 1.3f)
            text = "아직 원고가 없어요.\n\n아래 '새 원고'를 눌러 발표문을 붙여넣거나, 메모·카카오톡에서 글을 '공유 → 발표 암기'로 보내 보세요.\n\n" +
                "빈 줄로 단락을 나눠 두면 '단락 3번씩 반복' 연습이 더 잘 돼요."
        }
        listView = ListView(this).apply {
            setOnItemClickListener { _, _, pos, _ -> openPlayer(scripts[pos].id) }
            setOnItemLongClickListener { _, _, pos, _ ->
                showItemMenu(scripts[pos])
                true
            }
        }
        val addBtn = Button(this).apply {
            text = "＋ 새 원고"
            setOnClickListener { startActivity(Intent(this@MainActivity, EditorActivity::class.java)) }
        }
        root.addView(emptyText)
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(addBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(8), dp(16), dp(16))
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        scripts = ScriptStore.list(this)
        emptyText.visibility = if (scripts.isEmpty()) View.VISIBLE else View.GONE
        listView.adapter = object : ArrayAdapter<Script>(this, android.R.layout.simple_list_item_2, android.R.id.text1, scripts) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val s = scripts[position]
                val parsed = TextParser.parse(s.body)
                v.findViewById<TextView>(android.R.id.text1).text = s.title
                v.findViewById<TextView>(android.R.id.text2).text =
                    "문장 ${parsed.size}개 · 단락 ${TextParser.paragraphCount(parsed)}개 · " +
                        s.body.replace(Regex("\\s+"), " ").trim().take(40)
                return v
            }
        }
    }

    private fun openPlayer(id: String) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_ID, id))
    }

    private fun showItemMenu(s: Script) {
        AlertDialog.Builder(this)
            .setTitle(s.title)
            .setItems(arrayOf("연습하기", "수정", "삭제")) { _, which ->
                when (which) {
                    0 -> openPlayer(s.id)
                    1 -> startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, s.id))
                    2 -> AlertDialog.Builder(this)
                        .setMessage("'${s.title}' 원고를 삭제할까요? 저장해 둔 음성 파일은 그대로 남아요.")
                        .setPositiveButton("삭제") { _, _ ->
                            ScriptStore.delete(this, s.id)
                            refresh()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            }
            .show()
    }
}
