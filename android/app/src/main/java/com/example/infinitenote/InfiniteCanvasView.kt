package com.example.infinitenote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class CanvasMode { PEN, ERASER, PAN, MIND }
enum class PenKind { BALLPOINT, PENCIL, FOUNTAIN, BRUSH }

private data class Point(val x: Float, val y: Float)
data class InkPoint(val x: Float, val y: Float)
private data class Stroke(val points: MutableList<Point>, val color: Int, val size: Float, val kind: PenKind)
private data class ArrowLink(var fromId: Int, var toId: Int)
data class SavedNoteInfo(val key: String, val folder: String, val title: String, val updatedAt: Long)
enum class MindTemplate { BASIC, BRACE, ORG, TIMELINE, FISHBONE, TREE_TABLE, MATRIX }
private data class MindNode(
    val id: Int,
    var x: Float,
    var y: Float,
    var text: String,
    var parentId: Int?,
    var color: Int,
    var collapsed: Boolean = false,
    var sizeScale: Float = 1f,
    var inkStrokes: MutableList<MutableList<InkPoint>> = mutableListOf()
)

class InfiniteCanvasView(context: Context) : View(context) {
    var mode = CanvasMode.PEN
        set(value) {
            field = value
            invalidate()
        }
    var penSize = 6f
    var penColor = 0xFF132238.toInt()
    var penKind = PenKind.BALLPOINT
    var eraserSize = 36f
    var gridOpacity = 72
        private set
    var rotationLocked = false
        private set
    var rotationDegrees = 0f
        set(value) {
            field = ((value + 180f) % 360f) - 180f
            onRotationChanged?.invoke(field)
            invalidate()
        }
    var onRotationChanged: ((Float) -> Unit)? = null
    var onEditNodeRequested: (() -> Unit)? = null
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var nextNodeId = 1
    private var selectedNodeId: Int? = null
    private var activeStroke: Stroke? = null
    private var activeNode: MindNode? = null
    private var resizingNode: MindNode? = null
    private var resizeStartDistance = 1f
    private var resizeStartScale = 1f
    private var eraserPoint: Point? = null
    private var lastPan: Point? = null
    private var lastPinchDistance = 0f
    private var lastPinchAngle = 0f
    private var pinchMidpoint: Point? = null
    private var touchStart: Point? = null
    private var touchMoved = false
    private var multiTouchActive = false
    private var arrowStartNodeId: Int? = null
    private var selectedArrowIndex: Int? = null
    private var lastTapNodeId: Int? = null
    private var lastTapTime = 0L
    private var scaleToastUntil = 0L
    private val strokes = mutableListOf<Stroke>()
    private val nodes = mutableListOf<MindNode>()
    private val arrows = mutableListOf<ArrowLink>()
    private val history = mutableListOf<Triple<List<Stroke>, List<MindNode>, List<ArrowLink>>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val prefs = context.getSharedPreferences("infinite_note_document", Context.MODE_PRIVATE)
    private val scratchSound = ScratchSoundPlayer()

