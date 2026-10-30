package com.oea.launcher.multitask.projection

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Node-first controller for an application running on a virtual display.
 *
 * Why node actions and not injected touches: [AccessibilityService.dispatchGesture]
 * has no display parameter — injected gestures land on the physical screen and
 * would hit the user's foreground app. Accessibility node actions
 * ([AccessibilityNodeInfo.performAction]) are binder calls straight into the
 * target application's view hierarchy, so they work regardless of which
 * display that application lives on. This is the only input channel an
 * ordinary app has into a second application on a secondary display.
 *
 * Coordinate model: the floating window shows a scaled copy of the virtual
 * display. The session maps a touch to virtual-display pixels; this class
 * finds the deepest node whose bounds contain that point and performs the
 * action on it (or on its clickable/scrollable ancestor).
 *
 * Threading: all methods must be called from the single projection handler
 * thread (the session enforces this), because node objects are not safe to
 * share across threads.
 */
class OeaProjectionController(private val serviceProvider: () -> AccessibilityService?) {

    // ---------------------------------------------------------------- windows

    /** True when the accessibility service can report windows at all. */
    fun serviceAvailable(): Boolean = serviceProvider() != null

    /**
     * True when the platform can tell which display a window belongs to
     * ([AccessibilityWindowInfo.getDisplayId], API 30+). Without it, a live
     * window cannot be targeted reliably and is downgraded to view-only.
     */
    fun displayAware(): Boolean = Build.VERSION.SDK_INT >= 30

    /**
     * All accessibility windows the service can see on [displayId], newest
     * first. Package filter is advisory: a null package accepts anything.
     */
    fun windowsOnDisplay(displayId: Int, packageName: String? = null): List<AccessibilityWindowInfo> {
        val service = serviceProvider() ?: return emptyList()
        val windows = try {
            service.windows
        } catch (_: Throwable) {
            return emptyList()
        } ?: return emptyList()
        val out = ArrayList<AccessibilityWindowInfo>(4)
        for (window in windows) {
            if (windowDisplayId(window) != displayId) continue
            if (packageName != null) {
                val root = try {
                    window.root
                } catch (_: Throwable) {
                    null
                }
                val rootPkg = root?.packageName?.toString()
                if (rootPkg != null && rootPkg != packageName && rootPkg != OEA_SELF_PACKAGE_FALLBACK) {
                    continue
                }
            }
            out.add(window)
        }
        return out
    }

    /** True when the given package has a window on the given display. */
    fun packageOnDisplay(displayId: Int, packageName: String): Boolean =
        windowsOnDisplay(displayId, packageName).isNotEmpty()

