package com.nurvpn.app.ui.qr

import com.nurvpn.app.R

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "qr_result"
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var capture: CaptureManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)

        barcodeView = findViewById(R.id.barcode_scanner)
        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()

        barcodeView.decodeSingle { result ->
            val text = result.text
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, R.string.toast_qr_empty, Toast.LENGTH_SHORT).show()
                return@decodeSingle
            }
            val i = Intent().apply { putExtra(EXTRA_RESULT, text) }
            setResult(RESULT_OK, i)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
