package com.oea.launcher.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.oea.launcher.R

/**
 * The layer every OEA mini-app builds on, on top of [OeaKit].
 *
 * `OeaKit` is a settings kit: beautiful rows, groups, sheets and toggles — and no
 * idea what a gallery, a player or a camera needs. That gap is exactly why the
 * mini-apps looked like debug screens bolted onto a settings page: 300
 * hand-built ImageViews in a `ScrollView`, a "search" button that opened a
 * dialog, and empty white boxes where a state should have been.
 *
 * Everything here is shared by more than one app, so an OEA app is recognisable
 * whichever icon you tapped:
 *  * virtualised [RecyclerView] grids, lists and rails with OEA's spacing;
 *  * a search field that filters as you type, with a clear button;
 *  * a filter chip bar that owns its selection and reports changes;
 *  * real empty, loading and error states;
 *  * media tiles with duration badges, favourite stars and selection checks;
 *  * an action sheet with icons, a selection bar, and an in-window message bar.
 */
class OeaAppKit(context: Context, palette: OeaPalette) : OeaKit(context, palette) {

    /** A root that can hold a recycler, a floating bar and a message at once. */
    fun overlay(): FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(palette.background)
        clipChildren = false
    }

    // -------------------------------------------------------------------- lists

    /**
     * A virtualised grid. Every OEA media screen shows through this: only the
     * visible tiles exist, rows are recycled with their pictures, and scrolling
     * feels like a system app because it is the same widget.
     */
    fun grid(spanCount: Int, edgePx: Int = dp(4), innerPx: Int = dp(3)): RecyclerView {
        val span = spanCount.coerceAtLeast(1)
        val decoration = OeaSpacingDecoration(innerPx, span)
        return RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, span).apply {
                spanSizeLookup = OeaSpanLookup(span)
            }
            addItemDecoration(decoration)
            tag = decoration
            setPadding(edgePx, edgePx, edgePx, dp(104))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            itemAnimator = DefaultItemAnimator().apply {
                addDuration = 130L
                removeDuration = 90L
                moveDuration = 160L
                changeDuration = 110L
            }
            setItemViewCacheSize(14)
        }
    }

    /** Changes a grid's column count in place, keeping the scroll position. */
    fun setSpan(recycler: RecyclerView, span: Int) {
        val first = (recycler.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
        (recycler.layoutManager as? GridLayoutManager)?.spanCount = span.coerceAtLeast(1)
        (recycler.tag as? OeaSpacingDecoration)?.setSpanCount(span.coerceAtLeast(1))
        recycler.invalidateItemDecorations()
        if (first > 0) (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(first, dp(4))
    }

    /** A virtualised vertical list with OEA's card spacing. */
    fun list(spacingPx: Int = dp(10)): RecyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        setPadding(dp(14), dp(4), dp(14), dp(104))
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addItemDecoration(OeaListSpacingDecoration(spacingPx))
        itemAnimator = DefaultItemAnimator().apply {
            addDuration = 130L
            removeDuration = 90L
        }
    }

    /** A horizontal rail of cards (albums, continue watching, playlists). */
    fun rail(spacingPx: Int = dp(10)): RecyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false).apply {
            initialPrefetchItemCount = 4
        }
        setPadding(dp(14), 0, dp(14), 0)
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        addItemDecoration(OeaRailSpacingDecoration(spacingPx))
    }

    /** Calls [action] once the list has stopped scrolling — prefetch, headers. */
    fun onIdle(recycler: RecyclerView, action: () -> Unit) {
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) action()
            }
        })
    }

    /** The visible range of a grid, for prefetching and fast-scroll labels. */
    fun visibleRange(recycler: RecyclerView): Pair<Int, Int> {
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return 0 to 0
        return manager.findFirstVisibleItemPosition().coerceAtLeast(0) to
            manager.findLastVisibleItemPosition().coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ app bars

    inner class AppBar(
        val root: LinearLayout,
        val title: TextView,
        val subtitle: TextView,
        val actions: LinearLayout,
        val lead: View?
    ) {
        fun setSubtitle(value: String) {
            subtitle.text = value
            subtitle.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
        }
    }

    /**
     * The bar of a full-bleed screen: a title, a live count under it, and round
     * actions on the right. It pads itself for the status bar and the cutout, so a
     * notch never eats a button.
     */
    fun appBar(title: String, subtitle: String, onBack: (() -> Unit)? = null): AppBar {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val big = text(title, 28f, palette.text, OeaType.regular).apply {
            letterSpacing = -0.02f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = text(subtitle, 12f, palette.textSecondary, OeaType.medium).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(3), dp(2), dp(3), 0)
        }
        val lead: View? = onBack?.let {
            circleIconButton(R.drawable.oic_arrow_back, "Back", palette.card, palette.text) { it() }
                .apply { layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)) }
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (lead != null) top.addView(lead)
        top.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(big, LinearLayout.LayoutParams(-1, -2))
                addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = if (lead != null) dp(11) else dp(4) }
        )
        top.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        bar.addView(top)
        bar.setOnApplyWindowInsetsListener { v, insets ->
            val bars = OeaInsets.bars(insets)
            v.setPadding(v.paddingLeft, bars[1] + dp(8), v.paddingRight, v.paddingBottom)
            insets
        }
        return AppBar(bar, big, sub, actions, lead)
    }

    /** Adds a round action to an app bar, in order. */
    fun AppBar.addAction(iconRes: Int, description: String, active: Boolean = false, onToggle: ((Boolean) -> Unit)? = null, onClick: () -> Unit): View {
        if (onToggle != null) {
            val toggle = OeaIconToggle(context).apply {
                circleSizeDp = 40
                icon = icon(iconRes, if (active) palette.onAccent else palette.text)
                selectedBg = palette.accent
                selectedTint = palette.onAccent
                idleBg = palette.card
                tint = palette.text
                this.selected = active
                contentDescription = description
                isEnabled = true
                onChanged = { onToggle(selected) }
            }
            actions.addView(toggle, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
            return toggle
        }
        val button = circleIconButton(iconRes, description, palette.card, palette.text) { onClick() }
        actions.addView(button, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        return button
    }

    /** Swaps an app-bar button's icon without rebuilding the bar (a view model of one). */
    fun rebindIcon(button: View, iconRes: Int, tint: Int = palette.text) {
        when (button) {
            is OeaIconToggle -> {
                button.icon = icon(iconRes, if (button.selected) button.selectedTint else tint)
            }

            is ImageView -> button.setImageDrawable(icon(iconRes, tint))
            is ViewGroup -> (0 until button.childCount).asSequence()
                .map { button.getChildAt(it) }
                .filterIsInstance<ImageView>()
                .firstOrNull()
                ?.setImageDrawable(icon(iconRes, tint))
        }
    }

    // -------------------------------------------------------------------- search

    inner class SearchField(val root: FrameLayout, val editor: EditText) {
        fun setText(value: String) {
            editor.setText(value)
            editor.setSelection(editor.text.length)
        }

        fun query(): String = editor.text.toString().trim()

        fun focus() {
            root.post {
                editor.requestFocus()
                context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        fun close() {
            editor.clearFocus()
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(editor.windowToken, 0)
        }
    }

    /** A search field that filters as you type; [onSubmit] fires on the IME action. */
    fun searchField(hint: String, initial: String = "", onQuery: (String) -> Unit, onSubmit: (String) -> Unit = {}): SearchField {
        val box = FrameLayout(context).apply { background = rounded(palette.card, 22, palette.stroke, 1) }
        val magnifier = ImageView(context).apply {
            setImageDrawable(icon(R.drawable.oic_search, palette.textTertiary))
            layoutParams = FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                marginStart = dp(15)
            }
        }
        val editor = EditText(context).apply {
            this.hint = hint
            setText(initial)
            setTextColor(palette.text)
            setHintTextColor(palette.textTertiary)
            textSize = 15f
            typeface = OeaType.regular
            background = null
            setPadding(dp(43), dp(11), dp(40), dp(11))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val clear = ImageView(context).apply {
            setImageDrawable(icon(R.drawable.oic_close, palette.textSecondary))
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER_VERTICAL or Gravity.END).apply {
                marginEnd = dp(8)
            }
            background = ripple(oval(palette.cardHigh), 14)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(5), dp(5), dp(5), dp(5))
            visibility = View.GONE
            contentDescription = "Clear search"
            setOnClickListener {
                editor.setText("")
                onQuery("")
            }
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }

            override fun afterTextChanged(s: Editable?) {
                onQuery(s?.toString().orEmpty())
            }
        })
        editor.setOnEditorActionListener { _, actionId, event ->
            val submit = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (submit) {
                onSubmit(editor.text.toString().trim())
                editor.clearFocus()
                true
            } else false
        }
        box.addView(editor, FrameLayout.LayoutParams(-1, -2))
        box.addView(magnifier)
        box.addView(clear)
        return SearchField(box, editor)
    }

    // -------------------------------------------------------------- filter chips

    /** A scrolling chip bar that owns its selected id and reports changes. */
    inner class ChipBar(val root: View, val row: LinearLayout) {
        var options: List<Pair<String, String>> = emptyList()
            private set
        var selected: String = ""
            private set
        var onPick: ((String) -> Unit)? = null

        /** Replaces the chips, keeping [selected] when it still exists. */
        fun setOptions(options: List<Pair<String, String>>, preferred: String? = null) {
            this.options = options
            val wanted = preferred ?: selected
            selected = if (options.any { it.first == wanted }) wanted else options.firstOrNull()?.first ?: ""
            render()
        }

        fun select(id: String) {
            if (selected == id) return
            selected = id
            render()
        }

        private fun render() {
            row.removeAllViews()
            options.forEach { (id, label) ->
                row.addView(
                    chip(label, id == selected) {
                        if (selected == id) return@chip
                        selected = id
                        render()
                        onPick?.invoke(id)
                    },
                    LinearLayout.LayoutParams(-2, dp(34)).apply {
                        marginEnd = dp(6)
                    }
                )
            }
        }
    }

    fun chipBar(): ChipBar {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        return ChipBar(hscroll(row), row)
    }

    // --------------------------------------------------------------------- states

    /**
     * The card a screen shows when it has nothing to draw. A gallery that opens to
     * a blank page reads as broken even when it is only unauthorised, so every
     * empty state names what is missing and offers the one control that fixes it.
     */
    fun emptyState(
        iconRes: Int,
        title: String,
        body: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(26), dp(42), dp(26), dp(42))
        background = rounded(palette.card, 30, palette.stroke, 1)
        val halo = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.withAlpha(palette.accent, if (palette.dark) 0x33 else 0x1F))
            }
        }
        halo.addView(
            ImageView(context).apply { setImageDrawable(icon(iconRes, palette.accent)) },
            FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
        )
        addView(halo, LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(
            text(title, 18f, palette.text, OeaType.medium).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(15), 0, 0)
                setLineSpacing(dpf(2f), 1f)
            }
        )
        addView(
            text(body, 13.5f, palette.textSecondary).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(7), dp(6), 0)
                setLineSpacing(dpf(3f), 1f)
            }
        )
        if (actionLabel != null && onAction != null) {
            addView(
                primaryButton(actionLabel) { onAction() },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) }
            )
        }
    }

    /** The placeholders a list draws while its very first data loads. */
    fun skeletonGrid(rows: Int = 6, columns: Int = 3, tileHeightPx: Int = dp(104)): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            repeat(rows) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                repeat(columns) { index ->
                    row.addView(
                        OeaShimmer(context),
                        LinearLayout.LayoutParams(0, tileHeightPx, 1f).apply {
                            marginStart = if (index == 0) 0 else dp(5)
                        }
                    )
                }
                addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            }
        }

    /** A quiet busy row: a ring and a label, pulsing until it is removed. */
    fun progressRow(label: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val ring = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), palette.accent)
            }
        }
        addView(ring, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
        addView(text(label, 13.5f, palette.textSecondary), LinearLayout.LayoutParams(0, -2, 1f))
        val pulse = android.animation.ValueAnimator.ofFloat(1f, 0.45f)
        pulse.duration = 700L
        pulse.repeatCount = android.animation.ValueAnimator.INFINITE
        pulse.repeatMode = android.animation.ValueAnimator.REVERSE
        // The row fades, not the animator: inside the update listener `this` is the
        // ValueAnimator, which has no alpha of its own.
        pulse.addUpdateListener { pulseValue -> alpha = pulseValue.animatedValue as Float }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (pulse.isRunning.not()) pulse.start()
            }

            override fun onViewDetachedFromWindow(v: View) {
                pulse.cancel()
            }
        })
    }

    // ------------------------------------------------------------------ messages

    /**
     * An in-window message: a toast that belongs to the app, sits above its bars,
     * can carry an action, and replaces itself instead of stacking five copies.
     */
    inner class Snack(private val host: FrameLayout) {
        private var current: LinearLayout? = null

        fun show(message: String, tone: Tone = Tone.INFO, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
            val color = when (tone) {
                Tone.INFO -> palette.accent
                Tone.SUCCESS -> palette.success
                Tone.WARNING -> palette.warning
                Tone.DANGER -> palette.danger
            }
            current?.let { host.removeView(it) }
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dpf(18f)
                    setColor(if (palette.dark) Color.argb(0xF2, 26, 29, 36) else Color.argb(0xF6, 252, 252, 253))
                    setStroke(dp(1), palette.withAlpha(color, 0x55))
                }
                elevation = dpf(6f)
            }
            bar.addView(
                View(context).apply { background = rounded(color, 3) },
                LinearLayout.LayoutParams(dp(3), dp(22)).apply { marginEnd = dp(12) }
            )
            bar.addView(
                text(message, 13.5f, palette.text, OeaType.medium).apply {
                    maxLines = 3
                    setLineSpacing(dpf(1.5f), 1f)
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            if (actionLabel != null) {
                bar.addView(
                    text(actionLabel, 13.5f, color, OeaType.medium).apply {
                        setPadding(dp(12), dp(6), dp(4), dp(6))
                        isClickable = true
                        setOnClickListener {
                            onAction?.invoke()
                            dismiss()
                        }
                    }
                )
            }
            host.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(dp(14), 0, dp(14), dp(16))
            })
            bar.alpha = 0f
            bar.translationY = dpf(16f)
            bar.animate().alpha(1f).translationY(0f).setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f)).start()
            current = bar
            bar.postDelayed({ if (current === bar) dismiss() }, if (actionLabel != null) 5200L else 3200L)
        }

        fun dismiss() {
            val bar = current ?: return
            current = null
            bar.animate().alpha(0f).translationY(dpf(12f)).setDuration(160L)
                .withEndAction { host.removeView(bar) }
                .start()
        }
    }

    fun snack(host: FrameLayout): Snack = Snack(host)

    // -------------------------------------------------------------- content bits

    /** A media tile: cover, duration badge, favourite star, check, progress bar. */
    inner class CoverTile(
        val root: FrameLayout,
        val image: ImageView,
        val badge: TextView,
        val star: ImageView,
        val check: FrameLayout,
        val tint: View,
        val progress: View
    ) {
        var selected: Boolean = false

        /**
         * Puts the tile in or out of the selection. Not called setSelected: a property
         * of that name already compiles to a setter with the same JVM signature, and two
         * of them in one class is a declaration clash rather than an overload.
         */
        fun markSelected(on: Boolean) {
            selected = on
            check.visibility = if (on) View.VISIBLE else View.GONE
            check.background = oval(if (on) palette.accent else Color.TRANSPARENT, if (on) palette.accent else Color.WHITE, 2)
            tint.setBackgroundColor(if (on) palette.withAlpha(palette.accent, 0x4D) else Color.TRANSPARENT)
        }

        fun showBadge(text: String?) {
            badge.text = text.orEmpty()
            badge.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        fun showStar(on: Boolean) {
            star.visibility = if (on) View.VISIBLE else View.GONE
        }

        /** Fraction watched, painted as a rule along the bottom of the tile. */
        fun showProgress(fraction: Float) {
            if (fraction <= 0.001f) {
                progress.visibility = View.GONE
                return
            }
            progress.visibility = View.VISIBLE
            val width = root.width.takeIf { it > 0 } ?: (root.layoutParams.width.takeIf { it > 0 } ?: dp(120))
            progress.translationX = 0f
            progress.scaleX = 1f
            (progress.layoutParams as? FrameLayout.LayoutParams)?.width = (width * fraction.coerceIn(0f, 1f)).toInt()
            progress.requestLayout()
        }
    }

    fun coverTile(tileHeightPx: Int, cornerPx: Int): CoverTile {
        val root = FrameLayout(context).apply {
            clipToOutline = true
            background = rounded(palette.cardHigh, (cornerPx / context.resources.displayMetrics.density).toInt())
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerPx.toFloat())
                }
            }
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(palette.cardHigh)
        }
        root.addView(image, FrameLayout.LayoutParams(-1, tileHeightPx))
        val scrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(0xB0, 0, 0, 0))
            )
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val badge = text("", 11f, Color.WHITE, OeaType.medium).apply {
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = rounded(Color.argb(0x99, 0, 0, 0), 8)
            visibility = View.GONE
        }
        root.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(5), dp(5))
        })
        val star = ImageView(context).apply {
            setImageDrawable(icon(R.drawable.oic_star_fill, Color.argb(0xF2, 255, 213, 79)))
            visibility = View.GONE
        }
        root.addView(star, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(6), dp(6), 0, 0)
        })
        val tint = View(context)
        root.addView(tint, FrameLayout.LayoutParams(-1, -1))
        val check = FrameLayout(context).apply {
            visibility = View.GONE
            addView(
                ImageView(context).apply { setImageDrawable(icon(R.drawable.oic_check, Color.WHITE)) },
                FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER)
            )
        }
        root.addView(check, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(5), dp(5), dp(5), 0)
        })
        val progress = View(context).apply {
            background = rounded(palette.accent, 2)
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM or Gravity.START))
        return CoverTile(root, image, badge, star, check, tint, progress)
    }

    /** An album card: a tall cover with a count, then the name and its size. */
    inner class AlbumCard(
        val root: LinearLayout,
        val coverHolder: FrameLayout,
        val cover: ImageView,
        val name: TextView,
        val meta: TextView,
        val badge: TextView
    )

    fun albumCard(coverHeightPx: Int): AlbumCard {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val holder = FrameLayout(context).apply {
            clipToOutline = true
            background = rounded(palette.cardHigh, 16)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpf(16f))
                }
            }
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(palette.cardHigh)
        }
        holder.addView(cover, FrameLayout.LayoutParams(-1, -1))
        val scrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(0x88, 0, 0, 0))
            )
        }
        holder.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val badge = text("", 11f, Color.WHITE, OeaType.medium).apply {
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = rounded(Color.argb(0x80, 0, 0, 0), 9)
        }
        holder.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(7), dp(7))
        })
        column.addView(holder, LinearLayout.LayoutParams(-1, coverHeightPx))
        val name = text("—", 14.5f, palette.text, OeaType.medium).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(9), dp(2), 0)
        }
        val meta = text("—", 11.5f, palette.textSecondary).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(2), dp(2), 0)
        }
        column.addView(name)
        column.addView(meta)
        return AlbumCard(column, holder, cover, name, meta, badge)
    }

    // ------------------------------------------------------------- action sheets

    /** One row of an action sheet: what it does, and what it costs. */
    data class Action(
        val icon: Int,
        val label: String,
        val hint: String? = null,
        val destructive: Boolean = false,
        val disabled: Boolean = false,
        val run: () -> Unit
    )

    /**
     * The sheet a long-press opens: what can be done to this item and nothing
     * else, so nobody hunts through settings-styled rows for the share button.
     */
    fun actions(headline: String, sub: String? = null, actions: List<Action>) {
        val activity = context as? Activity ?: return
        sheet(headline, sub) { panel, dialog ->
            actions.forEach { action ->
                val tint = when {
                    action.disabled -> palette.textTertiary
                    action.destructive -> palette.danger
                    else -> palette.accent
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(58)
                    setPadding(dp(13), dp(10), dp(13), dp(10))
                    isClickable = !action.disabled
                    isFocusable = !action.disabled
                    alpha = if (action.disabled) 0.45f else 1f
                    background = if (action.disabled) rounded(palette.cardHigh, 16) else ripple(rounded(palette.card, 16), 16)
                    contentDescription = action.label
                }
                row.addView(
                    iconBadge(action.icon, palette.withAlpha(tint, if (palette.dark) 0x33 else 0x1F), tint, 38),
                    LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(13) }
                )
                val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(
                    text(action.label, 15.5f, if (action.destructive) palette.danger else palette.text, OeaType.medium).apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )
                if (!action.hint.isNullOrBlank()) {
                    texts.addView(
                        text(action.hint, 12f, palette.textSecondary).apply {
                            setPadding(0, dp(2), 0, 0)
                            maxLines = 3
                            setLineSpacing(dpf(1.5f), 1f)
                        }
                    )
                }
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                if (!action.disabled) row.addView(iconView(R.drawable.oic_chevron_right, palette.textTertiary, 20))
                row.setOnClickListener {
                    dialog.dismiss()
                    action.run()
                }
                panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
    }

    /** The "n selected" bar that slides over the bottom of a screen. */
    inner class SelectionBar(
        val root: LinearLayout,
        val count: TextView,
        val actionRow: LinearLayout,
        val allButton: TextView
    ) {
        private var shown = false

        fun setCount(value: Int, total: Int) {
            count.text = if (total > 0) "$value of $total" else if (value == 1) "1 selected" else "$value selected"
            val shouldShow = value > 0
            if (shouldShow == shown) return
            shown = shouldShow
            if (shouldShow) {
                root.visibility = View.VISIBLE
                root.animate().translationY(0f).alpha(1f).setDuration(200L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f)).start()
            } else {
                root.animate().translationY(dpf(64f)).alpha(0f).setDuration(150L)
                    .withEndAction { root.visibility = View.GONE }
                    .start()
            }
        }

        fun setAction(index: Int, iconRes: Int, label: String, handler: () -> Unit) {
            val buttons = (0 until actionRow.childCount).map { actionRow.getChildAt(it) }.filterIsInstance<TextView>()
            buttons.getOrNull(index)?.apply {
                text = label
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon(iconRes, palette.onAccentSoft), null, null, null)
                compoundDrawablePadding = dp(6)
                setOnClickListener { handler() }
                visibility = View.VISIBLE
            }
        }

        fun hideAction(index: Int) {
            (0 until actionRow.childCount).map { actionRow.getChildAt(it) }
                .filterIsInstance<TextView>().getOrNull(index)?.visibility = View.GONE
        }

        fun selectAll(handler: () -> Unit) {
            allButton.setOnClickListener { handler() }
        }

        var allLabel: String
            get() = allButton.text.toString()
            set(value) {
                allButton.text = value
            }
    }

    fun selectionBar(host: FrameLayout, buttons: List<Pair<Int, String>>, onCancel: () -> Unit): SelectionBar {
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val allButton = text("All", 13f, palette.accent, OeaType.medium).apply {
            setPadding(dp(10), dp(6), dp(8), dp(6))
            isClickable = true
            background = ripple(null, 14)
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(9), dp(9), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dpf(20f)
                setColor(if (palette.dark) Color.argb(0xF6, 24, 27, 33) else Color.argb(0xFA, 255, 255, 255))
                setStroke(dp(1), palette.stroke)
            }
            visibility = View.GONE
            alpha = 0f
            translationY = dpf(64f)
            elevation = dpf(10f)
        }
        val count = text("0 selected", 14f, palette.text, OeaType.medium)
        bar.addView(count, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        bar.addView(allButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        buttons.forEach { (iconRes, label) ->
            actionRow.addView(
                tonalButton(label, iconRes) { },
                LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(6) }
            )
        }
        bar.addView(actionRow)
        bar.addView(
            circleIconButton(R.drawable.oic_close, "Cancel", palette.cardHigh, palette.text) { onCancel() },
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(2) }
        )
        host.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        return SelectionBar(bar, count, actionRow, allButton)
    }

    // ------------------------------------------------------------------ small bits

    /** A labelled number: the way a real app reports its own library. */
    fun statTile(label: String, value: String, tone: Int = palette.accent): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = rounded(palette.card, 20, palette.stroke, 1)
            addView(text(value, 19f, tone, OeaType.regular).apply { letterSpacing = -0.01f })
            addView(
                text(label, 11f, palette.textSecondary, OeaType.medium).apply {
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    gravity = Gravity.CENTER
                }
            )
        }

    /** A header that labels the block of content under it. */
    fun listHeader(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(8))
            addView(
                text(title, 13.5f, palette.textSecondary, OeaType.medium).apply {
                    letterSpacing = 0.06f
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            if (!trailing.isNullOrBlank()) {
                val button = text(trailing, 12.5f, palette.accent, OeaType.medium).apply {
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                    if (onTrailing != null) {
                        isClickable = true
                        background = ripple(null, 10)
                        setOnClickListener { onTrailing() }
                    }
                }
                addView(button)
            }
        }

    /** The typeface every numeric readout shares, so clocks and tapes align. */
    fun mono(view: TextView): TextView = view.apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        setFontFeatureSettings("tnum")
    }

    /** A pill that reports a state: "4K", "HDR", "1.5 GB", "12 Mar". */
    fun badge(value: String, tone: Int = palette.accentSoft, fg: Int = palette.onAccentSoft): TextView =
        TextView(context).apply {
            this.text = value
            textSize = 10.5f
            typeface = OeaType.medium
            setTextColor(fg)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = rounded(tone, 10)
            maxLines = 1
        }
}

