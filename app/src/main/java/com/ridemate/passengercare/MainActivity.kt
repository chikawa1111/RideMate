package com.ridemate.passengercare

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridemate.passengercare.care.CabinPlanner
import com.ridemate.passengercare.ui.ArrivalScreen
import com.ridemate.passengercare.ui.BoardingScreen
import com.ridemate.passengercare.ui.DrivingScreen
import com.ridemate.passengercare.ui.PreDepartureScreen
import com.ridemate.passengercare.ui.HomeScreen
import com.ridemate.passengercare.ui.HospitalPickScreen
import com.ridemate.passengercare.ui.RegisterScreen
import com.ridemate.passengercare.ui.SeatMonitorScreen
import com.ridemate.passengercare.ui.theme.RideMateTheme

/** 앱 화면 목록. 화면 이동이 단순해서 별도 내비게이션 라이브러리 없이 상태 하나로 관리한다. */
sealed interface Screen {
    data object Home : Screen
    data class Register(val passengerId: String?) : Screen
    data object Boarding : Screen
    data object PreDeparture : Screen
    data object Driving : Screen
    data object Arrival : Screen
    data object SeatMonitor : Screen
    data object HospitalPick : Screen
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** 기기의 음성 인식 화면 결과 */
    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            viewModel.onVoiceResult(if (res.resultCode == RESULT_OK) list else null)
        }

    private fun startVoice() {
        if (!viewModel.onMicTap()) return
        runCatching { speechLauncher.launch(viewModel.voiceIntent()) }
            .onFailure { viewModel.onVoiceResult(null) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 위치 권한은 요청하지 않는다(허용하면 주행 중 앱이 차단됨, Manifest 주석 참고). 위치는 Pleos 내비에서 받는다.
        setContent {
            val seats by viewModel.seats.collectAsStateWithLifecycle()
            val log by viewModel.log.collectAsStateWithLifecycle()
            val passengers by viewModel.passengers.collectAsStateWithLifecycle()
            val assignments by viewModel.assignments.collectAsStateWithLifecycle()
            val invertOccupancy by viewModel.invertOccupancy.collectAsStateWithLifecycle()
            val manualChecked by viewModel.manualChecked.collectAsStateWithLifecycle()
            val actions by viewModel.actions.collectAsStateWithLifecycle()
            val drive by viewModel.drive.collectAsStateWithLifecycle()
            val driveSimulated by viewModel.driveSimulated.collectAsStateWithLifecycle()
            val beltSimulated by viewModel.beltSimulated.collectAsStateWithLifecycle()
            val beltAlerts by viewModel.beltAlerts.collectAsStateWithLifecycle()
            val exitAlert by viewModel.exitAlert.collectAsStateWithLifecycle()
            val voice by viewModel.voice.collectAsStateWithLifecycle()
            val scene by viewModel.scene.collectAsStateWithLifecycle()
            val nextStop by viewModel.nextStop.collectAsStateWithLifecycle()
            val demoPositionLabel by viewModel.demoPositionLabel.collectAsStateWithLifecycle()
            val env by viewModel.env.collectAsStateWithLifecycle()
            val envChecked by viewModel.envChecked.collectAsStateWithLifecycle()
            val roadSimulated by viewModel.roadSimulated.collectAsStateWithLifecycle()
            val weatherSimLabel by viewModel.weatherSimLabel.collectAsStateWithLifecycle()
            val restDue by viewModel.restDue.collectAsStateWithLifecycle()
            val sleptThisTrip by viewModel.sleptThisTrip.collectAsStateWithLifecycle()
            val comfortAhead = env.ahead.isNotEmpty() && assignments.values.any {
                it is com.ridemate.passengercare.data.SeatAssignment.Known && it.passenger.comfortRide
            }
            val confirmedOut by viewModel.confirmedOut.collectAsStateWithLifecycle()
            val belongingsChecked by viewModel.belongingsChecked.collectAsStateWithLifecycle()
            var screen by remember { mutableStateOf<Screen>(Screen.Home) }
            // 내비가 목적지 도착을 알리면 하차 확인으로 바로 넘어간다
            androidx.compose.runtime.LaunchedEffect(Unit) {
                viewModel.autoArrive.collect { screen = Screen.Arrival }
            }
            val naviDestination by viewModel.naviDestination.collectAsStateWithLifecycle()
            val naviConnected by viewModel.naviConnected.collectAsStateWithLifecycle()
            val hvacReady by viewModel.hvacReady.collectAsStateWithLifecycle()
            val hospital by viewModel.hospital.collectAsStateWithLifecycle()
            val naviBookmarks by viewModel.naviBookmarks.collectAsStateWithLifecycle()
            val naviRecents by viewModel.naviRecents.collectAsStateWithLifecycle()
            val pregnantAboard by viewModel.pregnantAboard.collectAsStateWithLifecycle()
            val goHome = { screen = Screen.Home }

            // 운행 중에는 뒤로 가기로 경보 화면을 벗어나지 않는다. 운행 종료 버튼으로만 나간다.
            BackHandler(enabled = screen != Screen.Home) {
                when (screen) {
                    Screen.Driving -> Unit
                    Screen.Arrival -> { viewModel.cancelArrive(); screen = Screen.Driving }
                    else -> goHome()
                }
            }

            RideMateTheme {
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        passengers = passengers,
                        seatSensorConnected = seats.values.any { it.occupied != null },
                        hvacReady = hvacReady,
                        naviConnected = naviConnected,
                        hospital = hospital,
                        onPickHospital = { viewModel.refreshPlaces(); screen = Screen.HospitalPick },
                        onStartBoarding = { viewModel.resetBoarding(); screen = Screen.Boarding },
                        onAddPassenger = { screen = Screen.Register(null) },
                        onEditPassenger = { screen = Screen.Register(it.id) },
                        onOpenSeatMonitor = { screen = Screen.SeatMonitor }
                    )

                    is Screen.Register -> RegisterScreen(
                        existing = passengers.firstOrNull { it.id == s.passengerId },
                        newId = viewModel::newPassengerId,
                        onSave = { viewModel.savePassenger(it); goHome() },
                        onDelete = { viewModel.deletePassenger(it); goHome() },
                        onBack = goHome
                    )

                    Screen.Boarding -> BoardingScreen(
                        seats = seats,
                        passengers = passengers,
                        assignments = assignments,
                        onConfirm = viewModel::confirmSeat,
                        onClear = viewModel::clearSeat,
                        onDone = { viewModel.startPreDeparture(); screen = Screen.PreDeparture },
                        onBack = goHome
                    )

                    Screen.PreDeparture -> PreDepartureScreen(
                        checklist = CabinPlanner.checklist(assignments),
                        manualChecked = manualChecked,
                        seats = seats,
                        actions = actions,
                        onToggleManual = viewModel::toggleManual,
                        onSimulateBelt = viewModel::toggleSimulatedBelt,
                        onDepart = { viewModel.startTrip(); screen = Screen.Driving },
                        onBack = { screen = Screen.Boarding }
                    )

                    Screen.HospitalPick -> HospitalPickScreen(
                        current = hospital,
                        bookmarks = naviBookmarks,
                        recents = naviRecents,
                        naviConnected = naviConnected,
                        onRefresh = viewModel::refreshPlaces,
                        onPick = { viewModel.setHospital(it); goHome() },
                        onBack = goHome
                    )

                    Screen.Driving -> DrivingScreen(
                        drive = drive,
                        driveSimulated = driveSimulated,
                        seats = seats,
                        assignments = assignments,
                        alerts = beltAlerts,
                        beltSimulated = beltSimulated,
                        actions = actions,
                        exitAlert = exitAlert,
                        voice = voice,
                        scene = scene,
                        onMicTap = ::startVoice,
                        onVoiceCommand = viewModel::runCommand,
                        onDismissVoice = viewModel::dismissVoice,
                        nextStop = nextStop,
                        demoPositionLabel = demoPositionLabel,
                        onChooseRest = viewModel::chooseRest,
                        onCycleDemoPosition = viewModel::cycleDemoPosition,
                        env = env,
                        onCycleWeather = viewModel::cycleWeatherSim,
                        roadSimulated = roadSimulated,
                        onToggleRoad = viewModel::toggleRoadSim,
                        weatherSimLabel = weatherSimLabel,
                        restDue = restDue,
                        comfortAhead = comfortAhead,
                        onMarkRested = viewModel::markRested,
                        onAdvanceClock = viewModel::advanceClock,
                        naviDestination = naviDestination,
                        naviConnected = naviConnected,
                        hospitalName = hospital?.name,
                        pregnantAboard = pregnantAboard,
                        onUndoHospital = viewModel::undoHospital,
                        onSimulateDrive = viewModel::simulateDrive,
                        onSimulateBelt = viewModel::toggleSimulatedBelt,
                        onArrive = { viewModel.arrive(); screen = Screen.Arrival }
                    )

                    Screen.Arrival -> ArrivalScreen(
                        assignments = assignments,
                        seats = seats,
                        confirmedOut = confirmedOut,
                        belongingsChecked = belongingsChecked,
                        alert = exitAlert,
                        onToggleOut = viewModel::toggleConfirmedOut,
                        onToggleBelongings = viewModel::toggleBelongings,
                        env = env,
                        envChecked = envChecked,
                        onToggleEnvItem = viewModel::toggleEnvItem,
                        sleptThisTrip = sleptThisTrip,
                        onFinish = { viewModel.endTrip(); goHome() },
                        onBack = { viewModel.cancelArrive(); screen = Screen.Driving }
                    )

                    Screen.SeatMonitor -> SeatMonitorScreen(
                        seats = seats,
                        log = log,
                        invertOccupancy = invertOccupancy,
                        onToggleInvert = viewModel::setInvertOccupancy,
                        onRefresh = viewModel::refreshSeats,
                        onSimulateBelt = viewModel::simulateBelt,
                        onBack = goHome
                    )
                }
            }
        }
    }
}
