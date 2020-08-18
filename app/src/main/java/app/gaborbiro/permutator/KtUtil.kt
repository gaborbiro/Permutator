package app.gaborbiro.permutator

fun <A, B> Pair<A?, B?>.notNull(action: (A, B) -> Unit) {
    if (this.first != null && this.second != null)
        action(this.first!!, this.second!!)
}