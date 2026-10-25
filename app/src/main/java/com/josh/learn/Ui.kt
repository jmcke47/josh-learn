package com.josh.learn

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

// ------------------------------------------------------------------ view helpers

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.attrColor(attr: Int): Int { val tv = TypedValue(); theme.resolveAttribute(attr, tv, true); return tv.data }

fun Context.vbox(pad: Int = 0, block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)); block()
}
fun Context.hbox(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; block() }

fun Context.label(text: CharSequence, style: Int = com.google.android.material.R.attr.textAppearanceBodyLarge, bold: Boolean = false,
                  color: Int? = null, top: Int = 0) = TextView(this).apply {
    this.text = text
    setTextAppearance(TypedValue().also { theme.resolveAttribute(style, it, true) }.resourceId)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    color?.let { setTextColor(it) }
    if (top > 0) layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    setTextIsSelectable(false)
}
fun Context.title(text: CharSequence, top: Int = 16) = label(text, com.google.android.material.R.attr.textAppearanceTitleMedium, color = attrColor(androidx.appcompat.R.attr.colorPrimary), top = top)
fun Context.muted(text: CharSequence, top: Int = 0) = label(text, com.google.android.material.R.attr.textAppearanceBodyMedium, color = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant), top = top)

fun Context.button(text: String, style: Int = 0, onClick: (View) -> Unit) =
    (if (style == 0) MaterialButton(this) else MaterialButton(this, null, style)).apply { this.text = text; setOnClickListener(onClick) }
fun Context.outlined(text: String, onClick: (View) -> Unit) = button(text, com.google.android.material.R.attr.materialButtonOutlinedStyle, onClick)
fun Context.tonal(text: String, onClick: (View) -> Unit) = button(text, com.google.android.material.R.attr.materialButtonStyle, onClick)
fun Context.textBtn(text: String, onClick: (View) -> Unit) = button(text, androidx.appcompat.R.attr.borderlessButtonStyle, onClick)

fun Context.card(top: Int = 10, onClick: ((View) -> Unit)? = null, block: LinearLayout.() -> Unit) = MaterialCardView(this).apply {
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    radius = dp(16).toFloat(); cardElevation = 0f
    strokeWidth = dp(1); strokeColor = attrColor(com.google.android.material.R.attr.colorOutlineVariant)
    setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
    onClick?.let { setOnClickListener(it) }
    addView(vbox(16, block))
}

fun Context.input(hint: String, multiLine: Boolean = false, password: Boolean = false, value: String = ""): Pair<TextInputLayout, TextInputEditText> {
    val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
        this.hint = hint
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
    }
    val et = TextInputEditText(til.context).apply {
        setText(value)
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        if (multiLine) { minLines = 2; maxLines = 8 } else setSingleLine()
    }
    til.addView(et)
    return til to et
}

/** Single-selection chip row. */
fun <T> Context.choiceChips(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) = ChipGroup(this).apply {
    isSingleSelection = true; isSelectionRequired = true
    items.forEach { item ->
        addView(Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
            text = label(item); isCheckable = true; isChecked = item == selected
            setEnsureMinTouchTargetSize(true)
            setOnCheckedChangeListener { _, c -> if (c) onSelect(item) }
            id = View.generateViewId()
        })
    }
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
fun Context.openUrl(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

fun relTime(t: Long, now: Long = System.currentTimeMillis()): String {
    val d = now - t
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        d < 7 * 86_400_000L -> "${d / 86_400_000} d ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(t))
    }
}

// ------------------------------------------------------------------ base activity

open class BaseActivity : AppCompatActivity() {
    lateinit var toolbar: MaterialToolbar
    lateinit var providers: ProviderStore
    protected val io = Executors.newCachedThreadPool()
    protected val main = Handler(Looper.getMainLooper())
    private var providerItem: MenuItem? = null

