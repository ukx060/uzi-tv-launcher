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
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var grid: RecyclerView
    private lateinit var shelf: RecyclerView
    private lateinit var bgA: ImageView
    private lateinit var bgB: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroDesc: TextView
    private lateinit var adapter: AppAdapter
    private lateinit var shelfAdapter: ShelfAdapter
    private lateinit var prefs: Prefs
    private lateinit var images: ImageLoader

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val blurCache = LruCache<String, Bitmap>(32)
    private var showingA = true
    private var bgKey: String? = null
    private var pendingBg: Runnable? = null
    private var focusPkg: String? = null
    private var currentApp: AppEntry? = null
    private var shelfPkg: String? = null
    private var rotIndex = 0
    private var shelves: Map<String, List<ShelfItem>> = emptyMap()
    private var loaded = false

    private val rotate = object : Runnable {
        override fun run() {
            val items = shelfAdapter.items
            if (items.size > 1 && !shelf.hasFocus()) {
                rotIndex = (rotIndex + 1) % items.size
                showShelfItem(items[rotIndex], rotIndex)
                shelf.smoothScrollToPosition(rotIndex)
            }
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
        override fun onReceive(c: Context, i: Intent) {
            i.data?.schemeSpecificPart?.let { blurCache.remove(it) }
            reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFormat(PixelFormat.RGBA_8888) // avoid 16-bit banding on TV surfaces
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        images = ImageLoader(applicationContext)
        grid = findViewById(R.id.grid)
        shelf = findViewById(R.id.shelf)
        bgA = findViewById(R.id.bgA)
        bgB = findViewById(R.id.bgB)
        heroTitle = findViewById(R.id.heroTitle)
        heroSub = findViewById(R.id.heroSub)
        heroDesc = findViewById(R.id.heroDesc)

        val dm = resources.displayMetrics
        val d = dm.density
        val cols = 6
        val gap = (18 * d).toInt()
        val side = (64 * d).toInt() - gap / 2
        grid.setPadding(side, grid.paddingTop, side, grid.paddingBottom)
        val span = (dm.widthPixels - 2 * side) / cols
        val tileW = span - gap
        val tileH = tileW * 9 / 16

        adapter = AppAdapter(tileW, tileH, 18 * d, ::onTileFocused, ::launch, ::showMenu)
        grid.layoutManager = GridLayoutManager(this, cols)
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, v: View, p: RecyclerView, s: RecyclerView.State) {
                out.set(0, 0, 0, (16 * d).toInt())
            }
        })
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(40)
        grid.adapter = adapter

        shelfAdapter = ShelfAdapter(images, (150 * d).toInt(), (84 * d).toInt(), 14 * d, ::onShelfFocused, ::openShelf)
        shelf.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        shelf.itemAnimator = null
        shelf.adapter = shelfAdapter

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })

        if (hasListings()) startShelves()
        else requestPermissions(arrayOf(PERM_LISTINGS), REQ_LISTINGS)

        reload()
    }

    private fun hasListings() =
        checkSelfPermission(PERM_LISTINGS) == PackageManager.PERMISSION_GRANTED

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

    override fun onResume() {
        super.onResume()
        if (hasListings()) loadShelves()
        main.removeCallbacks(rotate)
        main.postDelayed(rotate, ROTATE_MS)
    }

    override fun onPause() {
        main.removeCallbacks(rotate)
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        try { contentResolver.unregisterContentObserver(tvObserver) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        images.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        grid.scrollToPosition(0)
        focusPosition(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (shelf.hasFocus()) focusPkg?.let { p -> focusPosition(adapter.items.indexOfFirst { it.pkg == p }.coerceAtLeast(0)) }
    }

    // ---------- apps ----------

    private fun reload() {
        io.execute {
            val list = AppRepo.load(applicationContext, prefs)
            main.post {
                adapter.submit(list)
                val target = focusPkg?.let { p -> list.indexOfFirst { it.pkg == p } }?.takeIf { it >= 0 } ?: 0
                if (!loaded || (grid.focusedChild == null && !shelf.hasFocus())) focusPosition(target)
                loaded = true
            }
        }
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

    // ---------- top shelf ----------

    private fun loadShelves() {
        io.execute {
            val m = ShelfRepo.load(applicationContext)
            main.post {
                shelves = m
                shelfPkg = null
                currentApp?.let { if (!shelf.hasFocus()) showAppShelf(it) }
            }
        }
    }

    private fun showAppShelf(e: AppEntry) {
        val items = shelves[e.pkg].orEmpty()
        if (items.isEmpty()) {
            shelfPkg = null
            shelfAdapter.submit(emptyList())
            shelf.visibility = View.INVISIBLE
            heroSub.text = ""
            heroTitle.text = e.label
            heroDesc.text = if (e.isSettings) "Hold OK for launcher options" else "Press OK to open · Hold OK for options"
            setBackground("blur|${e.pkg}", blurFor(e))
            return
        }
        if (shelfPkg != e.pkg) {
            shelfPkg = e.pkg
            shelfAdapter.submit(items)
            shelf.scrollToPosition(0)
            rotIndex = 0
        }
        shelf.visibility = View.VISIBLE
        showShelfItem(items[rotIndex.coerceIn(0, items.size - 1)], rotIndex)
        main.removeCallbacks(rotate)
        main.postDelayed(rotate, ROTATE_MS)
    }

    private fun showShelfItem(item: ShelfItem, idx: Int) {
        val app = currentApp?.label ?: ""
        heroSub.text = if (item.upNext) "Up Next · $app" else app
        heroTitle.text = item.title
        heroDesc.text = item.subtitle ?: ""
        shelfAdapter.active = idx
        val img = item.image
        if (img == null) { currentApp?.let { a -> setBackground("blur|${a.pkg}", blurFor(a)) }; return }
        setBackground("img|$img") { cb -> images.load(img, 1280) { b -> cb(b) } }
    }

    private fun onShelfFocused(item: ShelfItem, idx: Int) {
        rotIndex = idx
        showShelfItem(item, idx)
    }

    private fun openShelf(item: ShelfItem) {
        try {
            val uri = item.intentUri
            if (uri != null) {
                startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        } catch (_: Exception) {}
        adapter.items.firstOrNull { it.pkg == item.pkg }?.let { launch(it) }
    }

    // ---------- background ----------

    /** Debounced, keyed background swap. [produce] must call back on the main thread. */
    private fun setBackground(key: String, produce: ((Bitmap?) -> Unit) -> Unit) {
        if (bgKey == key) return
        bgKey = key
        pendingBg?.let { main.removeCallbacks(it) }
        val r = Runnable { produce { b -> if (b != null && bgKey == key) crossfade(b) } }
        pendingBg = r
        main.postDelayed(r, 110)
    }

    private fun blurFor(e: AppEntry): ((Bitmap?) -> Unit) -> Unit = { cb: (Bitmap?) -> Unit ->
        val hit = blurCache.get(e.pkg)
        if (hit != null) cb(hit)
        else {
            val d = e.banner ?: e.icon
            if (d == null) cb(null)
            else io.execute {
                val b = try { Blur.fromDrawable(d, e.banner != null) } catch (_: Throwable) { null }
                main.post { if (b != null) blurCache.put(e.pkg, b); cb(b) }
            }
        }
    }

    private fun crossfade(bmp: Bitmap) {
        val show = if (showingA) bgB else bgA
        val hide = if (showingA) bgA else bgB
        show.setImageBitmap(bmp)
        show.animate().alpha(1f).setDuration(400).start()
        hide.animate().alpha(0f).setDuration(400).start()
        showingA = !showingA
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
                    2 -> if (hasListings()) loadShelves() else requestPermissions(arrayOf(PERM_LISTINGS), REQ_LISTINGS)
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
