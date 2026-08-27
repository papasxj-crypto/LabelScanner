package net.meinook.labelscanner

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton

class PaywallActivity : AppCompatActivity() {

    private lateinit var billingManager: BillingManager
    private lateinit var appSettings: AppSettings

    private var secretTapCount = 0
    private var lastSecretTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paywall)

        appSettings = AppSettings(this)
        billingManager = BillingManager(this, lifecycleScope)

        val btnSubscribe = findViewById<MaterialButton>(R.id.btnSubscribe)
        val txtRestorePurchase = findViewById<TextView>(R.id.txtRestorePurchase)
        val txtPaywallTitle = findViewById<TextView>(R.id.txtPaywallTitle)

        // Secret Tap Backdoor for testing release configurations
        txtPaywallTitle.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSecretTapTime < 1500) {
                secretTapCount++
                if (secretTapCount >= 7) {
                    appSettings.setSubscriptionActive(true)
                    Toast.makeText(this, "Developer Bypass Activated", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            } else {
                secretTapCount = 1
            }
            lastSecretTapTime = currentTime
        }

        btnSubscribe.setOnClickListener {
            Toast.makeText(this, "Connecting to Play Store...", Toast.LENGTH_SHORT).show()
            billingManager.queryActivePurchases()
        }

        txtRestorePurchase.setOnClickListener {
            Toast.makeText(this, "Checking existing licenses...", Toast.LENGTH_SHORT).show()
            billingManager.queryActivePurchases()

            if (appSettings.isSubscriptionActive()) {
                Toast.makeText(this, "Subscription successfully restored!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "No active subscription found.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}