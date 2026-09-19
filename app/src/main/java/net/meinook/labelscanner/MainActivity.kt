package net.meinook.labelscanner

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.delay // Resolves delay() compile support
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var appSettings: AppSettings

    // Gating state to keep the system splash screen active during background checks
    private var isCheckingSubscription = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Initialize the Google Jetpack Splash Screen before onCreate
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)

        appSettings = AppSettings(this)

        // Keep the native system splash screen visible until our background checks finish
        splashScreen.setKeepOnScreenCondition { isCheckingSubscription }

        // Run background verification checks inside a lifecycle-aware coroutine
        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                // A. Local Debug Bypass check
                if (isDebuggable() && !appSettings.isDebugOverrideDisabled()) {
                    appSettings.setSubscriptionActive(true)
                }

                // B. Sync active status with Firebase Firestore
                syncSubscriptionWithFirestore()

            } catch (e: Exception) {
                Log.e("MainActivitySubscription", "Setup check failed", e)
            } finally {
                // C. Calculate elapsed time to enforce exactly 3 seconds minimum display duration
                val elapsedTime = System.currentTimeMillis() - startTime
                val minimumDisplayDuration = 3000L // 3 seconds
                val remainingTime = minimumDisplayDuration - elapsedTime

                if (remainingTime > 0) {
                    delay(remainingTime.milliseconds)
                }

                // D. Determine user routing once background tasks and 3s limit are met
                when {
                    appSettings.isUserRevoked() -> {
                        val intent = Intent(this@MainActivity, LockoutActivity::class.java).apply {
                            putExtra("revocation_reason", appSettings.getRevocationReason())
                        }
                        startActivity(intent)
                        finish()
                    }
                    appSettings.isSubscriptionActive() -> {
                        // Let the native splash screen fade out smoothly and reveal the dashboard
                        isCheckingSubscription = false
                    }
                    else -> {
                        startActivity(Intent(this@MainActivity, PaywallActivity::class.java))
                        finish()
                    }
                }
            }
        }

        // Standard Main Dashboard initialization continues below
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        AppSettings.indexExclusivityGroups(applicationContext)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        navController = navHostFragment.navController

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setupWithNavController(navController)

        bottomNav.post {
            handleIncomingShareIntent(intent)
        }
    }

    private fun isDebuggable(): Boolean {
        return (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    // Wrapped in standard try-catches to prevent uninitialized Firebase crashes
    private suspend fun syncSubscriptionWithFirestore() {
        try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val currentUser = auth.currentUser ?: return

            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val userDoc = db.collection("users").document(currentUser.uid).get().await()
            if (userDoc.exists()) {
                val active = userDoc.getBoolean("subscription_active") ?: false
                val revoked = userDoc.getBoolean("is_revoked") ?: false
                val reason = userDoc.getString("revocation_reason") ?: ""

                appSettings.setSubscriptionActive(active)
                appSettings.setUserRevoked(revoked, reason)
                Log.d("MainActivitySubscription", "Firestore sync complete: Active=$active, Revoked=$revoked")
            }
        } catch (e: NoClassDefFoundError) {
            Log.e("MainActivitySubscription", "Firebase SDK missing from classpath", e)
        } catch (e: IllegalStateException) {
            Log.e("MainActivitySubscription", "FirebaseApp not initialized yet", e)
        } catch (e: Exception) {
            Log.e("MainActivitySubscription", "Firestore sync bypassed", e)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIncomingShareIntent(it) }
    }

    private fun handleIncomingShareIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val url = extractUrlFromText(sharedText)

            if (!url.isNullOrEmpty()) {
                val bundle = Bundle().apply {
                    putString("recipe_url", url)
                }

                if (::navController.isInitialized) {
                    navController.navigate(R.id.recipeFragment, bundle)
                }
            }
        }
    }

    private fun extractUrlFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null

        val urlRegex = "(https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+)".toRegex()
        val matchResult = urlRegex.find(text)
        return matchResult?.value ?: if (text.startsWith("http://") || text.startsWith("https://")) text else null
    }
}