    /** "Import my topics": system file picker -> Grok export ZIP (analysed on-device, with preview) or a topics JSON. */
    private val topicsPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importTopicsFrom(uri)
    }
    fun importTopics() = runCatching { topicsPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/plain", "*/*")) }
        .onFailure { toast("No file picker available on this phone") }

    private sealed class ImportOutcome {
        class Json(val text: String) : ImportOutcome()
        class Grok(val r: GrokImport.Result) : ImportOutcome()
    }

    fun importTopicsFrom(uri: Uri) {
        var cancelled = false
        val tv = muted("Opening the file…").apply { setPadding(0, dp(12), 0, 0) }
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Import my topics")
            .setView(vbox(24) { addView(com.google.android.material.progressindicator.LinearProgressIndicator(context).apply { isIndeterminate = true }); addView(tv)
                addView(muted("Everything is analysed on this phone. Nothing is uploaded, and the chats themselves aren't kept.", top = 8).apply { textSize = 12f }) })
            .setCancelable(false).setNegativeButton(android.R.string.cancel) { _, _ -> cancelled = true }.show()
        io.execute {
            val r = runCatching {
                (contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Couldn't open that file.")).use { raw ->
                    val b = java.io.BufferedInputStream(raw, 1 shl 16)
                    b.mark(8); val head = ByteArray(4); val n = b.read(head); b.reset()
                    if (GrokImport.isZip(head.copyOf(maxOf(n, 0)))) {
                        main.post { tv.text = "Reading your Grok history…" }
                        ImportOutcome.Grok(GrokImport.analyseZip(b, onProgress = { c -> main.post { tv.text = "Reading your Grok history… $c chats" } }, cancelled = { cancelled }))
                    } else {
                        val text = String(b.readNBytesCompat(Topics.MAX_IMPORT_BYTES + 1), Charsets.UTF_8)
                        if (Topics.parseSuggested(text).isNotEmpty()) ImportOutcome.Json(text)
                        else ImportOutcome.Grok(GrokImport.analyseJson(text.byteInputStream(), cancelled = { cancelled }))
                    }
                }
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (dlg.isShowing) dlg.dismiss()
                if (cancelled || r.exceptionOrNull() is GrokImport.Cancelled) return@post
                r.onSuccess { o ->
                    when (o) {
                        is ImportOutcome.Json -> runCatching { Topics.importJson(this, o.text) }
                            .onSuccess { n -> toast("Imported $n topics"); onTopicsChanged() }
                            .onFailure { e -> importError(e.message) }
                        is ImportOutcome.Grok -> showTopicPreview(o.r)
                    }
                }
                r.onFailure { e -> importError(if (e is java.util.zip.ZipException) "That ZIP file looks damaged." else e.message) }
            }
        }
    }

    private fun importError(msg: String?) = MaterialAlertDialogBuilder(this).setTitle("Couldn't import topics")
        .setMessage(msg ?: "That file isn't a Grok export or a topics list.").setPositiveButton(android.R.string.ok, null).show()

    var lastTopicPreview: AlertDialog? = null

    /** Preview of topics found in a Grok export; untick any, then save (only topic names/sub-topics are stored). */
    fun showTopicPreview(r: GrokImport.Result) {
        if (r.topics.isEmpty()) {
            lastTopicPreview = MaterialAlertDialogBuilder(this).setTitle("No topics found")
                .setMessage("Read ${r.chats} chats but couldn't find clear topics. Is this a Grok data export?").setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val boxes = r.topics.map { t ->
            com.google.android.material.checkbox.MaterialCheckBox(this).apply {
                text = "${t.emoji} ${t.title} · ${t.chats} chats\n" + t.subtopics.take(3).joinToString(" · ")
                isChecked = true; setPadding(0, dp(6), 0, dp(6)); tag = t
            }
        }
        val body = vbox(20) {
            addView(muted("From ${r.chats} chats" + (if (r.excludedSensitive > 0) ", skipping ${r.excludedSensitive} personal or sensitive ones" else "") +
                ". Untick anything you don't want. Only these topic names are saved, privately on this phone."))
            boxes.forEach { addView(it) }
        }
        lastTopicPreview = MaterialAlertDialogBuilder(this).setTitle("Found ${r.topics.size} topics")
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Save") { _, _ ->
                val chosen = boxes.filter { it.isChecked }.map { it.tag as GrokImport.Topic }
                if (chosen.isEmpty()) { toast("Nothing selected"); return@setPositiveButton }
                runCatching { Topics.importJson(this, GrokImport.toJson(chosen)) }
                    .onSuccess { n -> toast("Saved $n topics"); onTopicsChanged() }.onFailure { importError(it.message) }
            }.show()
    }
    open fun onTopicsChanged() {}

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Display.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Display.themeRes(this))
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        providers = ProviderStore(this)
        AiClient.userAgent = "JoshLearn/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"
    }

    /** Toolbar + content. [scroll] wraps content in a ScrollView. */
    fun screen(title: String, content: View, back: Boolean = true, scroll: Boolean = false, bottom: View? = null) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            this.title = title
            setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurface))
            if (back) { setNavigationIcon(R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() } }
        }
        root.addView(toolbar, -1, -2)
        val body: View = if (scroll) ScrollView(this).apply { isFillViewport = true; addView(content) } else content
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        bottom?.let { root.addView(it, -1, -2) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = b.left, right = b.right, bottom = maxOf(b.bottom, ime.bottom))
            toolbar.updatePadding(top = b.top)
            insets
        }
        root.setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurface))
        setContentView(root)
    }

    // -------------------------------------------------------------- provider switcher

    /** Adds a "Gemini ⇄" action to the toolbar that switches the active provider instantly. */
    fun addProviderSwitcher(menu: Menu) {
        providerItem = menu.add(Menu.NONE, 0x5017, 0, providerTitle()).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
            setOnMenuItemClickListener { showProviderSwitcher(); true }
        }
    }

    /** Override to add toolbar actions; rebuilt on every resume. */
    open fun buildMenu(menu: Menu) {}

    fun rebuildMenu() { if (::toolbar.isInitialized) { toolbar.menu.clear(); buildMenu(toolbar.menu) } }

    override fun onResume() { super.onResume(); rebuildMenu() }

    private fun providerTitle() = providers.active.short + " ▾"

    open fun onProviderChanged() { providerItem?.title = providerTitle() }

    fun showProviderSwitcher() {
        val all = Provider.visible
        val labels = all.map { p ->
            val state = when {
                providers.isReady(p) -> providers.model(p)
                p == Provider.LOCAL -> if (ModelDownloader.state.active) "downloading ${ModelDownloader.state.percent}%" else "tap to download"
                else -> "not set up"
            }
            "${p.label}  ·  $state"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("AI provider")
            .setSingleChoiceItems(labels, all.indexOf(providers.active)) { d, i ->
                val p = all[i]
                d.dismiss()
                if (p == Provider.LOCAL && !LocalModels.supported(this)) {
                    MaterialAlertDialogBuilder(this).setTitle("Offline AI").setMessage(LocalModels.unsupportedReason(this))
                        .setPositiveButton(android.R.string.ok, null).show()
                } else if (p == Provider.LOCAL && !providers.isReady(p)) { providers.active = p; onProviderChanged(); OfflineSetup.show(this) }
                else if (!providers.isReady(p)) {
                    MaterialAlertDialogBuilder(this).setTitle("${p.label} isn't set up")
                        .setMessage("Add an API key for ${p.label} in Settings first.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }.show()
                } else { providers.active = p; onProviderChanged(); toast("Now using ${p.label} (${providers.model(p)})") }
            }
            .setNeutralButton("Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------- running AI calls

    /** Active provider config, or prompts setup and returns null. */
    fun requireProvider(p: Provider = providers.active): ProviderConfig? {
        providers.config(p)?.let { return it }
        if (p == Provider.LOCAL && !LocalModels.supported(this)) {
            val other = providers.ready().firstOrNull { it != Provider.LOCAL }
            if (other != null) { toast("Offline AI can't run here; using ${other.short}"); providers.active = other; onProviderChanged(); return providers.config(other) }
            MaterialAlertDialogBuilder(this).setTitle("Offline AI").setMessage(LocalModels.unsupportedReason(this))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }.show()
            return null
        }
        if (p == Provider.LOCAL) {
            val st = ModelDownloader.state
            if (st.active) { toast("The offline AI is still downloading (${st.percent}%). It'll be ready soon."); return null }
            val other = providers.ready().firstOrNull { it != Provider.LOCAL }
            if (other != null && p == providers.active && LocalModels.installed(this).isEmpty() && LearnApp.prefs(this).getBoolean("offline_declined", false)) {
                providers.active = other; onProviderChanged(); return providers.config(other)
            }
            OfflineSetup.show(this); return null
        }
        val other = providers.ready().firstOrNull()
        if (other != null && p == providers.active) { providers.active = other; onProviderChanged(); return providers.config(other) }
        showAddKeyPrompt(p)
        return null
    }

    /** "Add your <provider> key": Get-a-key link + Settings (+ Use Offline when possible). Shown instead of ever calling without a key. */
    fun showAddKeyPrompt(p: Provider) {
        val b = MaterialAlertDialogBuilder(this).setTitle("Add your ${p.short} key")
            .setMessage((if (p == Provider.GEMINI) "Gemini needs a free API key from Google AI Studio. Create one, then paste it in Settings → Google Gemini."
                else "${p.label} needs an API key. Add it in Settings.") +
                (if (Features.OFFLINE_AI && LocalModels.supported(this)) "\n\nOr use the Offline AI: no key needed." else ""))
            .setPositiveButton("Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
        if (p.keyUrl.isNotEmpty()) b.setNeutralButton(if (p == Provider.GEMINI) "Get a free key" else "Get a key") { _, _ -> openUrl(p.keyUrl) }
        if (Features.OFFLINE_AI && LocalModels.supported(this)) b.setNegativeButton("Use Offline") { _, _ -> providers.active = Provider.LOCAL; onProviderChanged(); requireProvider() }
        else b.setNegativeButton(android.R.string.cancel, null)
        lastKeyPrompt = b.show()
    }
    var lastKeyPrompt: AlertDialog? = null

    /**
     * Runs [task] off the main thread with a progress dialog (shows streamed character count),
     * then [onSuccess]. On failure shows a clear message with Retry / "Retry with <other provider>".
     */
    fun <T> runAi(status: String, provider: Provider = providers.active, task: (ProviderConfig, (String) -> Unit, () -> Boolean) -> T, onSuccess: (T) -> Unit) {
        val cfg = requireProvider(provider) ?: return
        var cancelled = false
        val view = vbox(24) {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(CircularProgressIndicator(context).apply { isIndeterminate = true })
        }
        val st = label("$status\nusing ${Gen.label(cfg)}").apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }
        val step = muted("").apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        val prog = muted("").apply { gravity = Gravity.CENTER }
        val preview = muted("").apply { gravity = Gravity.START; maxLines = 4; setPadding(0, dp(8), 0, 0); textSize = 12f }
        view.addView(st); view.addView(step); view.addView(prog)
        if (cfg.provider == Provider.LOCAL) {
            view.addView(preview)
            view.addView(muted("Offline answers are simpler and can be wrong. Double-check important facts.", top = 12).apply { gravity = Gravity.CENTER; textSize = 12f })
        }
        val dlg = MaterialAlertDialogBuilder(this).setView(view).setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled = true }.show()
        var chars = 0; var lastPost = 0L
        val tail = StringBuilder()
        io.execute {
            val r = runCatching {
                task(cfg, { delta ->
                    if (delta.startsWith(AiClient.STATUS)) { val s = delta.substring(1); chars = 0; tail.setLength(0); main.post { step.text = s; prog.text = ""; preview.text = "" }; return@task }
                    chars += delta.length; tail.append(delta); if (tail.length > 400) tail.delete(0, tail.length - 300)
                    val now = System.currentTimeMillis()
                    if (now - lastPost > 250) { lastPost = now; val c = chars; val t = tail.toString(); main.post { prog.text = "Writing… $c characters"; preview.text = t } }
                }, { cancelled })
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (dlg.isShowing) dlg.dismiss()
                if (cancelled) return@post
                r.onSuccess(onSuccess)
                r.onFailure { e ->
                    val ae = e as? AiException
                    if (ae?.kind == AiException.Kind.NO_KEY) { showAddKeyPrompt(cfg.provider); return@onFailure }
                    if (ae?.transient == true && cfg.provider != Provider.LOCAL && providers.isReady(Provider.LOCAL)) {
                        toast("${cfg.provider.short} is busy, using the Offline AI instead")
                        runAi(status, Provider.LOCAL, task, onSuccess); return@onFailure
                    }
                    showAiError(e, cfg.provider) { p -> runAi(status, p, task, onSuccess) }
                }
            }
        }
    }

    /** Error dialog with Retry, "Retry with <other provider>" and Settings shortcuts. */
    fun showAiError(e: Throwable, failed: Provider, retry: (Provider) -> Unit) {
        val ae = e as? AiException
        if (ae?.kind == AiException.Kind.CANCELLED) return
        val msg = ae?.message ?: "Something went wrong: ${e.javaClass.simpleName}: ${e.message}"
        val others = providers.ready().filter { it != failed }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(when (ae?.kind) {
                AiException.Kind.RATE_LIMIT -> "Slow down a moment"
                AiException.Kind.QUOTA -> "Quota reached"
                AiException.Kind.AUTH, AiException.Kind.NO_KEY -> "API key problem"
                AiException.Kind.NETWORK -> "Connection problem"
                AiException.Kind.LOCAL -> "Offline AI problem"
                else -> "Couldn't finish"
            })
            .setMessage(msg + if (others.isEmpty() && ae?.providerProblem == true) "\n\nTip: add a second provider in Settings to get a 'Retry with…' option." else "")
        val alt = others.firstOrNull()
        if (alt != null && ae?.kind != AiException.Kind.PARSE && ae?.kind != AiException.Kind.NETWORK) {
            b.setPositiveButton("Retry with ${alt.short}") { _, _ -> providers.active = alt; onProviderChanged(); retry(alt) }
            b.setNeutralButton("Retry") { _, _ -> retry(failed) }
        } else {
            b.setPositiveButton("Retry") { _, _ -> retry(failed) }
            if (ae?.kind in setOf(AiException.Kind.AUTH, AiException.Kind.BAD_MODEL, AiException.Kind.QUOTA, AiException.Kind.NO_KEY))
                b.setNeutralButton("Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        b.setNegativeButton("Close", null)
        val d = b.show()
        // Rate limit with a known wait: count down on the Retry button.
        val wait = ae?.retryAfterSec ?: 0
        if (ae?.kind == AiException.Kind.RATE_LIMIT && wait in 1..120) {
            val btn = if (alt != null) d.getButton(AlertDialog.BUTTON_NEUTRAL) else d.getButton(AlertDialog.BUTTON_POSITIVE)
            val end = System.currentTimeMillis() + wait * 1000L
            val tick = object : Runnable { override fun run() {
                val left = ((end - System.currentTimeMillis()) / 1000).toInt()
                if (!d.isShowing) return
                if (left > 0) { btn.text = "Retry (${left}s)"; main.postDelayed(this, 1000) } else btn.text = "Retry"
            } }
            tick.run()
        }
    }

    override fun onDestroy() { super.onDestroy(); io.shutdown() }
}

/** First-run: set up the on-device model (no key needed). API providers stay optional in Settings. */
object OfflineSetup {
    fun show(a: BaseActivity, onStarted: () -> Unit = {}) {
        val ram = LocalModels.totalRamGb(a)
        var choice = LocalModels.recommended(a)
        val body = a.vbox(20) {
            addView(a.label("Josh Learn can run its AI right on your phone: no account, no API key, works offline."))
            addView(a.muted("It needs a one-time download. Offline answers are simpler than online AI and can be wrong, so double-check anything important.", top = 8))
            addView(a.title("Choose a model"))
            val group = android.widget.RadioGroup(a)
            LocalModel.entries.forEach { m ->
                group.addView(com.google.android.material.radiobutton.MaterialRadioButton(a).apply {
                    id = View.generateViewId(); tag = m
                    text = "${m.label}  ·  ${m.sizeText}" + (if (m == LocalModels.recommended(a)) "  (recommended)" else "") + "\n" + m.blurb +
                        (if (ram < m.minRamGb - 0.5) "\n⚠ Your phone has %.0f GB RAM; this may not run.".format(ram) else "")
                    isChecked = m == choice
                    setPadding(0, a.dp(6), 0, a.dp(6))
                    setOnCheckedChangeListener { _, c -> if (c) choice = m }
                })
            }
            addView(group)
            addView(com.google.android.material.checkbox.MaterialCheckBox(a).apply {
                text = "Download on Wi-Fi only"; isChecked = LocalModels.wifiOnly(a)
                setOnCheckedChangeListener { _, c -> LocalModels.setWifiOnly(a, c) }
            })
            addView(a.muted("Free space: %.1f GB. Models are stored privately in the app and kept across updates.".format(LocalModels.freeBytes(a) / 1e9), top = 4))
        }
        MaterialAlertDialogBuilder(a).setTitle("Set up Offline AI")
            .setView(ScrollView(a).apply { addView(body) })
            .setNegativeButton("Not now") { _, _ -> LearnApp.prefs(a).edit().putBoolean("offline_declined", true).apply() }
            .setNeutralButton("Use an API key") { _, _ -> a.startActivity(Intent(a, SettingsActivity::class.java)) }
            .setPositiveButton("Download") { _, _ ->
                LocalModels.select(a, choice)
                val err = ModelDownloader.start(a, choice)
                if (err != null) MaterialAlertDialogBuilder(a).setTitle("Can't download yet").setMessage(err).setPositiveButton(android.R.string.ok, null).show()
                else { a.providers.active = Provider.LOCAL; a.onProviderChanged(); a.toast("Downloading ${choice.short} (${choice.sizeText}). You can keep using the app."); onStarted() }
            }.show()
    }
}

/** "Test key": a tiny request (a few tokens) to verify key + model. */
object KeyTester {
    fun test(a: BaseActivity, p: Provider, after: () -> Unit = {}) {
        a.runAi("Testing ${p.short} key…", p, { cfg, _, _ ->
            AiClient.complete(cfg, listOf(Msg("user", "Reply with just the word OK.")))
        }) { reply ->
            MaterialAlertDialogBuilder(a).setIcon(R.drawable.ic_check_circle).setTitle("${p.short} key works")
                .setMessage("Model ${a.providers.model(p)} replied: \"${reply.trim().take(60)}\"")
                .setPositiveButton(android.R.string.ok, null).show()
            after()
        }
    }
}

fun ViewGroup.addAll(vararg v: View) = v.forEach { addView(it) }

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
    while (total < max) { val n = read(buf, 0, minOf(buf.size, max - total)); if (n < 0) break; out.write(buf, 0, n); total += n }
    return out.toByteArray()
}

/**
 * One-tap depth control: a 5-stop slider from "Like I'm 5" through "Normal" to "Expert" with the current choice named above it.
 * Tap anywhere on the track to jump to that depth.
 */
fun Context.depthSlider(current: Depth, compact: Boolean = false, onChange: (Depth) -> Unit): View = vbox {
    val name = label("", com.google.android.material.R.attr.textAppearanceTitleSmall, bold = true)
    fun show(d: Depth) { name.text = "Depth: ${d.label}" }
    show(current)
    if (!compact) addView(name)
    val slider = com.google.android.material.slider.Slider(context).apply {
        valueFrom = 0f; valueTo = (Depth.entries.size - 1).toFloat(); stepSize = 1f; value = current.ordinal.toFloat()
        setLabelFormatter { Depth.entries[it.toInt()].label }
        contentDescription = "Lesson depth"
        addOnChangeListener { _, v, fromUser -> val d = Depth.entries[v.toInt()]; show(d); if (fromUser) onChange(d) }
    }
    addView(slider, LinearLayout.LayoutParams(-1, -2))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf("Like I'm 5" to Gravity.START, "Normal" to Gravity.CENTER_HORIZONTAL, "Expert" to Gravity.END).forEach { (t, g) ->
            addView(muted(t).apply { textSize = 12f; gravity = g }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        setPadding(dp(12), 0, dp(12), 0)
    })
    tag = slider
}
