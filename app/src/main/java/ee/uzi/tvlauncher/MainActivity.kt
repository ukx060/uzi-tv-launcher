package ee.uzi.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Home screen modelled on tvOS: a Top Shelf showcase (full-bleed art, title, Play / Open,
 * page dots) for the focused app, above a grid of app tiles. Tiles dim unless focused.
 */
class MainActivity : Activity() {

    private lateinit var grid: RecyclerView
    private lateinit var topWrap: View
    private lateinit var bgA: ImageView
    private lateinit var bgB: ImageView
    private lateinit var artScrim: View
    private lateinit var artCard: ImageView
    private lateinit var artFrame: View
    private lateinit var artClip: View
    private lateinit var artScrimLeft: View
    private lateinit var panel: View
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroDesc: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnOpen: TextView
    private lateinit var dots: LinearLayout
    private lateinit var adapter: AppAdapter
    private lateinit var prefs: Prefs
    private lateinit var images: ImageLoader

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cols = 5
    private var dp = 1f
    private var showingA = true
    private var bgKey: String? = null
    private var pendingBg: Runnable? = null
    private var focusPkg: String? = null
    private var currentApp: AppEntry? = null
    private var shelfPkg: String? = null
    private var shelfItems: List<ShelfItem> = emptyList()
    private var shelfIndex = 0
    private var shelfOn = false
    private var shelves: Map<String, List<ShelfItem>> = emptyMap()
    private var loaded = false
    private var initSent = false

    private val rotate = object : Runnable {
        override fun run() {
            if (shelfOn && shelfItems.size > 1 && !panel.hasFocus()) showShelfItem((shelfIndex + 1) % shelfItems.size)
            main.postDelayed(this, ROTATE_MS)
        }
    }

