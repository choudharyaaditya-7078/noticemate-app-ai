package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.data.local.AppDiagnosticLogger
import com.example.data.model.FailedPipelineStage
import com.example.data.model.NoticeLanguage
import com.example.data.model.NoticeRequestError
import com.example.data.model.NoticeRequestErrorCategory
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the current device connectivity state with an explicit distinction between
 * a raw network connection (Wi-Fi / cellular link) and working internet access.
 */
enum class NetworkConnectivityStatus {
    /**
     * Device is connected to a network that advertises internet capability and has been
     * validated for actual internet reachability (`NET_CAPABILITY_VALIDATED`).
     */
    ONLINE,

    /**
     * Device is connected to a local network (e.g., Wi-Fi router or cellular data link),
     * but internet access is not validated or not working (captive portal, router offline, DNS/WAN down).
     */
    CONNECTED_NO_INTERNET,

    /**
     * Device has no active network connection.
     */
    OFFLINE;

    val hasInternetAccess: Boolean
        get() = this == ONLINE

    val isNetworkConnected: Boolean
        get() = this == ONLINE || this == CONNECTED_NO_INTERNET

    fun badgeLabel(language: NoticeLanguage): String {
        return when (this) {
            ONLINE -> if (language == NoticeLanguage.HINDI) "ऑनलाइन" else "Online"
            CONNECTED_NO_INTERNET ->
                if (language == NoticeLanguage.HINDI) "इंटरनेट उपलब्ध नहीं" else "No Internet Access"
            OFFLINE -> if (language == NoticeLanguage.HINDI) "ऑफ़लाइन" else "Offline"
        }
    }

    fun bannerTitle(language: NoticeLanguage): String {
        return when (this) {
            ONLINE ->
                if (language == NoticeLanguage.HINDI) "इंटरनेट कनेक्शन सक्रिय है" else "Connected to Internet"
            CONNECTED_NO_INTERNET ->
                if (language == NoticeLanguage.HINDI) {
                    "नेटवर्क जुड़ा है, लेकिन इंटरनेट काम नहीं कर रहा है"
                } else {
                    "Network Connected — No Internet Access"
                }
            OFFLINE ->
                if (language == NoticeLanguage.HINDI) {
                    "कोई इंटरनेट कनेक्शन नहीं है (ऑफ़लाइन)"
                } else {
                    "You Are Currently Offline"
                }
        }
    }

    fun bannerMessage(language: NoticeLanguage): String {
        return when (this) {
            ONLINE ->
                if (language == NoticeLanguage.HINDI) {
                    "AI नोटिस सत्यापन और विश्लेषण के लिए तैयार।"
                } else {
                    "Ready for AI notice verification and analysis."
                }
            CONNECTED_NO_INTERNET ->
                if (language == NoticeLanguage.HINDI) {
                    "आपका डिवाइस वाई-फ़ाई या मोबाइल नेटवर्क से जुड़ा है, लेकिन इंटरनेट एक्सेस उपलब्ध नहीं है। AI नोटिस विश्लेषण ऑफ़लाइन नहीं चल सकता; आपके चुने गए दस्तावेज़ और सहेजे गए नोटिस सुरक्षित हैं।"
                } else {
                    "Your device is connected to a network, but internet access is unavailable. AI notice analysis requires working internet access. Your selected document and saved notices remain preserved."
                }
            OFFLINE ->
                if (language == NoticeLanguage.HINDI) {
                    "इंटरनेट उपलब्ध नहीं है। सहेजे गए नोटिस देखे जा सकते हैं और आपका चुना गया दस्तावेज़ सुरक्षित है। कनेक्शन वापस आने पर आप विश्लेषण पुनः प्रयास कर सकते हैं।"
                } else {
                    "Internet access is unavailable. Saved notices remain accessible and your selected document is preserved, but AI notice verification and analysis require an internet connection."
                }
        }
    }
}

/**
 * Abstraction for checking startup network connectivity and observing live connectivity changes.
 */
interface NetworkConnectivityMonitor {
    val status: StateFlow<NetworkConnectivityStatus>

    /**
     * Synchronously queries and updates the current connectivity state.
     */
    fun checkCurrentConnectivity(): NetworkConnectivityStatus

    /**
     * Registers live platform network callbacks if not already active.
     */
    fun startMonitoring()

    /**
     * Unregisters platform network callbacks when no longer needed.
     */
    fun stopMonitoring()
}

