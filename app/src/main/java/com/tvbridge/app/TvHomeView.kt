package com.tvbridge.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.*
import android.view.*
import android.widget.Button

class TvHomeView(
    context: Context,
    private val onLive: Runnable,
    private val onMovie: Runnable,
    private val onSettings: Runnable,
    private val onAllApps: Runnable
) : ViewGroup(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val clockFont = Typeface.create("sans-serif", Typeface.BOLD)
    private val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val dateFormat = java.text.SimpleDateFormat("yyyy年M月d日  EEEE", java.util.Locale.SIMPLIFIED_CHINESE)

    // 4 Main Tabs
    val homeTab = Button(context)
    val liveTab = Button(context)
    val movieTab = Button(context)
    val settingsTab = Button(context)
    val tabs = listOf(homeTab, liveTab, movieTab, settingsTab)

    private val appButtons = mutableListOf<ShortcutButton>()
    private var appMenu: android.widget.ListPopupWindow? = null
    private var moving: LauncherApps.Entry? = null
    private var menuSelection = 0
    private var menuEntry: LauncherApps.Entry? = null

    private val launcherApps = LauncherApps(context)
    private val appRow = android.widget.GridLayout(context).apply { columnCount = 5 }
    private val appRail = android.widget.FrameLayout(context).apply { addView(appRow) }

    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var styledScale = -1f
    var drawsBackdrop = true
    private var darkHome = HomeAppearance.dark(context)
    private var appearanceKey = HomeAppearance.key(context)
    private val ink get() = GlassUi.ink(context, darkHome)
    private var appSignature: String? = null
    private var rememberedFocus = "首頁"
    private var activatedFocus = ""
    private var labelInk: Rect? = null

    private fun labelInkBounds(): Rect {
        labelInk?.let { return it }
        val previousSize = paint.textSize
        val previousTypeface = paint.typeface
        paint.textSize = 32f
        paint.typeface = bold
        val bounds = Rect()
        paint.getTextBounds("常用應用", 0, 4, bounds)
        paint.textSize = previousSize
        paint.typeface = previousTypeface
        labelInk = bounds
        return bounds
    }

    private fun homeTextColors() = GlassUi.colors(context)

    fun refreshAppearance() {
        val dark = HomeAppearance.dark(context)
        if (appearanceKey != HomeAppearance.key(context)) {
            appearanceKey = HomeAppearance.key(context)
            darkHome = dark
            styledScale = -1f
            appSignature = null
            refreshApps()
            requestLayout()
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
        isFocusable = false

        val tabConfigs = listOf(
            Triple(homeTab, "首頁", Runnable { focusFirstApp() }),
            Triple(liveTab, "直播", onLive),
            Triple(movieTab, "電影", onMovie),
            Triple(settingsTab, "設定", onSettings)
        )

        tabConfigs.forEach { (btn, label, action) ->
            btn.apply {
                if (android.os.Build.VERSION.SDK_INT >= 21) backgroundTintList = null
                if (android.os.Build.VERSION.SDK_INT >= 21) stateListAnimator = null
                if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
                text = label
                contentDescription = label
                id = generateViewId()
                isAllCaps = false
                isFocusableInTouchMode = true
                setTextColor(homeTextColors())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setOnClickListener {
                    activatedFocus = label
                    rememberedFocus = label
                    action.run()
                }
                setOnFocusChangeListener { v, focus ->
                    if (focus) {
                        rememberedFocus = label
                    }
                    (v as Button).setTextColor(homeTextColors())
                    v.animate().scaleX(if (focus) 1.06f else 1f).scaleY(if (focus) 1.06f else 1f).setDuration(120).start()
                }
            }
            NativeUi.touchClicks(btn)
            addView(btn)
        }

        // Setup horizontal D-Pad chain between tabs
        homeTab.nextFocusLeftId = homeTab.id
        homeTab.nextFocusRightId = liveTab.id

        liveTab.nextFocusLeftId = homeTab.id
        liveTab.nextFocusRightId = movieTab.id

        movieTab.nextFocusLeftId = liveTab.id
        movieTab.nextFocusRightId = settingsTab.id

        settingsTab.nextFocusLeftId = movieTab.id
        settingsTab.nextFocusRightId = settingsTab.id

        tabs.forEach { it.nextFocusUpId = it.id }

        homeTab.isSelected = true

        addView(appRail)
        refreshApps()
    }

    private fun focusFirstApp() {
        if (appButtons.isNotEmpty()) {
            appButtons.first().requestFocus()
        }
    }

    fun refreshApps() {
        val entries = launcherApps.favorites().take(LauncherApps.MAX_FAVORITES)
        val signature = entries.joinToString { it.component.flattenToString() + ":" + it.label + ":" + it.updated } + ":" + darkHome
        if (appSignature == signature) {
            requestLayout()
            return
        }
        val focused = (findFocus() as? Button)?.text?.toString()
        appSignature = signature
        appRow.removeAllViews()
        appButtons.clear()

        fun add(label: String, entry: LauncherApps.Entry?) {
            val b = ShortcutButton(context).apply {
                tag = entry
                text = label
                contentDescription = label
                id = generateViewId()
                isAllCaps = false
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                isFocusableInTouchMode = true
                if (android.os.Build.VERSION.SDK_INT >= 21) backgroundTintList = null
                if (android.os.Build.VERSION.SDK_INT >= 21) stateListAnimator = null
                if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding((8 * scale).toInt(), 0, (8 * scale).toInt(), 0)
                setTextColor(homeTextColors())
                setOnClickListener {
                    activatedFocus = label
                    rememberedFocus = label
                    if (moving != null) finishMove()
                    else if (entry == null) onAllApps.run()
                    else if (!launcherApps.launch(entry)) NativeUi.notice(context, "應用已移除或無法打開")
                }
                setOnFocusChangeListener { _, focus ->
                    if (focus) rememberedFocus = label
                }
            }
            val icon = if (entry != null) try { launcherApps.icon(entry) } catch (_: Exception) { AppIcons.Vector("grid") { b.currentTextColor } } else AppIcons.Vector("plus") { b.currentTextColor }
            val n = (46 * scale).toInt()
            icon.setBounds(0, 0, n, n)
            b.icon = icon
            if (entry != null) b.setOnLongClickListener { showAppMenu(b, entry); true }
            NativeUi.touchClicks(b)
            appButtons.add(b)
            appRow.addView(b)
        }

        entries.forEach { add(it.label, it) }
        add("全部應用", null)

        val toolForColumn = { col: Int ->
            when (col) {
                0 -> homeTab
                1 -> liveTab
                2 -> movieTab
                else -> settingsTab
            }
        }

        appButtons.forEachIndexed { i, b ->
            b.nextFocusLeftId = appButtons[if (i % 5 == 0) i else i - 1].id
            b.nextFocusRightId = appButtons.getOrNull(if (i % 5 == 4) i else i + 1)?.id ?: b.id
            b.nextFocusUpId = if (i >= 5) appButtons[i - 5].id else toolForColumn(i % 5).id
            b.nextFocusDownId = appButtons.getOrNull(i + 5)?.id ?: b.id
        }

        // Link tab down navigation to corresponding app button column
        tabs.forEachIndexed { i, tab ->
            tab.nextFocusDownId = appButtons.getOrNull(i)?.id ?: appButtons.firstOrNull()?.id ?: tab.id
        }

        styledScale = -1f
        requestLayout()
        if (focused != null) appButtons.firstOrNull { it.text.toString() == focused }?.requestFocus()
    }

    private fun focusEntry(entry: LauncherApps.Entry) {
        appButtons.firstOrNull { (it.tag as? LauncherApps.Entry)?.component == entry.component }?.requestFocus()
    }

    private fun finishMove() {
        val entry = moving ?: return
        moving = null
        refreshApps()
        focusEntry(entry)
        invalidate()
    }

    private fun enterMove(entry: LauncherApps.Entry) {
        appMenu?.dismiss()
        moving = entry
        refreshApps()
        focusEntry(entry)
        invalidate()
    }

    private fun moveEntry(entry: LauncherApps.Entry, offset: Int) {
        launcherApps.move(entry, offset)
        val order = launcherApps.saved()
        appButtons.sortBy { button ->
            (button.tag as? LauncherApps.Entry)?.let { order.indexOf(it.component.flattenToString()).takeIf { n -> n >= 0 } } ?: Int.MAX_VALUE
        }
        styledScale = -1f
        requestLayout()
        invalidate()
        focusEntry(entry)
    }

    private fun showAppMenu(anchor: View, entry: LauncherApps.Entry) {
        if (appMenu?.isShowing == true) return
        val popup = android.widget.ListPopupWindow(context)
        appMenu = popup
        menuEntry = entry
        moving = null
        menuSelection = 0
        popup.anchorView = anchor
        popup.isModal = false
        popup.width = if (anchor.width > 0) anchor.width else (160 * scale).toInt()
        popup.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        popup.horizontalOffset = 0
        popup.verticalOffset = -anchor.height - (112 * scale).toInt()
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.setAdapter(object : android.widget.ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, listOf("移動", "移除常用")) {
            override fun getView(position: Int, convert: View?, parent: ViewGroup): View =
                android.widget.TextView(context).apply {
                    text = getItem(position)
                    NativeUi.textSize(this, 30f)
                    gravity = Gravity.CENTER
                    val s = (14 * scale).toInt()
                    val half = (7 * scale).toInt()
                    setPadding((20 * scale).toInt(), if (position == 0) s else half, (20 * scale).toInt(), if (position == 0) half else s)
                    isSingleLine = true
                    isSelected = position == menuSelection
                    background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(GlassUi.ink(context))
                }
        })
        popup.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) enterMove(entry)
            else {
                launcherApps.remove(entry)
                popup.dismiss()
            }
        }
        popup.setOnDismissListener {
            appMenu = null
            menuEntry = null
            moving = null
            refreshApps()
            focusEntry(entry)
            invalidate()
        }
        popup.show()
        popup.listView?.let {
            it.background = GlassUi.surface(it, 20f)
            it.setPadding(0, 0, 0, 0)
            it.divider = null
            it.dividerHeight = 0
            it.setHeaderDividersEnabled(false)
            it.setFooterDividersEnabled(false)
            it.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        post { if (popup.isShowing) popup.setSelection(0) }
        popup.listView?.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                menuSelection = position
                val list = popup.listView
                if (list != null) for (i in 0 until list.childCount) {
                    val child = list.getChildAt(i)
                    val selected = list.firstVisiblePosition + i == position
                    child.isSelected = selected
                    child.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    (child as? android.widget.TextView)?.let {
                        it.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        it.setTextColor(GlassUi.ink(context))
                    }
                }
                invalidate()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
        requestLayout()
        invalidate()
    }

    fun handleKey(code: Int): Boolean {
        moving?.let { entry ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU -> finishMove()
                KeyEvent.KEYCODE_DPAD_LEFT -> moveEntry(entry, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveEntry(entry, 1)
                else -> return false
            }
            return true
        }

        appMenu?.takeIf { it.isShowing }?.let { popup ->
            val selected = menuSelection
            val entry = menuEntry ?: return false
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> popup.dismiss()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> popup.performItemClick(selected)
                KeyEvent.KEYCODE_DPAD_UP -> {
                    menuSelection = 0
                    popup.setSelection(0)
                    requestLayout()
                    invalidate()
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    menuSelection = 1
                    popup.setSelection(1)
                    requestLayout()
                    invalidate()
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (selected == 0) enterMove(entry) else return false
                else -> return false
            }
            return true
        }

        if (code == KeyEvent.KEYCODE_MENU) {
            val tile = findFocus() as? ShortcutButton
            val entry = tile?.tag as? LauncherApps.Entry
            if (tile != null && entry != null) {
                showAppMenu(tile, entry)
                return true
            }
        }
        return false
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) {
            appMenu?.dismiss()
            moving = null
        }
    }

    override fun onDetachedFromWindow() {
        appMenu?.dismiss()
        super.onDetachedFromWindow()
    }

    private val twoRows get() = appButtons.size > 5
    private fun tabBackground(view: View) = GlassUi.surface(view, 24f, faint = true)
    private fun appTileBackground(view: View) = GlassUi.surface(view, faint = true)

    override fun onMeasure(width: Int, height: Int) {
        val w = MeasureSpec.getSize(width)
        val h = MeasureSpec.getSize(height)
        setMeasuredDimension(w, h)
        scale = minOf(w / 1920f, h / 1080f)
        ox = (w - 1920 * scale) / 2
        oy = (h - 1080 * scale) / 2

        if (styledScale != scale) {
            styledScale = scale
            updateButtonStyles()
            appButtons.forEachIndexed { i, button ->
                button.layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    this.width = (320 * scale).toInt()
                    this.height = ((if (twoRows) 168 else 200) * scale).toInt()
                    columnSpec = android.widget.GridLayout.spec(i % 5)
                    rowSpec = android.widget.GridLayout.spec(i / 5)
                    marginEnd = if (i % 5 == 4) 0 else (32 * scale).toInt()
                    bottomMargin = if (i < 5) (24 * scale).toInt() else 0
                }
                button.iconPixels = if (button.tag == null) 56f else if (twoRows) 76f else 100f
            }
        }

        appRail.measure(
            MeasureSpec.makeMeasureSpec((1728 * scale).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(((if (twoRows) 360 else 200) * scale).toInt(), MeasureSpec.EXACTLY)
        )

        val tabWidth = (240 * scale).toInt()
        val tabHeight = (72 * scale).toInt()
        tabs.forEach { b ->
            b.measure(
                MeasureSpec.makeMeasureSpec(tabWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(tabHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    private fun updateButtonStyles() {
        tabs.forEach { b ->
            b.setTextColor(homeTextColors())
            b.background = tabBackground(b)
            NativeUi.textSize(b, 30f)
            b.gravity = Gravity.CENTER
            val iconKey = when (b) {
                homeTab -> "home"
                liveTab -> "tv"
                movieTab -> "play"
                else -> "settings"
            }
            AppIcons.bind(b, iconKey, 32f)
            val inset = ((b.measuredWidth - 32 * scale - 12 * scale - b.paint.measureText(b.text.toString())) / 2).toInt().coerceAtLeast((16 * scale).toInt())
            b.setPadding(inset, 0, inset, 0)
        }
        appButtons.forEach { button ->
            button.setTextColor(homeTextColors())
            NativeUi.textSize(button, 28f)
            button.background = appTileBackground(button)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val y = (oy + 64 * scale).toInt()
        val tabSpacing = (24 * scale).toInt()
        tabs.forEachIndexed { i, button ->
            val x = (ox + (96 * scale) + i * (button.measuredWidth + tabSpacing)).toInt()
            button.layout(x, y, x + button.measuredWidth, y + button.measuredHeight)
        }

        val ax = (ox + 96 * scale).toInt()
        val gridTop = (if (twoRows) 530 else 610) * scale
        val ay = (oy + gridTop).toInt()
        appRail.layout(ax, ay, ax + appRail.measuredWidth, ay + appRail.measuredHeight)

        appButtons.forEach { button ->
            button.contentDescription = button.text.toString() + (if (moving?.component == (button.tag as? LauncherApps.Entry)?.component && moving != null) "，移動中" else "")
            button.isSelected = moving?.component == (button.tag as? LauncherApps.Entry)?.component && moving != null
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val entry = moving ?: return
        val tile = appButtons.firstOrNull { (it.tag as? LauncherApps.Entry)?.component == entry.component } ?: return
        val x = appRail.left + tile.left.toFloat()
        val y = appRail.top + tile.top + tile.height * .48f
        val order = launcherApps.saved()
        val index = order.indexOf(entry.component.flattenToString())
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * scale
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun arrow(center: Float, left: Boolean, enabled: Boolean) {
            arrowPaint.alpha = if (enabled) 255 else 65
            val sign = if (left) -1 else 1
            val p = Path()
            p.moveTo(center - sign * 3 * scale, y - 6 * scale)
            p.lineTo(center + sign * 3 * scale, y)
            p.lineTo(center - sign * 3 * scale, y + 6 * scale)
            canvas.drawPath(p, arrowPaint)
        }
        arrow(x + 13 * scale, true, index > 0)
        arrow(x + tile.width - 13 * scale, false, index < order.lastIndex)
    }

    fun focusFirst() {
        val entry = activatedFocus.ifEmpty { rememberedFocus }
        val target = if (entry.isEmpty()) null else (appButtons + tabs).firstOrNull {
            it.visibility == View.VISIBLE && it.isFocusable && (it.text?.toString() == entry || it.contentDescription?.toString() == entry)
        }
        (target ?: homeTab).requestFocus()
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int = ink, strong: Boolean = false) {
        paint.shader = null
        paint.color = color
        paint.textSize = size
        paint.typeface = if (strong) bold else regular
        c.drawText(value, x, y, paint)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        paint.shader = null
        paint.isFilterBitmap = true
        if (drawsBackdrop) HomeAppearance.drawBackdrop(context, c, width, height, darkHome, paint)
        c.save()
        c.translate(ox, oy)
        c.scale(scale, scale)

        val now = java.util.Date()
        val zone = java.util.TimeZone.getDefault()
        timeFormat.timeZone = zone
        dateFormat.timeZone = zone
        val time = timeFormat.format(now)
        paint.textSize = if (twoRows) 130f else 150f
        paint.typeface = clockFont
        val timeY = if (twoRows) 290f else 350f

        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = if (darkHome) 0x55000000 else 0x55ffffff
        c.drawText(time, 96f + 3f, timeY + 4f, paint)

        paint.color = if (darkHome) 0xd0f0ede0.toInt() else 0xd02a3a4a.toInt()
        c.drawText(time, 96f, timeY, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (darkHome) 0x66ffffff else 0x66404040.toInt()
        c.drawText(time, 96f, timeY, paint)

        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        text(c, dateFormat.format(now), 100f, if (twoRows) 345f else 405f, 34f)
        val ink = labelInkBounds()
        text(c, "常用應用", 96f, (if (twoRows) 530f else 610f) - 24f, 32f, strong = true)
        c.restore()
    }
}