    init {
        loadDocument()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFFBFAF6.toInt())
        drawGrid(canvas)
        drawCenterGuide(canvas)
        strokes.forEach { drawStroke(canvas, it) }
        drawMindMap(canvas)
        drawEraserPreview(canvas)
        drawNavigationOverlay(canvas)
        drawScaleToast(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        if (event.pointerCount == 2) {
            multiTouchActive = true
            activeNode = null
            activeStroke = null
            handlePinch(event)
            return true
        }
        val world = toWorld(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(world, event)
            MotionEvent.ACTION_MOVE -> onMove(world, event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) onUp()
                scratchSound.stop()
                activeStroke = null
                activeNode = null
                resizingNode = null
                eraserPoint = null
                lastPan = null
                lastPinchDistance = 0f
                lastPinchAngle = 0f
                pinchMidpoint = null
                touchStart = null
                touchMoved = false
                multiTouchActive = false
                saveDocument()
            }
        }
        invalidate()
        return true
    }

    fun undo() {
        val snapshot = history.removeLastOrNull() ?: return
        strokes.clear()
        strokes.addAll(snapshot.first.map { it.copy(points = it.points.toMutableList()) })
        nodes.clear()
        nodes.addAll(snapshot.second.map { it.copy() })
        arrows.clear()
        arrows.addAll(snapshot.third.map { it.copy() })
        saveDocument()
        invalidate()
    }

    fun adjustGridOpacity(delta: Int) {
        gridOpacity = (gridOpacity + delta).coerceIn(18, 150)
        saveDocument()
        invalidate()
    }

    fun centerOnOrigin() {
        offsetX = 0f
        offsetY = 0f
        saveDocument()
        invalidate()
    }

    fun toggleRotationLock(): Boolean {
        rotationLocked = !rotationLocked
        saveDocument()
        invalidate()
        return rotationLocked
    }

    fun saveNamedDocument(folderInput: String, titleInput: String): SavedNoteInfo {
        saveDocument()
        val folder = folderInput.trim().ifBlank { "기본" }
        val title = titleInput.trim().ifBlank { "새 메모" }
        val raw = prefs.getString("document", null).orEmpty()
        val items = savedDocuments().toMutableList()
        val existing = items.firstOrNull { it.folder == folder && it.title == title }
        val key = existing?.key ?: "saved_${System.currentTimeMillis()}"
        val updated = System.currentTimeMillis()
        val next = SavedNoteInfo(key, folder, title, updated)
        prefs.edit().putString(key, raw).apply()
        items.removeAll { it.key == key }
        items.add(0, next)
        saveSavedDocumentIndex(items)
        return next
    }

    fun savedDocuments(): List<SavedNoteInfo> {
        val array = JSONArray(prefs.getString("documents_index", "[]"))
        val result = mutableListOf<SavedNoteInfo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            result.add(SavedNoteInfo(
                key = item.optString("key"),
                folder = item.optString("folder", "기본"),
                title = item.optString("title", "새 메모"),
                updatedAt = item.optLong("updatedAt", 0L)
            ))
        }
        return result.filter { it.key.isNotBlank() && prefs.contains(it.key) }
    }

    fun loadSavedDocument(key: String): Boolean {
        val raw = prefs.getString(key, null) ?: return false
        prefs.edit().putString("document", raw).apply()
        loadDocument()
        history.clear()
        invalidate()
        return true
    }

    fun openHelpDocument() {
        saveNamedDocument("자동저장", "도움말 열기 전")
        saveHistory()
        strokes.clear()
        nodes.clear()
        arrows.clear()
        selectedArrowIndex = null
        arrowStartNodeId = null
        selectedNodeId = null
        scale = 0.82f
        offsetX = 0f
        offsetY = 0f
        rotationDegrees = 0f
        val center = Point(0f, 0f)
        val palette = intArrayOf(
            0xFF0F766E.toInt(),
            0xFF2563EB.toInt(),
            0xFFF97316.toInt(),
            0xFFE11D48.toInt(),
            0xFF7C3AED.toInt(),
            0xFF334155.toInt()
        )
        val root = addTemplateNode(center.x, center.y, "2M 메모 사용설명서", null, palette[0], 1.25f)
        val sections = listOf(
            "필기" to listOf("✎ 펜 선택", "−/+ 굵기", "색상 선택", "슥슥 필기음"),
            "캔버스" to listOf("두 손가락 확대", "↔ 이동", "⟳ 회전", "🔒 각도 고정", "⌖ 중앙 복귀"),
            "지우개" to listOf("⌫ 부분 삭제", "−/+ 지우개 크기", "🧹 필기 전체 삭제", "박스 탭 삭제"),
            "마인드맵" to listOf("◎ 중심주제", "└ 자녀", "├ 형제", "✎ 박스 수정", "↝ 화살표"),
            "꾸미기" to listOf("🎨 색 테마", "▤ 템플릿", "□ 크기 조절", "☷ 수동 정렬"),
            "저장" to listOf("▣ 저장함", "폴더/메모명", "저장 목록 불러오기", "자동저장")
        )
        sections.forEachIndexed { index, section ->
            val branch = addTemplateNode(
                center.x + 260f,
                center.y + (index - 2.5f) * 130f,
                section.first,
                root.id,
                palette[(index + 1) % palette.size],
                1.02f
            )
            section.second.forEachIndexed { itemIndex, item ->
                addTemplateNode(
                    branch.x + 250f,
                    branch.y + (itemIndex - 1.5f) * 54f,
                    item,
                    branch.id,
                    Color.WHITE,
                    0.82f
                )
            }
        }
        selectedNodeId = root.id
        saveDocument()
        invalidate()
    }

    private fun saveSavedDocumentIndex(items: List<SavedNoteInfo>) {
        prefs.edit().putString("documents_index", JSONArray().apply {
            items.forEach {
                put(JSONObject()
                    .put("key", it.key)
                    .put("folder", it.folder)
                    .put("title", it.title)
                    .put("updatedAt", it.updatedAt))
            }
        }.toString()).apply()
    }

    fun selectedNodeText(): String? = selectedNode()?.text

    fun setSelectedNodeText(text: String) {
        val node = selectedNode() ?: return
        saveHistory()
        node.text = text.ifBlank { node.text }
        node.inkStrokes.clear()
        saveDocument()
        invalidate()
    }

    fun setSelectedNodeInk(strokes: List<List<InkPoint>>) {
        val node = selectedNode() ?: return
        if (strokes.isEmpty()) return
        saveHistory()
        node.inkStrokes = strokes.map { it.toMutableList() }.toMutableList()
        node.text = ""
        saveDocument()
        invalidate()
    }

    fun setSelectedNodeSize(scaleValue: Float) {
        val node = selectedNode() ?: return
        saveHistory()
        val nextScale = scaleValue.coerceIn(0.65f, 2.2f)
        if (node.parentId == null) {
            val ids = descendantsOf(node.id).map { it.id }.toMutableSet()
            ids.add(node.id)
            nodes.filter { it.id in ids }.forEach { it.sizeScale = nextScale }
        } else {
            node.sizeScale = nextScale
        }
        saveDocument()
        invalidate()
    }

    fun selectedNodeSize(): Float = selectedNode()?.sizeScale ?: 1f

    fun adjustSelectedNodeSize(delta: Float) {
        val node = selectedNode() ?: return
        setSelectedNodeSize(node.sizeScale + delta)
    }

    fun adjustWholeMindMapSize(delta: Float) {
        val node = selectedNode() ?: return
        val root = rootOf(node) ?: node
        saveHistory()
        val nextScale = (root.sizeScale + delta).coerceIn(0.65f, 2.2f)
        val ids = descendantsOf(root.id).map { it.id }.toMutableSet()
        ids.add(root.id)
        nodes.filter { it.id in ids }.forEach { it.sizeScale = nextScale }
        saveDocument()
        invalidate()
    }

    fun adjustSelectedOnlySize(delta: Float) {
        val node = selectedNode() ?: return
        saveHistory()
        node.sizeScale = (node.sizeScale + delta).coerceIn(0.65f, 2.2f)
        saveDocument()
        invalidate()
    }

    fun deleteSelectedMindNode() {
        val node = selectedNode() ?: return
        saveHistory()
        val ids = descendantsOf(node.id).map { it.id }.toMutableSet()
        ids.add(node.id)
        nodes.removeAll { it.id in ids }
        arrows.removeAll { it.fromId in ids || it.toId in ids }
        selectedNodeId = null
        saveDocument()
        invalidate()
    }

    fun beginArrowFromSelected() {
        arrowStartNodeId = selectedNodeId
        selectedArrowIndex = null
    }

    fun reverseSelectedArrow() {
        val index = selectedArrowIndex ?: arrows.lastIndex.takeIf { it >= 0 } ?: return
        saveHistory()
        val arrow = arrows[index]
        val oldFrom = arrow.fromId
        arrow.fromId = arrow.toId
        arrow.toId = oldFrom
        selectedArrowIndex = index
        saveDocument()
        invalidate()
    }

    fun addChildToSelected() {
        val parent = selectedNode() ?: return
        saveHistory()
        val childCount = childrenOf(parent.id).size
        nodes.add(MindNode(
            nextNodeId++,
            parent.x + horizontalGap(parent),
            parent.y + (childCount - 0.5f) * verticalGap(parent),
            "새 가지",
            parent.id,
            Color.WHITE,
            sizeScale = parent.sizeScale
        ))
        selectedNodeId = nodes.last().id
        saveDocument()
        invalidate()
    }

    fun addRootMindNode() {
        saveHistory()
        val point = toWorld(width / 2f, height / 2f)
        val rootCount = nodes.count { it.parentId == null }
        nodes.add(MindNode(
            nextNodeId++,
            point.x,
            point.y + rootCount * 24f,
            "중심 생각",
            null,
            0xFF0F766E.toInt(),
            sizeScale = selectedNode()?.sizeScale ?: 1f
        ))
        selectedNodeId = nodes.last().id
        saveDocument()
        invalidate()
    }

    fun addSiblingToSelected() {
        val node = selectedNode() ?: return
        val parent = nodes.find { it.id == node.parentId } ?: return
        saveHistory()
        val siblingCount = childrenOf(parent.id).size
        nodes.add(MindNode(
            nextNodeId++,
            node.x,
            node.y + max(verticalGap(node), siblingCount * 18f),
            "새 가지",
            parent.id,
            Color.WHITE,
            sizeScale = node.sizeScale
        ))
        selectedNodeId = nodes.last().id
        saveDocument()
        invalidate()
    }

    fun clearAllHandwriting() {
        if (strokes.isEmpty()) return
        saveHistory()
        strokes.clear()
        saveDocument()
        invalidate()
    }

    fun setSelectedNodeColor(color: Int) {
        val node = selectedNode() ?: return
        saveHistory()
        node.color = color
        saveDocument()
        invalidate()
    }

    fun applyMindTheme(colors: IntArray) {
        if (nodes.isEmpty() || colors.isEmpty()) return
        saveHistory()
        val roots = nodes.filter { it.parentId == null }
        roots.forEachIndexed { rootIndex, root ->
            root.color = colors[rootIndex % colors.size]
            descendantsOf(root.id).forEachIndexed { index, node ->
                node.color = colors[(index + rootIndex + 1) % colors.size]
            }
        }
        saveDocument()
        invalidate()
    }

    fun applyMindTemplate(template: MindTemplate) {
        saveHistory()
        val center = toWorld(width / 2f, height / 2f)
        val baseScale = selectedNode()?.sizeScale ?: 1f
        nodes.clear()
        arrows.clear()
        selectedArrowIndex = null
        arrowStartNodeId = null
        when (template) {
            MindTemplate.BASIC -> createBasicTemplate(center, baseScale)
            MindTemplate.BRACE -> createBraceTemplate(center, baseScale)
            MindTemplate.ORG -> createOrgTemplate(center, baseScale)
            MindTemplate.TIMELINE -> createTimelineTemplate(center, baseScale)
            MindTemplate.FISHBONE -> createFishboneTemplate(center, baseScale)
            MindTemplate.TREE_TABLE -> createTreeTableTemplate(center, baseScale)
            MindTemplate.MATRIX -> createMatrixTemplate(center, baseScale)
        }
        selectedNodeId = nodes.firstOrNull()?.id
        saveDocument()
        invalidate()
    }

    fun toggleSelectedFold() {
        val node = selectedNode() ?: return
        if (childrenOf(node.id).isEmpty()) return
        saveHistory()
        node.collapsed = !node.collapsed
        saveDocument()
        invalidate()
    }

    fun autoLayoutMindMap(recordHistory: Boolean = true) {
        if (recordHistory) saveHistory()
        val roots = nodes.filter { it.parentId == null }
        var y = 0f
        roots.forEach {
            layoutSubtree(it, it.x, y)
            y += verticalGap(it) * 2.2f
        }
        saveDocument()
        invalidate()
    }

    private fun onDown(world: Point, event: MotionEvent) {
        touchStart = Point(event.x, event.y)
        touchMoved = false
        multiTouchActive = false
        when (mode) {
            CanvasMode.PEN -> {
                saveHistory()
                activeStroke = Stroke(mutableListOf(world), penColor, penSize, penKind).also { strokes.add(it) }
                scratchSound.start()
            }
            CanvasMode.ERASER -> eraseAt(world)
            CanvasMode.PAN -> lastPan = Point(event.x, event.y)
            CanvasMode.MIND -> {
                val hit = hitNode(world)
                if (hit != null) {
                    val now = SystemClock.uptimeMillis()
                    if (lastTapNodeId == hit.id && now - lastTapTime < 360L) {
                        selectedNodeId = hit.id
                        activeNode = null
                        onEditNodeRequested?.invoke()
                        lastTapTime = 0L
                        lastTapNodeId = null
                        return
                    }
                    lastTapNodeId = hit.id
                    lastTapTime = now
                    val startId = arrowStartNodeId
                    if (startId != null && startId != hit.id) {
                        saveHistory()
                        arrows.add(ArrowLink(startId, hit.id))
                        selectedArrowIndex = arrows.lastIndex
                        arrowStartNodeId = null
                        saveDocument()
                    } else {
                        selectedNodeId = hit.id
                        selectedArrowIndex = null
                        if (isNearNodeBorder(hit, world)) {
                            resizingNode = hit
                            resizeStartDistance = distanceFromNodeCenter(hit, world).coerceAtLeast(1f)
                            resizeStartScale = hit.sizeScale
                            activeNode = null
                        } else {
                            activeNode = hit
                        }
                    }
                } else {
                    activeNode = null
                    selectedArrowIndex = hitArrow(world)
                    if (selectedArrowIndex != null) selectedNodeId = null
                }
            }
        }
    }

    private fun onMove(world: Point, event: MotionEvent) {
        touchStart?.let {
            if (hypot(event.x - it.x, event.y - it.y) > 12f) {
                touchMoved = true
                if (mode == CanvasMode.MIND && activeNode == null) {
                    lastPan = Point(event.x, event.y)
                }
            }
        }
        when {
            activeStroke != null -> activeStroke?.points?.add(world)
            activeNode != null -> {
                activeNode?.x = world.x
                activeNode?.y = world.y
            }
            resizingNode != null -> {
                resizingNode?.let { node ->
                    val ratio = distanceFromNodeCenter(node, world) / resizeStartDistance
                    val nextScale = (resizeStartScale * ratio).coerceIn(0.65f, 2.2f)
                    if (node.parentId == null) {
                        val ids = descendantsOf(node.id).map { it.id }.toMutableSet()
                        ids.add(node.id)
                        nodes.filter { it.id in ids }.forEach { it.sizeScale = nextScale }
                    } else {
                        node.sizeScale = nextScale
                    }
                }
            }
            mode == CanvasMode.ERASER -> eraseAt(world)
            mode == CanvasMode.PAN && lastPan != null -> {
                val previous = lastPan ?: return
                val delta = screenDeltaToWorld(event.x - previous.x, event.y - previous.y)
                offsetX += delta.x
                offsetY += delta.y
                lastPan = Point(event.x, event.y)
            }
            mode == CanvasMode.MIND && lastPan != null -> {
                val previous = lastPan ?: return
                val delta = screenDeltaToWorld(event.x - previous.x, event.y - previous.y)
                offsetX += delta.x
                offsetY += delta.y
                lastPan = Point(event.x, event.y)
            }
        }
    }

    private fun onUp() {
        if (mode != CanvasMode.MIND || multiTouchActive || touchMoved) return
        if (hitNode(touchStart?.let { toWorld(it.x, it.y) } ?: return) != null) return
        if (nodes.isNotEmpty()) {
            selectedNodeId = null
            return
        }
        saveHistory()
        val point = touchStart?.let { toWorld(it.x, it.y) } ?: return
        nodes.add(MindNode(nextNodeId++, point.x, point.y, "중심 생각", null, 0xFF0F766E.toInt()))
        selectedNodeId = nodes.last().id
        saveDocument()
    }

    private fun handlePinch(event: MotionEvent) {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        val distance = hypot(dx, dy)
        val angle = atan2(dy, dx)
        val midpoint = Point((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
        if (lastPinchDistance == 0f) {
            lastPinchDistance = distance
            lastPinchAngle = angle
            pinchMidpoint = midpoint
            return
        }
        pinchMidpoint?.let {
            val delta = screenDeltaToWorld(midpoint.x - it.x, midpoint.y - it.y)
            offsetX += delta.x
            offsetY += delta.y
        }
        scale = (scale * (distance / lastPinchDistance)).coerceIn(0.2f, 4f)
        scaleToastUntil = SystemClock.uptimeMillis() + 900L
        if (!rotationLocked) {
            rotationDegrees += Math.toDegrees((angle - lastPinchAngle).toDouble()).toFloat()
        }
        lastPinchDistance = distance
        lastPinchAngle = angle
        pinchMidpoint = midpoint
        invalidate()
    }

    private fun drawGrid(canvas: Canvas) {
        paint.color = Color.argb(gridOpacity, 0x94, 0xA3, 0xB8)
        paint.strokeWidth = 1f
        val corners = listOf(
            toWorld(0f, 0f),
            toWorld(width.toFloat(), 0f),
            toWorld(0f, height.toFloat()),
            toWorld(width.toFloat(), height.toFloat())
        )
        val minX = corners.minOf { it.x } - 160f
        val maxX = corners.maxOf { it.x } + 160f
        val minY = corners.minOf { it.y } - 160f
        val maxY = corners.maxOf { it.y } + 160f
        val step = 42f
        var x = floor(minX / step) * step
        while (x <= ceil(maxX / step) * step) {
            val a = toScreen(Point(x, minY))
            val b = toScreen(Point(x, maxY))
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            x += step
        }
        var y = floor(minY / step) * step
        while (y <= ceil(maxY / step) * step) {
            val a = toScreen(Point(minX, y))
            val b = toScreen(Point(maxX, y))
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            y += step
        }
    }

    private fun drawCenterGuide(canvas: Canvas) {
        val origin = toScreen(Point(0f, 0f))
        paint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.8f
        paint.color = 0xB80F766E.toInt()
        canvas.drawLine(0f, origin.y, width.toFloat(), origin.y, paint)
        canvas.drawLine(origin.x, 0f, origin.x, height.toFloat(), paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = 0xCC0F766E.toInt()
        canvas.drawCircle(origin.x, origin.y, 6f, paint)
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.size < 2) return
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = stroke.color
        paint.alpha = when (stroke.kind) {
            PenKind.PENCIL -> 115
            PenKind.FOUNTAIN -> 225
            PenKind.BRUSH -> 190
            PenKind.BALLPOINT -> 255
        }
        paint.strokeWidth = when (stroke.kind) {
            PenKind.PENCIL -> stroke.size * scale * 0.7f
            PenKind.FOUNTAIN -> stroke.size * scale * 1.15f
            PenKind.BRUSH -> stroke.size * scale * 1.9f
            PenKind.BALLPOINT -> stroke.size * scale
        }
        val path = Path()
        stroke.points.forEachIndexed { index, point ->
            val s = toScreen(point)
            if (index == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
        }
        canvas.drawPath(path, paint)
        if (stroke.kind == PenKind.PENCIL) {
            paint.alpha = 70
            paint.strokeWidth = max(1f, stroke.size * scale * 0.24f)
            listOf(-2f, 2f).forEach { offset ->
                val grain = Path()
                stroke.points.forEachIndexed { index, point ->
                    val s = toScreen(Point(point.x, point.y + offset))
                    if (index == 0) grain.moveTo(s.x, s.y) else grain.lineTo(s.x, s.y)
                }
                canvas.drawPath(grain, paint)
            }
        }
        paint.alpha = 255
    }

    private fun drawMindMap(canvas: Canvas) {
        val visible = visibleNodes()
        val visibleIds = visible.map { it.id }.toSet()
        drawCustomArrows(canvas, visibleIds, drawLine = true, drawHead = false)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0x990F766E.toInt()
        visible.forEach { node ->
            val parent = nodes.find { it.id == node.parentId }
            if (parent != null && parent.id in visibleIds) {
                val a = toScreen(parent)
                val b = toScreen(node)
                canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            }
        }
        visible.forEach { node ->
            val s = toScreen(node)
            val nodeWidth = nodeWidth(node) * scale
            val nodeHeight = nodeHeight(node) * scale
            val rect = RectF(s.x - nodeWidth / 2f, s.y - nodeHeight / 2f, s.x + nodeWidth / 2f, s.y + nodeHeight / 2f)
            canvas.save()
            canvas.rotate(rotationDegrees, s.x, s.y)
            paint.style = Paint.Style.FILL
            paint.color = node.color
            canvas.drawRoundRect(rect, 10f, 10f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (node.id == selectedNodeId) 4f else 2f
            paint.color = if (node.id == selectedNodeId) 0xFF132238.toInt() else 0xFF0F766E.toInt()
            canvas.drawRoundRect(rect, 10f, 10f, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (node.color == Color.WHITE) 0xFF132238.toInt() else Color.WHITE
            if (node.inkStrokes.isNotEmpty()) {
                drawNodeInk(canvas, rect, node.inkStrokes)
            } else {
                drawNodeText(canvas, rect, node.text)
            }
            if (node.collapsed && childrenOf(node.id).isNotEmpty()) {
                paint.color = if (node.color == Color.WHITE) 0xFF0F766E.toInt() else Color.WHITE
                canvas.drawCircle(s.x + 56f * scale, s.y + 17f * scale, 4f * scale, paint)
            }
            canvas.restore()
        }
        drawCustomArrows(canvas, visibleIds, drawLine = false, drawHead = true)
    }

    private fun drawCustomArrows(canvas: Canvas, visibleIds: Set<Int>, drawLine: Boolean, drawHead: Boolean) {
        arrows.forEachIndexed { index, arrow ->
            val from = nodes.find { it.id == arrow.fromId }
            val to = nodes.find { it.id == arrow.toId }
            if (from == null || to == null || from.id !in visibleIds || to.id !in visibleIds) return@forEachIndexed
            val fromCenter = toScreen(from)
            val toCenter = toScreen(to)
            val a = arrowEdgePoint(fromCenter, toCenter, from, 6f * scale)
            val b = arrowEdgePoint(toCenter, fromCenter, to, 14f * scale)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (index == selectedArrowIndex) 5f else 3f
            paint.color = if (index == selectedArrowIndex) 0xFFDC2626.toInt() else 0xCC132238.toInt()
            if (drawLine) {
                paint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
                canvas.drawPath(arrowCurve(a, b), paint)
            }
            paint.pathEffect = null
            if (drawHead) {
                drawArrowHead(canvas, curveArrowFrom(a, b), b)
            }
        }
        paint.pathEffect = null
    }

    private fun arrowEdgePoint(center: Point, toward: Point, node: MindNode, extra: Float): Point {
        val dx = toward.x - center.x
        val dy = toward.y - center.y
        val distance = hypot(dx, dy).coerceAtLeast(1f)
        val unitX = dx / distance
        val unitY = dy / distance
        val halfWidth = nodeWidth(node) * scale / 2f
        val halfHeight = nodeHeight(node) * scale / 2f
        val hitX = if (abs(unitX) > 0.001f) halfWidth / abs(unitX) else Float.MAX_VALUE
        val hitY = if (abs(unitY) > 0.001f) halfHeight / abs(unitY) else Float.MAX_VALUE
        val edgeDistance = min(hitX, hitY) + extra
        return Point(center.x + unitX * edgeDistance, center.y + unitY * edgeDistance)
    }

    private fun arrowCurve(from: Point, to: Point): Path {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val distance = hypot(dx, dy).coerceAtLeast(1f)
        val bend = min(120f, distance * 0.22f)
        val normalX = -dy / distance
        val normalY = dx / distance
        val c1 = Point(from.x + dx * 0.34f + normalX * bend, from.y + dy * 0.34f + normalY * bend)
        val c2 = Point(from.x + dx * 0.66f + normalX * bend, from.y + dy * 0.66f + normalY * bend)
        return Path().apply {
            moveTo(from.x, from.y)
            cubicTo(c1.x, c1.y, c2.x, c2.y, to.x, to.y)
        }
    }

    private fun curveArrowFrom(from: Point, to: Point): Point {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val distance = hypot(dx, dy).coerceAtLeast(1f)
        val bend = min(120f, distance * 0.22f)
        val normalX = -dy / distance
        val normalY = dx / distance
        return Point(from.x + dx * 0.82f + normalX * bend * 0.45f, from.y + dy * 0.82f + normalY * bend * 0.45f)
    }

    private fun drawArrowHead(canvas: Canvas, from: Point, to: Point) {
        val angle = atan2(to.y - from.y, to.x - from.x)
        val length = 34f
        val wing = 0.72f
        val p1 = Point(
            to.x - cos(angle - wing) * length,
            to.y - sin(angle - wing) * length
        )
        val p2 = Point(
            to.x - cos(angle + wing) * length,
            to.y - sin(angle + wing) * length
        )
        val path = Path()
        path.moveTo(to.x, to.y)
        path.lineTo(p1.x, p1.y)
        path.lineTo(p2.x, p2.y)
        path.close()
        val oldStyle = paint.style
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = 2f
        canvas.drawPath(path, paint)
        paint.style = oldStyle
    }

    private fun drawEraserPreview(canvas: Canvas) {
        val point = eraserPoint ?: return
        val screen = toScreen(point)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0x990F766E.toInt()
        canvas.drawCircle(screen.x, screen.y, eraserSize * scale, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0x220F766E
        canvas.drawCircle(screen.x, screen.y, eraserSize * scale, paint)
    }

    private fun drawNavigationOverlay(canvas: Canvas) {
        val boxWidth = min(width * 0.82f, 430f)
        val boxHeight = min(height * 0.32f, 300f)
        val margin = 12f
        val left = width - boxWidth - margin
        val top = height - boxHeight - margin
        val panel = RectF(left, top, left + boxWidth, top + boxHeight)

        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = 0x9CFFFFFF.toInt()
        canvas.drawRoundRect(panel, 16f, 16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.color = 0x66000000
        canvas.drawRoundRect(panel, 16f, 16f, paint)

        paint.style = Paint.Style.FILL
        paint.color = 0xFF132238.toInt()
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = 22f
        canvas.drawText("${(scale * 100f).toInt()}%", panel.right - 14f, panel.top + 32f, paint)

        val content = contentBounds() ?: return
        val viewport = viewportWorldBounds()
        content.union(viewport)
        content.inset(-80f, -80f)

        val map = RectF(panel.left + 14f, panel.top + 44f, panel.right - 14f, panel.bottom - 14f)
        paint.style = Paint.Style.FILL
        paint.color = 0x220F766E
        canvas.drawRoundRect(map, 10f, 10f, paint)

        fun mapX(x: Float): Float = map.left + ((x - content.left) / content.width().coerceAtLeast(1f)) * map.width()
        fun mapY(y: Float): Float = map.top + ((y - content.top) / content.height().coerceAtLeast(1f)) * map.height()

        paint.color = 0xCC0F766E.toInt()
        strokes.forEach { stroke ->
            stroke.points.forEach { point ->
                canvas.drawCircle(mapX(point.x), mapY(point.y), 2.4f, paint)
            }
        }
        nodes.forEach { node ->
            val cx = mapX(node.x)
            val cy = mapY(node.y)
            canvas.drawRoundRect(RectF(cx - 7f, cy - 4.5f, cx + 7f, cy + 4.5f), 3f, 3f, paint)
        }

        val viewRect = RectF(mapX(viewport.left), mapY(viewport.top), mapX(viewport.right), mapY(viewport.bottom))
        viewRect.sort()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.2f
        paint.color = 0xFFDC2626.toInt()
        canvas.drawRoundRect(viewRect, 6f, 6f, paint)
    }

    private fun drawScaleToast(canvas: Canvas) {
        val remaining = scaleToastUntil - SystemClock.uptimeMillis()
        if (remaining <= 0L) return
        val alpha = (remaining / 900f).coerceIn(0f, 1f)
        val text = "${(scale * 100f).toInt()}%"
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((190 * alpha).toInt(), 19, 34, 56)
        val panel = RectF(width / 2f - 82f, height / 2f - 42f, width / 2f + 82f, height / 2f + 42f)
        canvas.drawRoundRect(panel, 24f, 24f, paint)
        paint.color = Color.argb((255 * alpha).toInt(), 255, 255, 255)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 34f
        canvas.drawText(text, width / 2f, height / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        postInvalidateDelayed(16L)
    }

    private fun contentBounds(): RectF? {
        var bounds: RectF? = null
        fun include(x: Float, y: Float) {
            val current = bounds
            if (current == null) {
                bounds = RectF(x, y, x, y)
            } else {
                current.union(x, y)
            }
        }
        strokes.forEach { stroke -> stroke.points.forEach { include(it.x, it.y) } }
        nodes.forEach { node ->
            bounds = (bounds ?: RectF(node.x, node.y, node.x, node.y)).apply {
                union(RectF(
                    node.x - nodeWidth(node) / 2f,
                    node.y - nodeHeight(node) / 2f,
                    node.x + nodeWidth(node) / 2f,
                    node.y + nodeHeight(node) / 2f
                ))
            }
        }
        return bounds
    }

    private fun viewportWorldBounds(): RectF {
        val corners = listOf(
            toWorld(0f, 0f),
            toWorld(width.toFloat(), 0f),
            toWorld(0f, height.toFloat()),
            toWorld(width.toFloat(), height.toFloat())
        )
        return RectF(
            corners.minOf { it.x },
            corners.minOf { it.y },
            corners.maxOf { it.x },
            corners.maxOf { it.y }
        )
    }

    private fun eraseAt(world: Point) {
        eraserPoint = world
        val hit = hitNode(world)
        if (hit != null) {
            deleteMindNode(hit)
            return
        }
        saveHistory()
        strokes.removeAll { stroke -> stroke.points.any { hypot(it.x - world.x, it.y - world.y) < eraserSize / scale } }
        saveDocument()
    }

    private fun deleteMindNode(node: MindNode) {
        saveHistory()
        val ids = descendantsOf(node.id).map { it.id }.toMutableSet()
        ids.add(node.id)
        nodes.removeAll { it.id in ids }
        arrows.removeAll { it.fromId in ids || it.toId in ids }
        if (selectedNodeId in ids) selectedNodeId = null
        saveDocument()
        invalidate()
    }

    private fun addTemplateNode(x: Float, y: Float, text: String, parentId: Int?, color: Int, scaleValue: Float): MindNode {
        return MindNode(nextNodeId++, x, y, text, parentId, color, sizeScale = scaleValue).also { nodes.add(it) }
    }

    private fun createBasicTemplate(center: Point, scaleValue: Float) {
        val palette = intArrayOf(0xFF0F766E.toInt(), 0xFF60A5FA.toInt(), 0xFFF97316.toInt(), 0xFFEC4899.toInt(), 0xFF8B5CF6.toInt())
        val root = addTemplateNode(center.x, center.y, "중심 생각", null, palette[0], scaleValue)
        listOf("아이디어", "계획", "자료", "실행").forEachIndexed { index, label ->
            val y = center.y + (index - 1.5f) * verticalGap(root)
            addTemplateNode(center.x + horizontalGap(root), y, label, root.id, palette[(index + 1) % palette.size], scaleValue)
        }
    }

    private fun createBraceTemplate(center: Point, scaleValue: Float) {
        val palette = intArrayOf(0xFF2563EB.toInt(), 0xFF93C5FD.toInt(), 0xFFE5E7EB.toInt(), 0xFFFCA5A5.toInt())
        val root = addTemplateNode(center.x - 80f, center.y, "주제", null, palette[0], scaleValue)
        repeat(3) { i ->
            val branch = addTemplateNode(center.x + horizontalGap(root), center.y + (i - 1) * 92f, "분류 ${i + 1}", root.id, palette[(i + 1) % palette.size], scaleValue)
            repeat(2) { j ->
                addTemplateNode(branch.x + horizontalGap(branch) * 0.86f, branch.y + (j - 0.5f) * 56f, "항목", branch.id, Color.WHITE, scaleValue * 0.86f)
            }
        }
    }

    private fun createOrgTemplate(center: Point, scaleValue: Float) {
        val palette = intArrayOf(0xFF6B7280.toInt(), 0xFF9CA3AF.toInt(), 0xFFE5E7EB.toInt())
        val root = addTemplateNode(center.x, center.y - 150f, "상위", null, palette[0], scaleValue)
        repeat(3) { i ->
            val manager = addTemplateNode(center.x + (i - 1) * 180f, center.y, "담당 ${i + 1}", root.id, palette[1], scaleValue * 0.9f)
            repeat(2) { j ->
                addTemplateNode(manager.x + (j - 0.5f) * 92f, center.y + 120f, "작업", manager.id, palette[2], scaleValue * 0.78f)
            }
        }
    }

    private fun createTimelineTemplate(center: Point, scaleValue: Float) {
        val palette = intArrayOf(0xFFFCA5A5.toInt(), 0xFFF97316.toInt(), 0xFF60A5FA.toInt(), 0xFF4F46E5.toInt())
        var previous: MindNode? = null
        repeat(4) { i ->
            val node = addTemplateNode(center.x + (i - 1.5f) * 190f, center.y, "${i + 1}단계", if (i == 0) null else previous?.id, palette[i], scaleValue)
            previous = node
        }
    }

    private fun createFishboneTemplate(center: Point, scaleValue: Float) {
        val palette = intArrayOf(0xFF334155.toInt(), 0xFF94A3B8.toInt(), 0xFFE2E8F0.toInt())
        val root = addTemplateNode(center.x + 220f, center.y, "결과", null, palette[0], scaleValue)
        repeat(4) { i ->
            val x = center.x - 160f + i * 120f
            val y = center.y + if (i % 2 == 0) -120f else 120f
            addTemplateNode(x, y, "원인 ${i + 1}", root.id, palette[1], scaleValue * 0.86f)
        }
    }

    private fun createTreeTableTemplate(center: Point, scaleValue: Float) {
        val palette = intArrayOf(0xFF0F766E.toInt(), 0xFF99F6E4.toInt(), 0xFFE5E7EB.toInt())
        val root = addTemplateNode(center.x, center.y - 150f, "표 제목", null, palette[0], scaleValue)
        repeat(3) { row ->
            repeat(3) { col ->
                addTemplateNode(center.x + (col - 1) * 150f, center.y + row * 82f, "칸", root.id, palette[(row + col + 1) % palette.size], scaleValue * 0.75f)
            }
        }
    }

    private fun createMatrixTemplate(center: Point, scaleValue: Float) {
        val palette = intArrayOf(0xFF6366F1.toInt(), 0xFFFDE68A.toInt(), 0xFFBBF7D0.toInt(), 0xFFF0ABFC.toInt())
        val root = addTemplateNode(center.x, center.y - 170f, "비교 주제", null, palette[0], scaleValue)
        repeat(2) { row ->
            repeat(3) { col ->
                addTemplateNode(center.x + (col - 1) * 160f, center.y + row * 105f, "항목", root.id, palette[(row * 3 + col + 1) % palette.size], scaleValue * 0.82f)
            }
        }
    }

    private fun saveHistory() {
        history.add(Triple(
            strokes.map { it.copy(points = it.points.toMutableList()) },
            nodes.map { it.copy(inkStrokes = it.inkStrokes.map { stroke -> stroke.toMutableList() }.toMutableList()) },
            arrows.map { it.copy() }
        ))
        if (history.size > 60) history.removeAt(0)
    }

    private fun saveDocument() {
        val root = JSONObject()
        root.put("nextNodeId", nextNodeId)
        root.put("scale", scale)
        root.put("offsetX", offsetX)
        root.put("offsetY", offsetY)
        root.put("rotationDegrees", rotationDegrees)
        root.put("rotationLocked", rotationLocked)
        root.put("gridOpacity", gridOpacity)
        root.put("strokes", JSONArray().apply {
            strokes.forEach { stroke ->
                put(JSONObject().apply {
                    put("color", stroke.color)
                    put("size", stroke.size)
                    put("kind", stroke.kind.name)
                    put("points", JSONArray().apply {
                        stroke.points.forEach { put(JSONObject().put("x", it.x).put("y", it.y)) }
                    })
                })
            }
        })
        root.put("nodes", JSONArray().apply {
            nodes.forEach { node ->
                put(JSONObject().apply {
                    put("id", node.id)
                    put("x", node.x)
                    put("y", node.y)
                    put("text", node.text)
                    put("parentId", node.parentId ?: JSONObject.NULL)
                    put("color", node.color)
                    put("collapsed", node.collapsed)
                    put("sizeScale", node.sizeScale)
                    put("inkStrokes", JSONArray().apply {
                        node.inkStrokes.forEach { stroke ->
                            put(JSONArray().apply {
                                stroke.forEach { put(JSONObject().put("x", it.x).put("y", it.y)) }
                            })
                        }
                    })
                })
            }
        })
        root.put("arrows", JSONArray().apply {
            arrows.forEach { put(JSONObject().put("fromId", it.fromId).put("toId", it.toId)) }
        })
        prefs.edit().putString("document", root.toString()).apply()
    }

    private fun loadDocument() {
        val raw = prefs.getString("document", null) ?: return
        runCatching {
            val root = JSONObject(raw)
            nextNodeId = root.optInt("nextNodeId", 1)
            scale = root.optDouble("scale", 1.0).toFloat()
            offsetX = root.optDouble("offsetX", 0.0).toFloat()
            offsetY = root.optDouble("offsetY", 0.0).toFloat()
            rotationDegrees = root.optDouble("rotationDegrees", 0.0).toFloat()
            rotationLocked = root.optBoolean("rotationLocked", false)
            gridOpacity = root.optInt("gridOpacity", 72).coerceIn(18, 150)
            strokes.clear()
            val strokeArray = root.optJSONArray("strokes") ?: JSONArray()
            for (i in 0 until strokeArray.length()) {
                val item = strokeArray.getJSONObject(i)
                val points = mutableListOf<Point>()
                val pointArray = item.optJSONArray("points") ?: JSONArray()
                for (p in 0 until pointArray.length()) {
                    val point = pointArray.getJSONObject(p)
                    points.add(Point(point.optDouble("x").toFloat(), point.optDouble("y").toFloat()))
                }
                val kind = runCatching { PenKind.valueOf(item.optString("kind", PenKind.BALLPOINT.name)) }.getOrDefault(PenKind.BALLPOINT)
                strokes.add(Stroke(points, item.optInt("color"), item.optDouble("size").toFloat(), kind))
            }
            nodes.clear()
            val nodeArray = root.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until nodeArray.length()) {
                val item = nodeArray.getJSONObject(i)
                val ink = mutableListOf<MutableList<InkPoint>>()
                val inkArray = item.optJSONArray("inkStrokes") ?: JSONArray()
                for (s in 0 until inkArray.length()) {
                    val strokeJson = inkArray.getJSONArray(s)
                    val stroke = mutableListOf<InkPoint>()
                    for (p in 0 until strokeJson.length()) {
                        val point = strokeJson.getJSONObject(p)
                        stroke.add(InkPoint(point.optDouble("x").toFloat(), point.optDouble("y").toFloat()))
                    }
                    ink.add(stroke)
                }
                nodes.add(MindNode(
                    id = item.optInt("id"),
                    x = item.optDouble("x").toFloat(),
                    y = item.optDouble("y").toFloat(),
                    text = item.optString("text"),
                    parentId = if (item.isNull("parentId")) null else item.optInt("parentId"),
                    color = item.optInt("color", Color.WHITE),
                    collapsed = item.optBoolean("collapsed"),
                    sizeScale = item.optDouble("sizeScale", 1.0).toFloat(),
                    inkStrokes = ink
                ))
            }
            arrows.clear()
            val arrowArray = root.optJSONArray("arrows") ?: JSONArray()
            for (i in 0 until arrowArray.length()) {
                val item = arrowArray.getJSONObject(i)
                arrows.add(ArrowLink(item.optInt("fromId"), item.optInt("toId")))
            }
        }
    }

    private fun drawNodeInk(canvas: Canvas, rect: RectF, ink: List<List<InkPoint>>) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 3f * scale * ((rect.width() / scale) / 140f).coerceIn(0.65f, 2.2f)
        val darkBox = paint.color != 0xFF132238.toInt()
        paint.color = if (darkBox) Color.WHITE else 0xFF132238.toInt()
        val padX = rect.width() * 0.05f
        val padY = rect.height() * 0.05f
        ink.forEach { stroke ->
            if (stroke.size < 2) return@forEach
            val path = Path()
            stroke.forEachIndexed { index, p ->
                val x = rect.left + padX + p.x * (rect.width() - padX * 2f)
                val y = rect.top + padY + p.y * (rect.height() - padY * 2f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawNodeText(canvas: Canvas, rect: RectF, text: String) {
        val value = text.ifBlank { " " }
        val padX = rect.width() * 0.05f
        val padY = rect.height() * 0.05f
        val maxWidth = (rect.width() - padX * 2f).coerceAtLeast(1f)
        val maxHeight = (rect.height() - padY * 2f).coerceAtLeast(1f)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = maxHeight
        val measured = paint.measureText(value).coerceAtLeast(1f)
        paint.textSize = min(maxHeight * 0.82f, maxHeight * maxWidth / measured).coerceAtLeast(8f)
        val baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(value, rect.centerX(), baseline, paint)
    }

    private fun hitArrow(world: Point): Int? {
        var bestIndex: Int? = null
        var bestDistance = Float.MAX_VALUE
        arrows.forEachIndexed { index, arrow ->
            val from = nodes.find { it.id == arrow.fromId } ?: return@forEachIndexed
            val to = nodes.find { it.id == arrow.toId } ?: return@forEachIndexed
            val distance = distanceToSegment(world, Point(from.x, from.y), Point(to.x, to.y))
            if (distance < 28f && distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun distanceToSegment(point: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return hypot(point.x - a.x, point.y - a.y)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        val projection = Point(a.x + t * dx, a.y + t * dy)
        return hypot(point.x - projection.x, point.y - projection.y)
    }

    private fun hitNode(world: Point): MindNode? = visibleNodes().lastOrNull {
        abs(it.x - world.x) <= nodeWidth(it) / 2f && abs(it.y - world.y) <= nodeHeight(it) / 2f
    }

    private fun nodeWidth(node: MindNode): Float = 140f * node.sizeScale

    private fun nodeHeight(node: MindNode): Float = 50f * node.sizeScale

    private fun selectedNode(): MindNode? = nodes.find { it.id == selectedNodeId }
    private fun childrenOf(id: Int): List<MindNode> = nodes.filter { it.parentId == id }
    private fun descendantsOf(id: Int): List<MindNode> = childrenOf(id).flatMap { listOf(it) + descendantsOf(it.id) }
    private fun visibleNodes(): List<MindNode> {
        val hidden = nodes.filter { it.collapsed }.flatMap { descendantsOf(it.id) }.map { it.id }.toSet()
        return nodes.filter { it.id !in hidden }
    }

    private fun layoutSubtree(node: MindNode, x: Float, y: Float) {
        node.x = x
        node.y = y
        val kids = childrenOf(node.id)
        if (kids.isEmpty() || node.collapsed) return
        val spacing = kids.maxOfOrNull { verticalGap(it) }?.let { max(it, verticalGap(node)) } ?: verticalGap(node)
        val total = (kids.size - 1) * spacing
        kids.forEachIndexed { index, child -> layoutSubtree(child, x + horizontalGap(node), y - total / 2f + index * spacing) }
    }

    private fun rootOf(node: MindNode): MindNode? {
        var current = node
        while (current.parentId != null) {
            current = nodes.find { it.id == current.parentId } ?: return current
        }
        return current
    }

    private fun horizontalGap(node: MindNode): Float = 110f + nodeWidth(node) * 0.95f

    private fun verticalGap(node: MindNode): Float = 42f + nodeHeight(node) * 1.35f

    private fun distanceFromNodeCenter(node: MindNode, point: Point): Float = hypot(point.x - node.x, point.y - node.y)

    private fun isNearNodeBorder(node: MindNode, point: Point): Boolean {
        val halfW = nodeWidth(node) / 2f
        val halfH = nodeHeight(node) / 2f
        val dx = abs(point.x - node.x)
        val dy = abs(point.y - node.y)
        val inside = dx <= halfW && dy <= halfH
        val nearX = abs(dx - halfW) <= 18f
        val nearY = abs(dy - halfH) <= 18f
        return inside && (nearX || nearY)
    }

    private fun screenDeltaToWorld(dx: Float, dy: Float): Point {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return Point((dx * c + dy * s) / scale, (-dx * s + dy * c) / scale)
    }

    private fun toWorld(x: Float, y: Float): Point {
        val sx = x - width / 2f
        val sy = y - height / 2f
        val delta = screenDeltaToWorld(sx, sy)
        return Point(delta.x - offsetX, delta.y - offsetY)
    }

    private fun toScreen(point: Point): Point {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val x = (point.x + offsetX) * scale
        val y = (point.y + offsetY) * scale
        return Point(x * c - y * s + width / 2f, x * s + y * c + height / 2f)
    }

    override fun onDetachedFromWindow() {
        scratchSound.release()
        super.onDetachedFromWindow()
    }

    private fun toScreen(node: MindNode) = toScreen(Point(node.x, node.y))
}

private class ScratchSoundPlayer {
    private val sampleRate = 22050
    private val buffer = ShortArray(sampleRate / 2)
    private var track: AudioTrack? = null
    private var playing = false

    init {
        var low = 0f
        var lower = 0f
        for (i in buffer.indices) {
            val raw = Random.nextFloat() * 2f - 1f
            low = low * 0.82f + raw * 0.18f
            lower = lower * 0.94f + low * 0.06f
            val paperPulse = if ((i / 1300) % 3 == 0) 0.72f else 0.38f
            val softGrain = sin(i * 0.045f) * 0.16f + sin(i * 0.017f) * 0.10f
            buffer[i] = ((lower * paperPulse + softGrain) * 1250).toInt().toShort()
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .apply {
                write(buffer, 0, buffer.size)
                setLoopPoints(0, buffer.size, -1)
                setVolume(0.12f)
            }
    }

    fun start() {
        if (playing) return
        track?.play()
        playing = true
    }

    fun stop() {
        if (!playing) return
        track?.pause()
        track?.flush()
        playing = false
    }

    fun release() {
        track?.release()
        track = null
        playing = false
    }
}
