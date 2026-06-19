package cool.hin.memox.utils.changehistory

interface Change {
    fun redo()

    fun undo()
}
