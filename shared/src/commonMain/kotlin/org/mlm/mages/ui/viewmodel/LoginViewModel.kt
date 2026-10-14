package org.mlm.mages.ui.viewmodel

import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import mages.shared.generated.resources.*
import org.mlm.mages.accounts.MatrixAccount
import org.mlm.mages.accounts.MatrixClients
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.PasswordLoginKind
import org.mlm.mages.matrix.createMatrixPort
import org.mlm.mages.platform.getDeviceDisplayName
import org.mlm.mages.platform.shouldRequestLocalNetworkPermission
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.LoginUiState
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res

class LoginViewModel(
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val matrixClients: MatrixClients
) : BaseViewModel<LoginUiState>(LoginUiState()) {

    private var ssoJob: Job? = null
    private var oauthJob: Job? = null
    private var serverCheckJob: Job? = null

    init {
        launch {
            val savedHs = settingsRepository.flow.first().homeserver
            if (savedHs.isNotBlank()) {
                updateState { copy(homeserver = savedHs) }
            }
            debouncedProbeServer()
        }
    }

    sealed class Event { data object LoginSuccess : Event() }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setHomeserver(value: String) {
        updateState { copy(homeserver = value, loginDetails = null, needsLocalNetworkPermission = false) }
        debouncedProbeServer()
    }

    fun setUser(value: String) = updateState { copy(user = value) }
    fun setPass(value: String) = updateState { copy(pass = value) }

    fun setPasswordLoginKind(value: PasswordLoginKind) =
        updateState { copy(passwordLoginKind = value) }

    fun setPhoneCountry(value: String) =
        updateState { copy(phoneCountry = value.uppercase()) }

    fun togglePasswordLogin() = updateState { copy(showPasswordLogin = !showPasswordLogin) }

    /* waits 600ms after last keystroke before probing. */
    private fun debouncedProbeServer() {
        serverCheckJob?.cancel()
        serverCheckJob = launch {
            delay(600)
            probeServer()
        }
    }

    /** Probe the homeserver for supported login methods. */
    private suspend fun probeServer() {
        val hs = normalizeHomeserver(currentState.homeserver)
        if (hs.isBlank()) {
            updateState { copy(loginDetails = null, isCheckingServer = false) }
            return
        }

        if (shouldRequestLocalNetworkPermission(hs)) {
            updateState {
                copy(
                    isCheckingServer = false,
                    loginDetails = null,
                    needsLocalNetworkPermission = true,
                )
            }
            return
        }

        updateState { copy(isCheckingServer = true, error = null, needsLocalNetworkPermission = false) }

        val settings = settingsRepository.flow.first()
        val proxyUrl = if (settings.proxyEnabled) settings.proxyUrl.takeIf { it.isNotBlank() } else null

        try {
            val details = createMatrixPort().probeHomeserver(hs, proxyUrl)

            updateState {
                copy(
                    loginDetails = details,
                    isCheckingServer = false,
                    // Always show password as fallback when no other auth methods available
                    showPasswordLogin = !details.supportsOauth && !details.supportsSso && !details.supportsPassword
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val unreachable = getString(Res.string.couldnt_reach_server, hs)
            val detail = e.message?.takeIf { it.isNotBlank() }
            updateState {
                copy(
                    loginDetails = null,
                    isCheckingServer = false,
                    showPasswordLogin = false,
                    error = if (detail == null) unreachable else "$unreachable\n$detail",
                )
            }
        }
    }

    private fun normalizeHomeserver(input: String): String {
        val hs = input.trim()
        if (hs.isBlank()) return ""
        return hs
    }

    private suspend fun resolvedHomeserver(port: MatrixPort, fallback: String): String {
        return runCatching { port.homeserverLoginDetails().homeserverUrl }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }

    @OptIn(ExperimentalTime::class)
    private fun newAccountId(): String {
        val t = Clock.System.now().toEpochMilliseconds()
        val r1 = Random.nextInt().toUInt().toString(16)
        val r2 = Random.nextInt().toUInt().toString(16)
        return "acct_${t}_${r1}_${r2}"
    }

    @OptIn(ExperimentalTime::class)
    fun submit() {
        val s = currentState
        if (s.isBusy || s.user.isBlank() || s.pass.isBlank()) return
        cancelSso()
        cancelOauth()

        val hs = s.effectiveHomeserver
        if (hs.isBlank()) {
            launch {
                val pleaseEnterAServerFallback = getString(Res.string.please_enter_a_server)
                updateState { copy(error = pleaseEnterAServerFallback) }
            }
            return
        }

        launch(onError = { t ->
            val loginFailedFallback = getString(Res.string.login_failed)
            val message = t.failureMessage(loginFailedFallback)
            updateState { copy(isBusy = false, error = message) }
        }) {
            updateState { copy(isBusy = true, error = null) }

            val accountId = newAccountId()
            val port = createMatrixPort()
            val settings = settingsRepository.flow.first()
            val proxyUrl = if (settings.proxyEnabled) settings.proxyUrl.takeIf { it.isNotBlank() } else null

            try {
                port.init(hs, accountId, proxyUrl)

                when (s.passwordLoginKind) {
                    PasswordLoginKind.Username -> {
                        port.login(s.user.trim(), s.pass, getDeviceDisplayName())
                    }

                    PasswordLoginKind.Email -> {
                        port.loginEmail(s.user.trim(), s.pass, getDeviceDisplayName())
                    }

                    PasswordLoginKind.Phone -> {
                        val country = s.phoneCountry.trim().uppercase()
                        if (country.length != 2) {
                            port.close()
                            val countryError = getString(Res.string.enter_a_2_letter_country_code_e_g_us_or_de)
                            updateState {
                                copy(
                                    isBusy = false,
                                    error = countryError
                                )
                            }
                            return@launch
                        }

                        port.loginPhone(country, s.user.trim(), s.pass, getDeviceDisplayName())
                    }
                }

                if (!port.isLoggedInSuspend()) {
                    port.close()
                    val loginFailedFallback2 = getString(Res.string.login_failed)
                    updateState { copy(isBusy = false, error = loginFailedFallback2) }
                    return@launch
                }

                val userId = port.whoami()
                if (userId.isNullOrBlank()) {
                    port.close()
                    val loginFailedTheServerDidNotReturnAUserIdTryAgainFallback = getString(Res.string.login_failed_the_server_did_not_return_a_user_id_try_again)
                    updateState { copy(isBusy = false, error = loginFailedTheServerDidNotReturnAUserIdTryAgainFallback) }
                    return@launch
                }

                val resolvedHs = resolvedHomeserver(port, hs)

                val account = MatrixAccount(
                    id = accountId,
                    userId = userId,
                    homeserver = resolvedHs,
                    deviceId = "",
                    accessToken = "",
                    addedAtMs = Clock.System.now().toEpochMilliseconds(),
                    proxyUrl = proxyUrl,
                )

                matrixClients.addLoggedInAccount(account, port)

                settingsRepository.update {
                    it.copy(
                        homeserver = resolvedHs,
                        androidNotifBaselineMs = Clock.System.now().toEpochMilliseconds()
                    )
                }

                updateState { copy(isBusy = false, error = null, homeserver = s.homeserver) }
                _events.send(Event.LoginSuccess)
            } catch (e: Exception) {
                runCatching { port.close() }
                val loginFailedFallback22 = getString(Res.string.login_failed)
                val message = e.failureMessage(loginFailedFallback22)
                updateState { copy(isBusy = false, error = message) }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    fun startSso(openUrl: (String) -> Boolean) {
        val s = currentState
        if (s.isBusy) return
        cancelSso()
        cancelOauth()

        val hs = s.effectiveHomeserver
        if (hs.isBlank()) {
            launch {
                val pleaseEnterAServerFallback2 = getString(Res.string.please_enter_a_server)
                updateState { copy(error = pleaseEnterAServerFallback2) }
            }
            return
        }

        ssoJob = launch(onError = { t ->
            if (t !is CancellationException) {
                val ssoFailedFallback = getString(Res.string.sso_failed)
                updateState { copy(isBusy = false, ssoInProgress = false, error = t.message ?: ssoFailedFallback) }
            }
        }) {
            updateState { copy(isBusy = true, ssoInProgress = true, error = null) }

            val accountId = newAccountId()
            val port = createMatrixPort()
            val settings = settingsRepository.flow.first()
            val proxyUrl = if (settings.proxyEnabled) settings.proxyUrl.takeIf { it.isNotBlank() } else null

            try {
                port.init(hs, accountId, proxyUrl)

                val ssoResult = port.loginSsoLoopback(openUrl, deviceName = getDeviceDisplayName())
                if (ssoResult.isFailure) {
                    port.close()
                    val ssoError = ssoResult.exceptionOrNull()?.message ?: getString(Res.string.sso_failed_or_was_cancelled)
                    updateState {
                        copy(
                            isBusy = false,
                            ssoInProgress = false,
                            error = ssoError
                        )
                    }
                    return@launch
                }

                val userId = port.whoami()
                if (userId.isNullOrBlank()) {
                    port.close()
                    val ssoFailedTheServerDidNotReturnAUserIdTryAgainFallback = getString(Res.string.sso_failed_the_server_did_not_return_a_user_id_try_again)
                    updateState { copy(isBusy = false, ssoInProgress = false, error = ssoFailedTheServerDidNotReturnAUserIdTryAgainFallback) }
                    return@launch
                }

                val resolvedHs = resolvedHomeserver(port, hs)

                val account = MatrixAccount(
                    id = accountId,
                    userId = userId,
                    homeserver = resolvedHs,
                    deviceId = "",
                    accessToken = "",
                    addedAtMs = Clock.System.now().toEpochMilliseconds(),
                    proxyUrl = proxyUrl,
                )

                matrixClients.addLoggedInAccount(account, port)

                settingsRepository.update {
                    it.copy(
                        homeserver = resolvedHs,
                        androidNotifBaselineMs = Clock.System.now().toEpochMilliseconds()
                    )
                }

                updateState { copy(isBusy = false, ssoInProgress = false, error = null, homeserver = s.homeserver) }
                _events.send(Event.LoginSuccess)
            } catch (e: CancellationException) {
                runCatching { port.close() }
                throw e
            } catch (e: Exception) {
                runCatching { port.close() }
                val ssoFailedFallback2 = getString(Res.string.sso_failed)
                updateState { copy(isBusy = false, ssoInProgress = false, error = e.message ?: ssoFailedFallback2) }
            }
        }
    }

    fun cancelSso() {
        ssoJob?.cancel()
        ssoJob = null
        updateState { copy(isBusy = false, ssoInProgress = false) }
    }

    @OptIn(ExperimentalTime::class)
    fun startOauth(openUrl: (String) -> Boolean) {
        val s = currentState
        if (s.isBusy) return
        cancelSso()
        cancelOauth()

        val hs = s.effectiveHomeserver
        if (hs.isBlank()) {
            launch {
                val pleaseEnterAServerFallback22 = getString(Res.string.please_enter_a_server)
                updateState { copy(error = pleaseEnterAServerFallback22) }
            }
            return
        }

        oauthJob = launch(onError = { t ->
            if (t !is CancellationException) {
                val oauthFailedFallback = getString(Res.string.oauth_failed)
                updateState { copy(isBusy = false, oauthInProgress = false, error = t.message ?: oauthFailedFallback) }
            }
        }) {
            updateState { copy(isBusy = true, oauthInProgress = true, error = null) }

            val accountId = newAccountId()
            val port = createMatrixPort()
            val settings = settingsRepository.flow.first()
            val proxyUrl = if (settings.proxyEnabled) settings.proxyUrl.takeIf { it.isNotBlank() } else null

            try {
                port.init(hs, accountId, proxyUrl)

                when (val result = port.loginOauth(openUrl, deviceName = getDeviceDisplayName())) {
                    MatrixPort.OauthLoginResult.RedirectStarted -> {
                        // Web flow continues after full-page redirect.
                        return@launch
                    }

                    is MatrixPort.OauthLoginResult.Failed -> {
                        port.close()
                        val oauthError = result.message ?: getString(Res.string.oauth_failed)
                        updateState {
                            copy(
                                isBusy = false,
                                oauthInProgress = false,
                                error = oauthError
                            )
                        }
                        return@launch
                    }

                    MatrixPort.OauthLoginResult.Completed -> {
                        // continue below
                    }
                }

                val userId = port.whoami()
                if (userId.isNullOrBlank()) {
                    port.close()
                    val oauthFailedTheServerDidNotReturnAUserIdTryAgainFallback = getString(Res.string.oauth_failed_the_server_did_not_return_a_user_id_try_again)
                    updateState { copy(isBusy = false, oauthInProgress = false, error = oauthFailedTheServerDidNotReturnAUserIdTryAgainFallback) }
                    return@launch
                }

                val resolvedHs = resolvedHomeserver(port, hs)

                val account = MatrixAccount(
                    id = accountId,
                    userId = userId,
                    homeserver = resolvedHs,
                    deviceId = "",
                    accessToken = "",
                    addedAtMs = Clock.System.now().toEpochMilliseconds(),
                    proxyUrl = proxyUrl,
                )

                matrixClients.addLoggedInAccount(account, port)

                settingsRepository.update {
                    it.copy(
                        homeserver = resolvedHs,
                        androidNotifBaselineMs = Clock.System.now().toEpochMilliseconds()
                    )
                }

                updateState { copy(isBusy = false, oauthInProgress = false, error = null, homeserver = s.homeserver) }
                _events.send(Event.LoginSuccess)
            } catch (e: CancellationException) {
                runCatching { port.close() }
                throw e
            } catch (e: Exception) {
                runCatching { port.close() }
                val oauthFailedFallback2 = getString(Res.string.oauth_failed)
                updateState { copy(isBusy = false, oauthInProgress = false, error = e.message ?: oauthFailedFallback2) }
            }
        }
    }

    fun cancelOauth() {
        oauthJob?.cancel()
        oauthJob = null
        updateState { copy(isBusy = false, oauthInProgress = false) }
    }

    fun clearError() = updateState { copy(error = null) }

    fun onLocalNetworkPermissionGranted() {
        updateState { copy(needsLocalNetworkPermission = false) }
        serverCheckJob?.cancel()
        serverCheckJob = launch {
            probeServer()
        }
    }

    override fun onCleared() {
        serverCheckJob?.cancel()
        cancelSso()
        cancelOauth()
        super.onCleared()
    }
}
