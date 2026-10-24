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
    private val expanded = mutableSetOf(Provider.GEMINI)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = UpdateController(this)
        root = vbox(16)
        screen("Settings", root, scroll = true)
        render()
    }

    override fun onResume() { super.onResume(); updater.onResume() }
    override fun onProviderChanged() { super.onProviderChanged(); render() }

    private fun render() {
        root.removeAllViews()
        val prefs = LearnApp.prefs(this)
        root.apply {
            // ---------------- AI providers
            addView(title("AI provider", top = 0))
            addView(muted("Active provider is used for lessons, quizzes and chat. Switch any time from the ▾ button in the toolbar."))
            addView(ChipGroup(context).apply {
                isSingleSelection = true
                Provider.entries.forEach { p ->
                    addView(Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
                        id = View.generateViewId(); isCheckable = true
                        text = p.short + if (providers.isReady(p)) "" else " (not set up)"
                        isChecked = providers.active == p; isEnabled = providers.isReady(p) || providers.active == p
                        setOnClickListener { if (providers.isReady(p)) { providers.active = p; onProviderChanged(); toast("Now using ${p.label}") } }
                    })
                }
            })
            Provider.entries.forEach { addView(providerCard(it)) }

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
