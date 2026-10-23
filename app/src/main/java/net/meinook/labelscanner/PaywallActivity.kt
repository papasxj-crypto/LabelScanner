package net.meinook.labelscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class PaywallActivity : AppCompatActivity(), BillingManager.BillingUpdatesListener {

    private lateinit var billingManager: BillingManager
    private lateinit var appSettings: AppSettings

    private lateinit var btnClose: ImageView
    private lateinit var txtPaywallTitle: TextView
    private lateinit var cardPlanYearly: MaterialCardView
    private lateinit var cardPlanMonthly: MaterialCardView
    private lateinit var radioYearly: RadioButton
    private lateinit var radioMonthly: RadioButton
    private lateinit var txtYearlyPrice: TextView
    private lateinit var txtMonthlyPrice: TextView
    private lateinit var btnSubscribe: MaterialButton
    private lateinit var txtTrialDisclosure: TextView
    private lateinit var txtRestorePurchase: TextView
    private lateinit var txtPrivacyPolicy: TextView
    private lateinit var txtTermsOfService: TextView

    private var isYearlySelected = true
    private var monthlyPriceText = "$4.99"
    private var yearlyPriceText = "$39.99"

    private var secretTapCount = 0
    private var lastSecretTapTime = 0L

    companion object {
        private const val PRIVACY_POLICY_URL = "https://filterpointsoftware.com/privacy"
        private const val TERMS_OF_SERVICE_URL = "https://filterpointsoftware.com/terms"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paywall)

        appSettings = AppSettings(this)
        billingManager = BillingManager(this, lifecycleScope, this)

        initializeViews()
        setupListeners()
        updatePlanSelectionUI()
    }

    private fun initializeViews() {
        btnClose = findViewById(R.id.btnClose)
        txtPaywallTitle = findViewById(R.id.txtPaywallTitle)
        cardPlanYearly = findViewById(R.id.cardPlanYearly)
        cardPlanMonthly = findViewById(R.id.cardPlanMonthly)
        radioYearly = findViewById(R.id.radioYearly)
        radioMonthly = findViewById(R.id.radioMonthly)
        txtYearlyPrice = findViewById(R.id.txtYearlyPrice)
        txtMonthlyPrice = findViewById(R.id.txtMonthlyPrice)
        btnSubscribe = findViewById(R.id.btnSubscribe)
        txtTrialDisclosure = findViewById(R.id.txtTrialDisclosure)
        txtRestorePurchase = findViewById(R.id.txtRestorePurchase)
        txtPrivacyPolicy = findViewById(R.id.txtPrivacyPolicy)
        txtTermsOfService = findViewById(R.id.txtTermsOfService)
    }

    private fun setupListeners() {
        btnClose.setOnClickListener {
            finish()
        }

        // Secret Tap Backdoor for testing
        txtPaywallTitle.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSecretTapTime < 1500) {
                secretTapCount++
                if (secretTapCount >= 7) {
                    appSettings.setSubscriptionActive(true)
                    Toast.makeText(this, "Developer Bypass Activated", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } else {
                secretTapCount = 1
            }
            lastSecretTapTime = currentTime
        }

        cardPlanYearly.setOnClickListener {
            isYearlySelected = true
            updatePlanSelectionUI()
        }

        radioYearly.setOnClickListener {
            isYearlySelected = true
            updatePlanSelectionUI()
        }

        cardPlanMonthly.setOnClickListener {
            isYearlySelected = false
            updatePlanSelectionUI()
        }

        radioMonthly.setOnClickListener {
            isYearlySelected = false
            updatePlanSelectionUI()
        }

        btnSubscribe.setOnClickListener {
            btnSubscribe.isEnabled = false
            btnSubscribe.text = "Opening Checkout..."
            billingManager.launchPurchaseFlow(this, isYearlySelected)
            btnSubscribe.postDelayed({
                btnSubscribe.isEnabled = true
                updateSubscribeButtonText()
            }, 3000)
        }

        txtRestorePurchase.setOnClickListener {
            Toast.makeText(this, "Checking existing subscriptions...", Toast.LENGTH_SHORT).show()
            billingManager.queryActivePurchases { isRestored ->
                if (isRestored) {
                    Toast.makeText(this, "Subscription successfully restored!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "No active subscription found.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        txtPrivacyPolicy.setOnClickListener {
            openWebPage(PRIVACY_POLICY_URL)
        }

        txtTermsOfService.setOnClickListener {
            openWebPage(TERMS_OF_SERVICE_URL)
        }
    }

    private fun updatePlanSelectionUI() {
        radioYearly.isChecked = isYearlySelected
        radioMonthly.isChecked = !isYearlySelected

        val selectedStrokeColor = ContextCompat.getColor(this, R.color.accentWarm)
        val defaultStrokeColor = ContextCompat.getColor(this, R.color.surfaceStroke)

        cardPlanYearly.strokeColor = if (isYearlySelected) selectedStrokeColor else defaultStrokeColor
        cardPlanYearly.strokeWidth = if (isYearlySelected) 4 else 2

        cardPlanMonthly.strokeColor = if (!isYearlySelected) selectedStrokeColor else defaultStrokeColor
        cardPlanMonthly.strokeWidth = if (!isYearlySelected) 4 else 2

        updateSubscribeButtonText()

        txtTrialDisclosure.text = if (isYearlySelected) {
            "7 days free, then $yearlyPriceText/year. Cancel anytime in Google Play Subscriptions before trial ends to avoid charges."
        } else {
            "7 days free, then $monthlyPriceText/month. Cancel anytime in Google Play Subscriptions before trial ends to avoid charges."
        }
    }

    private fun updateSubscribeButtonText() {
        btnSubscribe.text = "Start 7-Day Free Trial"
    }

    private fun openWebPage(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    override fun onProductDetailsLoaded(
        monthlyPrice: String,
        yearlyPrice: String,
        hasMonthlyTrial: Boolean,
        hasYearlyTrial: Boolean
    ) {
        monthlyPriceText = monthlyPrice
        yearlyPriceText = yearlyPrice

        txtMonthlyPrice.text = "$monthlyPrice / month"
        txtYearlyPrice.text = "$yearlyPrice / year"

        updatePlanSelectionUI()
    }

    override fun onPurchaseSuccess() {
        Toast.makeText(this, "FilterPoint Pro Unlocked!", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onBillingError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        btnSubscribe.isEnabled = true
        updateSubscribeButtonText()
    }
}