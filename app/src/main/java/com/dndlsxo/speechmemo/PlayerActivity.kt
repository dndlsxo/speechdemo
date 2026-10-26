package com.dndlsxo.speechmemo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

class PlayerActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_ID = "id"
        private val MODES = arrayOf("연속 재생", "문장마다 따라 말하기", "단락 3번씩 반복")
        private val EXPORT_KINDS = arrayOf(
            "기본 — 원고를 자연스럽게 쭉 읽기",
            "따라 말하기용 — 문장마다 말할 시간 두기",
            "단락 3번씩 반복 — 단락 단위로 외우기",
        )
        private val EXPORT_SUFFIX = arrayOf("낭독", "따라말하기", "단락반복")
        private val BLANKS = arrayOf("원문 전체", "30% 가리기", "60% 가리기", "첫 글자만", "모두 가리기")
        private const val M_EXPORT = 1
        private const val M_VOICE = 2
        private const val M_ENGINE = 3
        private const val M_INSTALL = 4
        private const val M_EDIT = 5
        private const val HIGHLIGHT = 0x55FFC107
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private var scriptId = ""
    private var scriptTitle = ""
    private var sentences: List<Sentence> = emptyList()
    private val views = mutableListOf<TextView>()
    private val revealed = mutableSetOf<Int>()

    private var current = 0
    private var playing = false
    private var token = 0
    private var pendingId = ""
    private var paraRepeat = 0

    private var mode = 0
    private var blankLevel = 0
    private var loopAll = false
    private var rate = 1.0f

    /** Delays between sentences (cleared on pause). */
    private val gapHandler = Handler(Looper.getMainLooper())
    /** Delivers TTS callbacks to the main thread (never cleared, so exports keep working). */
    private val uiHandler = Handler(Looper.getMainLooper())

    private var exporter: AudioExporter? = null

    private lateinit var scroll: ScrollView
    private lateinit var listBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var playBtn: Button

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            val id = utteranceId ?: return
            uiHandler.post { onUtteranceDone(id) }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            val id = utteranceId ?: return
            uiHandler.post { onUtteranceError(id) }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            finish()
            return
        }
        scriptId = id
        rate = prefs.getFloat("rate", 1.0f)
        mode = prefs.getInt("mode", 0)
        blankLevel = prefs.getInt("blank", 0)
        loopAll = prefs.getBoolean("loop", false)

        buildUi()
        if (!loadScript()) {
            finish()
            return
        }
        tts = TextToSpeech(this, this, prefs.getString("engine", null))
    }

    override fun onRestart() {
        super.onRestart()
        // Coming back from the editor: pick up any changes to the script.
        val s = ScriptStore.get(this, scriptId)
        if (s == null) {
            finish()
            return
        }
        if (s.title != scriptTitle || TextParser.parse(s.body) != sentences) {
            pausePlayback()
            loadScript()
        }
    }

    override fun onDestroy() {
        gapHandler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacksAndMessages(null)
        exporter?.cancel()
        exporter = null
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            showEngineProblem()
            return
        }
        tts.setOnUtteranceProgressListener(listener)
        val r = tts.setLanguage(Locale.KOREAN)
        prefs.getString("voice", null)?.let { name ->
            try {
                tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it }
            } catch (_: Exception) {
            }
        }
        tts.setSpeechRate(rate)
        ttsReady = true
        updateStatus()
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) showEngineProblem()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        scroll = ScrollView(this)
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(0x33888888) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(10))
        }
        statusText = TextView(this).apply {
            textSize = 13f
            alpha = 0.7f
        }
        panel.addView(statusText)

        // Blank-out level
        val blankLabel = TextView(this).apply {
            textSize = 14f
            minWidth = dp(118)
            text = "가리기: ${BLANKS[blankLevel]}"
        }
        val blankBar = SeekBar(this).apply {
            max = BLANKS.size - 1
            progress = blankLevel
            setOnSeekBarChangeListener(simpleSeek { p ->
                blankLevel = p
                blankLabel.text = "가리기: ${BLANKS[p]}"
                revealed.clear()
                renderAll()
                prefs.edit().putInt("blank", p).apply()
            })
        }
        panel.addView(row(blankLabel, blankBar))

        // Speed
        val speedLabel = TextView(this).apply {
            textSize = 14f
            minWidth = dp(118)
            text = speedText()
        }
        val speedBar = SeekBar(this).apply {
            max = 20 // 0.5x .. 1.5x in 0.05 steps
            progress = ((rate - 0.5f) / 0.05f).roundToInt().coerceIn(0, 20)
            setOnSeekBarChangeListener(simpleSeek { p ->
                rate = 0.5f + p * 0.05f
                speedLabel.text = speedText()
                if (ttsReady) tts.setSpeechRate(rate)
                prefs.edit().putFloat("rate", rate).apply()
            })
        }
        panel.addView(row(speedLabel, speedBar))

        // Mode + loop
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PlayerActivity, android.R.layout.simple_spinner_dropdown_item, MODES)
            setSelection(mode)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    mode = position
                    paraRepeat = 0
                    prefs.edit().putInt("mode", position).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        val loopBox = CheckBox(this).apply {
            text = "전체 반복"
            isChecked = loopAll
            setOnCheckedChangeListener { _, c ->
                loopAll = c
                prefs.edit().putBoolean("loop", c).apply()
            }
        }
        panel.addView(row(spinner, loopBox, stretch = setOf(0)))

        // Transport buttons
        val prevBtn = Button(this).apply {
            text = "◀ 이전"
            setOnClickListener { step(-1) }
        }
        playBtn = Button(this).apply {
            text = "▶ 재생"
            setOnClickListener { if (playing) pausePlayback() else startAt(current) }
        }
        val nextBtn = Button(this).apply {
            text = "다음 ▶"
            setOnClickListener { step(1) }
        }
        panel.addView(row(prevBtn, playBtn, nextBtn, stretch = setOf(0, 1, 2)))

        root.addView(panel)
        setContentView(root)
    }

    private fun speedText() = "속도: " + String.format(Locale.US, "%.2f", rate) + "x"

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun loadScript(): Boolean {
        val s = ScriptStore.get(this, scriptId) ?: return false
        scriptTitle = s.title
        title = s.title
        sentences = TextParser.parse(s.body)
        revealed.clear()
        current = current.coerceIn(0, maxOf(0, sentences.size - 1))
        buildSentenceViews()
        updateStatus()
        return true
    }

    private fun buildSentenceViews() {
        listBox.removeAllViews()
        views.clear()
        listBox.addView(TextView(this).apply {
            textSize = 13f
            alpha = 0.6f
            text = "문장을 누르면 거기서부터 읽어요. 가린 문장은 길게 눌러 잠깐 확인할 수 있어요."
            setPadding(dp(8), 0, dp(8), dp(12))
        })
        if (sentences.isEmpty()) {
            listBox.addView(TextView(this).apply {
                textSize = 16f
                text = "원고가 비어 있어요. 오른쪽 위 메뉴의 '원고 수정'에서 내용을 넣어 주세요."
                setPadding(dp(8), dp(24), dp(8), 0)
            })
            return
        }
        sentences.forEachIndexed { i, s ->
            val tv = TextView(this).apply {
                textSize = 19f
                setLineSpacing(0f, 1.35f)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { startAt(i) }
                setOnLongClickListener {
                    if (!revealed.add(i)) revealed.remove(i)
                    render(i)
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0 && sentences[i - 1].para != s.para) lp.topMargin = dp(18)
            listBox.addView(tv, lp)
            views.add(tv)
        }
        renderAll()
        highlight(false)
    }

    private fun render(i: Int) {
        val s = sentences[i]
        views[i].text = if (blankLevel == 0 || i in revealed) s.text else Masker.mask(s.text, blankLevel, i)
    }

    private fun renderAll() {
        for (i in views.indices) render(i)
    }

    private fun highlight(scrollTo: Boolean = true) {
        views.forEachIndexed { i, v -> v.setBackgroundColor(if (i == current) HIGHLIGHT else Color.TRANSPARENT) }
        if (scrollTo && current in views.indices) {
            val v = views[current]
            scroll.post { scroll.smoothScrollTo(0, maxOf(0, v.top - scroll.height / 3)) }
        }
    }

    private fun updateStatus() {
        statusText.text = when {
            sentences.isEmpty() -> ""
            else -> {
                val base = "문장 ${current + 1} / ${sentences.size} · 단락 ${sentences[current].para + 1} / ${TextParser.paragraphCount(sentences)}"
                if (ttsReady) base else "$base · 음성 엔진 준비 중…"
            }
        }
    }

    // ---------------------------------------------------------------- playback

    private fun startAt(i: Int) {
        if (!ttsReady) {
            toast("음성 엔진을 준비하고 있어요. 잠시 후 다시 눌러 주세요.")
            return
        }
        if (sentences.isEmpty() || exporter != null) return
        gapHandler.removeCallbacksAndMessages(null)
        token++
        paraRepeat = 0
        current = i.coerceIn(0, sentences.size - 1)
        playing = true
        playBtn.text = "⏸ 일시정지"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speakCurrent()
    }

    private fun speakCurrent() {
        highlight()
        updateStatus()
        pendingId = "p_${token}_$current"
        tts.speak(sentences[current].text, TextToSpeech.QUEUE_FLUSH, null, pendingId)
    }

    private fun pausePlayback() {
        val wasPlaying = playing
        playing = false
        token++
        gapHandler.removeCallbacksAndMessages(null)
        if (ttsReady && (wasPlaying || exporter == null)) tts.stop()
        if (::playBtn.isInitialized) playBtn.text = "▶ 재생"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun step(delta: Int) {
        if (sentences.isEmpty()) return
        val i = (current + delta).coerceIn(0, sentences.size - 1)
        if (playing) {
            startAt(i)
        } else {
            current = i
            highlight()
            updateStatus()
        }
    }

    private fun onUtteranceDone(id: String) {
        exporter?.let {
            if (it.handles(id)) {
                it.onDone(id)
                return
            }
        }
        if (playing && id == pendingId) afterSentence()
    }

    private fun onUtteranceError(id: String) {
        exporter?.let {
            if (it.handles(id)) {
                it.onError(id)
                return
            }
        }
        if (playing && id == pendingId) {
            pausePlayback()
            toast("이 문장을 읽지 못했어요. 메뉴에서 목소리나 음성 엔진을 바꿔 보세요.")
        }
    }

    private fun afterSentence() {
        val i = current
        var gap = Timing.baseGap(sentences, i)
        var next = i + 1
        when (mode) {
            Timing.MODE_SHADOW -> gap += Timing.shadowMs(sentences[i].text, rate)
            Timing.MODE_PARA_REPEAT -> if (Timing.isParaEnd(sentences, i)) {
                if (paraRepeat < Timing.PARA_REPEATS - 1) {
                    paraRepeat++
                    next = Timing.paraStart(sentences, i)
                    gap = 1000L
                } else {
                    paraRepeat = 0
                    gap = 1600L
                }
            }
        }
        if (next >= sentences.size) {
            if (loopAll) {
                next = 0
                gap = 2000L
            } else {
                pausePlayback()
                current = 0
                highlight(false)
                updateStatus()
                toast("끝까지 읽었어요.")
                return
            }
        }
        val t = token
        val target = next
        gapHandler.postDelayed({
            if (playing && t == token) {
                current = target
                speakCurrent()
            }
        }, gap)
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_EXPORT, 0, "음성 파일로 저장").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_VOICE, 1, "목소리 선택")
        menu.add(0, M_ENGINE, 2, "음성 엔진 선택")
        menu.add(0, M_INSTALL, 3, "음성 데이터 설치·설정")
        menu.add(0, M_EDIT, 4, "원고 수정")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_EXPORT -> chooseExport()
            M_VOICE -> chooseVoice()
            M_ENGINE -> chooseEngine()
            M_INSTALL -> openTtsInstall()
            M_EDIT -> {
                pausePlayback()
                startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, scriptId))
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun chooseVoice() {
        if (!ttsReady) {
            toast("음성 엔진을 준비하고 있어요.")
            return
        }
        val list = try {
            (tts.voices ?: emptySet()).filter { it.locale.language == "ko" }
                .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
        } catch (_: Exception) {
            emptyList()
        }
        if (list.isEmpty()) {
            toast("이 음성 엔진에서는 한국어 목소리를 고를 수 없어요. 엔진을 바꿔 보세요.")
            return
        }
        val currentName = try { tts.voice?.name } catch (_: Exception) { null }
        val labels = list.mapIndexed { n, v ->
            buildString {
                append("목소리 ${n + 1}")
                append(
                    when {
                        v.quality >= Voice.QUALITY_VERY_HIGH -> " · 최고 음질"
                        v.quality >= Voice.QUALITY_HIGH -> " · 고음질"
                        else -> ""
                    }
                )
                if (v.isNetworkConnectionRequired) append(" · 인터넷 필요")
                if (v.name == currentName) append("  ✓ 사용 중")
                append("\n")
                append(v.name)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("목소리 선택 (누르면 미리 듣기)")
            .setItems(labels.toTypedArray()) { _, which ->
                val v = list[which]
                tts.voice = v
                prefs.edit().putString("voice", v.name).apply()
                previewVoice()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun previewVoice() {
        pausePlayback()
        tts.speak("안녕하세요. 이 목소리로 원고를 읽어 드릴게요.", TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    private fun chooseEngine() {
        if (!::tts.isInitialized) return
        val engines = tts.engines
        if (engines.isEmpty()) {
            showEngineProblem()
            return
        }
        val active = prefs.getString("engine", null) ?: tts.defaultEngine
        val labels = engines.map { if (it.name == active) "${it.label}  ✓ 사용 중" else it.label }
        AlertDialog.Builder(this)
            .setTitle("음성 엔진 선택")
            .setItems(labels.toTypedArray()) { _, which ->
                val e = engines[which]
                pausePlayback()
                prefs.edit().putString("engine", e.name).remove("voice").apply()
                ttsReady = false
                tts.shutdown()
                tts = TextToSpeech(this, this, e.name)
                updateStatus()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun openTtsInstall() {
        val engine = prefs.getString("engine", null) ?: if (::tts.isInitialized) tts.defaultEngine else null
        val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { if (engine != null) setPackage(engine) }
        try {
            startActivity(install)
        } catch (_: Exception) {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                toast("설정 > 일반 > 텍스트 음성 변환 에서 직접 설치해 주세요.")
            }
        }
    }

    private fun showEngineProblem() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("한국어 음성을 쓸 수 없어요")
            .setMessage(
                "휴대폰에 한국어 음성 데이터가 없거나 음성 엔진이 꺼져 있어요.\n\n" +
                    "Play 스토어에서 'Google 음성 인식 및 합성'(Speech Recognition & Synthesis)을 설치·업데이트하고, " +
                    "한국어 음성 데이터를 받은 뒤 다시 열어 주세요. 삼성 휴대폰이라면 '삼성 TTS 엔진'도 쓸 수 있어요."
            )
            .setPositiveButton("음성 데이터 설치") { _, _ -> openTtsInstall() }
            .setNeutralButton("엔진 바꾸기") { _, _ -> chooseEngine() }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ---------------------------------------------------------------- export

    private fun chooseExport() {
        if (!ttsReady) {
            toast("음성 엔진을 준비하고 있어요.")
            return
        }
        if (sentences.isEmpty()) {
            toast("원고가 비어 있어요.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("어떤 파일로 만들까요?")
            .setItems(EXPORT_KINDS) { _, which -> runExport(which) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun runExport(kind: Int) {
        pausePlayback()
        val total = sentences.size
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = total
            progress = 0
        }
        val msg = TextView(this).apply {
            text = "문장을 음성으로 바꾸는 중… (0/$total)"
            setPadding(0, 0, 0, dp(8))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(4))
            addView(msg)
            addView(bar)
        }
        lateinit var dlg: AlertDialog
        val ex = AudioExporter(
            this, tts, sentences, Timing.plan(sentences, kind, rate), scriptTitle, EXPORT_SUFFIX[kind],
            object : AudioExporter.Listener {
                override fun onProgress(done: Int, total: Int) {
                    bar.progress = done
                    msg.text = if (done < total) "문장을 음성으로 바꾸는 중… ($done/$total)" else "하나의 파일로 합치는 중…"
                }

                override fun onSaved(uri: Uri, where: String) {
                    exporter = null
                    if (isFinishing) return
                    dlg.dismiss()
                    showSaved(uri, where)
                }

                override fun onFailed(message: String) {
                    exporter = null
                    if (isFinishing) return
                    dlg.dismiss()
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("저장하지 못했어요")
                        .setMessage(message)
                        .setPositiveButton("확인", null)
                        .show()
                }
            },
        )
        dlg = AlertDialog.Builder(this)
            .setTitle("음성 파일 만드는 중")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("취소") { _, _ ->
                ex.cancel()
                exporter = null
            }
            .create()
        dlg.show()
        exporter = ex
        ex.start()
    }

    private fun showSaved(uri: Uri, where: String) {
        AlertDialog.Builder(this)
            .setTitle("저장했어요")
            .setMessage(
                "$where\n\n음악 앱에서 이 파일을 '한 곡 반복'으로 틀어 두면 화면을 꺼도 계속 들을 수 있어요."
            )
            .setPositiveButton("바로 듣기") { _, _ ->
                val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(open)
                } catch (_: Exception) {
                    toast("파일을 열 수 있는 음악 앱이 없어요. 파일 앱에서 음악/SpeechMemo 폴더를 열어 주세요.")
                }
            }
            .setNeutralButton("공유") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("audio/*")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "음성 파일 보내기"))
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
