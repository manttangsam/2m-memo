package com.example.infinitenote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val accent = 0xFF0F766E.toInt()
    private val ink = 0xFF132238.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val canvas = InfiniteCanvasView(this)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFFFBFAF6.toInt()) }
        val topBar = buildTopBar()
        val topBarParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP)
        val penPanel = buildPenPanel(canvas)
        val eraserPanel = buildEraserPanel(canvas)
        val mindPanel = buildMindPanel(canvas)
        val rotatePanel = buildRotatePanel(canvas)
        val rotateButton = topBar.findViewWithTag<Button>("rotate")
        val toggleBarButton = iconButton("▴").apply {
            setOnClickListener {
                val show = topBar.visibility != View.VISIBLE
                topBar.visibility = if (show) View.VISIBLE else View.GONE
                if (!show) {
                    penPanel.visibility = View.GONE
                    eraserPanel.visibility = View.GONE
                    mindPanel.visibility = View.GONE
                    rotatePanel.visibility = View.GONE
                }
                text = if (show) "▴" else "▾"
            }
        }

        fun showPanel(panel: View?) {
            penPanel.visibility = if (panel == penPanel) View.VISIBLE else View.GONE
            eraserPanel.visibility = if (panel == eraserPanel) View.VISIBLE else View.GONE
            mindPanel.visibility = if (panel == mindPanel) View.VISIBLE else View.GONE
            rotatePanel.visibility = if (panel == rotatePanel) View.VISIBLE else View.GONE
        }

        root.addView(canvas, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(topBar, topBarParams)
        root.addView(toggleBarButton, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.TOP).apply {
            topMargin = dp(110)
            rightMargin = dp(10)
        })
        root.addView(penPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP).apply {
            topMargin = dp(52)
        })
        root.addView(eraserPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP).apply {
            topMargin = dp(52)
        })
        root.addView(mindPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP).apply {
            topMargin = dp(52)
        })
        root.addView(rotatePanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP).apply {
            topMargin = dp(52)
        })

        topBar.findViewWithTag<Button>("pen").setOnClickListener {
            canvas.mode = CanvasMode.PEN
            showPanel(penPanel)
        }
        topBar.findViewWithTag<Button>("eraser").setOnClickListener {
            canvas.mode = CanvasMode.ERASER
            showPanel(eraserPanel)
        }
        topBar.findViewWithTag<Button>("pan").setOnClickListener {
            canvas.mode = CanvasMode.PAN
            showPanel(null)
        }
        topBar.findViewWithTag<Button>("mind").setOnClickListener {
            canvas.mode = CanvasMode.MIND
            showPanel(mindPanel)
        }
        topBar.findViewWithTag<Button>("rotate").setOnClickListener {
            showPanel(rotatePanel)
        }
        canvas.onRotationChanged = { angle ->
            runOnUiThread {
                rotateButton.text = "⟳\n${angle.toInt()}°"
            }
        }
        canvas.onEditNodeRequested = { runOnUiThread { editSelectedNode(canvas) } }
        topBar.findViewWithTag<Button>("undo").setOnClickListener { canvas.undo() }
        topBar.findViewWithTag<Button>("layout").setOnClickListener { canvas.autoLayoutMindMap() }
        topBar.findViewWithTag<Button>("gridLight").setOnClickListener { canvas.adjustGridOpacity(-18) }
        topBar.findViewWithTag<Button>("gridDark").setOnClickListener { canvas.adjustGridOpacity(18) }
        topBar.findViewWithTag<Button>("center").setOnClickListener { canvas.centerOnOrigin() }
        topBar.findViewWithTag<Button>("angleLock").setOnClickListener {
            val locked = canvas.toggleRotationLock()
            (it as Button).text = if (locked) "🔒" else "🔓"
        }
        topBar.findViewWithTag<Button>("save").setOnClickListener { showSaveDialog(canvas) }
        topBar.findViewWithTag<Button>("help").setOnClickListener {
            canvas.openHelpDocument()
            Toast.makeText(this, "도움말을 열었어요. 이전 작업은 자동저장에 보관했어요.", Toast.LENGTH_LONG).show()
        }

        penPanel.visibility = View.GONE
        eraserPanel.visibility = View.GONE
        mindPanel.visibility = View.GONE
        rotatePanel.visibility = View.GONE
        setContentView(root)
    }

    private fun buildTopBar(): HorizontalScrollView {
        val row = iconRow()
        listOf(
            Triple("pen", "✎", "펜"),
            Triple("eraser", "⌫", "지우개"),
            Triple("pan", "↔", "이동"),
            Triple("save", "▣", "저장함"),
            Triple("help", "?", "도움말"),
            Triple("mind", "◎", "마인드맵"),
            Triple("rotate", "⟳\n0°", "회전"),
            Triple("angleLock", "🔓", "각도 고정"),
            Triple("undo", "↶", "실행취소"),
            Triple("layout", "☷", "자동정렬"),
            Triple("center", "⌖", "중앙으로"),
            Triple("gridLight", "▦−", "격자 연하게"),
            Triple("gridDark", "▦+", "격자 진하게")
        ).forEach { (tagValue, label, desc) ->
            row.addView(iconButton(label, desc).apply { tag = tagValue }, iconLayout())
        }
        return scrollBar(row)
    }

    private fun buildPenPanel(canvas: InfiniteCanvasView): HorizontalScrollView {
        val row = iconRow()
        listOf(
            Triple("•", "볼펜", { canvas.penKind = PenKind.BALLPOINT }),
            Triple("✐", "연필", { canvas.penKind = PenKind.PENCIL }),
            Triple("✒", "만년필", { canvas.penKind = PenKind.FOUNTAIN }),
            Triple("🖌", "붓펜", { canvas.penKind = PenKind.BRUSH })
        ).forEach { (icon, desc, action) -> row.addView(iconButton(icon, desc, action), iconLayout()) }
        row.addView(iconButton("−", "펜 얇게") { canvas.penSize = (canvas.penSize - 1f).coerceAtLeast(2f) }, iconLayout())
        row.addView(iconButton("+", "펜 굵게") { canvas.penSize = (canvas.penSize + 1f).coerceAtMost(28f) }, iconLayout())
        addColorIcons(row) { canvas.penColor = it }
        return scrollBar(row)
    }

    private fun buildEraserPanel(canvas: InfiniteCanvasView): HorizontalScrollView {
        val row = iconRow()
        row.addView(iconButton("⌫", "지우개"), iconLayout())
        row.addView(iconButton("−", "지우개 작게") { canvas.eraserSize = (canvas.eraserSize - 4f).coerceAtLeast(12f) }, iconLayout())
        row.addView(iconButton("+", "지우개 크게") { canvas.eraserSize = (canvas.eraserSize + 4f).coerceAtMost(90f) }, iconLayout())
        row.addView(iconButton("🧹", "필기 전체 삭제") { canvas.clearAllHandwriting() }, iconLayout())
        return scrollBar(row)
    }

    private fun buildMindPanel(canvas: InfiniteCanvasView): HorizontalScrollView {
        val row = iconRow()
        listOf(
            Triple("◎", "중심주제 추가", { canvas.addRootMindNode() }),
            Triple("└", "하위 가지 추가", { canvas.addChildToSelected() }),
            Triple("├", "형제 가지 추가", { canvas.addSiblingToSelected() }),
            Triple("⇄", "접기 펼치기", { canvas.toggleSelectedFold() }),
            Triple("☷", "자동정렬", { canvas.autoLayoutMindMap() }),
            Triple("🎨", "테마", { showMindThemeDialog(canvas) }),
            Triple("▤", "템플릿", { showMindTemplateDialog(canvas) }),
            Triple("✎", "박스 수정", { editSelectedNode(canvas) }),
            Triple("⌫", "삭제", { canvas.deleteSelectedMindNode() }),
            Triple("↝", "화살표 시작", { canvas.beginArrowFromSelected() }),
            Triple("⇆", "화살표 방향 전환", { canvas.reverseSelectedArrow() }),
            Triple("A−", "전체 박스 작게", { canvas.adjustWholeMindMapSize(-0.12f) }),
            Triple("A+", "전체 박스 크게", { canvas.adjustWholeMindMapSize(0.12f) }),
            Triple("□−", "선택 박스 작게", { canvas.adjustSelectedOnlySize(-0.12f) }),
            Triple("□+", "선택 박스 크게", { canvas.adjustSelectedOnlySize(0.12f) })
        ).forEach { (icon, desc, action) -> row.addView(iconButton(icon, desc, action), iconLayout()) }
        addColorIcons(row) { canvas.setSelectedNodeColor(it) }
        return scrollBar(row)
    }

    private fun showMindThemeDialog(canvas: InfiniteCanvasView) {
        val names = arrayOf("새벽", "무지개", "에너지", "코드", "키오토", "장미", "민트", "녹차")
        val palettes = arrayOf(
            intArrayOf(0xFFFF6B6B.toInt(), 0xFFFFE66D.toInt(), 0xFF95D5B2.toInt(), 0xFF7BDFF2.toInt(), 0xFFB8B8FF.toInt(), 0xFFFF8BD1.toInt()),
            intArrayOf(0xFF2563EB.toInt(), 0xFFFACC15.toInt(), 0xFF22C55E.toInt(), 0xFFA7F3D0.toInt(), 0xFFFCA5A5.toInt(), 0xFFEF4444.toInt(), 0xFF7F1D1D.toInt()),
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFE5E7EB.toInt(), 0xFFFF1F1F.toInt(), 0xFFFFC400.toInt(), 0xFF2563EB.toInt(), 0xFF020617.toInt()),
            intArrayOf(0xFFFDE68A.toInt(), 0xFFBBF7D0.toInt(), 0xFFFFFFFF.toInt(), 0xFFE879F9.toInt(), 0xFF93C5FD.toInt(), 0xFF334155.toInt()),
            intArrayOf(0xFFFFCDD2.toInt(), 0xFFFF7043.toInt(), 0xFF60A5FA.toInt(), 0xFF4F46E5.toInt(), 0xFF1E1B4B.toInt()),
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFBCFE8.toInt(), 0xFFFFA3B8.toInt(), 0xFFFF5C8A.toInt(), 0xFFE11D48.toInt(), 0xFFBE123C.toInt()),
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFA7F3D0.toInt(), 0xFF67E8F9.toInt(), 0xFF2DD4BF.toInt(), 0xFF14B8A6.toInt(), 0xFF0F766E.toInt()),
            intArrayOf(0xFFD9E2C3.toInt(), 0xFFB7B79A.toInt(), 0xFF5F8F5F.toInt(), 0xFF406C45.toInt(), 0xFF1F3D2A.toInt())
        )
        AlertDialog.Builder(this)
            .setTitle("마인드맵 색 테마")
            .setItems(names) { _, which -> canvas.applyMindTheme(palettes[which]) }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showMindTemplateDialog(canvas: InfiniteCanvasView) {
        val names = arrayOf("마인드맵", "브레이스 맵", "조직도", "타임라인", "피시본", "트리 테이블", "행렬")
        val templates = arrayOf(
            MindTemplate.BASIC,
            MindTemplate.BRACE,
            MindTemplate.ORG,
            MindTemplate.TIMELINE,
            MindTemplate.FISHBONE,
            MindTemplate.TREE_TABLE,
            MindTemplate.MATRIX
        )
        AlertDialog.Builder(this)
            .setTitle("마인드맵 템플릿")
            .setItems(names) { _, which -> canvas.applyMindTemplate(templates[which]) }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun buildRotatePanel(canvas: InfiniteCanvasView): HorizontalScrollView {
        val row = iconRow()
        row.addView(iconButton("↺", "왼쪽 5도") { canvas.rotationDegrees -= 5f }, iconLayout())
        row.addView(iconButton("0°", "회전 초기화") { canvas.rotationDegrees = 0f }, iconLayout())
        row.addView(iconButton("↻", "오른쪽 5도") { canvas.rotationDegrees += 5f }, iconLayout())
        return scrollBar(row)
    }

    private fun showSaveDialog(canvas: InfiniteCanvasView) {
        val folderInput = EditText(this).apply {
            hint = "폴더명"
            setText("기본")
            setSingleLine(true)
        }
        val titleInput = EditText(this).apply {
            hint = "메모명"
            setText("새 메모")
            setSingleLine(true)
            setSelectAllOnFocus(true)
        }
        val saved = canvas.savedDocuments()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(TextView(context).apply {
                text = "폴더와 메모명을 정해 저장합니다. 같은 이름이면 덮어씁니다."
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
            })
            addView(folderInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(titleInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (saved.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = "저장된 메모"
                    setTextColor(ink)
                    textSize = 14f
                    setPadding(0, dp(12), 0, dp(4))
                })
                saved.take(12).forEach { note ->
                    addView(Button(context).apply {
                        text = "${note.folder} / ${note.title}"
                        textSize = 13f
                        setAllCaps(false)
                        setOnClickListener {
                            val loaded = canvas.loadSavedDocument(note.key)
                            Toast.makeText(context, if (loaded) "불러왔어요" else "불러오지 못했어요", Toast.LENGTH_SHORT).show()
                        }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply {
                        topMargin = dp(4)
                    })
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("메모 저장함")
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                val savedNote = canvas.saveNamedDocument(folderInput.text.toString(), titleInput.text.toString())
                Toast.makeText(this, "${savedNote.folder} / ${savedNote.title} 저장됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun iconRow(): LinearLayout = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xEEF8FAFC.toInt())
        }

    private fun scrollBar(row: LinearLayout): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(0xEEF8FAFC.toInt())
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun iconLayout(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
        leftMargin = dp(4)
        rightMargin = dp(4)
    }

    private fun editSelectedNode(canvas: InfiniteCanvasView) {
        if (canvas.selectedNodeText() == null) return
        AlertDialog.Builder(this)
            .setTitle("박스 수정 방식")
            .setItems(arrayOf("필기로 입력", "타이핑으로 입력")) { _, which ->
                if (which == 0) showHandwritingEditor(canvas) else showTypingEditor(canvas)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showHandwritingEditor(canvas: InfiniteCanvasView) {
        val pad = HandwritingInputView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(TextView(context).apply {
                text = "아래 칸에 손글씨로 적으면 박스 안에 그대로 들어갑니다."
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
            })
            addView(pad)
            addView(iconButton("↺", "다시 쓰기") { pad.clear() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        }
        AlertDialog.Builder(this)
            .setTitle("박스 필기 수정")
            .setView(container)
            .setPositiveButton("넣기") { _, _ -> canvas.setSelectedNodeInk(pad.normalizedStrokes()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showTypingEditor(canvas: InfiniteCanvasView) {
        val input = EditText(this).apply {
            setText(canvas.selectedNodeText().orEmpty())
            setSingleLine(false)
            minLines = 2
            textSize = 18f
            setSelectAllOnFocus(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(TextView(context).apply {
                text = "키보드로 입력하면 박스 안 글자로 들어갑니다."
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
            })
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("박스 타이핑 수정")
            .setView(container)
            .setPositiveButton("넣기") { _, _ -> canvas.setSelectedNodeText(input.text.toString()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun addColorIcons(row: LinearLayout, onColor: (Int) -> Unit) {
        listOf(0xFF132238.toInt(), 0xFFDC2626.toInt(), 0xFF2563EB.toInt(), accent, 0xFFF59E0B.toInt(), Color.WHITE).forEach { color ->
            row.addView(Button(this).apply {
                text = "●"
                textSize = 24f
                contentDescription = "색상"
                setTextColor(color)
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFFFFF.toInt())
                    setStroke(dp(1), 0x22000000)
                }
                setOnClickListener { onColor(color) }
            }, iconLayout())
        }
    }

    private fun iconButton(label: String, description: String = label, action: (() -> Unit)? = null): Button = Button(this).apply {
        text = label
        textSize = when {
            label.contains("\n") -> 11f
            label.length > 1 -> 14f
            else -> 22f
        }
        contentDescription = description
        typeface = Typeface.DEFAULT_BOLD
        minWidth = 0
        minHeight = 0
        setTextColor(ink)
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
            setStroke(dp(1), 0x22000000)
        }
        elevation = dp(2).toFloat()
        if (action != null) setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/*
    private fun buildPenPanel(canvas: InfiniteCanvasView): LinearLayout {
        val panel = panel()
        val penChoices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        panel.addView(TextView(this).apply {
            text = "펜 설정"
            setTextColor(ink)
            textSize = 15f
        })
        panel.addView(pill("펜 종류 펼치기") {
            penChoices.visibility = if (penChoices.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        penChoices.addView(row().apply {
            addWeighted(pill("🖊 볼펜") { canvas.penKind = PenKind.BALLPOINT })
            addWeighted(pill("✏ 연필") { canvas.penKind = PenKind.PENCIL })
        })
        penChoices.addView(row().apply {
            addWeighted(pill("🖋 만년필") { canvas.penKind = PenKind.FOUNTAIN })
            addWeighted(pill("🖌 붓펜") { canvas.penKind = PenKind.BRUSH })
        })
        panel.addView(penChoices)
        panel.addView(row().apply {
            addView(TextView(context).apply {
                text = "굵기"
                setTextColor(ink)
                width = dp(48)
            })
            addView(SeekBar(context).apply {
                max = 26
                progress = 4
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        canvas.penSize = progress + 2f
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        panel.addView(colorRow { canvas.penColor = it })
        return panel
    }

    private fun buildEraserPanel(canvas: InfiniteCanvasView): LinearLayout {
        val panel = panel()
        val sizeText = TextView(this).apply {
            text = "지우개 영역 36"
            setTextColor(ink)
            textSize = 15f
        }
        panel.addView(sizeText)
        panel.addView(row().apply {
            addView(TextView(context).apply {
                text = "크기"
                setTextColor(ink)
                width = dp(48)
            })
            addView(SeekBar(context).apply {
                max = 78
                progress = 24
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val size = progress + 12
                        canvas.eraserSize = size.toFloat()
                        sizeText.text = "지우개 영역 $size"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        panel.addView(TextView(this).apply {
            text = "지우개는 필기 획만 지워요. 마인드맵 박스는 마인드맵 메뉴에서 삭제합니다."
            setTextColor(0xFF64748B.toInt())
            textSize = 12f
        })
        return panel
    }

    private fun buildMindPanel(canvas: InfiniteCanvasView): LinearLayout {
        val panel = panel()
        val moreTools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        panel.addView(TextView(this).apply {
            text = "마인드맵"
            setTextColor(ink)
            textSize = 15f
        })
        panel.addView(row().apply {
            addView(pill("+자식") { canvas.addChildToSelected() })
            addView(pill("+형제") { canvas.addSiblingToSelected() })
            addView(pill("더보기") {
                moreTools.visibility = if (moreTools.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            })
        })
        panel.addView(row().apply {
            addWeighted(pill("전체 작게") { canvas.adjustWholeMindMapSize(-0.12f) })
            addWeighted(pill("전체 크게") { canvas.adjustWholeMindMapSize(0.12f) })
        })
        panel.addView(row().apply {
            addWeighted(pill("개별 작게") { canvas.adjustSelectedOnlySize(-0.12f) })
            addWeighted(pill("개별 크게") { canvas.adjustSelectedOnlySize(0.12f) })
        })
        moreTools.addView(row().apply {
            addView(pill("접기") { canvas.toggleSelectedFold() })
            addView(pill("정렬") { canvas.autoLayoutMindMap() })
        })
        moreTools.addView(row().apply {
            addWeighted(pill("✎ 수정") { editSelectedNode(canvas) })
            addWeighted(pill("삭제") { canvas.deleteSelectedMindNode() })
        })
        moreTools.addView(row().apply {
            addWeighted(pill("화살표 시작") { canvas.beginArrowFromSelected() })
            addWeighted(pill("방향 전환") { canvas.reverseSelectedArrow() })
        })
        moreTools.addView(colorRow { canvas.setSelectedNodeColor(it) })
        panel.addView(moreTools)
        return panel
    }

    private fun editSelectedNode(canvas: InfiniteCanvasView) {
        if (canvas.selectedNodeText() == null) return
        AlertDialog.Builder(this)
            .setTitle("박스 수정 방식")
            .setItems(arrayOf("필기로 입력", "타이핑으로 입력")) { _, which ->
                if (which == 0) {
                    showHandwritingEditor(canvas)
                } else {
                    showTypingEditor(canvas)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showHandwritingEditor(canvas: InfiniteCanvasView) {
        val pad = HandwritingInputView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(TextView(context).apply {
                text = "아래 칸에 손글씨로 적으면 박스 안에 그대로 들어갑니다."
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
            })
            addView(pad)
            addView(pill("다시 쓰기") { pad.clear() })
        }
        AlertDialog.Builder(this)
            .setTitle("박스 필기 수정")
            .setView(container)
            .setPositiveButton("넣기") { _, _ -> canvas.setSelectedNodeInk(pad.normalizedStrokes()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showTypingEditor(canvas: InfiniteCanvasView) {
        val input = EditText(this).apply {
            setText(canvas.selectedNodeText().orEmpty())
            setSingleLine(false)
            minLines = 2
            textSize = 18f
            setSelectAllOnFocus(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(TextView(context).apply {
                text = "키보드로 입력하면 박스 안 글자로 들어갑니다."
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
            })
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("박스 타이핑 수정")
            .setView(container)
            .setPositiveButton("넣기") { _, _ -> canvas.setSelectedNodeText(input.text.toString()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun buildRotatePanel(canvas: InfiniteCanvasView): LinearLayout {
        val panel = panel()
        val angleText = TextView(this).apply {
            text = "밑판 회전 0°"
            setTextColor(ink)
            textSize = 15f
        }
        panel.addView(angleText)
        panel.addView(row().apply {
            addView(TextView(context).apply {
                text = "-90°"
                setTextColor(ink)
                width = dp(46)
            })
            addView(SeekBar(context).apply {
                max = 180
                progress = 90
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val angle = progress - 90
                        canvas.rotationDegrees = angle.toFloat()
                        angleText.text = "밑판 회전 ${angle}°"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "+90°"
                setTextColor(ink)
                gravity = Gravity.END
                width = dp(46)
            })
        })
        panel.addView(row().apply {
            addView(pill("왼쪽 5°") {
                canvas.rotationDegrees -= 5f
                angleText.text = "밑판 회전 ${canvas.rotationDegrees.toInt()}°"
            })
            addView(pill("초기화") {
                canvas.rotationDegrees = 0f
                angleText.text = "밑판 회전 0°"
            })
            addView(pill("오른쪽 5°") {
                canvas.rotationDegrees += 5f
                angleText.text = "밑판 회전 ${canvas.rotationDegrees.toInt()}°"
            })
        })
        panel.addView(TextView(this).apply {
            text = "두 손가락으로도 확대/축소와 회전을 같이 조절할 수 있어요."
            setTextColor(0xFF64748B.toInt())
            textSize = 12f
        })
        return panel
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(0xF7FFFFFF.toInt())
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun LinearLayout.addWeighted(view: View) {
        addView(view, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        })
    }

    private fun pill(label: String, action: (() -> Unit)? = null): Button = Button(this).apply {
        text = label
        textSize = 13f
        minWidth = 0
        minHeight = 0
        setTextColor(ink)
        setPadding(dp(12), 0, dp(12), 0)
        if (action != null) setOnClickListener { action() }
    }

    private fun iconButton(label: String): Button = Button(this).apply {
        text = label
        textSize = if (label.contains("\n")) 12f else 21f
        typeface = Typeface.DEFAULT_BOLD
        minWidth = 0
        minHeight = 0
        setTextColor(ink)
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
            setStroke(dp(1), 0x22000000)
        }
        elevation = dp(2).toFloat()
    }

    private fun colorRow(onColor: (Int) -> Unit): LinearLayout = row().apply {
        val colors = listOf(0xFF132238.toInt(), 0xFFDC2626.toInt(), 0xFF2563EB.toInt(), accent, 0xFFF59E0B.toInt(), Color.WHITE)
        colors.forEach { color ->
            addView(Button(context).apply {
                text = "●"
                textSize = 22f
                setTextColor(color)
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFFFFF.toInt())
                    setStroke(dp(1), 0x22000000)
                }
                setOnClickListener { onColor(color) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
*/
