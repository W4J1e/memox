package cool.hin.memox.data.imports

class ImportException(val textResId: Int, cause: Throwable) : RuntimeException(cause)
