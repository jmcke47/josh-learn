package com.josh.learn

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Play Billing for the one-time [Premium.PRODUCT_ID] unlock.
 *
 * Play flavor: talks to Google Play; caches entitlement locally so premium works offline;
 * stays free and surfaces a "not available" message when Billing isn't present.
 * GitHub flavor: does not connect (everything is already unlocked).
 *
 * [clientFactory] is replaced in tests with [FakeBillingClient].
 */
object BillingStore {
    enum class Status { Idle, Connecting, Ready, Unavailable, Pending, Owned, Error }

    data class ProductInfo(val id: String, val title: String, val description: String, val price: String, val raw: Any? = null)
    data class PurchaseInfo(val productId: String, val token: String, val purchased: Boolean, val pending: Boolean, val acknowledged: Boolean)

    interface Client {
        fun start(onReady: (ok: Boolean, message: String?) -> Unit)
        fun end()
        fun queryProduct(id: String, onResult: (ProductInfo?) -> Unit)
        fun queryPurchases(onResult: (ok: Boolean, purchases: List<PurchaseInfo>) -> Unit)
        fun launch(activity: Activity, product: ProductInfo, onError: (String) -> Unit)
        fun acknowledge(token: String, onDone: (Boolean) -> Unit)
        fun setListener(l: (List<PurchaseInfo>) -> Unit)
    }

    @Volatile var status: Status = Status.Idle
        private set
    @Volatile var product: ProductInfo? = null
        private set
    @Volatile var lastMessage: String? = null
        private set
    @Volatile var clientFactory: (Context) -> Client = { PlayBillingClient(it) }

    private var client: Client? = null
    private val listeners = mutableListOf<(Status) -> Unit>()
    private lateinit var app: Context