    /** Package of the active (focused) window on a display, or null. */
    fun activePackageOnDisplay(displayId: Int): String? {
        val service = serviceProvider() ?: return null
        val windows = try {
            service.windows
        } catch (_: Throwable) {
            null
        } ?: return null
        var active: AccessibilityWindowInfo? = null
        var fallback: AccessibilityWindowInfo? = null
        for (window in windows) {
            if (windowDisplayId(window) != displayId) continue
            if (window.isActive) {
                active = window
                break
            }
            if (fallback == null && window.isFocused) fallback = window
        }
        val chosen = active ?: fallback ?: return null
        val pkg = try {
            chosen.root?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
        return pkg
    }

    // ---------------------------------------------------------------- probes

    /** Cheap capability snapshot used by sessions and diagnostics. */
    data class Probe(
        val serviceBound: Boolean,
        val windowsOnDisplay: Int,
        val nodesOnDisplay: Int,
        val clickableNodes: Int,
        val editableNodes: Int,
        val scrollableNodes: Int
    ) {
        /** Node control needs at least one actionable node. */
        val nodeControlAvailable: Boolean get() = clickableNodes > 0 || editableNodes > 0 || scrollableNodes > 0
    }

    fun probe(displayId: Int, packageName: String? = null): Probe {
        val service = serviceProvider()
            ?: return Probe(false, 0, 0, 0, 0, 0)
        var windows = 0
        var nodes = 0
        var clickable = 0
        var editable = 0
        var scrollable = 0
        for (window in windowsOnDisplay(displayId, packageName)) {
            windows++
            val root = try {
                window.root
            } catch (_: Throwable) {
                null
            } ?: continue
            walk(root) { node ->
                nodes++
                if (node.isClickable || node.isLongClickable || node.isCheckable) clickable++
                if (node.isEditable) editable++
                if (node.isScrollable) scrollable++
                true
            }
        }
        return Probe(true, windows, nodes, clickable, editable, scrollable)
    }

    // ---------------------------------------------------------------- actions

    /** Taps whatever is at virtual-display pixel (x, y). */
    fun clickAt(displayId: Int, x: Int, y: Int): OeaNodeOutcome {
        val node = nodeAt(displayId, x, y)
            ?: return OeaNodeOutcome.Failed("Nothing is exposed at that point. This screen area may not be accessible.")
        val target = clickableAncestor(node) ?: node
        try {
            // Focus first, exactly like a real tap would.
            try {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Throwable) {
            }
            val ok = try {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (t: Throwable) {
                return OeaNodeOutcome.Rejected("Tap was refused: ${t.message ?: t.javaClass.simpleName}")
            }
            return if (ok) {
                OeaNodeOutcome.Ok(describe(target))
            } else {
                OeaNodeOutcome.Rejected("That control does not accept taps through accessibility (no clickable node there).")
            }
        } finally {
            recycleQuietly(node)
            if (target !== node) recycleQuietly(target)
        }
    }

    /** Long-presses whatever is at virtual-display pixel (x, y). */
    fun longPressAt(displayId: Int, x: Int, y: Int): OeaNodeOutcome {
        val node = nodeAt(displayId, x, y)
            ?: return OeaNodeOutcome.Failed("Nothing is exposed at that point.")
        val target = clickableAncestor(node) ?: node
        try {
            val ok = try {
                target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            } catch (t: Throwable) {
                return OeaNodeOutcome.Rejected("Long press was refused: ${t.message ?: t.javaClass.simpleName}")
            }
            return if (ok) OeaNodeOutcome.Ok("Long-pressed ${describe(target)}")
            else OeaNodeOutcome.Rejected("That control does not accept long presses through accessibility.")
        } finally {
            recycleQuietly(node)
            if (target !== node) recycleQuietly(target)
        }
    }

    /**
     * Scrolls the list containing virtual-display pixel (x, y), or any
     * scrollable container on the display. [forward] scrolls down/forward.
     */
    fun scroll(displayId: Int, x: Int, y: Int, forward: Boolean): OeaNodeOutcome {
        val startNode = nodeAt(displayId, x, y)
        val scrollable = startNode?.let { scrollableAncestor(it) } ?: firstScrollable(displayId, null)
        if (startNode != null) recycleQuietly(startNode)
        if (scrollable == null) {
            return OeaNodeOutcome.Failed("No scrollable container is exposed on this screen.")
        }
        try {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val ok = try {
                scrollable.performAction(action)
            } catch (t: Throwable) {
                return OeaNodeOutcome.Rejected("Scroll was refused: ${t.message ?: t.javaClass.simpleName}")
            }
            return if (ok) OeaNodeOutcome.Ok("Scrolled ${if (forward) "forward" else "back"}")
            else OeaNodeOutcome.Rejected("The list reports it cannot scroll further this way.")
        } finally {
            recycleQuietly(scrollable)
        }
    }

    /**
     * Puts [text] into the focused editable field (falling back to the first
     * editable field) on the display. This is how the live window's text
     * strip types into the target application without a system keyboard:
     * the app receives the text exactly as if typed, then the user taps the
     * app's own send button through the projection.
     */
    fun setText(displayId: Int, text: String, packageName: String? = null): OeaNodeOutcome {
        val field = findEditable(displayId, packageName, focusedFirst = true)
            ?: return OeaNodeOutcome.Failed(
                "No text field is available. Tap a text field in the window first, then type."
            )
        try {
            try {
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Throwable) {
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = try {
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (t: Throwable) {
                return OeaNodeOutcome.Rejected("This field refused text: ${t.message ?: t.javaClass.simpleName}")
            }
            return if (ok) OeaNodeOutcome.Ok("Typed into ${describe(field)}")
            else OeaNodeOutcome.Rejected(
                "This field does not accept typed text through accessibility. Try Paste instead."
            )
        } finally {
            recycleQuietly(field)
        }
    }

    /** Pastes the clipboard into the focused/first editable field. */
    fun pasteClipboard(displayId: Int, packageName: String? = null): OeaNodeOutcome {
        val field = findEditable(displayId, packageName, focusedFirst = true)
            ?: return OeaNodeOutcome.Failed("No text field is available to paste into.")
        try {
            try {
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Throwable) {
            }
            val ok = try {
                field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (t: Throwable) {
                return OeaNodeOutcome.Rejected("Paste was refused: ${t.message ?: t.javaClass.simpleName}")
            }
            return if (ok) OeaNodeOutcome.Ok("Pasted into ${describe(field)}")
            else OeaNodeOutcome.Rejected("This field does not accept pasting through accessibility.")
        } finally {
            recycleQuietly(field)
        }
    }

    /**
     * In-app Back: prefers an app-provided "Navigate up"/"Back" control on
     * the virtual display. System global Back is deliberately NOT used: it is
     * not display-scoped and would press Back inside the user's foreground
     * app on the physical screen.
     */
    fun backInApp(displayId: Int, packageName: String? = null): OeaNodeOutcome {
        val candidates = listOf("navigate up", "back", "up")
        for (label in candidates) {
            val node = findByLabel(displayId, packageName, label)
            if (node != null) {
                try {
                    val target = clickableAncestor(node) ?: node
                    val ok = try {
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (_: Throwable) {
                        false
                    }
                    if (ok) return OeaNodeOutcome.Ok("Pressed in-app Back (\"$label\").")
                } finally {
                    recycleQuietly(node)
                }
            }
        }
        return OeaNodeOutcome.Rejected(
            "No in-app Back control is exposed on this screen. System Back cannot be scoped to the projection."
        )
    }

    /** Finds and clicks a node by visible text or content description. */
    fun clickLabel(displayId: Int, label: String, packageName: String? = null): OeaNodeOutcome {
        val node = findByLabel(displayId, packageName, label)
            ?: return OeaNodeOutcome.Failed("No control labelled \"$label\" is exposed.")
        try {
            val target = clickableAncestor(node) ?: node
            val ok = try {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Throwable) {
                false
            }
            return if (ok) OeaNodeOutcome.Ok("Pressed \"$label\".")
            else OeaNodeOutcome.Rejected("\"$label\" refused the tap.")
        } finally {
            recycleQuietly(node)
        }
    }

    // ---------------------------------------------------------------- probes (continued)

    /**
     * Non-destructive end-to-end interaction proof for the experiment: it
     * focuses the first focusable control of the target app on the display
     * and immediately releases the focus again. Nothing is clicked, typed,
     * scrolled or sent — but if this succeeds, the very channel a live
     * window uses for taps and text demonstrably reaches the real app.
     */
    fun interactionProbe(displayId: Int, packageName: String? = null): OeaNodeOutcome {
        val node = findFirstFocusable(displayId, packageName)
            ?: return OeaNodeOutcome.Failed(
                "No focusable control was exposed to try; nothing was touched."
            )
        try {
            val focused = try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (t: Throwable) {
                return OeaNodeOutcome.Failed(
                    "Focus threw ${t.javaClass.simpleName}: ${t.message}"
                )
            }
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
            } catch (_: Throwable) {
            }
            return if (focused) {
                OeaNodeOutcome.Ok("focused and released ${describe(node)}")
            } else {
                OeaNodeOutcome.Rejected(
                    "The app refused a focus action through accessibility."
                )
            }
        } finally {
            recycleQuietly(node)
        }
    }

    private fun findFirstFocusable(displayId: Int, packageName: String?): AccessibilityNodeInfo? {
        for (window in windowsOnDisplay(displayId, packageName)) {
            val root = try {
                window.root
            } catch (_: Throwable) {
                null
            } ?: continue
            var found: AccessibilityNodeInfo? = null
            walk(root) { node ->
                if (node.isFocusable) {
                    found = node
                    false
                } else true
            }
            if (found != null) return found
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Deepest visible node whose bounds contain (x, y), preferring the
     * smallest (most specific) hit — the same node a real touch would reach.
     */
    private fun nodeAt(displayId: Int, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE
        val rect = Rect()
        for (window in windowsOnDisplay(displayId, null)) {
            val root = try {
                window.root
            } catch (_: Throwable) {
                null
            } ?: continue
            walk(root) { node ->
                try {
                    node.getBoundsInScreen(rect)
                    if (rect.contains(x, y) && node.isVisibleToUser) {
                        val area = rect.width() * rect.height()
                        if (area in 1 until bestArea) {
                            if (best != null) recycleQuietly(best)
                            best = node
                            bestArea = area
                            // Keep walking: a deeper child may hit better.
                            return@walk true
                        }
                    }
                } catch (_: Throwable) {
                }
                true
            }
            // Keep windows searched; best across all windows wins.
        }
        return best
    }

    private fun firstScrollable(displayId: Int, packageName: String?): AccessibilityNodeInfo? {
        for (window in windowsOnDisplay(displayId, packageName)) {
            val root = try {
                window.root
            } catch (_: Throwable) {
                null
            } ?: continue
            var found: AccessibilityNodeInfo? = null
            walk(root) { node ->
                if (node.isScrollable) {
                    found = node
                    false
                } else true
            }
            if (found != null) return found
        }
        return null
    }

    private fun findEditable(
        displayId: Int,
        packageName: String?,
        focusedFirst: Boolean
    ): AccessibilityNodeInfo? {
        var first: AccessibilityNodeInfo? = null
        for (window in windowsOnDisplay(displayId, packageName)) {
            val root = try {
                window.root
            } catch (_: Throwable) {
                null
            } ?: continue
            walk(root) { node ->
                if (node.isEditable) {
                    if (node.isFocused && focusedFirst) {
                        if (first != null) recycleQuietly(first)
                        first = node
                        return@walk false
                    }
                    if (first == null) first = node
                }
                true
            }
        }
        return first
    }

    private fun findByLabel(
        displayId: Int,
        packageName: String?,
        label: String
    ): AccessibilityNodeInfo? {
        val needle = label.trim().lowercase()
        if (needle.isEmpty()) return null
        for (window in windowsOnDisplay(displayId, packageName)) {
            val root = try {
                window.root
            } catch (_: Throwable) {
                null
            } ?: continue
            var hit: AccessibilityNodeInfo? = null
            walk(root) { node ->
                val text = node.text?.toString()?.trim()?.lowercase()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase()
                if (text == needle || desc == needle) {
                    hit = node
                    false
                } else true
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 24) {
            if (current.isClickable || current.isLongClickable || current.isCheckable) return current
            val parent = try {
                current.parent
            } catch (_: Throwable) {
                null
            }
            // Do not recycle the caller's node; recycle intermediates only.
            if (current !== node) recycleQuietly(current)
            current = parent
            hops++
        }
        return null
    }

    private fun scrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 24) {
            if (current.isScrollable) return current
            val parent = try {
                current.parent
            } catch (_: Throwable) {
                null
            }
            if (current !== node) recycleQuietly(current)
            current = parent
            hops++
        }
        return null
    }

    /** Depth-first walk that stops when [visit] returns false. */
    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean) {
        if (!visit(node)) return
        val count = try {
            node.childCount
        } catch (_: Throwable) {
            return
        }
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            walk(child, visit)
        }
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val label = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: (node.className?.toString()?.substringAfterLast('.') ?: "control")
        return "\"${label.take(40)}\""
    }

    private fun windowDisplayId(window: AccessibilityWindowInfo): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.displayId
            } catch (_: Throwable) {
                android.view.Display.DEFAULT_DISPLAY
            }
        } else {
            android.view.Display.DEFAULT_DISPLAY
        }

    @Suppress("DEPRECATION")
    private fun recycleQuietly(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < 33) {
            try {
                node.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        /**
         * OEA's own package is accepted inside a filtered window list: the
         * trampoline activity's window belongs to OEA and shares the target
         * task on the virtual display. It must never be treated as the
         * target application, but it must also not hide the display.
         */
        private const val OEA_SELF_PACKAGE_FALLBACK = "com.oea.launcher"
    }
}
