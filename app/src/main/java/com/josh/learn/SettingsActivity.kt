package com.josh.learn

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.edit
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

class SettingsActivity : BaseActivity() {
    private lateinit var root: LinearLayout
    private lateinit var updater: UpdateController
    private val expanded = mutableSetOf<Provider>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = UpdateController(this)
        root = vbox(16)
        screen("Settings", root, scroll = true)
        render()
    }

    override fun onResume() { super.onResume(); updater.onResume() }
    private var dlView: android.widget.TextView? = null
    private var lastStatus: ModelDownloader.Status? = null
    private val dlListener: (ModelDownloader.State) -> Unit = { st ->
        if (lastStatus != null && lastStatus != st.status) render() else dlView?.text = downloadText(st)
        lastStatus = st.status
    }
    override fun onStart() { super.onStart(); ModelDownloader.listen(dlListener) }
    override fun onStop() { super.onStop(); ModelDownloader.unlisten(dlListener) }

    private fun downloadText(st: ModelDownloader.State) = when (st.status) {
        ModelDownloader.Status.RUNNING -> "Downloading ${st.model?.short}: ${st.progressText()}"
        ModelDownloader.Status.VERIFYING -> "Checking the downloaded file…"
        ModelDownloader.Status.PAUSED, ModelDownloader.Status.FAILED -> st.message
        ModelDownloader.Status.DONE -> "Download complete."
        else -> ""
    }

    private fun offlineCard() = card {
        val st = ModelDownloader.state
        addView(label("Offline AI (on this phone)" + if (providers.active == Provider.LOCAL) "  ✓ active" else "", bold = true))
        LocalModels.unsupportedReason(context)?.let { reason ->
            addView(muted("⚠ $reason", top = 4))
            if (LocalModels.loadCrashed(context)) addView(textBtn("Try Offline AI again") { LocalModels.clearLoadCrashed(context); render() })
            return@card
        }
        addView(muted("Runs on your phone with no key and no internet. Answers are simpler than online AI and can be wrong. " +
            "Models are kept when the app updates. Phone RAM: %.1f GB · free storage: %.1f GB".format(LocalModels.totalRamGb(context), LocalModels.freeBytes(context) / 1e9), top = 4))
        val selected = LocalModels.selected(context)
        LocalModel.entries.forEach { m ->
            val installed = LocalModels.isInstalled(context, m)
            val partial = LocalModels.partialBytes(context, m)
            val downloadingThis = st.active && st.model == m
            addView(card(top = 10) {
                addView(label(m.label + (if (m == selected && installed) "  ✓ in use" else ""), bold = true))
                addView(muted("${m.sizeText} download · ${m.license} licence · ${m.blurb}" +
                    if (LocalModels.totalRamGb(context) < m.minRamGb - 0.5) "\n⚠ Recommended: ${m.minRamGb} GB+ RAM" else "", top = 2))
                when {
                    installed -> {
                        addView(muted("Downloaded", top = 4))
                        addView(hbox {
                            if (m != selected) addView(button("Use this model") { LocalModels.select(context, m); LocalLlm.release(); providers.active = Provider.LOCAL; onProviderChanged() })
                            addView(textBtn("Delete (${m.sizeText})") {
                                MaterialAlertDialogBuilder(this@SettingsActivity).setTitle("Delete ${m.short}?")
                                    .setMessage("Frees ${m.sizeText}. You can download it again later.")
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton("Delete") { _, _ -> LocalModels.delete(context, m); onProviderChanged() }.show()
                            })
                        })
                    }
                    downloadingThis -> {
                        val tv = muted(downloadText(st), top = 4); dlView = tv; addView(tv)
                        addView(outlined("Pause") { ModelDownloader.pause() })
                    }
                    else -> {
                        if (st.model == m && st.message.isNotEmpty() && !st.active) addView(muted(st.message, top = 4))
                        addView(hbox {
                            addView(button(if (partial > 0) "Resume (%.0f%% done)".format(partial * 100.0 / m.sizeBytes) else "Download") {
                                LocalModels.select(context, m)
                                val err = ModelDownloader.start(this@SettingsActivity, m)
                                if (err != null) MaterialAlertDialogBuilder(this@SettingsActivity).setMessage(err).setPositiveButton(android.R.string.ok, null).show()
                                else { providers.active = Provider.LOCAL; onProviderChanged() }
                            }.apply { isEnabled = !st.active })
                            if (partial > 0) addView(textBtn("Discard partial") { LocalModels.delete(context, m); render() })
                        })
                    }
                }
            })
        }
        addView(MaterialSwitch(context).apply {
            text = "Download on Wi-Fi only"; isChecked = LocalModels.wifiOnly(context)
            setOnCheckedChangeListener { _, c -> LocalModels.setWifiOnly(context, c) }
        })
        addView(MaterialSwitch(context).apply {
            text = "Use GPU (faster on some phones, experimental)"; isChecked = LocalModels.useGpu(context)
            setOnCheckedChangeListener { _, c -> LocalModels.setUseGpu(context, c); LocalLlm.release() }
        })
        if (providers.active != Provider.LOCAL && LocalModels.usable(context) != null) addView(textBtn("Make active") { providers.active = Provider.LOCAL; onProviderChanged() })
    }
    override fun onProviderChanged() { super.onProviderChanged(); render() }
    override fun onTopicsChanged() = render()

    private fun render() {
        root.removeAllViews()
        val prefs = LearnApp.prefs(this)
        root.apply {
            // ---------------- AI providers
            addView(title("AI provider", top = 0))
            addView(muted("The active provider is used for lessons, quizzes and chat. Switch any time with the ▾ button in the toolbar. If one fails, you'll get a \"Retry with…\" option."))
            addView(ChipGroup(context).apply {
                isSingleSelection = true
                Provider.visible.forEach { p ->
                    addView(Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
                        id = View.generateViewId(); isCheckable = true
                        text = p.short + if (providers.isReady(p)) "" else " (not set up)"
                        isChecked = providers.active == p; isEnabled = providers.isReady(p) || providers.active == p || (p == Provider.LOCAL && LocalModels.supported(context))
                        setOnClickListener {
                            if (providers.isReady(p) || (p == Provider.LOCAL && LocalModels.supported(context))) { providers.active = p; onProviderChanged(); toast("Now using ${p.label}") }
                        }
                    })
                }
            })
            if (Features.OFFLINE_AI) addView(offlineCard())
            addView(title("Online providers (optional)"))
            addView(muted("Better answers, but need your own API key and internet. Each key is stored encrypted."))
            Provider.entries.filter { it != Provider.LOCAL }.forEach { addView(providerCard(it)) }

            // ---------------- Suggested topics (imported)
            addView(title("Suggested topics"))
            val n = Topics.suggested(context).size
            addView(muted(if (n > 0) "$n imported topics shown in \"Suggested for you\". Stored privately on this phone." else "Import your Grok data export (.zip) or a topics file (.json) to fill \"Suggested for you\". It's analysed and kept on this phone."))
            addView(hbox {
                addView(outlined(if (n > 0) "Import new file" else "Import my topics") { importTopics() })
                if (n > 0) { addView(View(context), LinearLayout.LayoutParams(dp(8), 1)); addView(textBtn("Clear imported topics") {
                    MaterialAlertDialogBuilder(this@SettingsActivity).setTitle("Clear imported topics?")
                        .setMessage("Removes your suggested topics from this phone. Lessons you made stay.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Clear") { _, _ -> Topics.clearImported(context); render() }.show()
                }) }
            })


            // ---------------- Lessons
            addView(title("New lessons"))
            addView(muted("Default level"))
            addView(choiceChips(Difficulty.entries, enumOr(prefs.getString("difficulty", null), Difficulty.BEGINNER), { it.label }) { prefs.edit { putString("difficulty", it.name) } })
            addView(muted("Default length"))
            addView(choiceChips(LessonLength.entries, enumOr(prefs.getString("length", null), LessonLength.MEDIUM), { it.label }) { prefs.edit { putString("length", it.name) } })

            // ---------------- Appearance
            addView(title("Theme"))
            addView(choiceChips(listOf("system", "light", "dark"), prefs.getString("theme", "system")!!, { it.replaceFirstChar(Char::uppercase) }) {
                prefs.edit { putString("theme", it) }; LearnApp.applyTheme(this@SettingsActivity)
            })

            // ---------------- Reminder
            addView(title("Daily review reminder"))
            val time = muted("")
            fun showTime() { time.text = "Reminds you at %02d:%02d if flashcards are due.".format(Reminders.hour(context), Reminders.minute(context)) }
            showTime()
            addView(MaterialSwitch(context).apply {
                text = "Remind me to review"; isChecked = Reminders.enabled(context)
                setOnCheckedChangeListener { _, c -> prefs.edit { putBoolean("reminder_on", c) }; Reminders.schedule(context) }
            })
            addView(time)
            addView(outlined("Change time") {
                val picker = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H)
                    .setHour(Reminders.hour(this@SettingsActivity)).setMinute(Reminders.minute(this@SettingsActivity)).setTitleText("Reminder time").build()
                picker.addOnPositiveButtonClickListener {
                    prefs.edit { putInt("reminder_hour", picker.hour); putInt("reminder_minute", picker.minute) }
                    Reminders.schedule(this@SettingsActivity); showTime()
                }
                picker.show(supportFragmentManager, "time")
            })

            // ---------------- Updates
            addView(title("Updates"))
            addView(muted("Josh Learn v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
            addView(button("Check for updates") { updater.checkForUpdates() })
            addView(textBtn("Advanced: update source URL") { updater.editUpdateUrl() })

            addView(title("Privacy"))
            addView(muted("API keys are encrypted with a key held in the Android Keystore and never leave this phone except to the provider you chose. " +
                "Lessons are stored only on this phone. Requests go straight from the phone to the AI provider.", top = 4))
        }
    }

    private fun providerCard(p: Provider) = card {
        val ready = providers.isReady(p)
        val header = hbox {
            addView(vbox {
                addView(label(p.label + if (providers.active == p) "  ✓ active" else "", bold = true))
                addView(muted(when {
                    ready -> "Key ${providers.maskedKey(p)} · ${providers.model(p)}"
                    providers.hasKey(p) -> "Key saved · needs ${if (p == Provider.CUSTOM) "server URL / model" else "model"}"
                    else -> if (p == Provider.GEMINI) "Not set up · free tier available" else "Optional · not set up"
                }))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(if (p in expanded) "▴" else "▾"))
        }
        header.setOnClickListener { if (!expanded.remove(p)) expanded += p; render() }
        addView(header)
        if (p !in expanded) return@card

        addView(muted(p.keyHint, top = 8))
        if (p.keyUrl.isNotEmpty()) addView(textBtn(if (p == Provider.GEMINI) "Get a free key" else "Get a key") { openUrl(p.keyUrl) })
        val (urlTil, urlEt) = input("Server base URL (…/v1)", value = providers.baseUrl(p))
        if (p == Provider.CUSTOM) { urlTil.helperText = "e.g. https://openrouter.ai/api/v1 or https://api.groq.com/openai/v1"; addView(urlTil) }
        val (keyTil, keyEt) = input(if (providers.hasKey(p)) "New API key (saved: ${providers.maskedKey(p)})" else "API key", password = true)
        addView(keyTil)
        val (modelTil, modelEt) = input("Model", value = providers.model(p))
        if (p.defaultModel.isNotEmpty()) modelTil.helperText = "Default: ${p.defaultModel}" + if (p == Provider.GEMINI) " (free tier)" else ""
        addView(modelTil)
        if (p.suggestions.isNotEmpty()) addView(ChipGroup(context).apply {
            p.suggestions.forEach { m -> addView(Chip(context).apply { text = m; setOnClickListener { modelEt.setText(m) } }) }
        })
        fun save(): Boolean {
            val key = keyEt.text?.toString()?.replace(Regex("\\s"), "") ?: ""
            if (p == Provider.CUSTOM) {
                val u = urlEt.text?.toString()?.trim() ?: ""
                if (u.isNotEmpty() && !u.startsWith("https://") && !u.startsWith("http://")) { urlTil.error = "Must start with https://"; return false }
                providers.setBaseUrl(p, u)
            }
            if (p == Provider.GEMINI && key.isNotEmpty() && !key.startsWith("AIza")) toast("Note: Gemini keys usually start with AIza")
            if (key.isNotEmpty()) providers.setKey(p, key)
            providers.setModel(p, modelEt.text?.toString() ?: "")
            if (providers.isReady(p) && !providers.isReady(providers.active)) providers.active = p
            return true
        }
        addView(hbox {
            addView(button("Save") { if (save()) { toast("Saved"); keyEt.setText(""); onProviderChanged() } })
            addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
            addView(outlined("Test key") {
                if (!save()) return@outlined
                if (!providers.isReady(p)) { toast("Enter a key first"); return@outlined }
                KeyTester.test(this@SettingsActivity, p) { render() }
            })
        })
        if (providers.hasKey(p)) addView(textBtn("Remove key") {
            MaterialAlertDialogBuilder(this@SettingsActivity).setTitle("Remove ${p.short} key?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Remove") { _, _ ->
                    providers.setKey(p, null)
                    if (providers.active == p) providers.ready().firstOrNull()?.let { providers.active = it }
                    onProviderChanged()
                }.show()
        })
        if (ready && providers.active != p) addView(textBtn("Make active") { providers.active = p; onProviderChanged() })
    }
}
