package com.oea.launcher.workspace

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.oea.launcher.R
import com.oea.launcher.ui.OeaType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One Home/drawer/dock/folder icon drawn natively (icon, label, badge, frozen
 * and locked states, folder and app-pair previews) with launcher-grade touch
 * handling: tap, long-press and drag without any system drag-and-drop.
 */
class OeaTileView(context: Context) : View(context) {
    enum class Kind { APP, SHORTCUT, FOLDER, PAIR }

    interface Listener {
        fun onTileClick(tile: OeaTileView)
        fun onTileLongPress(tile: OeaTileView)
        fun onTileDragStart(tile: OeaTileView, rawX: Float, rawY: Float)
        fun onTileDragMove(tile: OeaTileView, rawX: Float, rawY: Float)
        fun onTileDragEnd(tile: OeaTileView, rawX: Float, rawY: Float, cancelled: Boolean)
    }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = OeaType.bold
        textAlign = Paint.Align.CENTER
    }
    private val folderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconRect = Rect()
    private val rectF = RectF()
    private val frozenFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    var kind = Kind.APP
    var key: String = ""
    var payload: Any? = null
    var listener: Listener? = null

    var label: CharSequence = ""
        set(value) { field = value; contentDescription = value; invalidate() }
    var icon: Drawable? = null
        set(value) { field = value; invalidate() }
    var previews: List<Drawable?> = emptyList()
        set(value) { field = value; invalidate() }
    var smallBadge: Drawable? = null
        set(value) { field = value; invalidate() }
    var badgeCount = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    var badgeStyle = "dot"
    var frozen = false
        set(value) { field = value; invalidate() }
    var locked = false
        set(value) { field = value; invalidate() }
    var showLabel = true
    var iconSizePx = (54 * density).toInt()
    var labelColor = Color.WHITE
    var labelShadow = 0
    var labelSizeSp = 12f
    var labelLines = 1
    var accent = Color.rgb(138, 180, 248)
    var onAccent = Color.BLACK
    var folderColor = Color.argb(70, 255, 255, 255)
    var badgeRing = Color.TRANSPARENT
    var folderCornerFraction = 0.3f
    var stateBadgeBg = Color.rgb(32, 34, 40)
    var stateBadgeTint = Color.WHITE
    var dragging = false
        set(value) { field = value; alpha = if (value) 0.25f else 1f }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downRawX = 0f
    private var downRawY = 0f
    /** Movement cancels long-press; a tap is cancelled only for a deliberate gesture. */
    private var movedBeyondSlop = false
    private var tapCancelled = false
    private var longPressed = false
    private var dragStarted = false
    private val longPressRunnable = Runnable {
        longPressed = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        animatePress(false)
        listener?.onTileLongPress(this)
    }

    init {
        isClickable = true
        isFocusable = true
        isHapticFeedbackEnabled = true
    }

    fun iconBounds(out: Rect) {
        out.set(iconRect)
    }

    override fun onDraw(canvas: Canvas) {
        val labelSize = labelSizeSp * scaledDensity
        labelPaint.textSize = labelSize
        labelPaint.typeface = OeaType.regular
        labelPaint.color = labelColor
        if (labelShadow != 0) labelPaint.setShadowLayer(3f * density, 0f, 1f * density, labelShadow)
        else labelPaint.clearShadowLayer()
        val gap = (6 * density).toInt()
        val lineHeight = (labelPaint.fontMetrics.descent - labelPaint.fontMetrics.ascent)
        val wantLabel = showLabel && label.isNotEmpty()
        val labelBlock = if (wantLabel) (lineHeight * labelLines).toInt() + gap else 0
        // Shrink the icon when the cell is smaller than the preferred size.
        val size = iconSizePx
            .coerceAtMost(width - (4 * density).toInt())
            .coerceAtMost(height - labelBlock - (4 * density).toInt())
            .coerceAtLeast((16 * density).toInt())
        val contentHeight = size + labelBlock
        val top = ((height - contentHeight) / 2).coerceAtLeast(0)
        val left = (width - size) / 2
        iconRect.set(left, top, left + size, top + size)

        when (kind) {
            Kind.FOLDER -> drawFolder(canvas)
            Kind.PAIR -> drawPair(canvas)
            else -> drawIcon(canvas, icon, iconRect)
        }
        if (kind == Kind.SHORTCUT) smallBadge?.let { badge ->
            val s = (size * 0.38f).toInt()
            badge.setBounds(iconRect.right - s + s / 6, iconRect.bottom - s + s / 6, iconRect.right + s / 6, iconRect.bottom + s / 6)
            badge.draw(canvas)
        }
        if (frozen) drawStateBadge(canvas, R.drawable.oic_ac_unit)
        else if (locked) drawStateBadge(canvas, R.drawable.oic_lock)
        if (badgeCount > 0 && badgeStyle != "off") drawBadge(canvas)

        if (wantLabel) {
            val avail = (width - 6 * density).coerceAtLeast(10f)
            val baseline = iconRect.bottom + gap - labelPaint.fontMetrics.ascent
            if (labelLines <= 1) {
                val text = TextUtils.ellipsize(label, labelPaint, avail, TextUtils.TruncateAt.END)
                val w = labelPaint.measureText(text, 0, text.length)
                canvas.drawText(text, 0, text.length, (width - w) / 2f, baseline, labelPaint)
            } else {
                drawTwoLines(canvas, avail, baseline, lineHeight)
            }
        }
    }

    private fun drawTwoLines(canvas: Canvas, avail: Float, baseline: Float, lineHeight: Float) {
        val full = label.toString()
        var cut = labelPaint.breakText(full, true, avail, null)
        if (cut >= full.length) {
            val w = labelPaint.measureText(full)
            canvas.drawText(full, (width - w) / 2f, baseline, labelPaint)
            return
        }
        val space = full.lastIndexOf(' ', cut)
        if (space > 0) cut = space
        val first = full.substring(0, cut).trim()
        val rest = TextUtils.ellipsize(full.substring(cut).trim(), labelPaint, avail, TextUtils.TruncateAt.END).toString()
        canvas.drawText(first, (width - labelPaint.measureText(first)) / 2f, baseline, labelPaint)
        canvas.drawText(rest, (width - labelPaint.measureText(rest)) / 2f, baseline + lineHeight, labelPaint)
    }

    private fun drawIcon(canvas: Canvas, drawable: Drawable?, bounds: Rect) {
        val d = drawable ?: return
        d.bounds = bounds
        if (frozen) {
            d.colorFilter = frozenFilter
            d.alpha = 150
            d.draw(canvas)
            d.colorFilter = null
            d.alpha = 255
        } else {
            d.draw(canvas)
        }
    }

    private fun drawFolder(canvas: Canvas) {
        folderPaint.color = folderColor
        rectF.set(iconRect)
        val radius = iconRect.width() * folderCornerFraction
        canvas.drawRoundRect(rectF, radius, radius, folderPaint)
        val inner = iconRect.width() * 0.13f
        val cell = (iconRect.width() - inner * 2 - iconRect.width() * 0.06f) / 2f
        val gapPx = iconRect.width() * 0.06f
        previews.take(4).forEachIndexed { i, d ->
            val cx = iconRect.left + inner + (i % 2) * (cell + gapPx)
            val cy = iconRect.top + inner + (i / 2) * (cell + gapPx)
            val r = Rect(cx.toInt(), cy.toInt(), (cx + cell).toInt(), (cy + cell).toInt())
            drawIcon(canvas, d, r)
        }
    }

    private fun drawPair(canvas: Canvas) {
        folderPaint.color = folderColor
        rectF.set(iconRect)
        val radius = iconRect.width() * folderCornerFraction
        canvas.drawRoundRect(rectF, radius, radius, folderPaint)
        val s = (iconRect.width() * 0.56f).toInt()
        val pad = (iconRect.width() * 0.08f).toInt()
        previews.getOrNull(0)?.let { drawIcon(canvas, it, Rect(iconRect.left + pad, iconRect.top + pad, iconRect.left + pad + s, iconRect.top + pad + s)) }
        previews.getOrNull(1)?.let { drawIcon(canvas, it, Rect(iconRect.right - pad - s, iconRect.bottom - pad - s, iconRect.right - pad, iconRect.bottom - pad)) }
    }

    private fun drawStateBadge(canvas: Canvas, res: Int) {
        val r = iconRect.width() * 0.19f
        val cx = iconRect.right - r * 0.55f
        val cy = iconRect.bottom - r * 0.55f
        badgePaint.color = stateBadgeBg
        canvas.drawCircle(cx, cy, r, badgePaint)
        val d = context.getDrawable(res)?.mutate() ?: return
        d.setTint(stateBadgeTint)
        val inner = (r * 0.62f).toInt()
        d.setBounds((cx - inner).toInt(), (cy - inner).toInt(), (cx + inner).toInt(), (cy + inner).toInt())
        d.draw(canvas)
    }

    private fun drawBadge(canvas: Canvas) {
        if (badgeStyle == "count") {
            val text = if (badgeCount > 99) "99+" else badgeCount.toString()
            badgeTextPaint.textSize = 10.5f * scaledDensity
            badgeTextPaint.color = onAccent
            val h = 18f * density
            val w = max(h, badgeTextPaint.measureText(text) + 10f * density)
            val right = iconRect.right + 4f * density
            val top = iconRect.top - 4f * density
            rectF.set(right - w, top, right, top + h)
            if (badgeRing != Color.TRANSPARENT) {
                badgePaint.color = badgeRing
                val ring = 1.5f * density
                canvas.drawRoundRect(RectF(rectF.left - ring, rectF.top - ring, rectF.right + ring, rectF.bottom + ring), h, h, badgePaint)
            }
            badgePaint.color = accent
            canvas.drawRoundRect(rectF, h / 2f, h / 2f, badgePaint)
            val fm = badgeTextPaint.fontMetrics
            canvas.drawText(text, rectF.centerX(), rectF.centerY() - (fm.ascent + fm.descent) / 2f, badgeTextPaint)
        } else {
            val r = max(4.5f * density, iconRect.width() * 0.09f)
            val cx = iconRect.right - r * 0.9f
            val cy = iconRect.top + r * 0.9f
            if (badgeRing != Color.TRANSPARENT) {
                badgePaint.color = badgeRing
                canvas.drawCircle(cx, cy, r + 1.5f * density, badgePaint)
            }
            badgePaint.color = accent
            canvas.drawCircle(cx, cy, r, badgePaint)
        }
    }

    private fun animatePress(down: Boolean) {
        animate().scaleX(if (down) 0.9f else 1f).scaleY(if (down) 0.9f else 1f)
            .setDuration(if (down) 90L else 220L)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                movedBeyondSlop = false
                tapCancelled = false
                longPressed = false
                dragStarted = false
                animatePress(true)
                if (listener != null) postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.rawX - downRawX)
                val dy = abs(event.rawY - downRawY)
                val dist = max(dx, dy)
                if (longPressed) {
                    if (!dragStarted && dist > touchSlop) {
                        dragStarted = true
                        listener?.onTileDragStart(this, event.rawX, event.rawY)
                    }
                    if (dragStarted) listener?.onTileDragMove(this, event.rawX, event.rawY)
                    return true
                }
                // Stop long-press as soon as the finger moves, but do not discard
                // an ordinary tap for tiny hand drift. Horizontal cancellation
                // uses the same sensitivity threshold as the parent pager.
                if (dist > touchSlop && !movedBeyondSlop) {
                    movedBeyondSlop = true
                    removeCallbacks(longPressRunnable)
                    animatePress(false)
                }
                val swipeSlop = touchSlop *
                    com.oea.launcher.data.OeaDataStore.get(context).gestureSlopScale()
                val deliberateHorizontalSwipe = dx > swipeSlop && dx > dy * 1.1f
                val deliberateVerticalGesture = dy > touchSlop * 1.5f && dy > dx * 1.1f
                if (deliberateHorizontalSwipe || deliberateVerticalGesture) tapCancelled = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                animatePress(false)
                when {
                    dragStarted -> listener?.onTileDragEnd(this, event.rawX, event.rawY, false)
                    longPressed -> Unit
                    !tapCancelled -> performClick()
                }
                longPressed = false
                movedBeyondSlop = false
                tapCancelled = false
                dragStarted = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                animatePress(false)
                if (dragStarted) listener?.onTileDragEnd(this, event.rawX, event.rawY, true)
                longPressed = false
                movedBeyondSlop = false
                tapCancelled = false
                dragStarted = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onTileClick(this)
        return true
    }

    override fun performLongClick(): Boolean {
        listener?.onTileLongPress(this)
        return true
    }
}

