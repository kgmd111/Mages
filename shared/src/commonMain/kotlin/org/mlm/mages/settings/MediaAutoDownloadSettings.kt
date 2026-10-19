package org.mlm.mages.settings

import org.mlm.mages.matrix.AutoDownloadNetwork
import org.mlm.mages.matrix.MediaAutoDownloadRules

const val AUTO_DOWNLOAD_WIFI = "Wi-Fi"
const val AUTO_DOWNLOAD_MOBILE = "Mobile data"
const val AUTO_DOWNLOAD_ROAMING = "Roaming"

fun AppSettings.autoDownloadRules(): MediaAutoDownloadRules = MediaAutoDownloadRules(
    photos = autoDownloadPhotos.toAutoDownloadNetworks(),
    audio = autoDownloadAudio.toAutoDownloadNetworks(),
    video = autoDownloadVideo.toAutoDownloadNetworks(),
    documents = autoDownloadDocuments.toAutoDownloadNetworks(),
)

private fun Set<String>.toAutoDownloadNetworks(): List<AutoDownloadNetwork> = mapNotNull { option ->
    when (option) {
        AUTO_DOWNLOAD_WIFI -> AutoDownloadNetwork.Wifi
        AUTO_DOWNLOAD_MOBILE -> AutoDownloadNetwork.Mobile
        AUTO_DOWNLOAD_ROAMING -> AutoDownloadNetwork.Roaming
        else -> null
    }
}
