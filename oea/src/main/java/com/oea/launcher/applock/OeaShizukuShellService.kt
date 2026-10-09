package com.oea.launcher.applock

class OeaShizukuShellService : IOeaShizukuShellService.Stub() {
    override fun setSuspended(packageName: String, suspended: Boolean): String {
        if (!PACKAGE_NAME.matches(packageName)) return "64\nInvalid package name"
        val operation = if (suspended) "suspend" else "unsuspend"
        val result = execute(arrayOf("cmd", "package", operation, "--user", "0", packageName))
        return result.first.toString() + "\n" + result.second
    }

    override fun getSuspended(packageName: String): Int {
        if (!PACKAGE_NAME.matches(packageName)) return -1
        val result = execute(arrayOf("dumpsys", "package", packageName))
        if (result.first != 0 || !result.second.contains("User 0:")) return -1
        return if (Regex("""(?m)^\s*User 0:.*\bsuspended=true\b""").containsMatchIn(result.second)) 1 else 0
    }

    private fun execute(args: Array<String>): Pair<Int, String> = runCatching {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        process.waitFor() to output
    }.getOrElse { -1 to (it.message ?: "Command failed") }

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")
    }
}
