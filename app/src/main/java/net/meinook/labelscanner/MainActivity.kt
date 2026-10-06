package net.meinook.labelscanner

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_TAB_ID = "TARGET_TAB_ID"
    }

    private lateinit var navController: NavController
    private lateinit var appSettings: AppSettings

    // Gating state to keep the system splash screen active during background checks
    private var isCheckingSubscription = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Initialize the Google Jetpack Splash Screen before onCreate
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)

        appSettings = AppSettings(this)

        // Keep the native system splash screen visible until background checks finish
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
                // C. Enforce exactly 3 seconds minimum display duration
                val elapsedTime = System.currentTimeMillis() - startTime
                val minimumDisplayDuration = 3000L
                val remainingTime = minimumDisplayDuration - elapsedTime

                if (remainingTime > 0) {
                    delay(remainingTime.milliseconds)
                }

                // D. Determine user routing once background tasks and 3s limit are met
                when {
                    appSettings.isUserRevoked() -> {
                        val lockoutIntent = Intent(this@MainActivity, LockoutActivity::class.java).apply {
                            putExtra("revocation_reason", appSettings.getRevocationReason())
                        }
                        startActivity(lockoutIntent)
                        finish()
                    }
                    appSettings.isSubscriptionActive() -> {
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

        // 1. Global Tab Navigation: Always land on the Root screen of each tab
        bottomNav.setOnItemSelectedListener { item ->
            val navOptions = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(false)
                .setPopUpTo(
                    navController.graph.findStartDestination().id,
                    inclusive = false,
                    saveState = false
                )
                .build()

            try {
                navController.navigate(item.itemId, null, navOptions)
                true
            } catch (e: Exception) {
                false
            }
        }

        // 2. Tab Reselection: Hard reset and root pop handler
        bottomNav.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    val currentDestId = navController.currentDestination?.id
                    if (currentDestId != null && currentDestId != R.id.navigation_home) {
                        navController.popBackStack(R.id.navigation_home, false)
                    }
                    val currentFragment = navHostFragment.childFragmentManager.fragments.firstOrNull()
                    if (currentFragment is HomeFragment) {
                        currentFragment.resetToReadyState()
                    }
                }
                R.id.navigation_my_health -> {
                    val currentDestId = navController.currentDestination?.id
                    if (currentDestId != null && currentDestId != R.id.navigation_my_health) {
                        navController.popBackStack(R.id.navigation_my_health, false)
                    }
                }
                R.id.recipeFragment -> {
                    val currentFragment = navHostFragment.childFragmentManager.fragments.firstOrNull()
                    if (currentFragment is RecipeFragment) {
                        currentFragment.resetToCleanInput()
                    }
                }
                R.id.navigation_history -> {
                    val currentDestId = navController.currentDestination?.id
                    if (currentDestId != null && currentDestId != R.id.navigation_history) {
                        navController.popBackStack(R.id.navigation_history, false)
                    }
                }
            }
        }

        // 3. Destination Change Listener: Synchronizes bottomNav with system back button navigation
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val targetMenuId = when (destination.id) {
                R.id.navigation_home, R.id.resultDetailFragment -> R.id.navigation_home
                R.id.navigation_my_health, R.id.customWatchlistFragment -> R.id.navigation_my_health
                R.id.recipeFragment -> R.id.recipeFragment
                R.id.navigation_history, R.id.recipeDetailFragment -> R.id.navigation_history
                else -> null
            }

            if (targetMenuId != null) {
                val menuItem = bottomNav.menu.findItem(targetMenuId)
                if (menuItem != null && !menuItem.isChecked) {
                    menuItem.isChecked = true
                }
            }
        }

        bottomNav.post {
            val targetTab = intent?.getIntExtra(EXTRA_TARGET_TAB_ID, 0) ?: 0
            if (targetTab != 0) {
                bottomNav.selectedItemId = targetTab
            } else if (!appSettings.hasCompletedInitialSetup()) {
                bottomNav.selectedItemId = R.id.navigation_my_health
            } else {
                handleIncomingShareIntent(intent)
            }
        }
    }

    private fun isDebuggable(): Boolean {
        return (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

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
        intent?.let { incomingIntent ->
            val targetTab = incomingIntent.getIntExtra(EXTRA_TARGET_TAB_ID, 0)
            if (targetTab != 0) {
                val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
                bottomNav?.selectedItemId = targetTab
            }
            handleIncomingShareIntent(incomingIntent)
        }
    }

    private fun handleIncomingShareIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val url = extractUrlFromText(sharedText)

            intent.removeExtra(Intent.EXTRA_TEXT)
            intent.action = null

            if (!url.isNullOrEmpty()) {
                val bundle = Bundle().apply {
                    putString("recipe_url", url)
                }
                if (::navController.isInitialized) {
                    navController.navigate(R.id.recipeFragment, bundle)
                }
            } else if (!sharedText.isNullOrBlank()) {
                val bundle = Bundle().apply {
                    putString("RECIPE_INPUT", sharedText)
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