/**
 * Production Android implementation backed by [ConnectivityManager] and [ConnectivityManager.NetworkCallback].
 * Distinguishes between [NetworkConnectivityStatus.ONLINE] (`NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`),
 * [NetworkConnectivityStatus.CONNECTED_NO_INTERNET] (network transport connected without validated internet),
 * and [NetworkConnectivityStatus.OFFLINE].
 */
class AndroidNetworkConnectivityMonitor(
    context: Context
) : NetworkConnectivityMonitor {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _status = MutableStateFlow(evaluateActiveConnectivity())
    override val status: StateFlow<NetworkConnectivityStatus> = _status.asStateFlow()

    @Volatile
    private var isCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateStatusFromManager("onAvailable")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val evaluated = evaluateCapabilities(networkCapabilities)
            val previous = _status.value
            _status.value = evaluated
            if (previous != evaluated) {
                AppDiagnosticLogger.info(
                    "NETWORK",
                    "Connectivity capabilities changed: $previous -> $evaluated"
                )
            }
        }

        override fun onLost(network: Network) {
            updateStatusFromManager("onLost")
        }

        override fun onUnavailable() {
            val previous = _status.value
            _status.value = NetworkConnectivityStatus.OFFLINE
            if (previous != NetworkConnectivityStatus.OFFLINE) {
                AppDiagnosticLogger.info(
                    "NETWORK",
                    "Connectivity unavailable: $previous -> OFFLINE"
                )
            }
        }
    }

    override fun checkCurrentConnectivity(): NetworkConnectivityStatus {
        val current = evaluateActiveConnectivity()
        _status.value = current
        return current
    }

    override fun startMonitoring() {
        if (isCallbackRegistered) return
        val cm = connectivityManager ?: return
        checkCurrentConnectivity()
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
            isCallbackRegistered = true
        } catch (defaultErr: Exception) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback)
                isCallbackRegistered = true
            } catch (fallbackErr: Exception) {
                AppDiagnosticLogger.warn(
                    "NETWORK",
                    "Could not register ConnectivityManager callback: ${fallbackErr.message}"
                )
            }
        }
    }

    override fun stopMonitoring() {
        if (!isCallbackRegistered) return
        val cm = connectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Ignore if already unregistered
        } finally {
            isCallbackRegistered = false
        }
    }

    private fun updateStatusFromManager(trigger: String) {
        val evaluated = evaluateActiveConnectivity()
        val previous = _status.value
        _status.value = evaluated
        if (previous != evaluated) {
            AppDiagnosticLogger.info(
                "NETWORK",
                "Connectivity changed ($trigger): $previous -> $evaluated"
            )
        }
    }

    private fun evaluateActiveConnectivity(): NetworkConnectivityStatus {
        val cm = connectivityManager ?: return NetworkConnectivityStatus.OFFLINE
        return try {
            val activeNetwork = cm.activeNetwork ?: return NetworkConnectivityStatus.OFFLINE
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
                ?: return NetworkConnectivityStatus.OFFLINE
            evaluateCapabilities(capabilities)
        } catch (e: Exception) {
            AppDiagnosticLogger.warn("NETWORK", "Failed to read active network capabilities: ${e.message}")
            NetworkConnectivityStatus.OFFLINE
        }
    }

    companion object {
        fun evaluateCapabilities(capabilities: NetworkCapabilities?): NetworkConnectivityStatus {
            if (capabilities == null) return NetworkConnectivityStatus.OFFLINE

            val hasTransport =
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)

            val hasInternetCapability =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            return when {
                !hasTransport && !hasInternetCapability -> NetworkConnectivityStatus.OFFLINE
                hasInternetCapability && isValidated -> NetworkConnectivityStatus.ONLINE
                hasTransport || hasInternetCapability -> NetworkConnectivityStatus.CONNECTED_NO_INTERNET
                else -> NetworkConnectivityStatus.OFFLINE
            }
        }
    }
}

/**
 * Deterministic [NetworkConnectivityMonitor] implementation for unit/Robolectric tests and
 * default ViewModel construction when a platform [Context] is not provided.
 */
