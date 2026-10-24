package com.example.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

class QrScanActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RESULT = "SCAN_RESULT"
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private var handled = false
    private var torchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_qr_scan)

        barcodeView = findViewById(R.id.barcode_view)
        try {
            barcodeView.viewFinder.visibility = View.GONE
            barcodeView.statusView?.visibility = View.GONE
        } catch (_: Throwable) {
        }
        barcodeView.setDecoderFactory(DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE)))
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = result?.text
                if (handled || text.isNullOrBlank()) return
                handled = true
                try {
                    barcodeView.pause()
                } catch (_: Throwable) {
                }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
                finish()
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {
            }
        })

        applyInsets()
        findViewById<View>(R.id.qr_close).setOnClickListener { finish() }
        findViewById<View>(R.id.qr_torch).setOnClickListener { toggleTorch() }
    }

    private fun applyInsets() {
        val topBar = findViewById<View>(R.id.qr_top_bar)
        val bottomBar = findViewById<View>(R.id.qr_bottom_bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.qr_root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(dp(16), bars.top + dp(10), dp(16), dp(10))
            bottomBar.setPadding(dp(28), dp(16), dp(28), bars.bottom + dp(28))
            insets
        }
    }

    private fun toggleTorch() {
        val button = findViewById<View>(R.id.qr_torch)
        val next = !torchOn
        try {
            if (next) barcodeView.setTorchOn() else barcodeView.setTorchOff()
            torchOn = next
            button.alpha = if (torchOn) 1f else 0.6f
        } catch (_: Throwable) {
            torchOn = false
            button.alpha = 0.6f
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        try {
            barcodeView.resume()
        } catch (_: Throwable) {
        }
    }

    override fun onPause() {
        try {
            barcodeView.pause()
        } catch (_: Throwable) {
        }
        super.onPause()
    }
}
