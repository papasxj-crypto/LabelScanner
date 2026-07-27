package net.meinook.labelscanner

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CompoundBarcodeView

class ScannerActivity : AppCompatActivity() {

    private lateinit var barcodeView: CompoundBarcodeView
    private var isScanningActive = false

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            // Only process results if our 3-second aim delay has elapsed
            if (!isScanningActive) return

            result?.text?.let { upc ->
                isScanningActive = false
                barcodeView.pause()
                val intent = Intent().apply {
                    putExtra("SCAN_RESULT", upc)
                }
                setResult(RESULT_OK, intent)
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        barcodeView = findViewById(R.id.barcode_scanner)
        barcodeView.decodeContinuous(callback)
    }

    override fun onResume() {
        super.onResume()
        // 1. Keep camera preview active so you can see to aim, but turn scanning OFF
        isScanningActive = false
        barcodeView.resume()

        // 2. Wait 3000ms (3 full seconds) while camera is visible before activating detection
        Handler(Looper.getMainLooper()).postDelayed({
            isScanningActive = true
        }, 2000)
    }

    override fun onPause() {
        super.onPause()
        isScanningActive = false
        barcodeView.pause()
    }
}