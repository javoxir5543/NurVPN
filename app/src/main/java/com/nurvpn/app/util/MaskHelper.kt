package com.nurvpn.app.util

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText

/**
 * Crypt1 shifrlangan linklar uchun **password mask**.
 *
 * Agar foydalanuvchi `incy://crypt1/...`, `crypt1://...`, yoki `nurvpn://...`
 * linkni kiritsa — input avtomatik `****` ko'rinishiga o'tadi.
 *
 * Foydalanuvchi yozayotganda yoki paste qilganda ishlaydi.
 */
object MaskHelper {

    // Asl prefikslar (runtime quriladi)
    private val PREFIX_INCY: String = "in" + "cy" + "://crypt1/"
    private const val PREFIX_CRYPT: String = "crypt1://"
    private const val PREFIX_NURVPN: String = "nurvpn://"

    fun isCryptLink(s: CharSequence?): Boolean {
        val str = s?.toString() ?: return false
        return str.startsWith(PREFIX_INCY) ||
               str.startsWith(PREFIX_CRYPT) ||
               str.startsWith(PREFIX_NURVPN)
    }

    /**
     * EditText'ga TextWatcher o'rnatadi — crypt1 linklar uchun avtomatik
     * password mask yoqadi.
     *
     * Xatti-harakat:
     *  - Foydalanuvchi "incy://crypt1/..." deb yozsa → `****`
     *  - Boshqa matn (vless://, https://...) → oddiy matn
     *  - Cursor pozitsiyasi saqlanadi
     */
    fun attach(et: EditText) {
        // Default: oddiy matn
        if (et.inputType == InputType.TYPE_NULL) {
            et.inputType = InputType.TYPE_CLASS_TEXT
        }

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val want = if (isCryptLink(s)) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                if (et.inputType != want) {
                    val cursor = et.selectionEnd
                    et.inputType = want
                    try {
                        et.setSelection(cursor.coerceAtMost(et.text?.length ?: 0))
                    } catch (_: Throwable) { /* cursor tiklash xatosi — e'tiborsiz */ }
                }
            }
        })
    }
}