/** Even gutters between tiles; the outer edges belong to the RecyclerView. */
class OeaSpacingDecoration(private val inner: Int, private var span: Int) : RecyclerView.ItemDecoration() {

    fun setSpanCount(count: Int) {
        span = count.coerceAtLeast(1)
    }

    override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val column = position % span
        outRect.left = inner - column * inner / span
        outRect.right = inner - (span - 1 - column) * inner / span
        outRect.bottom = inner
    }
}

/** A uniform gap between cards in a vertical list. */
class OeaListSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.bottom = spacing
    }
}

/** A uniform gap between cards in a horizontal rail. */
class OeaRailSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.right = spacing
    }
}

/**
 * Lets an adapter put full-width headers inside a grid: a header row spans every
 * column, a tile takes exactly one. Without this, "grouped by day" would mean an
 * outer ScrollView around a nested grid, which is the layout that janks.
 */
class OeaSpanLookup(private val span: Int) : GridLayoutManager.SpanSizeLookup() {

    /** Which view type each position has, so a header can span the whole row. */
    var typeAt: ((Int) -> Int)? = null

    /** The header position a tile belongs to, or null when the grid is flat. */
    var headerAt: ((Int) -> Int?)? = null
    var headerType: Int = 1

    private fun isHeader(position: Int): Boolean = typeAt?.invoke(position) == headerType

    override fun getSpanSize(position: Int): Int = if (isHeader(position)) span else 1

    override fun getSpanIndex(position: Int, spanCount: Int): Int {
        if (isHeader(position)) return 0
        val header = headerAt?.invoke(position) ?: return super.getSpanIndex(position, spanCount)
        return (position - header - 1).coerceAtLeast(0) % spanCount
    }

    override fun getSpanGroupIndex(position: Int, spanCount: Int): Int {
        val header = headerAt?.invoke(position) ?: return super.getSpanGroupIndex(position, spanCount)
        return if (isHeader(position)) header else header + 1 + (position - header - 1) / spanCount
    }
}
