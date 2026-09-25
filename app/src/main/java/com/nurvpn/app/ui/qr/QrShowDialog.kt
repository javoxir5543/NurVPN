package com.nurvpn.app.ui.qr

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nurvpn.app.R
import com.nurvpn.app.util.QrGenerator

/** QR kodni dialogda ko'rsatadi. Pastda "Nusxalash" tugmasi. */
object QrShowDialog {

    fun show(ctx: Context, title: String, content: String) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val pad = (ctx.resources.displayMetrics.density * 20).toInt()
        val qrSize = (ctx.resources.displayMetrics.widthPixels * 0.72).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        val titleTv = TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, pad)
        }
        root.addView(titleTv)

        val bmp = QrGenerator.generate(content, qrSize)
        if (bmp == null) {
            Toast.makeText(ctx, R.string.toast_qr_gen_error, Toast.LENGTH_SHORT).show()
            return
        }

        val iv = ImageView(ctx).apply {
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize)
            adjustViewBounds = true
        }
        root.addView(iv)

        val copyTv = TextView(ctx).apply {
            text = ctx.getString(R.string.qr_share_copy)
            textSize = 14f
            setTextColor(Color.parseColor("#4A9EFF"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, pad, 0, 0)
            isClickable = true
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText(title, content))
                Toast.makeText(ctx, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(copyTv)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }
}
