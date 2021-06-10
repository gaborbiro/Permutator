package app.gaborbiro.permutator.uptime

sealed class UptimeEvent {
    object Start: UptimeEvent()
    object Stop: UptimeEvent()
    object PingSuccess: UptimeEvent()
    object PingFailed: UptimeEvent()
    object NetworkAvailable: UptimeEvent()
    object NetworkUnavailable: UptimeEvent()
}