/**
 * Fixed rows × columns grid. Children declare their cell with [LayoutParams];
 * widgets span a rectangle of cells. Empty space reports taps, double-taps
 * and long-presses for Home gestures.
 */
class OeaCellLayout(context: Context) : ViewGroup(context) {
    class LayoutParams(var cellX: Int, var cellY: Int, var spanX: Int = 1, var spanY: Int = 1) :
        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    var cols = 4
        set(value) { field = value.coerceAtLeast(1); requestLayout() }
    var rows = 5
        set(value) { field = value.coerceAtLeast(1); requestLayout() }
    var page = 0
    var highlightCell = -1
        set(value) { if (field != value) { field = value; invalidate() } }
    var highlightColor = Color.argb(60, 255, 255, 255)
    /** Drop preview for a widget, in cells (left/top inclusive, right/bottom exclusive). */
    var highlightRect: Rect? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    var emptyGestures: GestureDetector? = null
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    fun cellWidth(): Int = ((width - paddingLeft - paddingRight) / cols).coerceAtLeast(1)
    fun cellHeight(): Int = ((height - paddingTop - paddingBottom) / rows).coerceAtLeast(1)

    /** Cell index under local ([x], [y]), or -1 outside the grid. */
    fun cellAt(x: Float, y: Float): Int {
        val cx = ((x - paddingLeft) / cellWidth()).toInt()
        val cy = ((y - paddingTop) / cellHeight()).toInt()
        if (x < paddingLeft || y < paddingTop || cx !in 0 until cols || cy !in 0 until rows) return -1
        return cy * cols + cx
    }

