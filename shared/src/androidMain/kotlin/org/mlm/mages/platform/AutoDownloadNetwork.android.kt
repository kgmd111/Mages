package org.mlm.mages.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService

fun currentNetworkMetering(context: Context): Pair<Boolean, Boolean> {
    val manager = context.getSystemService<ConnectivityManager>() ?: return false to false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false to false
    return metering(capabilities)
}

fun observeNetworkMetering(context: Context, scope: CoroutineScope, service: MatrixService) {
    val manager = context.getSystemService<ConnectivityManager>() ?: return
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val (metered, roaming) = metering(capabilities)
            scope.launch { service.portOrNull?.setAutoDownloadNetwork(metered, roaming) }
        }
    }
    manager.registerDefaultNetworkCallback(callback)
}

private fun metering(capabilities: NetworkCapabilities): Pair<Boolean, Boolean> {
    val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    val roaming = metered && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    return metered to roaming
}
