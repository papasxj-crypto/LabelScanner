package net.meinook.labelscanner

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class BillingManager(
    private val context: Context,
    private val scope: CoroutineScope
) : PurchasesUpdatedListener {

    private var billingClient: BillingClient

    init {
        // Required initialization parameter configuration for modern Billing Client versions
        val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .enablePrepaidPlans()
            .build()

        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(pendingPurchasesParams)
            .build()

        startConnection()
    }

    private fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // Sync active status on connection setup
                    queryActivePurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Standard retry connection logic
                startConnection()
            }
        })
    }

    // Launch Google Play Checkout Flow (Displays the checkout overlay UI)
    fun launchSubscriptionPurchase(activity: Activity, productDetails: ProductDetails, offerToken: String) {
        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offerToken)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    // Google Play Listener: Triggered when checkout transaction is completed or updated
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                scope.launch {
                    handlePurchase(purchase)
                }
            }
        }
    }

    // Processes, Acknowledges, and Syncs Purchases to Cloud Firestore
    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

            // Subscriptions must be acknowledged within 3 days or Google processes an automatic refund
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                withContext(Dispatchers.IO) {
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            scope.launch {
                                syncPurchaseToFirestore(uid, purchase)
                            }
                        }
                    }
                }
            } else {
                syncPurchaseToFirestore(uid, purchase)
            }
        }
    }

    // Direct Firestore update to register receipt
    private suspend fun syncPurchaseToFirestore(uid: String, purchase: Purchase) {
        val db = FirebaseFirestore.getInstance()
        val updateData = mapOf(
            "subscription_active" to true,
            "purchase_token" to purchase.purchaseToken,
            "order_id" to purchase.orderId
        )
        try {
            db.collection("users").document(uid).update(updateData).await()
        } catch (e: Exception) {
            // Document might not exist yet; fallback to set
            db.collection("users").document(uid).set(updateData).await()
        }
    }

    // Query active subscriptions from Play Store cache to ensure local state matches Console
    fun queryActivePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@queryPurchasesAsync
                scope.launch {
                    if (purchases.isEmpty()) {
                        markSubscriptionInactive(uid)
                    } else {
                        for (purchase in purchases) {
                            handlePurchase(purchase)
                        }
                    }
                }
            }
        }
    }

    private suspend fun markSubscriptionInactive(uid: String) {
        val db = FirebaseFirestore.getInstance()
        try {
            db.collection("users").document(uid)
                .update("subscription_active", false)
                .await()
        } catch (e: Exception) {
            // Ignore if profile doesn't exist
        }
    }
}