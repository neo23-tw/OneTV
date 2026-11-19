package com.tvbridge.app

import android.app.*
import android.media.AudioManager
import android.os.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.*
import android.provider.Settings
import android.view.*
import android.widget.*

class MainActivity : Activity() {
    @JvmField var visible = false
    private lateinit var root: FrameLayout
    private lateinit var home: TvHomeView
    private var livePanel: LivePanel? = null
    private lateinit var apkInstaller: ApkInstaller
    private val updates by lazy { UpdateController(this, apkInstaller) }
    private var appsPanel: View? = null
    private var applications: LauncherApps? = null
    private var appsScanGeneration = 0
    private var appsEditing = false
    private var catalogReceiverRegistered = false
    private val catalogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LauncherApps.invalidate(context)
            if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) LiveLaunch.component(context)
            if (::home.isInitialized) {
                home.refreshApps()
                if (screen == "apps") showApps(appsEditing)
            }
        }
    }
    private val appearanceUi by lazy { AppearanceController(this, { keep -> appearanceUpdated(keep) }) }
    private val startupPrefs by lazy { getSharedPreferences("startup", MODE_PRIVATE) }
    private var screen = "home"
    private val handler = Handler(Looper.getMainLooper())
    private val liveSettings by lazy { LiveSettings(this) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(ColorDrawable(if (HomeAppearance.dark(this)) 0xff162233.toInt() else 0xffe9eff4.toInt()))
        if (intent.getBooleanExtra("from_boot", false)) BootReceiver.markArrived(this)
        apkInstaller = ApkInstaller(this)
        apkInstaller.restore(state)
        window.decorView.systemUiVisibility = 5894 or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        Hub.activity = this
        Hub.start(this)

        root = FrameLayout(this)
        sceneBackground(false)
        setContentView(root)
        UiSounds.attach(this)

        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(catalogReceiver, packages, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(catalogReceiver, packages)
        catalogReceiverRegistered = true

        home = TvHomeView(
            this,
            { openTelevision() },
            { showAppSettings() },
            { showApps() },
            { showApps(true) }
        ).apply { drawsBackdrop = false }

        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        home.focusFirst()

        Thread({ runCatching { LauncherApps(this).installed() } }, "apps-prewarm").start()
        HomeAppearance.prewarm(this)

        if (intent.getBooleanExtra("open_live", false)) root.post { openSelectedLive() }
        else if (state == null && !intent.getBooleanExtra("show_home", false)) scheduleLiveStartup()
    }

    private var startupDialog: AlertDialog? = null
    private fun scheduleLiveStartup() {
        startupDialog?.dismiss()
        if (screen == "live" || !startupPrefs.getBoolean("live", false)) return
        var seconds = 3
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val notice = TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            NativeUi.textSize(this, 30f)
            setTextColor(GlassUi.ink(this@MainActivity))
        }
        row.addView(notice, LinearLayout.LayoutParams(0, dp(32), 1f))
        var countdown: AlertDialog? = null
        val cancel = Button(this).apply {
            text = "取消"
            isAllCaps = false
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            NativeUi.textSize(this, 28f)
            setTextColor(GlassUi.colors(this@MainActivity))
            if (Build.VERSION.SDK_INT >= 21) {
                backgroundTintList = null
                stateListAnimator = null
                elevation = 0f
            }
            background = GlassUi.surface(this)
            setOnClickListener { countdown?.dismiss() }
            NativeUi.touchClicks(this)
        }
        row.addView(cancel, LinearLayout.LayoutParams(dp(58), dp(32)).apply { leftMargin = dp(12) })
        fun update() { notice.text = "${seconds} 秒后进入直播" }
        update()
        val dialog = NativeUi.show(AlertDialog.Builder(this).setView(row), compact = true)
        countdown = dialog
        cancel.requestFocus()
        startupDialog = dialog
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || isFinishing || isDestroyed) return
                seconds--
                if (seconds <= 0) {
                    dialog.dismiss()
                    openTelevision()
                } else {
                    update()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        dialog.setOnDismissListener {
            NativeUi.handleExternalDismiss(dialog)
            handler.removeCallbacks(tick)
            if (startupDialog === dialog) startupDialog = null
        }
        handler.postDelayed(tick, 1000)
    }

    private fun dp(value: Int) = (value * 2 * NativeUi.scale(this)).toInt()

    private fun button(parent: LinearLayout, label: String, action: () -> Unit): Button {
        val b = Button(this)
        if (Build.VERSION.SDK_INT >= 21) b.backgroundTintList = null
        if (Build.VERSION.SDK_INT >= 21) b.stateListAnimator = null
        if (Build.VERSION.SDK_INT >= 26) b.defaultFocusHighlightEnabled = false
        b.text = label
        b.isAllCaps = false
        NativeUi.textSize(b, 30f)
        AppIcons.bind(b, size = 30f)
        b.minWidth = 0; b.minimumWidth = 0
        b.setPadding(dp(16), 0, dp(16), 0)
        b.id = View.generateViewId()
        b.setTextColor(GlassUi.colors(this))
        b.background = GlassUi.surface(b, faint = true)
        b.setOnClickListener { action() }
        NativeUi.touchClicks(b)
        parent.addView(b, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(8) })
        return b
    }

    private fun isDefaultHome(): Boolean =
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == packageName

    private fun openHomeSettings() {
        for (action in listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)) {
            try { startActivity(Intent(action)); return } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
        NativeUi.notice(this, "系统未提供默认桌面设置")
    }

    private fun chooseDefaultHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(android.app.role.RoleManager::class.java)
                if (roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 20)
                    return
                }
            }
        } catch (_: RuntimeException) {}
        val homeChoice = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolver = packageManager.resolveActivity(homeChoice, 0)?.activityInfo
        if (resolver?.packageName == "android" && resolver.name.endsWith("ResolverActivity")) {
            try { startActivityForResult(homeChoice, 20); return } catch (_: RuntimeException) {}
        }
        openHomeSettings()
    }

    private fun desktopLabel() = if (isDefaultHome()) "已设为默认" else "未设置"

    internal fun openSelectedLive() {
        showHome()
        openTelevision()
    }

    private fun openTelevision() {
        startupDialog?.dismiss()
        if (LiveLaunch.open(this)) return
        showLive()
    }

    private fun showAppSettings(keepDialog: Boolean = false) {
        showHome(keepDialog)
        screen = "settings"
        home.visibility = View.GONE
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(28))
        }
        appsPanel = page
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        page.addView(header, LinearLayout.LayoutParams(-1, dp(48)))
        button(header, "返回") { showHome() }
        header.addView(TextView(this).apply {
            text = "设置"
            NativeUi.textSize(this, 40f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(GlassUi.ink(this@MainActivity))
            setPadding(dp(18), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val shortcuts = SystemControls.quickActions(this)
        val shortcutWidth = (0 until shortcuts.childCount).sumOf {
            (shortcuts.getChildAt(it).layoutParams as LinearLayout.LayoutParams).let { p -> p.width + p.leftMargin + p.rightMargin }
        }
        header.addView(shortcuts, LinearLayout.LayoutParams(shortcutWidth, dp(40)))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
        }
        page.addView(scroll, LinearLayout.LayoutParams(dp(480), 0, 1f).apply { topMargin = dp(24) })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GlassUi.surface(this, GlassUi.PANEL_RADIUS)
        }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -1))

        fun action(parent: LinearLayout, label: String, detail: String = "", run: () -> Unit): Button =
            button(parent, label, run).apply {
                setOnClickListener { NativeUi.selectControl(this); run() }
                layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) }
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                NativeUi.textSize(this, 30f)
                setCompoundDrawablesRelative(null, null, NativeUi.trailing(this, detail, ink = true), null)
            }

        fun toggle(parent: LinearLayout, label: String, checked: Boolean, change: (Boolean) -> Unit) {
            val v = SettingsSwitch(this).apply {
                text = label
                isChecked = checked
                NativeUi.textSize(this, 30f)
                setTextColor(GlassUi.colors(this@MainActivity))
                setPadding(dp(16), 0, dp(16), 0)
                background = GlassUi.surface(this, 20f, faint = true)
                if (Build.VERSION.SDK_INT >= 21) {
                    backgroundTintList = null
                    stateListAnimator = null
                }
                if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
                setOnCheckedChangeListener { _, on -> change(on) }
                NativeUi.touchClicks(this)
            }
            parent.addView(v, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) })
        }

        val desktop = action(body, "默认桌面应用", desktopLabel()) { chooseDefaultHome() }
        action(body, "桌面背景", HomeAppearance.label(this)) { appearanceUi.wallpaper() }
        action(body, "直播管理") { liveSettings.show() }
        toggle(body, "打开时进入直播", startupPrefs.getBoolean("live", false)) { startupPrefs.edit().putBoolean("live", it).apply() }
        toggle(body, "按键音", UiSounds.enabled(this)) { UiSounds.setEnabled(this, it) }
        action(body, "检查更新") { updates.check() }

        header.getChildAt(0).nextFocusDownId = desktop.id
        desktop.requestFocus()
    }

    private fun showApps(addMode: Boolean = false) {
        if (screen != "home") showHome()
        screen = "apps"
        home.visibility = View.GONE
        val repository = applications ?: LauncherApps(this).also { applications = it }
        var entries = listOf<LauncherApps.Entry>()
        val generation = ++appsScanGeneration
        val editing = addMode
        appsEditing = editing
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(24), dp(56), dp(24))
            background = GlassUi.backdrop(this)
        }
        appsPanel = page
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        page.addView(header, LinearLayout.LayoutParams(-1, dp(44)))
        button(header, "返回") { showHome() }
        header.addView(TextView(this).apply {
            text = if (addMode) "添加常用应用" else "全部应用"
            NativeUi.textSize(this, 40f)
            setTextColor(GlassUi.ink(this@MainActivity))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (addMode) button(header, "完成") { showHome() }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        val grid = GridLayout(this).apply { columnCount = 5 }
        scroll.addView(grid, FrameLayout.LayoutParams(-1, -2))

        fun render() {
            grid.removeAllViews()
            val saved = repository.saved()
            entries.forEachIndexed { i, entry ->
                val selected = entry.component.flattenToString() in saved
                val b = object : Button(this@MainActivity) {
                    private val mark = AppIcons.Vector("star") { currentTextColor }
                    override fun onDraw(canvas: android.graphics.Canvas) {
                        super.onDraw(canvas)
                        if (editing && isSelected) {
                            val size = dp(11)
                            mark.setBounds(width - dp(9) - size, dp(9), width - dp(9), dp(9) + size)
                            mark.draw(canvas)
                        }
                    }
                }.apply {
                    text = entry.label
                    contentDescription = entry.label + (if (editing) if (selected) "，已添加到首页" else "，添加到首页" else "")
                    isAllCaps = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    NativeUi.textSize(this, 30f)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    if (Build.VERSION.SDK_INT >= 21) {
                        backgroundTintList = null
                        stateListAnimator = null
                    }
                    if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setTextColor(GlassUi.colors(this@MainActivity))
                    background = GlassUi.surface(this, faint = true)
                    isSelected = editing && selected
                    isFocusableInTouchMode = true
                }
                try {
                    val icon = repository.icon(entry)
                    icon.setBounds(0, 0, dp(34), dp(34))
                    b.setCompoundDrawables(null, icon, null, null)
                    b.compoundDrawablePadding = dp(8)
                } catch (_: Exception) {}
                b.tag = entry
                if (!editing) b.setOnLongClickListener { showInstalledAppMenu(entry); true }
                b.setOnClickListener {
                    if (editing) {
                        if (!repository.toggle(entry)) NativeUi.notice(this, "常用应用最多添加 10 个")
                        render()
                        grid.getChildAt(i)?.requestFocus()
                    } else if (!repository.launch(entry)) NativeUi.notice(this, "无法打开应用")
                }
                NativeUi.touchClicks(b)
                grid.addView(b, GridLayout.LayoutParams().apply {
                    width = if (Build.VERSION.SDK_INT >= 21) 0 else (resources.displayMetrics.widthPixels - dp(152)) / 5
                    height = dp(100)
                    columnSpec = if (Build.VERSION.SDK_INT >= 21) GridLayout.spec(i % 5, 1f) else GridLayout.spec(i % 5)
                    rowSpec = GridLayout.spec(i / 5)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            if (entries.isEmpty()) grid.addView(TextView(this).apply { text = "暂无可打开的应用"; NativeUi.textSize(this, 28f) })
        }

        Thread({
            val found = runCatching { repository.installed() }.getOrDefault(emptyList())
            runOnUiThread {
                if (generation != appsScanGeneration || isFinishing || isDestroyed || screen != "apps") return@runOnUiThread
                entries = found
                render()
                grid.getChildAt(0)?.requestFocus()
            }
        }, "apps-scan").start()
    }

    private fun showInstalledAppMenu(entry: LauncherApps.Entry) {
        val flags = try { packageManager.getApplicationInfo(entry.component.packageName, 0).flags } catch (_: Exception) { android.content.pm.ApplicationInfo.FLAG_SYSTEM }
        val choices = mutableListOf("打开")
        if (entry.component.flattenToString() in applications?.saved().orEmpty()) choices.add("移出常用")
        if (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) choices.add("卸载应用")
        else if (flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) choices.add("卸载更新")
        choices.add("应用信息")
        NativeUi.show(AlertDialog.Builder(this).setTitle(entry.label).setItems(choices.toTypedArray()) { _, which ->
            when (choices[which]) {
                "打开" -> applications?.launch(entry)
                "移出常用" -> {
                    applications?.remove(entry)
                    home.refreshApps()
                    NativeUi.notice(this, "已移出常用")
                }
                "卸载应用", "卸载更新" -> try {
                    startActivityForResult(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${entry.component.packageName}")), 52)
                } catch (_: RuntimeException) { NativeUi.notice(this, "系统未提供卸载入口") }
                else -> try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${entry.component.packageName}")))
                } catch (_: RuntimeException) { NativeUi.notice(this, "无法打开应用信息") }
            }
        })
    }

    private fun showFocusedAppMenu(): Boolean {
        if (screen != "apps" || appsEditing) return false
        var view = appsPanel?.findFocus()
        while (view != null) {
            (view.tag as? LauncherApps.Entry)?.let { showInstalledAppMenu(it); return true }
            view = view.parent as? View
        }
        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("from_boot", false)) BootReceiver.markArrived(this)
        if (::home.isInitialized) {
            if (intent.getBooleanExtra("open_live", false)) openSelectedLive()
            else if (intent.getBooleanExtra("show_home", false) || intent.hasCategory(Intent.CATEGORY_HOME)) showHome()
            else scheduleLiveStartup()
        }
    }

    private fun sceneBackground(live: Boolean) {
        val color = if (live) 0xff121820.toInt() else if (HomeAppearance.dark(this)) 0xff162233.toInt() else 0xffe9eff4.toInt()
        window.setBackgroundDrawable(ColorDrawable(color))
        if (::root.isInitialized) root.background = if (live) ColorDrawable(color) else GlassUi.backdrop(root)
    }

    fun showHome(keepDialog: Boolean = false) {
        sceneBackground(false)
        appsPanel?.let { root.removeView(it) }
        appsPanel = null
        if (!keepDialog) NativeUi.dialogCommand("back")
        screen = "home"
        livePanel?.stop()
        livePanel?.let { root.removeView(it) }
        livePanel = null
        home.visibility = View.VISIBLE
        home.refreshAppearance()
        home.refreshApps()
        home.focusFirst()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (HomeAppearance.mode(this) == "bing") HomeAppearance.refreshBingIfStale(this) {
            if (!isDestroyed) {
                home.refreshAppearance()
                home.invalidate()
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun showLive(autoPlay: Boolean = true) {
        startupDialog?.dismiss()
        val previous = if (autoPlay) Hub.live.resumeChannel() else null
        if (autoPlay && previous == null && Hub.live.sourceList().isEmpty()) {
            showLiveSetup()
            return
        }
        if (screen == "live") return
        if (screen != "home") { showHome() }
        sceneBackground(true)
        home.visibility = View.GONE
        screen = "live"
        livePanel = LivePanel(
            this,
            Hub.live,
            { showHome() },
            noSources = { showLiveSetup() },
            pageFactory = { c, tap, state -> YangshipinPlayer(c, tap, state) }
        )
        root.addView(livePanel, FrameLayout.LayoutParams(-1, -1))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (autoPlay && previous != null) livePanel?.play(previous, false)
    }

    private fun showLiveSetup() {
        showHome()
        home.visibility = View.GONE
        screen = "liveSetup"
        val setup = FrameLayout(this).apply { background = GlassUi.backdrop(this) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        button(header, "返回") { showHome() }
        setup.addView(header, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(48); topMargin = dp(28)
        })

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GlassUi.surface(this, 28f)
        }
        val title = TextView(this).apply {
            text = "暂无直播源，请选择配置"
            NativeUi.textSize(this, 32f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(GlassUi.ink(this@MainActivity))
            setPadding(0, 0, 0, dp(16))
        }
        box.addView(title)

        val defaultBtn = button(box, "使用默认直播源") {
            Thread({
                LiveLaunch.selectInternal(this@MainActivity, "one", Hub.live)
                runOnUiThread {
                    NativeUi.notice(this@MainActivity, "已加载默认直播源")
                    showLive()
                }
            }, "live-load-default").start()
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(240), dp(44)).apply { bottomMargin = dp(10) } }

        button(box, "输入直播源网址") {
            val input = EditText(this).apply {
                hint = "请输入直播源网址 (http:// 或 https://)"
                isSingleLine = true
                setTextColor(GlassUi.ink(this@MainActivity))
            }
            NativeUi.show(AlertDialog.Builder(this).setTitle("添加直播源").setView(input).setPositiveButton("订阅") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    Thread({
                        val ok = Hub.live.subscribe(url)
                        runOnUiThread {
                            NativeUi.notice(this@MainActivity, if (ok) "订阅成功" else Hub.live.lastMessage.ifEmpty { "订阅失败" })
                            if (ok) showLive()
                        }
                    }, "live-subscribe-custom").start()
                }
            }.setNegativeButton("取消", null))
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(240), dp(44)) }

        setup.addView(box, FrameLayout.LayoutParams(dp(360), -2, Gravity.CENTER))
        appsPanel = setup
        root.addView(setup, FrameLayout.LayoutParams(-1, -1))
        defaultBtn.requestFocus()
    }

    fun liveUpdated() {
        if (screen == "liveSetup" && Hub.live.resumeChannel() != null) showLive()
        else livePanel?.refresh()
    }

    fun appearanceUpdated(keepDialog: Boolean = false) {
        if (!keepDialog) NativeUi.dialogCommand("back")
        home.refreshAppearance()
        if (screen == "settings") showAppSettings(keepDialog) else root.invalidate()
        HomeAppearance.prewarm(this)
    }

    fun playChannel(id: String): Boolean {
        val channel = Hub.live.findChannel(id) ?: return false
        showLive(false)
        livePanel?.play(channel, true)
        return true
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (appearanceUi.onResult(request, result, data)) { startupDialog?.dismiss(); return }
        if (apkInstaller.onResult(request)) return
        if (request == 20) return
        if (request == 52) { showApps(false); return }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        UiSounds.key(this, event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && screen == "home" &&
            event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) &&
            ::home.isInitialized && home.visibility == View.VISIBLE) {
            val focus = currentFocus
            val bad = focus == null || focus.visibility != View.VISIBLE || !focus.isFocusable || !isInHome(focus) || focus === home
            if (bad) { home.focusFirst(); return super.dispatchKeyEvent(event) }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode == KeyEvent.KEYCODE_MENU && showFocusedAppMenu()) return true
        if (event.action == KeyEvent.ACTION_DOWN && screen == "home" && event.keyCode == KeyEvent.KEYCODE_SETTINGS) { showAppSettings(); return true }
        if (event.action == KeyEvent.ACTION_DOWN && screen == "home") {
            if (home.handleKey(event.keyCode)) return true
            if (event.keyCode == KeyEvent.KEYCODE_MENU) { showAppSettings(); return true }
        }
        if (event.action == KeyEvent.ACTION_DOWN && screen == "live" && livePanel?.handleKey(event.keyCode) == true) return true
        return super.dispatchKeyEvent(event)
    }

    private fun exitOne() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask() else finish()
    }

    override fun onBackPressed() {
        when (screen) {
            "apps", "settings", "liveSetup" -> showHome()
            "live" -> if (livePanel?.back() != true) showHome()
            else -> if (!isDefaultHome()) exitOne()
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        apkInstaller.save(state)
    }

    override fun onResume() {
        super.onResume()
        LiveLaunch.component(this)
        apkInstaller.onResume()
        apkInstaller.checkInstallStatus()
        if (::home.isInitialized) {
            if (screen == "liveSetup" && Hub.live.resumeChannel() != null) {
                showLive(false)
            }
        }
        visible = true
        if (::home.isInitialized && screen == "home") {
            home.refreshApps()
            home.focusFirst()
        }
        livePanel?.resume()
    }

    override fun onPause() {
        apkInstaller.onPause()
        startupDialog?.dismiss()
        visible = false
        livePanel?.pause()
        super.onPause()
    }

    private fun isInHome(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === home) return true
            current = current.parent as? View
        }
        return false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && screen == "home" && ::home.isInitialized && home.visibility == View.VISIBLE) {
            val focus = currentFocus
            if (focus == null || focus.visibility != View.VISIBLE || !focus.isFocusable || !isInHome(focus)) {
                home.focusFirst()
            }
        }
    }

    override fun onDestroy() {
        apkInstaller.destroy()
        updates.destroy()
        if (catalogReceiverRegistered) {
            unregisterReceiver(catalogReceiver)
            catalogReceiverRegistered = false
        }
        handler.removeCallbacksAndMessages(null)
        livePanel?.stop()
        if (Hub.activity === this) Hub.activity = null
        Hub.stop()
        super.onDestroy()
    }
}