    fun listen(l: (Status) -> Unit) { synchronized(listeners) { listeners += l } }
    fun unlisten(l: (Status) -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun notifyListeners() { val s = status; synchronized(listeners) { listeners.toList() }.forEach { it(s) } }

    private fun setStatus(s: Status, msg: String? = lastMessage) {
        status = s; lastMessage = msg; notifyListeners()
    }

    /** Start (or restart) the client. Safe to call from Application.onCreate. No-op on the GitHub flavor. */
    fun init(c: Context) {
        app = c.applicationContext
        if (!Premium.playDistribution) { setStatus(Status.Owned, "GitHub build: every feature is unlocked."); return }
        if (Entitlements.hasPremium(app)) setStatus(Status.Owned)
        connect()
    }

    fun connect() {
        if (!Premium.playDistribution) return
        if (!::app.isInitialized) return
        client?.end()
        setStatus(Status.Connecting)
        val cl = clientFactory(app)
        client = cl
        cl.setListener { applyPurchases(it, fromQuery = false) }
        cl.start { ok, msg ->
            if (!ok) {
                setStatus(Status.Unavailable, msg ?: "Purchases aren't available on this device. You need the Play Store.")
                return@start
            }
            cl.queryProduct(Premium.PRODUCT_ID) { p -> product = p }
            restore { }
        }
    }

    /**
     * queryPurchasesAsync. [onDone] runs on the billing callback thread / caller thread.
     * A failed query never clears a cached entitlement (offline must keep working).
     */
    fun restore(onDone: (String) -> Unit = {}) {
        val cl = client
        if (!Premium.playDistribution) { onDone("This GitHub build already includes every feature."); return }
        if (cl == null) { onDone(lastMessage ?: "Purchases aren't available on this device."); return }
        cl.queryPurchases { ok, list ->
            if (!ok) {
                onDone("Couldn't reach Google Play. If you've already bought Premium, it will unlock again when you're online.")
                return@queryPurchases
            }
            applyPurchases(list, fromQuery = true)
            onDone(when {
                Entitlements.hasPremium(app) -> "Premium restored."
                Entitlements.isPending(app) -> "Your purchase is still pending. You'll be unlocked when Google Play confirms it."
                else -> "No previous purchase found."
            })
        }
    }

    fun launchPurchase(activity: Activity, onError: (String) -> Unit) {
        if (!Premium.playDistribution) { onError("This GitHub build already includes every feature."); return }
        if (status == Status.Unavailable || client == null) {
            onError(lastMessage ?: "Purchases aren't available on this device. You need the Play Store.")
            return
        }
        val p = product
        if (p == null) {
            // Try to fetch once more, then fail gracefully.
            client?.queryProduct(Premium.PRODUCT_ID) { info ->
                product = info
                if (info == null) onError("Couldn't load the Premium product. Check that $Premium.PRODUCT_ID is set up in Play Console.")
                else client?.launch(activity, info, onError)
            } ?: onError("Purchases aren't available on this device.")
            return
        }
        client?.launch(activity, p, onError)
    }

    private fun applyPurchases(list: List<PurchaseInfo>, fromQuery: Boolean) {
        if (!::app.isInitialized) return
        val ours = list.filter { it.productId == Premium.PRODUCT_ID }
        val purchased = ours.filter { it.purchased }
        val pending = ours.any { it.pending }
        if (purchased.isNotEmpty()) {
            purchased.filter { !it.acknowledged }.forEach { p ->
                client?.acknowledge(p.token) { ok -> if (ok) Entitlements.setPremium(app, true) }
            }
            Entitlements.setPremium(app, true)
            setStatus(Status.Owned)
        } else if (pending) {
            Entitlements.setPending(app, true)
            if (!Entitlements.hasPremium(app)) setStatus(Status.Pending, "Your purchase is pending confirmation from Google Play.")
        } else if (fromQuery) {
            // A successful empty query means they don't own it (or it was refunded).
            Entitlements.setPremium(app, false)
            Entitlements.setPending(app, false)
            setStatus(Status.Ready)
        } else if (status == Status.Connecting || status == Status.Idle) {
            setStatus(if (Entitlements.hasPremium(app)) Status.Owned else Status.Ready)
        }
    }

    fun resetForTests() {
        client?.end(); client = null; product = null; lastMessage = null; status = Status.Idle
        synchronized(listeners) { listeners.clear() }
        clientFactory = { PlayBillingClient(it) }
    }
}

/** Real Google Play Billing client (Play Billing Library 9.x). */
class PlayBillingClient(private val ctx: Context) : BillingStore.Client {
    private var listener: (List<BillingStore.PurchaseInfo>) -> Unit = {}
    private var details: ProductDetails? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            listener(purchases.map { it.info() })
        }
    }

    private val billing: BillingClient = BillingClient.newBuilder(ctx)
        .setListener(purchasesListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override fun setListener(l: (List<BillingStore.PurchaseInfo>) -> Unit) { listener = l }

    override fun start(onReady: (ok: Boolean, message: String?) -> Unit) {
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                onReady(ok, if (ok) null else friendly(result))
            }
            override fun onBillingServiceDisconnected() { /* auto-reconnect is enabled */ }
        })
    }

    override fun end() = runCatching { billing.endConnection() }.let { }

    override fun queryProduct(id: String, onResult: (BillingStore.ProductInfo?) -> Unit) {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id).setProductType(BillingClient.ProductType.INAPP).build())
        ).build()
        billing.queryProductDetailsAsync(params) { result, products ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) { onResult(null); return@queryProductDetailsAsync }
            val list = products.productDetailsList
            val d = list.firstOrNull { it.productId == id } ?: list.firstOrNull()
            details = d
            onResult(d?.let { it.info() })
        }
    }

    override fun queryPurchases(onResult: (ok: Boolean, purchases: List<BillingStore.PurchaseInfo>) -> Unit) {
        billing.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            val ok = result.responseCode == BillingClient.BillingResponseCode.OK
            onResult(ok, if (ok) purchases.map { it.info() } else emptyList())
        }
    }

    override fun launch(activity: Activity, product: BillingStore.ProductInfo, onError: (String) -> Unit) {
        val d = (product.raw as? ProductDetails) ?: details
        if (d == null) { onError("Couldn't load the Premium product."); return }
        val offer = d.oneTimePurchaseOfferDetails
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
            )).build()
        val result = billing.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) onError(friendly(result))
        offer // keep a read so shrinking doesn't think it's unused if we later pass an offer token
    }

    override fun acknowledge(token: String, onDone: (Boolean) -> Unit) {
        billing.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        ) { result -> onDone(result.responseCode == BillingClient.BillingResponseCode.OK) }
    }

    private fun ProductDetails.info(): BillingStore.ProductInfo {
        val price = oneTimePurchaseOfferDetails?.formattedPrice
            ?: oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
            ?: ""
        return BillingStore.ProductInfo(productId, title, description, price, raw = this)
    }

    private fun Purchase.info() = BillingStore.PurchaseInfo(
        productId = products.firstOrNull() ?: "",
        token = purchaseToken,
        purchased = purchaseState == Purchase.PurchaseState.PURCHASED,
        pending = purchaseState == Purchase.PurchaseState.PENDING,
        acknowledged = isAcknowledged,
    )

    private fun friendly(r: BillingResult): String = when (r.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
            "Purchases aren't available on this device. You need the Play Store."
        BillingClient.BillingResponseCode.USER_CANCELED -> "Purchase cancelled."
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "You already own Premium. Restoring…"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "Premium isn't available in your country yet."
        else -> r.debugMessage.ifBlank { "Couldn't complete the purchase (error ${r.responseCode})." }
    }
}