    fun childAtCell(cell: Int): View? {
        val cx = cell % cols
        val cy = cell / cols
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            if (cx >= lp.cellX && cx < lp.cellX + lp.spanX && cy >= lp.cellY && cy < lp.cellY + lp.spanY) return child
        }
        return null
    }

    /** Distance in px from local point to the centre of [cell]. */
    fun distanceToCellCenter(x: Float, y: Float, cell: Int): Float {
        val cx = paddingLeft + (cell % cols + 0.5f) * cellWidth()
        val cy = paddingTop + (cell / cols + 0.5f) * cellHeight()
        return kotlin.math.hypot(x - cx, y - cy)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = ((w - paddingLeft - paddingRight) / cols).coerceAtLeast(1)
        val ch = ((h - paddingTop - paddingBottom) / rows).coerceAtLeast(1)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as? LayoutParams ?: LayoutParams(0, 0)
            child.measure(
                MeasureSpec.makeMeasureSpec(cw * lp.spanX, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch * lp.spanY, MeasureSpec.EXACTLY)
)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellWidth()
        val ch = cellHeight()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as? LayoutParams ?: continue
            val left = paddingLeft + lp.cellX * cw
            val top = paddingTop + lp.cellY * ch
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(0, 0)
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(0, 0)

    override fun onDraw(canvas: Canvas) {
        val cw = cellWidth()
        val ch = cellHeight()
        val inset = min(cw, ch) * 0.08f
        highlightPaint.color = highlightColor
        highlightRect?.let { cells ->
            rect.set(paddingLeft + cells.left * cw + inset, paddingTop + cells.top * ch + inset,
                paddingLeft + cells.right * cw - inset, paddingTop + cells.bottom * ch - inset)
            val radius = min(cw, ch) * 0.24f
            canvas.drawRoundRect(rect, radius, radius, highlightPaint)
            return
        }
        if (highlightCell < 0) return
        val cx = highlightCell % cols
        val cy = highlightCell / cols
        rect.set(paddingLeft + cx * cw + inset, paddingTop + cy * ch + inset,
            paddingLeft + (cx + 1) * cw - inset, paddingTop + (cy + 1) * ch - inset)
        val radius = min(rect.width(), rect.height()) * 0.28f
        canvas.drawRoundRect(rect, radius, radius, highlightPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val detector = emptyGestures ?: return super.onTouchEvent(event)
        detector.onTouchEvent(event)
        return true
    }
}

/**
 * Horizontal pager with launcher physics: follows the finger, flings one page,
 * snaps with a decelerating curve and reports fractional scroll for the page
 * indicator. Adjacent pages fade slightly while swiping.
 */
class OeaPagedView(context: Context) : ViewGroup(context) {
    private val scroller = OverScroller(context, DecelerateInterpolator(1.7f))
    private var velocityTracker: VelocityTracker? = null
    private val config = ViewConfiguration.get(context)
    private val touchSlop = config.scaledTouchSlop
    private val maxFling = config.scaledMaximumFlingVelocity

    /** The user's gesture sensitivity scales both the drag start and the fling needed to turn a page. */
    private val slop get() = touchSlop * com.oea.launcher.data.OeaDataStore.get(context).gestureSlopScale()
    private val minFling get() = config.scaledMinimumFlingVelocity * 3 * com.oea.launcher.data.OeaDataStore.get(context).gestureSlopScale()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var settling = false

    var currentPage = 0
        private set
    var onScroll: ((position: Float) -> Unit)? = null
    var onPageChanged: ((page: Int) -> Unit)? = null
    var swipeEnabled = true

    fun pageCount() = childCount

    fun pageAt(index: Int): View? = getChildAt(index)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        }
        if (changed && !dragging && scroller.isFinished) scrollTo(currentPage * w, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scroller.forceFinished(true)
        scrollTo(currentPage * w, 0)
    }

    fun setPageImmediately(page: Int) {
        currentPage = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        scroller.forceFinished(true)
        if (width > 0) scrollTo(currentPage * width, 0)
        onScroll?.invoke(currentPage.toFloat())
    }

    fun snapToPage(page: Int, durationOverride: Int = -1) {
        val target = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val changed = target != currentPage
        currentPage = target
        val dx = target * width - scrollX
        val duration = if (durationOverride > 0) durationOverride else (240 + abs(dx) * 220 / width.coerceAtLeast(1)).coerceAtMost(520)
        scroller.forceFinished(true)
        scroller.startScroll(scrollX, 0, dx, 0, duration)
        settling = true
        postInvalidateOnAnimation()
        if (changed) onPageChanged?.invoke(target)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        } else if (settling) {
            settling = false
        }
    }

    /**
     * Page transition: "classic" (fade and slight shrink), "liquid" (pages
     * squash and stretch while their icons trail behind like liquid), "cube",
     * "stack", "zoom", "fade" or "none". Every effect returns to an identity
     * transform when a page is settled, so drag and drop and widget hit
     * testing are never affected.
     */
    var effect: String = "classic"
        set(value) {
            if (field == value) return
            field = value
            resetTransforms()
            applyEffect(scrollX)
        }

    private var scrollDirection = 1

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val w = width.coerceAtLeast(1)
        if (l != oldl) scrollDirection = if (l > oldl) 1 else -1
        onScroll?.invoke(l / w.toFloat())
        applyEffect(l)
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        child?.let { applyEffect(scrollX) }
    }

    private fun applyEffect(scroll: Int) {
        val w = width
        if (w <= 0) return
        val h = height.toFloat()
        val density = resources.displayMetrics.density
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val offset = ((i * w - scroll) / w.toFloat()).coerceIn(-1f, 1f)
            val a = abs(offset)
            when (effect) {
                "none" -> identity(child)
                "fade" -> { identity(child); child.alpha = 1f - a }
                "zoom" -> {
                    identity(child)
                    child.pivotX = w / 2f
                    child.pivotY = h / 2f
                    child.scaleX = 1f - 0.2f * a
                    child.scaleY = 1f - 0.2f * a
                    child.alpha = 1f - 0.7f * a
                }
                "stack" -> {
                    identity(child)
                    if (offset < 0f) {
                        // The page being left stays put and sinks while the next one slides over it.
                        child.translationX = -offset * w
                        child.pivotX = w / 2f
                        child.pivotY = h / 2f
                        child.scaleX = 1f - 0.14f * a
                        child.scaleY = 1f - 0.14f * a
                        child.alpha = 1f - a
                    }
                }
                "cube" -> {
                    identity(child)
                    child.cameraDistance = 7000f * density
                    child.pivotX = if (offset < 0f) w.toFloat() else 0f
                    child.pivotY = h / 2f
                    child.rotationY = -72f * offset
                    child.alpha = 1f - 0.35f * a
                }
                "liquid" -> liquid(child, offset, a, w, h, density)
                "depth" -> depth(child, offset, a, w, h, density)
                "paper" -> paper(child, offset, a, w, h)
                else -> {
                    identity(child)
                    child.alpha = 1f - 0.45f * a
                    child.scaleX = 1f - 0.05f * a
                    child.scaleY = 1f - 0.05f * a
                }
            }
        }
    }

    /**
     * Depth: the pages are plates at different distances from the eye.
     *
     * The page being left shrinks away from the viewer and slides under the one
     * arriving, which grows past it and settles flat. Nothing rotates, because a
     * flat rotation is the "just a slide" look this mode is meant to avoid: the
     * movement is straight towards or away from the viewer, which is what makes a
     * stack of layers read as having depth rather than as a wallpaper change.
     */
    private fun depth(child: View, offset: Float, a: Float, w: Int, h: Float, density: Float) {
        identity(child)
        child.cameraDistance = 4000f * density
        child.pivotX = w / 2f
        child.pivotY = h * 0.45f
        val near = kotlin.math.sin(Math.PI * a).toFloat()
        if (offset <= 0f) {
            // Leaving: falls back, dims and shrinks slightly.
            child.scaleX = 1f - 0.16f * a
            child.scaleY = 1f - 0.16f * a
            child.translationY = h * 0.02f * a
            child.alpha = 1f - 0.55f * a
        } else {
            // Arriving: comes forward from behind the departing page.
            child.scaleX = 0.84f + 0.16f * (1f - a)
            child.scaleY = 0.84f + 0.16f * (1f - a)
            child.translationY = -h * 0.01f * near
            child.alpha = 1f - 0.35f * a
        }
    }

    /**
     * Paper: the next sheet slides in over the last one, with a soft shadow along
     * its leading edge — the way a page turns in a book rather than a carousel.
     * The departing page barely moves, so the impression is paper being laid down.
     */
    private fun paper(child: View, offset: Float, a: Float, w: Int, h: Float) {
        identity(child)
        // A sheet being laid down barely moves or fades: it is the shadow along the
        // edge and the slight tilt of the arriving page that read as paper. Keeping
        // the movement this small is also what keeps Ink calm rather than busy.
        child.pivotX = if (offset < 0f) w / 2f else 0f
        child.pivotY = h / 2f
        child.rotationY = if (offset < 0f) 0f else 3.5f * a
        child.scaleX = 1f - 0.015f * a
        child.scaleY = 1f - 0.015f * a
        child.alpha = if (offset < 0f) 1f - 0.18f * a else 1f
    }

    /**
     * Liquid: the page narrows and swells like a drop in motion, and every
     * icon trails behind by its distance from the leading edge, so a page
     * flows rather than slides. All offsets scale with sin(pi * progress):
     * zero at rest, strongest mid-swipe.
     */
    private fun liquid(child: View, offset: Float, a: Float, w: Int, h: Float, density: Float) {
        val wave = kotlin.math.sin(Math.PI * a).toFloat()
        child.translationX = 0f
        child.pivotX = w / 2f
        child.pivotY = h / 2f
        // A shallow barrel turn rather than a flat slide: the page curves away
        // from the finger, which is what sells a physical sheet of glass.
        child.rotationY = -5.5f * offset
        child.cameraDistance = 9000f * density
        child.scaleX = 1f - 0.16f * a
        child.scaleY = 1f + 0.06f * wave
        child.alpha = 1f - 0.55f * a * a
        val page = child as? ViewGroup ?: return
        for (j in 0 until page.childCount) {
            val tile = page.getChildAt(j)
            val cx = ((tile.left + tile.width / 2f) / w).coerceIn(0f, 1f)
            val trailing = if (scrollDirection > 0) cx else 1f - cx
            // Icons lag behind their page by how far they sit from the leading
            // edge, drift down and up across the width, and rock a couple of
            // degrees as they catch up: water, not a slide.
            tile.translationX = scrollDirection * w * 0.12f * trailing * wave
            tile.translationY = -8f * density * wave * kotlin.math.sin(Math.PI * cx).toFloat()
            tile.rotation = scrollDirection * 2.4f * trailing * wave
            tile.pivotX = tile.width / 2f
            tile.pivotY = tile.height / 2f
            val s = 1f - 0.06f * wave * trailing
            tile.scaleX = s * (1f + 0.03f * wave * (1f - trailing))
            tile.scaleY = s
        }
    }

    private fun identity(child: View) {
        child.alpha = 1f
        child.scaleX = 1f
        child.scaleY = 1f
        child.translationX = 0f
        child.translationY = 0f
        child.rotation = 0f
        child.rotationY = 0f
    }

    private fun resetTransforms() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            identity(child)
            (child as? ViewGroup)?.let { page ->
                for (j in 0 until page.childCount) {
                    page.getChildAt(j).apply {
                        translationX = 0f
                        translationY = 0f
                        rotation = 0f
                        scaleX = 1f
                        scaleY = 1f
                    }
                }
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled || childCount <= 1) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                dragging = !scroller.isFinished && abs(scroller.finalX - scroller.currX) > slop
                if (dragging) scroller.forceFinished(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (!dragging && dx > slop && dx > dy * 1.1f) {
                    dragging = true
                    lastX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    velocityTracker?.recycle()
                    velocityTracker = null
                }
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                lastX = ev.x
                if (!scroller.isFinished) scroller.forceFinished(true)
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    if (abs(ev.x - downX) > slop) dragging = true else return true
                }
                val dx = lastX - ev.x
                lastX = ev.x
                val maxScroll = (childCount - 1) * width
                val next = scrollX + dx
                // Rubber-band at the first and last page.
                val resisted = if (next < 0 || next > maxScroll) dx * 0.35f else dx
                scrollBy(resisted.roundToInt(), 0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = velocityTracker
                tracker?.computeCurrentVelocity(1000, maxFling.toFloat())
                val vx = tracker?.xVelocity ?: 0f
                val w = width.coerceAtLeast(1)
                val exact = scrollX / w.toFloat()
                val target = when {
                    vx < -minFling -> kotlin.math.floor(exact).toInt() + 1
                    vx > minFling -> kotlin.math.ceil(exact).toInt() - 1
                    else -> exact.roundToInt()
                }
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                snapToPage(target)
            }
        }
        return true
    }
}

/** Page dots: the current page stretches into a pill that glides between dots. */
class OeaPageIndicator(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val rect = RectF()
    var pages = 1
        set(value) { field = value.coerceAtLeast(1); invalidate() }
    var position = 0f
        set(value) { field = value; invalidate() }
    var color = Color.WHITE
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (pages <= 1) return
        val dot = 6f * density
        val gap = 7f * density
        val pill = 18f * density
        val total = (pages - 1) * (dot + gap) + pill
        var x = (width - total) / 2f
        val cy = height / 2f
        val pos = position.coerceIn(0f, (pages - 1).toFloat())
        for (i in 0 until pages) {
            val weight = (1f - abs(pos - i)).coerceIn(0f, 1f)
            val w = dot + (pill - dot) * weight
            paint.color = color
            paint.alpha = (90 + 165 * weight).toInt()
            rect.set(x, cy - dot / 2f, x + w, cy + dot / 2f)
            canvas.drawRoundRect(rect, dot / 2f, dot / 2f, paint)
            x += w + gap
        }
    }
}
