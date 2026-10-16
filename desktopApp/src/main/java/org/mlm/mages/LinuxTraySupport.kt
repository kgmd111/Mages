package org.mlm.mages

import java.util.Locale
import java.util.concurrent.TimeUnit

private const val GNOME_TRAY_SKIP_MAJOR = 40
private const val GNOME_VERSION_TIMEOUT_SEC = 3L

internal fun shouldSkipDorkboxTray(): Boolean {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    if (!os.contains("linux")) return false
    val session = (System.getenv("GDMSESSION") ?: "").lowercase(Locale.ROOT)
    val desktop = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").lowercase(Locale.ROOT)
    if (desktop.startsWith("ubuntu") || session.startsWith("ubuntu")) return false
    val isGnomeSession = "gnome" in desktop ||
        session == "gnome" ||
        session == "gnome-wayland" ||
        session == "gnome-xorg" ||
        session == "gnome-classic" ||
        session == "default"
    if (!isGnomeSession) return false
    val major = gnomeShellMajor() ?: return false
    return major >= GNOME_TRAY_SKIP_MAJOR
}

private fun gnomeShellMajor(): Int? {
    val proc = runCatching {
        ProcessBuilder("gnome-shell", "--version").redirectErrorStream(true).start()
    }.getOrNull() ?: return null
    return try {
        if (!proc.waitFor(GNOME_VERSION_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        Regex("""\d+""").find(out)?.value?.toIntOrNull()
    } catch (_: Exception) {
        null
    } finally {
        proc.destroy()
    }
}