class ControllableNetworkConnectivityMonitor(
    initialStatus: NetworkConnectivityStatus = NetworkConnectivityStatus.ONLINE
) : NetworkConnectivityMonitor {

    private val _status = MutableStateFlow(initialStatus)
    override val status: StateFlow<NetworkConnectivityStatus> = _status.asStateFlow()

    var isMonitoringActive: Boolean = false
        private set

    fun setStatus(newStatus: NetworkConnectivityStatus) {
        _status.value = newStatus
    }

    override fun checkCurrentConnectivity(): NetworkConnectivityStatus {
        return _status.value
    }

    override fun startMonitoring() {
        isMonitoringActive = true
    }

    override fun stopMonitoring() {
        isMonitoringActive = false
    }
}

/**
 * Classifies upload, classification, and AI analysis failures into explicit, user-understandable
 * [NoticeRequestErrorCategory] buckets while preserving the underlying error detail.
 */
object NoticeErrorClassifier {

    fun buildNoInternetError(
        networkStatus: NetworkConnectivityStatus,
        stage: FailedPipelineStage,
        customDetail: String? = null
    ): NoticeRequestError {
        val cleanCustom = customDetail?.trim().orEmpty()
        val isConnectedWithoutInternet = networkStatus == NetworkConnectivityStatus.CONNECTED_NO_INTERNET

        val defaultEn = if (isConnectedWithoutInternet) {
            "Your device is connected to a network, but internet access is not working. AI notice verification and analysis require working internet access and cannot run offline."
        } else {
            "No internet connection is available. AI notice verification and analysis require an active internet connection and cannot continue offline."
        }

        val messageEn = when {
            cleanCustom.isNotBlank() && !cleanCustom.equals(defaultEn, ignoreCase = true) ->
                "$cleanCustom $defaultEn"
            else -> defaultEn
        }

        val messageHi = if (isConnectedWithoutInternet) {
            "आपका डिवाइस नेटवर्क से जुड़ा है, लेकिन इंटरनेट काम नहीं कर रहा है। AI नोटिस सत्यापन और विश्लेषण के लिए सक्रिय इंटरनेट आवश्यक है।"
        } else {
            "कोई इंटरनेट कनेक्शन उपलब्ध नहीं है। AI नोटिस सत्यापन और विश्लेषण ऑफ़लाइन नहीं चल सकता; इसके लिए सक्रिय इंटरनेट कनेक्शन आवश्यक है।"
        }

        val guidanceEn =
            "Your selected document and existing results are safely preserved. Reconnect to a working internet connection and tap Try Again."
        val guidanceHi =
            "आपका चुना गया दस्तावेज़ और मौजूदा परिणाम सुरक्षित हैं। इंटरनेट कनेक्शन बहाल होने पर 'पुनः प्रयास करें' (Try Again) दबाएं।"

        return NoticeRequestError(
            category = NoticeRequestErrorCategory.NO_INTERNET,
            stage = stage,
            messageEn = messageEn,
            messageHi = messageHi,
            guidanceEn = guidanceEn,
            guidanceHi = guidanceHi,
            canRetrySafely = true
        )
    }

    fun buildUploadFailureError(
        rawMessage: String?,
        throwable: Throwable? = null
    ): NoticeRequestError {
        val cleanDetail = rawMessage?.trim()?.takeIf { it.isNotBlank() }
            ?: throwable?.message?.trim()?.takeIf { it.isNotBlank() }
            ?: "Could not read or process the selected document file."

        val messageEn = if (cleanDetail.contains("upload", ignoreCase = true) ||
            cleanDetail.contains("file", ignoreCase = true) ||
            cleanDetail.contains("PDF", ignoreCase = true) ||
            cleanDetail.contains("image", ignoreCase = true)
        ) {
            cleanDetail
        } else {
            "Document upload or file read failed: $cleanDetail"
        }

        val messageHi =
            "चुने गए दस्तावेज़ को पढ़ने या प्रोसेस करने में समस्या हुई: $cleanDetail"
        val guidanceEn =
            "This is a local file read/upload failure (not a network or AI service error). Your document selection is preserved—you can tap Try Again or choose another file."
        val guidanceHi =
            "यह फ़ाइल पढ़ने/अपलोड करने की त्रुटि है (नेटवर्क या AI सेवा की समस्या नहीं)। आपका चुना गया दस्तावेज़ सुरक्षित है—आप पुनः प्रयास कर सकते हैं या दूसरी फ़ाइल चुन सकते हैं।"

        return NoticeRequestError(
            category = NoticeRequestErrorCategory.UPLOAD_FAILURE,
            stage = FailedPipelineStage.DOCUMENT_UPLOAD_OR_READ,
            messageEn = messageEn,
            messageHi = messageHi,
            guidanceEn = guidanceEn,
            guidanceHi = guidanceHi,
            canRetrySafely = true
        )
    }

