package app.gaborbiro.permutator

sealed class UptimeState {
    object Disabled: UptimeState()
    object WaitingForOffline: UptimeState()
    object WaitingForOnline: UptimeState()
}