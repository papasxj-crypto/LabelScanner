package net.meinook.labelscanner

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CompoundBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

class ScannerActivity : AppCompatActivity() {

    private lateinit var barcodeView: CompoundBarcodeView
    private var isScanningActive = false

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            // Only process results once camera autofocus settles
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

        // 1. Hardware Decoder Lockout: Decode ONLY 1D retail grocery barcodes (Ignores QR codes 100%)
        val retail1DFormats = listOf(
            BarcodeFormat.UPC_A,
            BarcodeFormat.UPC_E,
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8
        )
        barcodeView.decoderFactory = DefaultDecoderFactory(retail1DFormats)

        // 2. Viewfinder Aiming Guidance
        barcodeView.setStatusText("Align barcode within viewfinder")

        barcodeView.decodeContinuous(callback)
    }

    override fun onResume() {
        super.onResume()
        isScanningActive = false
        barcodeView.resume()

        // 3. 600ms autofocus settle window: prevents premature trigger while moving camera into position
        Handler(Looper.getMainLooper()).postDelayed({
            isScanningActive = true
        }, 600)
    }

    override fun onPause() {
        super.onPause()
        isScanningActive = false
        barcodeView.pause()
    }
}