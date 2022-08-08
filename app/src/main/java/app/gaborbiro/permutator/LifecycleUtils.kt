package app.gaborbiro.permutator

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer

fun <T : Any, L : LiveData<T>> Fragment.observe(liveData: L, body: (T?) -> Unit) {
    return liveData.observe(viewLifecycleOwner, Observer(body))
}

fun <T : Any, L : LiveData<T>> AppCompatActivity.observe(liveData: L, body: (T?) -> Unit) {
    return liveData.observe(this, Observer(body))
}

fun <T> MutableLiveData<T>.setIfDifferent(value: T) {
    if (this.value != value) {
        this.value = value
    }
}