    fun classifyPipelineFailure(
        stage: FailedPipelineStage,
        rawMessage: String?,
        throwable: Throwable? = null,
        networkStatus: NetworkConnectivityStatus = NetworkConnectivityStatus.ONLINE
    ): NoticeRequestError {
        if (stage == FailedPipelineStage.DOCUMENT_UPLOAD_OR_READ) {
            return buildUploadFailureError(rawMessage, throwable)
        }

        val cleanMsg = rawMessage?.trim().orEmpty()
        val lowerMsg = cleanMsg.lowercase()
        val rootCause = throwable?.cause ?: throwable

        val isTimeout =
            throwable is TimeoutCancellationException ||
                throwable is SocketTimeoutException ||
                rootCause is TimeoutCancellationException ||
                rootCause is SocketTimeoutException ||
                lowerMsg.contains("timed out") ||
                lowerMsg.contains("timeout")

        if (isTimeout) {
            val messageEn = cleanMsg.ifBlank {
                "The AI notice analysis request timed out while waiting for a response."
            }
            val messageHi =
                "AI नोटिस विश्लेषण सेवा से प्रतिक्रिया की प्रतीक्षा करते समय समय समाप्त (Timeout) हो गया। ($messageEn)"
            val guidanceEn =
                "The server took too long to respond. Your selected document and extracted content are preserved—tap Try Again to retry safely."
            val guidanceHi =
                "सर्वर से समय पर उत्तर नहीं मिला। आपका चुना गया दस्तावेज़ और प्रगति सुरक्षित हैं—सुरक्षित रूप से पुनः प्रयास करने के लिए 'Try Again' दबाएं।"
            return NoticeRequestError(
                category = NoticeRequestErrorCategory.REQUEST_TIMEOUT,
                stage = stage,
                messageEn = messageEn,
                messageHi = messageHi,
                guidanceEn = guidanceEn,
                guidanceHi = guidanceHi,
                canRetrySafely = true
            )
        }

        val isNetworkDisconnectException =
            throwable is UnknownHostException ||
                throwable is ConnectException ||
                throwable is NoRouteToHostException ||
                rootCause is UnknownHostException ||
                rootCause is ConnectException ||
                rootCause is NoRouteToHostException ||
                lowerMsg.contains("network connection lost") ||
                lowerMsg.contains("no internet") ||
                lowerMsg.contains("offline") ||
                lowerMsg.contains("unable to resolve host") ||
                lowerMsg.contains("network is unreachable") ||
                lowerMsg.contains("connection reset") ||
                lowerMsg.contains("network error while contacting")

        if (!networkStatus.hasInternetAccess || isNetworkDisconnectException) {
            return buildNoInternetError(
                networkStatus = if (!networkStatus.hasInternetAccess) {
                    networkStatus
                } else {
                    NetworkConnectivityStatus.OFFLINE
                },
                stage = stage,
                customDetail = cleanMsg.takeIf { it.isNotBlank() }
            )
        }

        // Internet is connected, so the failure is on the AI backend (5xx, 429, 401/403, or malformed/empty AI response)
        val messageEn = cleanMsg.ifBlank {
            "The AI notice analysis service is currently unavailable or returned an unexpected error."
        }
        val messageHi =
            "इंटरनेट कनेक्शन सक्रिय है, लेकिन AI नोटिस विश्लेषण सेवा वर्तमान में अनुपलब्ध है या त्रुटि लौटाई गई: $messageEn"
        val guidanceEn =
            "Your internet connection is working, but the AI backend service could not complete the request. Your selected document and existing results are preserved—tap Try Again in a moment."
        val guidanceHi =
            "आपका इंटरनेट कनेक्शन काम कर रहा है, लेकिन AI बैकएंड सेवा अनुरोध पूरा नहीं कर सकी। आपका दस्तावेज़ और मौजूदा परिणाम सुरक्षित हैं—कुछ देर बाद पुनः प्रयास करें।"

        return NoticeRequestError(
            category = NoticeRequestErrorCategory.AI_SERVICE_UNAVAILABLE,
            stage = stage,
            messageEn = messageEn,
            messageHi = messageHi,
            guidanceEn = guidanceEn,
            guidanceHi = guidanceHi,
            canRetrySafely = true
        )
    }
}
