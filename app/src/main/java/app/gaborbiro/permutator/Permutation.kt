package app.gaborbiro.permutator

class Permutation(var checked: Boolean = false, val things: List<String>, val duplicateCount: Int) {

    override fun toString(): String {
        return "$checked, $things"
    }
}