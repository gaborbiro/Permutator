package app.gaborbiro.permutator

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat.getSystemService

fun View.show() {
    visibility = View.VISIBLE
}

fun View.hide() {
    visibility = View.GONE
}

fun View.hideKeyboard() {
    getSystemService(context, InputMethodManager::class.java)?.hideSoftInputFromWindow(
        windowToken,
        0
    )
}