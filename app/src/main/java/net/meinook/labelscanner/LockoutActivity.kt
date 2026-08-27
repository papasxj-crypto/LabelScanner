package net.meinook.labelscanner

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LockoutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lockout)

        val reason = intent.getStringExtra("revocation_reason")
        if (!reason.isNullOrEmpty()) {
            findViewById<TextView>(R.id.txtLockoutReason).text = reason
        }
    }
}