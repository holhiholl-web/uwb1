package com.icar.uwbradar

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.uwb.*
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

data class UwbLiveState(
    val status: String = "하단 버튼으로 폰A(호스트) / 폰B(게스트)를 시작하세요",
    val isRanging: Boolean = false,
    val distanceMeters: Float = 0f,
    val azimuthDegrees: Float = 0f,
    val elevationDegrees: Float = 0f
)

class MainActivity : ComponentActivity() {

    private lateinit var uwbManager: UwbManager
    private lateinit var nearbyClient: ConnectionsClient
    private var uwbJob: Job? = null

    private val SERVICE_ID = "com.icar.pure.uwb"
    private val SESSION_ID = 9001
    private val SESSION_KEY = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)

    private var uiState by mutableStateOf(UwbLiveState())
    private var isController = true
    private var sessionScope: UwbClientSessionScope? = null
    private var controllerScope: UwbControllerSessionScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uwbManager = UwbManager.createInstance(this)
        nearbyClient = Nearby.getConnectionsClient(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {}

                LaunchedEffect(Unit) {
                    val perms = mutableListOf(
                        Manifest.permission.UWB_RANGING,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        perms.addAll(
                            listOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_ADVERTISE,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                    permLauncher.launch(perms.toTypedArray())
                }

                UwbRadarScreen(
                    state = uiState,
                    onHostClick = { initUwbRole(true) },
                    onGuestClick = { initUwbRole(false) },
                    onStopClick = { stopUwb() }
                )
            }
        }
    }

    private fun initUwbRole(asController: Boolean) {
        stopUwb()
        isController = asController

        lifecycleScope.launch {
            try {
                if (asController) {
                    uiState = UwbLiveState(status = "UWB Controller 칩 초기화 완료. 상대 폰 찾는 중...")
                    controllerScope = uwbManager.controllerSessionScope()
                    sessionScope = controllerScope
                    val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
                    nearbyClient.startDiscovery(SERVICE_ID, discoveryCallback, options)
                } else {
                    uiState = UwbLiveState(status = "UWB Controlee 칩 초기화 완료. 호스트 대기 중...")
                    sessionScope = uwbManager.controleeSessionScope()
                    val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
                    nearbyClient.startAdvertising("UWB_Guest", SERVICE_ID, connectionCallback, options)
                }
            } catch (e: Exception) {
                uiState = UwbLiveState(status = "UWB 하드웨어 오류: 설정에서 초광대역(UWB)이 켜져 있는지 확인하세요.")
            }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            uiState = uiState.copy(status = "상대 갤럭시 발견! UWB 채널/주소 교환 중...")
            nearbyClient.requestConnection("UWB_Host", endpointId, connectionCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            nearbyClient.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                nearbyClient.stopAdvertising()
                nearbyClient.stopDiscovery()
                exchangeUwbAddress(endpointId)
            }
        }
        override fun onDisconnected(endpointId: String) {}
    }

    private fun exchangeUwbAddress(endpointId: String) {
        val localAddr = sessionScope?.localAddress?.address ?: return
        if (isController) {
            val ch = controllerScope?.uwbComplexChannel ?: return
            val buf = ByteBuffer.allocate(1 + localAddr.size + 8)
            buf.put(localAddr.size.toByte())
            buf.put(localAddr)
            buf.putInt(ch.channel)
            buf.putInt(ch.preambleIndex)
            nearbyClient.sendPayload(endpointId, Payload.fromBytes(buf.array()))
        } else {
            val buf = ByteBuffer.allocate(1 + localAddr.size)
            buf.put(localAddr.size.toByte())
            buf.put(localAddr)
            nearbyClient.sendPayload(endpointId, Payload.fromBytes(buf.array()))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val buf = ByteBuffer.wrap(bytes)
            val len = buf.get().toInt()
            val peerAddr = ByteArray(len)
            buf.get(peerAddr)

            if (isController) {
                val ch = controllerScope?.uwbComplexChannel ?: return
                startPureUwbSession(peerAddr, ch.channel, ch.preambleIndex)
            } else {
                val channel = buf.int
                val preamble = buf.int
                startPureUwbSession(peerAddr, channel, preamble)
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun startPureUwbSession(peerAddrBytes: ByteArray, channel: Int, preamble: Int) {
        uwbJob?.cancel()
        uwbJob = lifecycleScope.launch {
            val scope = sessionScope ?: return@launch
            val params = RangingParameters(
                uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                sessionId = SESSION_ID,
                subSessionId = 0,
                sessionKeyInfo = SESSION_KEY,
                subSessionKeyInfo = null,
                complexChannel = UwbComplexChannel(channel, preamble),
                peerDevices = listOf(UwbDevice(UwbAddress(peerAddrBytes))),
                updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
            )

            uiState = uiState.copy(status = "순수 UWB 칩 Ranging 세션 시작됨!")

            scope.prepareSession(params).collectLatest { result ->
                when (result) {
                    is RangingResult.RangingResultPosition -> {
                        val pos = result.position
                        uiState = uiState.copy(
                            isRanging = true,
                            status = "UWB 하드웨어 실시간 추적 중 (ToF + AoA)",
                            distanceMeters = pos.distance?.value ?: uiState.distanceMeters,
                            azimuthDegrees = pos.azimuth?.value ?: uiState.azimuthDegrees,
                            elevationDegrees = pos.elevation?.value ?: uiState.elevationDegrees
                        )
                    }
                    is RangingResult.RangingResultPeerDisconnected -> {
                        uiState = uiState.copy(isRanging = false, status = "UWB 신호 끊김")
                    }
                }
            }
        }
    }

    private fun stopUwb() {
        uwbJob?.cancel()
        nearbyClient.stopAdvertising()
        nearbyClient.stopDiscovery()
        nearbyClient.stopAllEndpoints()
        uiState = UwbLiveState(status = "정지됨")
    }
}

@Composable
fun UwbRadarScreen(
    state: UwbLiveState,
    onHostClick: () -> Unit,
    onGuestClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080B10))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ICAR PURE UWB RADAR", color = Color(0xFF38BDF8), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(state.status, color = if (state.isRanging) Color(0xFF4ADE80) else Color(0xFF94A3B8), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF111827), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                val origin = Offset(size.width / 2f, size.height * 0.82f)
                val max
