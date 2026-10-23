package net.meinook.labelscanner

import android.app.Activity
import android.content.Context
import android.util.Log
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
    private val scope: CoroutineScope,
    private val listener: BillingUpdatesListener? = null
) : PurchasesUpdatedListener {

    private val appSettings = AppSettings(context)
    private var billingClient: BillingClient

    private var proProductDetails: ProductDetails? = null
    private var monthlyOfferToken: String? = null
    private var yearlyOfferToken: String? = null
    private var monthlyFormattedPrice: String = "$4.99"
    private var yearlyFormattedPrice: String = "$39.99"

    interface BillingUpdatesListener {
        fun onProductDetailsLoaded(
            monthlyPrice: String,
            yearlyPrice: String,
            hasMonthlyTrial: Boolean,
            hasYearlyTrial: Boolean
        )
        fun onPurchaseSuccess()
        fun onBillingError(message: String)
    }

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID_PRO = "filterpoint_pro"
        const val BASE_PLAN_MONTHLY = "monthly"
        const val BASE_PLAN_YEARLY = "yearly"

        fun isProUser(context: Context): Boolean {
            if (BuildConfig.BYPASS_PAYWALL) {
                return true
            }
            return AppSettings(context).isSubscriptionActive()
        }
    }

    init {
        val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .enablePrepaidPlans()
            .build()

        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(pendingPurchasesParams)
            .enableAutoServiceReconnection()
            .build()

        startConnection()
    }

    private fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "BillingClient connected successfully")
                    queryProductDetails()
                    queryActivePurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                    listener?.onBillingError("Setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "BillingClient disconnected. Auto-service reconnection active.")
            }
        })
    }

    fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_PRO)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(queryProductDetailsParams) { billingResult, queryProductDetailsResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val detailsList = queryProductDetailsResult.productDetailsList
                if (detailsList.isNotEmpty()) {
                    val details = detailsList[0]
                    proProductDetails = details
                    parseOffers(details)
                } else {
                    Log.w(TAG, "No product details returned for $PRODUCT_ID_PRO")
                }
            } else {
                Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

    private fun parseOffers(details: ProductDetails) {
        val offers = details.subscriptionOfferDetails ?: return

        var hasMonthlyTrial = false
        var hasYearlyTrial = false

        for (offer in offers) {
            val basePlanId = offer.basePlanId
            val pricingPhases = offer.pricingPhases.pricingPhaseList

            val containsFreeTrial = pricingPhases.any { it.priceAmountMicros == 0L }
            val recurringPhase = pricingPhases.lastOrNull()

            if (basePlanId == BASE_PLAN_MONTHLY) {
                if (containsFreeTrial || monthlyOfferToken == null) {
                    monthlyOfferToken = offer.offerToken
                    hasMonthlyTrial = containsFreeTrial
                    recurringPhase?.let { monthlyFormattedPrice = it.formattedPrice }
                }
            } else if (basePlanId == BASE_PLAN_YEARLY) {
                if (containsFreeTrial || yearlyOfferToken == null) {
                    yearlyOfferToken = offer.offerToken
                    hasYearlyTrial = containsFreeTrial
                    recurringPhase?.let { yearlyFormattedPrice = it.formattedPrice }
                }
            }
        }

        scope.launch(Dispatchers.Main) {
            listener?.onProductDetailsLoaded(
                monthlyPrice = monthlyFormattedPrice,
                yearlyPrice = yearlyFormattedPrice,
                hasMonthlyTrial = hasMonthlyTrial,
                hasYearlyTrial = hasYearlyTrial
            )
        }
    }

    fun launchPurchaseFlow(activity: Activity, isYearly: Boolean) {
        val details = proProductDetails
        val token = if (isYearly) yearlyOfferToken else monthlyOfferToken

        if (details == null || token == null) {
            Log.e(TAG, "Product details or offer token missing. Attempting reload.")
            queryProductDetails()
            listener?.onBillingError("Products loading. Please try again in a moment.")
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(token)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (!purchases.isNullOrEmpty()) {
                    for (purchase in purchases) {
                        scope.launch {
                            handlePurchase(purchase)
                        }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "User canceled billing flow.")
            }
            else -> {
                Log.e(TAG, "Purchase failed: ${billingResult.debugMessage}")
                scope.launch(Dispatchers.Main) {
                    listener?.onBillingError("Purchase error: ${billingResult.debugMessage}")
                }
            }
        }
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                withContext(Dispatchers.IO) {
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) { ackResult ->
                        if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            Log.d(TAG, "Purchase acknowledged successfully.")
                            grantEntitlement(purchase)
                        } else {
                            Log.e(TAG, "Failed to acknowledge purchase: ${ackResult.debugMessage}")
                        }
                    }
                }
            } else {
                grantEntitlement(purchase)
            }
        }
    }

    private fun grantEntitlement(purchase: Purchase) {
        appSettings.setSubscriptionActive(true)

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            scope.launch {
                syncPurchaseToFirestore(uid, purchase)
            }
        }

        scope.launch(Dispatchers.Main) {
            listener?.onPurchaseSuccess()
        }
    }

    fun queryActivePurchases(onComplete: ((Boolean) -> Unit)? = null) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            val hasActiveSubscription = (billingResult.responseCode == BillingClient.BillingResponseCode.OK) &&
                    purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }

            appSettings.setSubscriptionActive(hasActiveSubscription)

            val uid = FirebaseAuth.getInstance().currentUser?.uid
            scope.launch {
                if (hasActiveSubscription) {
                    for (purchase in purchases) {
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            handlePurchase(purchase)
                        }
                    }
                } else if (uid != null) {
                    markSubscriptionInactive(uid)
                }

                withContext(Dispatchers.Main) {
                    onComplete?.invoke(hasActiveSubscription)
                }
            }
        }
    }

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
            try {
                db.collection("users").document(uid).set(updateData).await()
            } catch (err: Exception) {
                Log.w(TAG, "Firestore sync skipped: ${err.message}")
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
            Log.w(TAG, "Firestore inactive update skipped: ${e.message}")
        }
    }
}