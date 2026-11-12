package com.josh.learn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Upsell screen for Josh Learn Premium (one-time [Premium.PRODUCT_ID]).
 * Old School theme: carved parchment and pixel font. Otherwise a Material card.
 */
class PremiumActivity : BaseActivity() {
    private lateinit var body: LinearLayout
    private val feature: Premium.Feature? get() = intent.getStringExtra(EXTRA_FEATURE)?.let { runCatching { Premium.Feature.valueOf(it) }.getOrNull() }
    private val listener: (BillingStore.Status) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Premium.unlocked(this)) { toast("Premium is already unlocked."); finish(); return }
        val scroll = ScrollView(this)
        body = vbox(20)
        scroll.addView(body)
        screen("Josh Learn Premium", scroll)
        BillingStore.init(this)
        render()
    }

    override fun onStart() { super.onStart(); BillingStore.listen(listener) }
    override fun onStop() { super.onStop(); BillingStore.unlisten(listener) }
    override fun onResume() { super.onResume(); if (Premium.unlocked(this)) { toast("Premium unlocked."); finish() } }

    private fun render() {
        body.removeAllViews()
        val os = isOsrs()
        val highlight = feature
        val price = BillingStore.product?.price?.takeIf { it.isNotBlank() }
        body.apply {
            val intro = TextView(context).apply {
                text = "Unlock the extras with a one-time purchase" + (price?.let { " ($it)" } ?: "") + "."
                textSize = 17f
                if (os) { typeface = com.josh.oldschool.Os.font(context, true); setTextColor(getColor(R.color.os_dialog_red)); setShadowLayer(0f, 0f, 0f, 0); gravity = Gravity.CENTER }
                else setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            addView(if (os) {
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.os_chatbox_carved)
                    val p = dp(14); setPadding(p, dp(12), p, dp(12))
                    addView(intro)
                    addView(muted("Free forever: lessons, quizzes, flashcards and offline AI. Pay once, keep Premium on this Google account.", top = 6).apply {
                        if (os) { typeface = com.josh.oldschool.Os.font(context); setTextColor(getColor(R.color.os_chat_blue)); setShadowLayer(0f, 0f, 0f, 0); gravity = Gravity.CENTER }
                    })
                }
            } else {
                card(top = 0) { addView(intro); addView(muted("Free forever: lessons, quizzes, flashcards and offline AI. Pay once, keep Premium on this Google account.", top = 6)) }
            })

            addView(title("Included with Premium"))
            Premium.gated.forEach { f ->
                addView(label((if (f == highlight) "➤ " else "") + f.title, bold = f == highlight))
                addView(muted(f.blurb))
            }

            when (BillingStore.status) {
                BillingStore.Status.Pending -> addView(muted("Your purchase is pending confirmation from Google Play. Premium will unlock automatically once it goes through.", top = 12))
                BillingStore.Status.Unavailable, BillingStore.Status.Error -> addView(muted(BillingStore.lastMessage
                    ?: "Purchases aren't available on this device. You need the Play Store.", top = 12))
                BillingStore.Status.Owned -> addView(muted("Premium is unlocked on this device.", top = 12))
                else -> {}
            }

            val canBuy = BillingStore.status in setOf(BillingStore.Status.Ready, BillingStore.Status.Idle, BillingStore.Status.Connecting, BillingStore.Status.Owned)
                && BillingStore.status != BillingStore.Status.Unavailable
                && !Premium.unlocked(this@PremiumActivity)
                && BillingStore.status != BillingStore.Status.Pending
            if (BillingStore.status != BillingStore.Status.Unavailable && !Premium.unlocked(this@PremiumActivity) && BillingStore.status != BillingStore.Status.Pending) {
                addView(button(if (price.isNullOrBlank()) "Unlock Premium" else "Unlock Premium · $price") {
                    BillingStore.launchPurchase(this@PremiumActivity) { msg ->
                        if (msg.contains("already own", true)) BillingStore.restore { t -> toast(t); if (Premium.unlocked(this@PremiumActivity)) finish() }
                        else toast(msg)
                    }
                }.apply { isEnabled = BillingStore.status != BillingStore.Status.Connecting; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) } })
            }
            addView(outlined("Restore purchases") {
                BillingStore.restore { msg -> toast(msg); if (Premium.unlocked(this@PremiumActivity)) finish() }
            })
            addView(muted("Premium is a one-time purchase, restored on any phone signed into the same Google account. A working Play Store is required.", top = 12))
        }
    }

    companion object {
        const val EXTRA_FEATURE = "feature"
        fun show(a: Activity, feature: Premium.Feature? = null) {
            a.startActivity(Intent(a, PremiumActivity::class.java).apply {
                if (feature != null) putExtra(EXTRA_FEATURE, feature.name)
            })
        }
    }
}
