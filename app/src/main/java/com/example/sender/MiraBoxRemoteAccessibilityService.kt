package com.example.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * AccessibilityService on the Phone Sender side that gives TV Box Remote Control
 * genuine Android TV-style D-Pad item-by-item navigation across phone apps and launchers,
 * automatic horizontal page flipping when hitting Left/Right edges, smooth vertical scrolling
 * (défilement) on Up/Down/P+/P-, a Cyan TV Selection Highlight Ring over the focused item,
 * reliable OK click + coordinate tap fallback, and an optional Air-Mouse pointer overlay.
 */
class MiraBoxRemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MiraBoxRemoteAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    private enum class NavDir { UP, DOWN, LEFT, RIGHT }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var focusHighlightView: View? = null
    private var cursorView: View? = null
    private var selectedBounds: Rect? = null
    private var activeWindowId: Int = -1

    private val hideFocusRunnable = Runnable { hideFocusHighlight() }
    private val hideCursorRunnable = Runnable { hideCursorOverlay() }

    private data class InteractiveCandidate(
        val bounds: Rect,
        val isClickable: Boolean,
        val area: Long,
        val label: String
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val wid = event.windowId
            if (wid != activeWindowId) {
                activeWindowId = wid
                selectedBounds = null
                mainHandler.post { hideFocusHighlight() }
            }
        }
    }

    override fun onInterrupt() {
        mainHandler.post {
            hideFocusHighlight()
            hideCursorOverlay()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            hideFocusHighlight()
            hideCursorOverlay()
        }
        super.onDestroy()
    }

    fun updateRemoteCursor(normX: Float, normY: Float) {
        mainHandler.post {
            val (screenW, screenH) = getScreenBounds()
            val px = (normX.coerceIn(0.02f, 0.98f) * screenW).toInt()
            val py = (normY.coerceIn(0.02f, 0.98f) * screenH).toInt()
            showCursorOverlay(px, py)
        }
    }

    fun executeRemoteTap(normX: Float, normY: Float) {
        val (screenW, screenH) = getScreenBounds()
        val x = (normX.coerceIn(0.02f, 0.98f) * screenW)
        val y = (normY.coerceIn(0.02f, 0.98f) * screenH)
        mainHandler.post { showCursorOverlay(x.toInt(), y.toInt()) }
        dispatchTapAt(x, y)
    }

    fun clearFocusHighlightOnly() {
        selectedBounds = null
        mainHandler.post { hideFocusHighlight() }
    }

    fun executeRemoteKey(hidKeyCode: Int) {
        when (hidKeyCode) {
            0x0029 -> {
                selectedBounds = null
                mainHandler.post { hideFocusHighlight() }
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            0x004A -> {
                selectedBounds = null
                mainHandler.post { hideFocusHighlight() }
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            0x0076 -> {
                selectedBounds = null
                mainHandler.post { hideFocusHighlight() }
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            0x0028 -> mainHandler.post { performSpatialOkClick() }
            0x0052 -> mainHandler.post { moveSpatialFocus(NavDir.UP) }
            0x0051 -> mainHandler.post { moveSpatialFocus(NavDir.DOWN) }
            0x0050 -> mainHandler.post { moveSpatialFocus(NavDir.LEFT) }
            0x004F -> mainHandler.post { moveSpatialFocus(NavDir.RIGHT) }
            0x004B -> mainHandler.post { performPageScroll(forward = true) }  // P+
            0x004E -> mainHandler.post { performPageScroll(forward = false) } // P-
        }
    }

    private fun moveSpatialFocus(dir: NavDir) {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val (screenW, screenH) = getScreenBounds()
        val wInt = screenW.toInt()
        val hInt = screenH.toInt()
        val candidates = collectInteractiveCandidates(root, wInt, hInt)

        if (candidates.isEmpty()) {
            // Custom canvas / video / WebView with no exposed accessibility buttons: scroll directly
            when (dir) {
                NavDir.DOWN -> performSwipe(0.5f, 0.72f, 0.5f, 0.28f, 210L)
                NavDir.UP -> performSwipe(0.5f, 0.28f, 0.5f, 0.72f, 210L)
                NavDir.RIGHT -> performSwipe(0.82f, 0.5f, 0.18f, 0.5f, 240L)
                NavDir.LEFT -> performSwipe(0.18f, 0.5f, 0.82f, 0.5f, 240L)
            }
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
            return
        }

        val current = selectedBounds ?: findCurrentlyFocusedRect(root)
        val chosen = if (current == null) {
            pickInitialCandidate(candidates, dir)
        } else {
            findBestNeighbor(current, candidates, dir)
        }

        // If moving UP/DOWN inside a scrollable container and the only match is far outside
        // (e.g. fixed top toolbar or bottom nav bar), scroll the feed first!
        if (current != null && (dir == NavDir.DOWN || dir == NavDir.UP)) {
            val scrollRect = findEnclosingVerticalScrollBounds(root, current)
            if (scrollRect != null && (chosen == null || !scrollRect.contains(chosen.bounds.centerX(), chosen.bounds.centerY()))) {
                val scrolled = tryScrollVertical(root, forward = (dir == NavDir.DOWN))
                if (scrolled) {
                    @Suppress("DEPRECATION")
                    runCatching { root.recycle() }
                    rescanAfterScroll(dir, previous = current, crossPage = false)
                    return
                }
            }
        }

        if (chosen != null) {
            selectCandidateRect(root, chosen.bounds)
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
            return
        }

        // Reached edge in this direction:
        // - LEFT/RIGHT at row/page edge -> flip horizontal launcher/pager page!
        // - UP/DOWN at top/bottom edge -> scroll vertically (défilement)!
        val beforeSig = buildCandidateSignature(candidates)
        val pagerCenterYFrac = findHorizontalPagerCenterYFrac(root, wInt, hInt)

        when (dir) {
            NavDir.RIGHT, NavDir.LEFT -> {
                val forward = (dir == NavDir.RIGHT)
                val accScrolled = tryScrollHorizontalPager(root, forward, wInt, hInt)
                @Suppress("DEPRECATION")
                runCatching { root.recycle() }
                if (accScrolled) {
                    rescanAfterScroll(
                        dir = dir,
                        previous = current,
                        crossPage = true,
                        beforeSig = beforeSig,
                        usedAccAction = true,
                        alreadySwiped = false,
                        pagerCenterYFrac = pagerCenterYFrac,
                        delayMs = 340L
                    )
                } else {
                    dispatchHorizontalPageSwipe(forward, pagerCenterYFrac) {
                        rescanAfterScroll(
                            dir = dir,
                            previous = current,
                            crossPage = true,
                            beforeSig = beforeSig,
                            usedAccAction = false,
                            alreadySwiped = true,
                            pagerCenterYFrac = pagerCenterYFrac,
                            delayMs = 220L
                        )
                    }
                }
            }
            NavDir.DOWN, NavDir.UP -> {
                val forward = (dir == NavDir.DOWN)
                val accScrolled = tryScrollVertical(root, forward)
                @Suppress("DEPRECATION")
                runCatching { root.recycle() }
                if (accScrolled) {
                    rescanAfterScroll(
                        dir = dir,
                        previous = current,
                        crossPage = false,
                        beforeSig = beforeSig,
                        usedAccAction = true,
                        alreadySwiped = false,
                        pagerCenterYFrac = pagerCenterYFrac,
                        delayMs = 250L
                    )
                } else {
                    val startY = if (forward) 0.72f else 0.28f
                    val endY = if (forward) 0.28f else 0.72f
                    performSwipe(0.5f, startY, 0.5f, endY, 240L) {
                        rescanAfterScroll(
                            dir = dir,
                            previous = current,
                            crossPage = false,
                            beforeSig = beforeSig,
                            usedAccAction = false,
                            alreadySwiped = true,
                            pagerCenterYFrac = pagerCenterYFrac,
                            delayMs = 180L
                        )
                    }
                }
            }
        }
    }

    private fun dispatchHorizontalPageSwipe(
        forward: Boolean,
        centerYFrac: Float,
        onFinished: (() -> Unit)? = null
    ) {
        val y = centerYFrac.coerceIn(0.28f, 0.72f)
        if (forward) {
            performSwipe(0.80f, y, 0.20f, y, 320L, onFinished)
        } else {
            performSwipe(0.20f, y, 0.80f, y, 320L, onFinished)
        }
    }

    private fun buildCandidateSignature(candidates: List<InteractiveCandidate>): String {
        return candidates.take(16).joinToString("|") {
            "${it.label}@${it.bounds.left},${it.bounds.top}"
        }
    }

    private fun rescanAfterScroll(
        dir: NavDir,
        previous: Rect?,
        crossPage: Boolean,
        beforeSig: String = "",
        usedAccAction: Boolean = false,
        alreadySwiped: Boolean = true,
        pagerCenterYFrac: Float = 0.48f,
        delayMs: Long = if (crossPage) 320L else 250L
    ) {
        mainHandler.postDelayed({
            val freshRoot = runCatching { rootInActiveWindow }.getOrNull() ?: return@postDelayed
            val (screenW, screenH) = getScreenBounds()
            val freshCandidates = collectInteractiveCandidates(freshRoot, screenW.toInt(), screenH.toInt())
            val freshSig = buildCandidateSignature(freshCandidates)

            // If Accessibility scroll returned true without visually changing the page (common on Samsung OneUI PagedView),
            // immediately fire a real horizontal swipe gesture across the pager and rescan after completion!
            if (crossPage && usedAccAction && !alreadySwiped && freshSig == beforeSig) {
                @Suppress("DEPRECATION")
                runCatching { freshRoot.recycle() }
                dispatchHorizontalPageSwipe(forward = (dir == NavDir.RIGHT), centerYFrac = pagerCenterYFrac) {
                    rescanAfterScroll(
                        dir = dir,
                        previous = previous,
                        crossPage = true,
                        beforeSig = beforeSig,
                        usedAccAction = false,
                        alreadySwiped = true,
                        pagerCenterYFrac = pagerCenterYFrac,
                        delayMs = 220L
                    )
                }
                return@postDelayed
            }

            if (freshCandidates.isNotEmpty()) {
                val pageActuallyChanged = beforeSig.isEmpty() || freshSig != beforeSig
                val picked = if (crossPage) {
                    if (pageActuallyChanged) {
                        pickOppositeEdgeCandidateAfterPageFlip(freshCandidates, dir, previous)
                    } else {
                        // Already on the very first/last page — stay put cleanly on current edge item
                        null
                    }
                } else {
                    if (previous != null) {
                        findBestNeighbor(previous, freshCandidates, dir)
                            ?: if (pageActuallyChanged) pickEdgeCandidateAfterVerticalScroll(freshCandidates, dir) else null
                    } else {
                        pickEdgeCandidateAfterVerticalScroll(freshCandidates, dir)
                    }
                }
                if (picked != null) {
                    selectCandidateRect(freshRoot, picked.bounds)
                }
            }
            @Suppress("DEPRECATION")
            runCatching { freshRoot.recycle() }
        }, delayMs)
    }

    private fun performSpatialOkClick() {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val rect = selectedBounds ?: root?.let { findCurrentlyFocusedRect(it) }

        if (root != null && rect != null) {
            val targetNode = findNodeMatchingRect(root, rect)
            val clickedViaAccessibility = targetNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (targetNode != null && targetNode !== root) {
                @Suppress("DEPRECATION")
                runCatching { targetNode.recycle() }
            }
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }

            if (!clickedViaAccessibility) {
                dispatchTapAt(rect.exactCenterX(), rect.exactCenterY())
            }
            showFocusHighlight(rect)
            return
        }

        if (root != null) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            val clicked = focused?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (focused != null && focused !== root) {
                @Suppress("DEPRECATION")
                runCatching { focused.recycle() }
            }
            if (clicked) {
                @Suppress("DEPRECATION")
                runCatching { root.recycle() }
                return
            }

            val (screenW, screenH) = getScreenBounds()
            val candidates = collectInteractiveCandidates(root, screenW.toInt(), screenH.toInt())
            val first = candidates.firstOrNull()
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
            if (first != null) {
                selectedBounds = first.bounds
                showFocusHighlight(first.bounds)
                dispatchTapAt(first.bounds.exactCenterX(), first.bounds.exactCenterY())
                return
            }
        }

        val (w, h) = getScreenBounds()
        dispatchTapAt(w * 0.5f, h * 0.5f)
    }

    private fun performPageScroll(forward: Boolean) {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val scrolled = if (root != null) {
            val res = tryScrollVertical(root, forward)
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
            res
        } else {
            false
        }

        if (!scrolled) {
            if (forward) {
                performSwipe(0.5f, 0.74f, 0.5f, 0.24f, 220L)
            } else {
                performSwipe(0.5f, 0.24f, 0.5f, 0.74f, 220L)
            }
        }
        selectedBounds = null
        hideFocusHighlight()
    }

    private fun collectInteractiveCandidates(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int
    ): List<InteractiveCandidate> {
        val screenArea = (screenW.toLong() * screenH.toLong()).coerceAtLeast(1L)
        val raw = mutableListOf<InteractiveCandidate>()

        fun dfs(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val w = rect.width()
            val h = rect.height()
            val inBounds = rect.right > 0 && rect.bottom > 0 && rect.left < screenW && rect.top < screenH
            if (!inBounds || w < 34 || h < 34) return

            val clamped = Rect(
                rect.left.coerceIn(0, screenW),
                rect.top.coerceIn(0, screenH),
                rect.right.coerceIn(0, screenW),
                rect.bottom.coerceIn(0, screenH)
            )
            val area = clamped.width().toLong() * clamped.height().toLong()
            val hasClickAction = node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_CLICK
            }
            val interactive = node.isClickable || node.isFocusable || node.isCheckable || hasClickAction

            val childCount = node.childCount
            val beforeChildrenSize = raw.size
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                dfs(child)
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
            val addedInteractiveChild = raw.size > beforeChildrenSize

            val notHugeContainer = area < (screenArea * 0.42f).toLong()
            if (interactive && notHugeContainer && (!addedInteractiveChild || area < (screenArea * 0.14f).toLong())) {
                val label = extractNodeLabel(node)
                raw.add(InteractiveCandidate(clamped, node.isClickable || hasClickAction, area, label))
            }
        }

        dfs(root)

        val deduped = mutableListOf<InteractiveCandidate>()
        for (c in raw.sortedBy { it.area }) {
            val dup = deduped.any { existing ->
                abs(existing.bounds.centerX() - c.bounds.centerX()) < 14 &&
                    abs(existing.bounds.centerY() - c.bounds.centerY()) < 14
            }
            if (!dup) deduped.add(c)
        }
        return deduped
    }

    private fun extractNodeLabel(node: AccessibilityNodeInfo, depth: Int = 0): String {
        val own = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (own != null) return own.take(28)
        if (depth >= 2) return ""
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childLabel = extractNodeLabel(child, depth + 1)
            @Suppress("DEPRECATION")
            runCatching { child.recycle() }
            if (childLabel.isNotEmpty()) return childLabel
        }
        return ""
    }

    private fun findBestNeighbor(
        current: Rect,
        candidates: List<InteractiveCandidate>,
        dir: NavDir
    ): InteractiveCandidate? {
        val cx = current.exactCenterX()
        val cy = current.exactCenterY()
        val minStepY = maxOf(28f, current.height() * 0.42f)
        val minStepX = maxOf(28f, current.width() * 0.42f)
        val maxRowDriftY = maxOf(current.height() * 0.65f, 95f)

        var best: InteractiveCandidate? = null
        var bestScore = Float.MAX_VALUE

        for (cand in candidates) {
            if (cand.bounds == current) continue
            val nx = cand.bounds.exactCenterX()
            val ny = cand.bounds.exactCenterY()
            val dx = nx - cx
            val dy = ny - cy

            // Strict band thresholds keep LEFT/RIGHT constrained to the current icon row
            // so tall widgets/docks never capture edge presses that should flip the launcher page.
            val sameRowBand = abs(dy) <= maxRowDriftY

            val validDirection = when (dir) {
                NavDir.RIGHT -> sameRowBand && dx > minStepX && cand.bounds.left >= current.left + (current.width() * 0.3f)
                NavDir.LEFT -> sameRowBand && dx < -minStepX && cand.bounds.right <= current.right - (current.width() * 0.3f)
                NavDir.DOWN -> dy > minStepY && cand.bounds.top >= current.top + (current.height() * 0.3f)
                NavDir.UP -> dy < -minStepY && cand.bounds.bottom <= current.bottom - (current.height() * 0.3f)
            }
            if (!validDirection) continue

            val (major, minor) = when (dir) {
                NavDir.RIGHT, NavDir.LEFT -> abs(dx) to abs(dy)
                NavDir.DOWN, NavDir.UP -> abs(dy) to abs(dx)
            }
            // Prefer elements aligned along the axis of movement so UP/DOWN never zig-zag sideways
            val alignmentPenalty = if (dir == NavDir.DOWN || dir == NavDir.UP) 4.5f else 3.6f
            val score = major + (alignmentPenalty * minor)
            if (score < bestScore) {
                bestScore = score
                best = cand
            }
        }
        return best
    }

    private fun pickInitialCandidate(
        candidates: List<InteractiveCandidate>,
        dir: NavDir
    ): InteractiveCandidate? {
        return when (dir) {
            NavDir.UP -> candidates.maxByOrNull { it.bounds.centerY() }
            NavDir.LEFT -> candidates.maxByOrNull { it.bounds.centerX() }
            NavDir.DOWN, NavDir.RIGHT -> candidates.minByOrNull {
                it.bounds.top * 2 + it.bounds.left
            }
        }
    }

    private fun pickOppositeEdgeCandidateAfterPageFlip(
        candidates: List<InteractiveCandidate>,
        dir: NavDir,
        previous: Rect?
    ): InteractiveCandidate? {
        val targetY = previous?.exactCenterY()
        return when (dir) {
            NavDir.RIGHT -> candidates.minByOrNull { cand ->
                val yDiff = if (targetY != null) abs(cand.bounds.exactCenterY() - targetY) * 0.5f else 0f
                cand.bounds.centerX() + yDiff
            }
            NavDir.LEFT -> candidates.maxByOrNull { cand ->
                val yDiff = if (targetY != null) abs(cand.bounds.exactCenterY() - targetY) * 0.5f else 0f
                cand.bounds.centerX() - yDiff
            }
            else -> candidates.firstOrNull()
        }
    }

    private fun pickEdgeCandidateAfterVerticalScroll(
        candidates: List<InteractiveCandidate>,
        dir: NavDir
    ): InteractiveCandidate? {
        return when (dir) {
            NavDir.DOWN -> candidates.maxByOrNull { it.bounds.centerY() }
            NavDir.UP -> candidates.minByOrNull { it.bounds.centerY() }
            else -> candidates.firstOrNull()
        }
    }

    private fun findEnclosingVerticalScrollBounds(root: AccessibilityNodeInfo, target: Rect): Rect? {
        var bestRect: Rect? = null
        var bestArea = Long.MAX_VALUE

        fun dfs(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.contains(target.centerX(), target.centerY())) {
                val cls = node.className?.toString().orEmpty()
                val looksHorizontalPager = cls.contains("Pager", ignoreCase = true) ||
                    cls.contains("Horizontal", ignoreCase = true)
                val supportsVert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    node.actionList.any {
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ||
                            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                    }
                } else {
                    node.isScrollable && !looksHorizontalPager
                }
                if ((node.isScrollable && !looksHorizontalPager) || supportsVert) {
                    val area = rect.width().toLong() * rect.height().toLong()
                    if (area < bestArea) {
                        bestArea = area
                        bestRect = rect
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    dfs(child)
                    @Suppress("DEPRECATION")
                    runCatching { child.recycle() }
                }
            }
        }
        dfs(root)
        return bestRect
    }

    private fun tryScrollVertical(root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val dirAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        } else {
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val fallbackAction = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        fun dfs(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.isScrollable) {
                val cls = node.className?.toString().orEmpty()
                val isHorizontalOnly = cls.contains("HorizontalScrollView", ignoreCase = true) ||
                    cls.contains("ViewPager", ignoreCase = true)
                if (!isHorizontalOnly) {
                    val hasDir = node.actionList.any { it.id == dirAction }
                    if (hasDir && node.performAction(dirAction)) return true
                    if (node.performAction(fallbackAction)) return true
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val ok = dfs(child)
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
                if (ok) return true
            }
            return false
        }
        return dfs(root)
    }

    private fun findHorizontalPagerCenterYFrac(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int
    ): Float {
        var bestCenterY = screenH * 0.48f
        var bestArea = 0L

        fun dfs(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val cls = node.className?.toString().orEmpty()
            val looksPager = cls.contains("Pager", ignoreCase = true) ||
                cls.contains("Workspace", ignoreCase = true) ||
                cls.contains("PagedView", ignoreCase = true) ||
                cls.contains("Horizontal", ignoreCase = true)
            if ((node.isScrollable || looksPager) &&
                rect.width() >= screenW * 0.45f &&
                rect.height() >= screenH * 0.25f
            ) {
                val area = rect.width().toLong() * rect.height().toLong()
                if (area > bestArea) {
                    bestArea = area
                    bestCenterY = rect.exactCenterY()
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                dfs(child)
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }
        dfs(root)
        return (bestCenterY / screenH.coerceAtLeast(1)).coerceIn(0.28f, 0.72f)
    }

    private fun tryScrollHorizontalPager(
        root: AccessibilityNodeInfo,
        forward: Boolean,
        screenW: Int,
        screenH: Int
    ): Boolean {
        val dirAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        } else {
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val fallbackAction = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        fun dfs(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.isScrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val largeEnough = rect.width() >= screenW * 0.40f && rect.height() >= screenH * 0.22f
                if (largeEnough) {
                    val hasHorizAction = node.actionList.any { it.id == dirAction }
                    if (hasHorizAction && node.performAction(dirAction)) return true

                    val cls = node.className?.toString().orEmpty()
                    val looksPager = cls.contains("Pager", ignoreCase = true) ||
                        cls.contains("Workspace", ignoreCase = true) ||
                        cls.contains("PagedView", ignoreCase = true) ||
                        cls.contains("Horizontal", ignoreCase = true)
                    val supportsVerticalOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val hasVert = node.actionList.any {
                            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ||
                                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                        }
                        val anyHoriz = node.actionList.any {
                            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id ||
                                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                        }
                        hasVert && !anyHoriz
                    } else {
                        false
                    }
                    if ((looksPager || !supportsVerticalOnly) && node.performAction(fallbackAction)) {
                        return true
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val ok = dfs(child)
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
                if (ok) return true
            }
            return false
        }
        return dfs(root)
    }

    private fun selectCandidateRect(root: AccessibilityNodeInfo, bounds: Rect) {
        selectedBounds = Rect(bounds)
        val node = findNodeMatchingRect(root, bounds)
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            if (node.isFocusable) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            if (node !== root) {
                @Suppress("DEPRECATION")
                runCatching { node.recycle() }
            }
        }
        showFocusHighlight(bounds)
    }

    private fun findCurrentlyFocusedRect(root: AccessibilityNodeInfo): Rect? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return null
        val rect = Rect()
        focused.getBoundsInScreen(rect)
        if (focused !== root) {
            @Suppress("DEPRECATION")
            runCatching { focused.recycle() }
        }
        return if (rect.width() > 0 && rect.height() > 0) rect else null
    }

    private fun findNodeMatchingRect(root: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect == target && (root.isClickable || root.isFocusable || root.isCheckable)) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val match = findNodeMatchingRect(child, target)
            if (match != null) {
                if (match !== child) {
                    @Suppress("DEPRECATION")
                    runCatching { child.recycle() }
                }
                return match
            }
            @Suppress("DEPRECATION")
            runCatching { child.recycle() }
        }
        if (rect == target) return root
        return null
    }

    private fun showFocusHighlight(bounds: Rect) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        val pad = 6
        val left = (bounds.left - pad).coerceAtLeast(0)
        val top = (bounds.top - pad).coerceAtLeast(0)
        val width = (bounds.width() + pad * 2).coerceAtLeast(36)
        val height = (bounds.height() + pad * 2).coerceAtLeast(36)

        val params = WindowManager.LayoutParams(
            width,
            height,
            left,
            top,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val existing = focusHighlightView
        if (existing == null) {
            val ring = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20f
                    setColor(Color.argb(38, 0, 229, 255))
                    setStroke(6, Color.rgb(0, 229, 255))
                }
            }
            runCatching {
                wm.addView(ring, params)
                focusHighlightView = ring
            }
        } else {
            runCatching { wm.updateViewLayout(existing, params) }
        }

        mainHandler.removeCallbacks(hideFocusRunnable)
        mainHandler.postDelayed(hideFocusRunnable, 5000L)
    }

    private fun hideFocusHighlight() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        focusHighlightView?.let { v ->
            runCatching { wm.removeView(v) }
        }
        focusHighlightView = null
    }

    private fun showCursorOverlay(px: Int, py: Int) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
        val sizePx = 38
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            (px - sizePx / 2).coerceAtLeast(0),
            (py - sizePx / 2).coerceAtLeast(0),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val existing = cursorView
        if (existing == null) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(120, 0, 229, 255))
                    setStroke(5, Color.WHITE)
                }
            }
            runCatching {
                wm.addView(dot, params)
                cursorView = dot
            }
        } else {
            runCatching { wm.updateViewLayout(existing, params) }
        }

        mainHandler.removeCallbacks(hideCursorRunnable)
        mainHandler.postDelayed(hideCursorRunnable, 4000L)
    }

    private fun hideCursorOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        cursorView?.let { v ->
            runCatching { wm.removeView(v) }
        }
        cursorView = null
    }

    private fun dispatchTapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        onFinished: (() -> Unit)? = null
    ) {
        val (w, h) = getScreenBounds()
        val path = Path().apply {
            moveTo(x1 * w, y1 * h)
            lineTo(x2 * w, y2 * h)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val callback = if (onFinished != null) {
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainHandler.post { onFinished() }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    mainHandler.post { onFinished() }
                }
            }
        } else {
            null
        }
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, null)
        if (!accepted && onFinished != null) {
            mainHandler.post { onFinished() }
        }
    }

    @Suppress("DEPRECATION")
    private fun getScreenBounds(): Pair<Float, Float> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width().toFloat() to bounds.height().toFloat()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
        }
    }
}