    private val reloadShelvesLater = Runnable { loadShelves() }
    private val tvObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(reloadShelvesLater)
            main.postDelayed(reloadShelvesLater, 2000)
        }
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFormat(PixelFormat.RGBA_8888)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        images = ImageLoader(applicationContext)
        grid = findViewById(R.id.grid)
        topWrap = findViewById(R.id.topWrap)
        bgA = findViewById(R.id.bgA)
        bgB = findViewById(R.id.bgB)
        artScrim = findViewById(R.id.artScrim)
        artCard = findViewById(R.id.artCard)
        artFrame = findViewById(R.id.artFrame)
        artClip = findViewById(R.id.artClip)
        artClip.clipToOutline = true
        findViewById<ImageView>(R.id.artShadow).setImageBitmap(softShadow((468 * dp).toInt(), (290 * dp).toInt(), (30 * dp)))
        artScrimLeft = findViewById(R.id.artScrimLeft)
        panel = findViewById(R.id.shelfPanel)
        heroTitle = findViewById(R.id.heroTitle)
        heroSub = findViewById(R.id.heroSub)
        heroDesc = findViewById(R.id.heroDesc)
        btnPlay = findViewById(R.id.btnPlay)
        btnOpen = findViewById(R.id.btnOpen)
        dots = findViewById(R.id.dots)

        val dm = resources.displayMetrics
        dp = dm.density
        val gap = (24 * dp).toInt()
        val side = (56 * dp).toInt() - gap / 2
        grid.setPadding(side, grid.paddingTop, side, grid.paddingBottom)
        val tileW = (dm.widthPixels - 2 * side) / cols - gap
        val tileH = tileW * 9 / 16

        adapter = AppAdapter(tileW, tileH, 22 * dp, ::onTileFocused, ::launch, ::showMenu, ::onTileUp)
        grid.layoutManager = GridLayoutManager(this, cols)
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, v: View, p: RecyclerView, s: RecyclerView.State) {
                out.set(0, 0, 0, (18 * dp).toInt())
            }
        })
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(40)
        grid.adapter = adapter
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = applyScrollFade()
        })

        setupButtons()

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        if (hasListings()) startShelves() else requestPermissions(arrayOf(PERM_LISTINGS), REQ_LISTINGS)
        reload()
    }

    // ---------- lifecycle ----------

    override fun onResume() {
        super.onResume()
        if (hasListings()) loadShelves()
        main.removeCallbacks(rotate); main.postDelayed(rotate, ROTATE_MS)
    }

    override fun onPause() { main.removeCallbacks(rotate); super.onPause() }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        try { contentResolver.unregisterContentObserver(tvObserver) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        io.shutdownNow(); images.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        grid.scrollToPosition(0)
        focusPosition(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            panel.hasFocus() -> focusApp(shelfPkg)
            grid.computeVerticalScrollOffset() > 0 -> { grid.smoothScrollToPosition(0); focusPosition(0) }
        }
    }

    // ---------- permissions / shelves ----------

    private fun hasListings() = checkSelfPermission(PERM_LISTINGS) == PackageManager.PERMISSION_GRANTED

    private fun startShelves() {
        try {
            contentResolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, tvObserver)
            contentResolver.registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, tvObserver)
        } catch (_: Exception) {}
        loadShelves()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_LISTINGS && hasListings()) startShelves()
    }

    private fun loadShelves() {
        io.execute {
            val m = try { ShelfRepo.load(applicationContext) } catch (_: Exception) { emptyMap() }
            main.post {
                shelves = m
                if (!panel.hasFocus()) { shelfPkg = null; currentApp?.let { showAppShelf(it) } }
            }
        }
    }

    /** Apps publish Top Shelf rows when a launcher asks; the stock launcher normally does this. */
    private fun requestPrograms(list: List<AppEntry>) {
        for (e in list) if (!e.isSettings) try {
            sendBroadcast(Intent(TvContract.ACTION_INITIALIZE_PROGRAMS).setPackage(e.pkg))
        } catch (_: Exception) {}
    }

    // ---------- apps ----------

    private fun reload() {
        io.execute {
            val list = AppRepo.load(applicationContext, prefs)
            if (!initSent) { initSent = true; requestPrograms(list) }
            main.post {
                adapter.submit(list)
                val target = focusPkg?.let { p -> list.indexOfFirst { it.pkg == p } }?.takeIf { it >= 0 } ?: 0
                if (!loaded || (grid.focusedChild == null && !panel.hasFocus())) focusPosition(target)
                loaded = true
            }
        }
    }

    private fun focusApp(pkg: String?) {
        focusPosition(adapter.items.indexOfFirst { it.pkg == pkg }.coerceAtLeast(0))
    }

    private fun focusPosition(pos: Int) {
        if (pos >= adapter.itemCount) return
        grid.post {
            val vh = grid.findViewHolderForAdapterPosition(pos)
            if (vh != null) vh.itemView.findViewById<View>(R.id.tile).requestFocus()
            else {
                grid.scrollToPosition(pos)
                grid.post { grid.findViewHolderForAdapterPosition(pos)?.itemView?.findViewById<View>(R.id.tile)?.requestFocus() }
            }
        }
    }

    private fun onTileFocused(e: AppEntry, pos: Int) {
        focusPkg = e.pkg
        currentApp = e
        showAppShelf(e)
    }

    /** UP from the first row jumps into the Top Shelf (always onto Play). */
    private fun onTileUp(pos: Int): Boolean {
        if (pos >= cols || !shelfOn) return false
        btnPlay.requestFocus()
        return true
    }

    // ---------- Top Shelf ----------

    private fun setupButtons() {
        for (b in arrayOf(btnPlay, btnOpen)) {
            b.setOnFocusChangeListener { v, has ->
                val s = if (has) 1.08f else 1f
                v.animate().scaleX(s).scaleY(s).translationZ(if (has) 10 * dp else 0f).setDuration(150).start()
            }
        }
        btnPlay.setOnClickListener { shelfItems.getOrNull(shelfIndex)?.let { openShelf(it) } }
        btnOpen.setOnClickListener { currentApp?.let { launch(it) } }
        val keys = View.OnKeyListener { v, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when {
                code == KeyEvent.KEYCODE_DPAD_DOWN -> { focusApp(shelfPkg); true }
                code == KeyEvent.KEYCODE_DPAD_UP -> true
                code == KeyEvent.KEYCODE_DPAD_LEFT && v === btnPlay -> { stepShelf(-1); true }
                code == KeyEvent.KEYCODE_DPAD_RIGHT && v === btnOpen -> { stepShelf(1); true }
                else -> false
            }
        }
        btnPlay.setOnKeyListener(keys)
        btnOpen.setOnKeyListener(keys)
    }

    private fun stepShelf(d: Int) {
        if (shelfItems.size < 2) return
        showShelfItem((shelfIndex + d + shelfItems.size) % shelfItems.size)
    }

    private fun showAppShelf(e: AppEntry) {
        val items = shelves[e.pkg].orEmpty()
        if (items.isEmpty()) {
            shelfPkg = null; shelfItems = emptyList()
            setShelf(false)
            clearBackground()
            return
        }
        if (shelfPkg != e.pkg) {
            shelfPkg = e.pkg
            shelfItems = items
            shelfIndex = 0
            buildDots(items.size)
        }
        btnOpen.text = "Open ${e.label}"
        setShelf(true)
        showShelfItem(shelfIndex.coerceIn(0, items.size - 1))
        main.removeCallbacks(rotate); main.postDelayed(rotate, ROTATE_MS)
    }

    private fun setShelf(on: Boolean) {
        shelfOn = on
        btnPlay.isFocusable = on
        btnOpen.isFocusable = on
        val a = if (on) 1f else 0f
        panel.animate().alpha(a).setDuration(260).start()
        artScrim.animate().alpha(a).setDuration(260).start()
        artScrimLeft.animate().alpha(a).setDuration(260).start()
    }

    private fun buildDots(n: Int) {
        dots.removeAllViews()
        if (n < 2) return
        repeat(n) {
            val v = View(this)
            val lp = LinearLayout.LayoutParams((5 * dp).toInt(), (5 * dp).toInt())
            lp.marginEnd = (6 * dp).toInt()
            v.layoutParams = lp
            v.setBackgroundResource(R.drawable.dot)
            dots.addView(v)
        }
    }

    private fun updateDots() {
        for (i in 0 until dots.childCount) {
            val v = dots.getChildAt(i)
            val active = i == shelfIndex
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply {
                width = ((if (active) 16 else 5) * dp).toInt()
            }
            v.alpha = if (active) 0.95f else 0.3f
        }
    }

    private fun showShelfItem(i: Int) {
        val item = shelfItems.getOrNull(i) ?: return
        shelfIndex = i
        val app = currentApp?.label ?: ""
        heroSub.text = if (item.upNext) "Up Next · $app" else "Featured on $app"
        heroTitle.text = item.title
        heroDesc.text = item.subtitle ?: ""
        heroDesc.visibility = if (item.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        btnPlay.text = if (item.upNext) "Resume" else "Play"
        updateDots()
        val img = item.image
        if (img == null) { clearBackground(); return }
        setBackground("img|$img") { cb -> images.load(img, 1280) { b -> cb(b) } }
    }

    private fun openShelf(item: ShelfItem) {
        try {
            item.intentUri?.let {
                startActivity(Intent.parseUri(it, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        } catch (_: Exception) {}
        adapter.items.firstOrNull { it.pkg == item.pkg }?.let { launch(it) }
    }

    private fun applyScrollFade() {
        val off = grid.computeVerticalScrollOffset()
        val f = (1f - off / (160 * dp)).coerceIn(0f, 1f)
        topWrap.alpha = f
        val canFocus = shelfOn && f > 0.5f
        btnPlay.isFocusable = canFocus
        btnOpen.isFocusable = canFocus
    }

    // ---------- background ----------

    private fun setBackground(key: String, produce: ((Bitmap?) -> Unit) -> Unit) {
        if (bgKey == key) return
        bgKey = key
        pendingBg?.let { main.removeCallbacks(it) }
        val r = Runnable { produce { b -> if (b != null && bgKey == key) crossfade(b) } }
        pendingBg = r
        main.postDelayed(r, 120)
    }

    private fun clearBackground() {
        bgKey = null
        pendingBg?.let { main.removeCallbacks(it) }
        bgA.animate().alpha(0f).setDuration(350).start()
        bgB.animate().alpha(0f).setDuration(350).start()
        artFrame.animate().alpha(0f).setDuration(250).start()
    }

    /**
     * Sharp, large art goes full-bleed. Small art (e.g. 480px YouTube thumbnails) would look cheap
     * stretched, so it is shown as a crisp card and the background becomes a soft ambient blur of it.
     */
    private fun crossfade(bmp: Bitmap) {
        val hiRes = bmp.width >= 1100
        val bg = if (hiRes) bmp else Ambient.blur(bmp)
        val show = if (showingA) bgB else bgA
        val hide = if (showingA) bgA else bgB
        show.setImageBitmap(bg)
        show.setColorFilter(if (hiRes) 0 else 0x4D000000, android.graphics.PorterDuff.Mode.SRC_ATOP)
        show.animate().cancel()
        show.scaleX = 1f; show.scaleY = 1f
        show.animate().alpha(1f).setDuration(500).start()
        if (hiRes) kenBurns(show)
        hide.animate().alpha(0f).setDuration(500).start()
        showingA = !showingA
        if (hiRes) artFrame.animate().alpha(0f).setDuration(250).start()
        else {
            artCard.setImageBitmap(bmp)
            artFrame.alpha = 0f
            artFrame.translationY = 8 * dp
            artFrame.animate().alpha(1f).translationY(0f).setDuration(500).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            kenBurns(artCard)
        }
    }

    /** Slow push-in on artwork, like the motion in Apple TV's Top Shelf. */
    private fun kenBurns(v: View) {
        v.animate().cancel()
        v.scaleX = 1f; v.scaleY = 1f
        v.animate().scaleX(1.07f).scaleY(1.07f).setDuration(ROTATE_MS + 1500)
            .setInterpolator(android.view.animation.LinearInterpolator()).start()
    }

    private fun softShadow(w: Int, h: Int, pad: Float): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC000000.toInt()
            maskFilter = android.graphics.BlurMaskFilter(pad * 0.6f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        android.graphics.Canvas(b).drawRoundRect(android.graphics.RectF(pad, pad, w - pad, h - pad), 16 * dp, 16 * dp, p)
        return b
    }

    // ---------- actions ----------

    private fun launch(e: AppEntry) {
        try {
            if (e.isSettings) {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            }
            val pm = packageManager
            val i = pm.getLeanbackLaunchIntentForPackage(e.pkg) ?: pm.getLaunchIntentForPackage(e.pkg)
            if (i == null) { toast("Can't open ${e.label}"); return }
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ex: Exception) { toast("Can't open ${e.label}") }
    }

    private fun showMenu(e: AppEntry, pos: Int) {
        if (e.isSettings) {
            val opts = arrayOf("Show hidden apps (${prefs.hidden.size})", "Reset app order", "Refresh Top Shelf", "Android TV settings")
            AlertDialog.Builder(this).setTitle("Uzi TV").setItems(opts) { _, w ->
                when (w) {
                    0 -> { prefs.hidden = emptySet(); reload() }
                    1 -> { prefs.order = Prefs.DEFAULT_ORDER; reload() }
                    2 -> if (hasListings()) { requestPrograms(adapter.items); loadShelves() }
                         else requestPermissions(arrayOf(PERM_LISTINGS), REQ_LISTINGS)
                    3 -> launch(e)
                }
            }.show()
            return
        }
        val opts = arrayOf("Move to front", "Move left", "Move right", "Hide", "App info")
        AlertDialog.Builder(this).setTitle(e.label).setItems(opts) { _, w ->
            val last = adapter.itemCount - 2
            when (w) {
                0 -> moveTo(pos, 0)
                1 -> moveTo(pos, (pos - 1).coerceAtLeast(0))
                2 -> moveTo(pos, (pos + 1).coerceAtMost(last))
                3 -> { prefs.hidden = prefs.hidden + e.pkg; reload() }
                4 -> try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { toast("App info not available") }
            }
        }.show()
    }

    private fun moveTo(from: Int, to: Int) {
        adapter.move(from, to)
        prefs.order = adapter.items.filter { !it.isSettings }.map { it.pkg }
        focusPkg = adapter.items[to].pkg
        focusPosition(to)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_LISTINGS = 7
        private const val PERM_LISTINGS = "android.permission.READ_TV_LISTINGS"
        private const val ROTATE_MS = 7000L
    }
}
