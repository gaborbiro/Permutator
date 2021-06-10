package app.gaborbiro.permutator

import android.app.Application
import androidx.lifecycle.MutableLiveData

class PermutatorApp: Application() {

    val uptimeState = MutableLiveData<UptimeState>(UptimeState.Disabled)
}