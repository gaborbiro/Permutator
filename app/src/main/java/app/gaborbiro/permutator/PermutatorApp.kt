package app.gaborbiro.permutator

import android.app.Application
import androidx.lifecycle.MutableLiveData

class PermutatorApp: Application() {

    val uptimeServiceRunning = MutableLiveData<Boolean>()
}