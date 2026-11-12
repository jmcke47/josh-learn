package com.josh.learn

import android.app.Activity

/** In-memory BillingStore.Client for Robolectric tests. */
class FakeBillingClient : BillingStore.Client {
    var available = true
    var product: BillingStore.ProductInfo? = BillingStore.ProductInfo(Premium.PRODUCT_ID, "Josh Learn Premium", "One-time unlock", "$6.99")
    var purchases: MutableList<BillingStore.PurchaseInfo> = mutableListOf()
    var queryPurchasesOk = true
    var launchError: String? = null
    var acknowledgeOk = true
    var started = false
    var launchCount = 0
    var restoreCount = 0
    var acknowledgedTokens = mutableListOf<String>()
    private var listener: (List<BillingStore.PurchaseInfo>) -> Unit = {}

    override fun start(onReady: (ok: Boolean, message: String?) -> Unit) {
        started = true
        onReady(available, if (available) null else "Purchases aren't available on this device. You need the Play Store.")
    }
    override fun end() { started = false }
    override fun queryProduct(id: String, onResult: (BillingStore.ProductInfo?) -> Unit) = onResult(product?.takeIf { it.id == id })
    override fun queryPurchases(onResult: (ok: Boolean, purchases: List<BillingStore.PurchaseInfo>) -> Unit) {
        restoreCount++
        onResult(queryPurchasesOk, purchases.toList())
    }
    override fun launch(activity: Activity, product: BillingStore.ProductInfo, onError: (String) -> Unit) {
        launchCount++
        launchError?.let { onError(it); return }
        listener(purchases.toList())
    }
    override fun acknowledge(token: String, onDone: (Boolean) -> Unit) {
        acknowledgedTokens += token
        purchases = purchases.map { if (it.token == token) it.copy(acknowledged = true) else it }.toMutableList()
        onDone(acknowledgeOk)
    }
    override fun setListener(l: (List<BillingStore.PurchaseInfo>) -> Unit) { listener = l }

    fun emit(list: List<BillingStore.PurchaseInfo>) { purchases = list.toMutableList(); listener(list) }
}
