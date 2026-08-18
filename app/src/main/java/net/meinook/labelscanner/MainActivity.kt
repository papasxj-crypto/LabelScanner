package net.meinook.labelscanner

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        AppSettings.indexExclusivityGroups(applicationContext)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        navController = navHostFragment.navController

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setupWithNavController(navController)

        // Force the share intent to process after the bottom navigation settles
        bottomNav.post {
            handleIncomingShareIntent(intent)
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