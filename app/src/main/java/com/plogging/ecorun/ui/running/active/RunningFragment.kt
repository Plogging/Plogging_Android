package com.plogging.ecorun.ui.running.active

import android.Manifest
import android.content.*
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import androidx.activity.addCallback
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.coroutineScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.maps.android.SphericalUtil
import com.google.maps.android.ktx.addMarker
import com.google.maps.android.ktx.addPolyline
import com.google.maps.android.ktx.awaitMap
import com.plogging.ecorun.R
import com.plogging.ecorun.base.BaseFragment
import com.plogging.ecorun.data.local.SharedPreference
import com.plogging.ecorun.data.model.NotificationArgs
import com.plogging.ecorun.databinding.FragmentRunningBinding
import com.plogging.ecorun.ui.main.MainViewModel
import com.plogging.ecorun.util.extension.*
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.addTo

@AndroidEntryPoint
class RunningFragment : BaseFragment<FragmentRunningBinding, RunningViewModel>() {
    override fun getViewBinding() = FragmentRunningBinding.inflate(layoutInflater)
    private val runningBroadcastReceiver by lazy { RunningBroadcastReceiver() }
    private lateinit var runningServiceConnection: ServiceConnection
    override val viewModel: RunningViewModel by viewModels()
    private val latLngList: MutableList<LatLng> = mutableListOf()
    private var runningService: RunningService? = null
    private var isTrashTransitionVisible = false
    private var runningLocationServiceBound = false
    private var currentMarker: Marker? = null
    private lateinit var map: GoogleMap
    private var startMarker: Marker? = null
    private var trashCountList = IntArray(6)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        manageRunningState()
        initServiceConnection()
        initDataFromNotification()
        initView()
        bottomViewDown()
        getDistance()
    }

    // 바인드된 서비스는 화면이 보여지면 실행된다. 그리고 서비스에서는 foregroundService를 비활성화 한다.
    override fun onStart() {
        super.onStart()
        runningService?.stopForeground(true)
        runningService?.serviceRunningInForeground = false
        val serviceIntent = Intent(requireContext(), RunningService()::class.java)
        requireActivity().bindService(
            serviceIntent,
            runningServiceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    // 뒤로가기를 이곳에 구현하여 기본 activity back press를 커스텀한다.
    override fun onResume() {
        super.onResume()
        backPress()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            runningBroadcastReceiver,
            IntentFilter(RunningService.ACTION_FOREGROUND_ONLY_LOCATION_BROADCAST)
        )

        if (!allGranted()) requireContext().toast(getString(R.string.deny_permission))
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(
            runningBroadcastReceiver
        )
        super.onPause()
    }

    // 바인드된 서비스를 해제시키고 notification을 띄운다.
    override fun onStop() {
        runningService?.trashCountList = trashCountList
        runningService?.distance = viewModel.distanceMeter.value!!
        if (runningLocationServiceBound) {
            requireActivity().unbindService(runningServiceConnection)
            runningLocationServiceBound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        runningService?.unsubscribeToLocationUpdates() // service 해제
        super.onDestroy()
    }

    private fun bottomViewDown() {
        parentFragment?.parentFragment?.let {
            ViewModelProvider(it).get(MainViewModel::class.java).showBottomNav.value = false
        }
    }

    private fun initView() {
        val mapFragment =
            childFragmentManager.findFragmentById(R.id.map_running) as SupportMapFragment

        // google 맵
        lifecycle.coroutineScope.launchWhenCreated {
            map = mapFragment.awaitMap()
            val latitude = SharedPreference.getLatitude(requireContext()).toDouble()
            val longitude = SharedPreference.getLongitude(requireContext()).toDouble()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), 17f))
        }
    }

    private fun initServiceConnection() {
        runningServiceConnection = object : ServiceConnection { // service와 연결되었는지 상태 확인
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val binder = service as RunningService.LocalBinder
                runningService = binder.service
                runningLocationServiceBound = true
                runningService?.subscribeToLocationUpdates()
                // 노티를 클릭하면 아래의 코드 실행
                // 노티를 클릭하고 앱 밖으로 나가 어플 아이콘이나 실행하고 있는 어플리케이션 클릭시 이 때 argument는 null이 아니다
                // startMarker가 초기화가 아직 되지 않은 것으로 노티를 클릭해 처음부터 실행된다는 것을 판단한다.
                if (arguments != null && startMarker == null) {
                    val args = arguments?.get("backgroundData") as NotificationArgs
                    viewModel.runningState.onNext(args.runningState!!)
                    runningService?.compositeDisposable?.dispose()
                    runningService?.runningTime?.onNext(args.time!!)
                    runningService?.startTimer()
                    viewModel.distanceMeter.onNext(args.distance)
                    trashCountList = args.trashList
                    setTrashArrayView()
                    if (runningService?.locationList?.isEmpty() == true)
                        args.locationList?.map { runningService?.locationList?.add(it) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                runningService = null
                runningLocationServiceBound = false
            }
        }
    }

    private fun initDataFromNotification() {
        if (arguments == null) {
            getTimerNumber(0)
            readyTimer()
        } else {
            val args = arguments?.get("backgroundData") as NotificationArgs
            viewModel.runningState.onNext(RunningViewModel.RunningState.START)
            getTimerNumber(args.time!!)
        }
    }

    private fun locationToScreen(location: ArrayList<Location>) {
        if (viewModel.runningState.value == RunningViewModel.RunningState.PAUSE) return
        val currentLatLng = LatLng(location.last().latitude, location.last().longitude)
        if (latLngList.isNotEmpty()) { // 거리 구하기
            val lastLatLng = latLngList.last()
            latLngList.clear()
            viewModel.lastDistance.onNext(
                SphericalUtil.computeDistanceBetween(lastLatLng, currentLatLng).toFloat()
            )
        }
        location.map { latLngList.add(LatLng(it.latitude, it.longitude)) }
        //현재 위치 마커 표시
        startMarker = startMarker ?: map.addMarker {
            icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_start_point))
            position(currentLatLng)
        }
        if (currentMarker != null) currentMarker?.remove()
        currentMarker = map.addMarker {
            icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_current_point))
            position(currentLatLng)
        }
        // 지도 선 그리기
        map.addPolyline {
            color(Color.parseColor("#ff8090"))
            width(20f)
            addAll(latLngList)
        }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 17f))
    }

    private fun getDistance() {
        viewModel.getDistance()
        viewModel.distanceMeter
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                binding.tvRunningCalorieNum.text = it.meterToCalorie().toString()
                binding.tvRunningDistanceNum.text = it.meterToKilometer()
            }, {})
            .addTo(disposables)
    }

    private fun getTimerNumber(time: Int) {
        binding.tvRunningTimeNum.text = time.toSplitTime()
    }

    // 달리기 상태에 따라 뷰가 바뀜
    private fun manageRunningState() {
        viewModel.runningState.subscribe({ status ->
            runningService?.runningState?.onNext(status)
            when (status) {
                RunningViewModel.RunningState.START -> {
                    activity?.startService(Intent(requireContext(), RunningService::class.java))
                    binding.mlRunning.setTransition(
                        R.id.start_count_disappear,
                        R.id.end_count_disappear
                    )
                    binding.mlRunning.transitionToEnd()
                    viewModel.runningState.onNext(RunningViewModel.RunningState.ACTIVE)
                }
                RunningViewModel.RunningState.ACTIVE -> binding.ivRunningPlay.isSelected = false
                RunningViewModel.RunningState.PAUSE -> binding.ivRunningPlay.isSelected = true
                RunningViewModel.RunningState.STOP -> {
                    activity?.stopService(Intent(requireContext(), RunningService::class.java))
                    binding.btnRunningFinish.visibility = VISIBLE
                    binding.ivRunningPlay.visibility = INVISIBLE
                    binding.ivRunningStop.visibility = INVISIBLE
                }
                RunningViewModel.RunningState.INITIAL -> {
                }
            }
        }, {})
            .addTo(disposables)
    }

    // 달리기 시작 전 준비 카운터, motion layout의 에니메이션 적용
    private fun readyTimer() {
        viewModel.readyTimer()
        viewModel.readySeconds.observeOn(AndroidSchedulers.mainThread()).subscribe { second ->
            binding.tvRunningReadyCount.text = second.toString()
            binding.clRunningReady.progress = 0f
            when (second) {
                0 -> binding.tvRunningReadyCount.text = "1"
                1 -> {
                    binding.tvRunningReadyCount.setTextColor(Color.parseColor("#ff697a"))
                    binding.clRunningReady.setTransitionDuration(1200)
                    binding.clRunningReady.transitionToEnd()
                }
                2 -> {
                    binding.tvRunningReadyCount.setTextColor(Color.parseColor("#ffbf00"))
                    binding.clRunningReady.transitionToEnd()
                }
                3 -> {
                    binding.tvRunningReadyCount.setTextColor(Color.parseColor("#37d5ab"))
                    binding.clRunningReady.transitionToEnd()
                }
            }
        }.addTo(disposables)
    }

    override fun clickListener() {
        var bundle = bundleOf()
        binding.ivRunningStop.setOnClickListener {
            bundle = bundleOf(
                getString(R.string.distance) to viewModel.distanceMeter.value,
                getString(R.string.running_time) to runningService?.runningTime?.value,
            )
            runningService?.unsubscribeToLocationUpdates()
            viewModel.runningState.onNext(RunningViewModel.RunningState.STOP)
        }
        binding.ivRunningPlay.setOnClickListener {
            if (viewModel.runningState.value == RunningViewModel.RunningState.ACTIVE)
                viewModel.runningState.onNext(RunningViewModel.RunningState.PAUSE)
            else viewModel.runningState.onNext(RunningViewModel.RunningState.ACTIVE)
        }
        binding.btnRunningSaveTrash.setOnClickListener {
            isTrashTransitionVisible = true
            binding.mlRunning.setTransition(
                R.id.start_show_trash_dialog,
                R.id.end_show_trash_dialog
            )
            binding.mlRunning.transitionToEnd()
        }
        binding.btnTrashSave.setOnClickListener {
            saveTrashCountArray()
        }
        binding.btnRunningFinish.setOnClickListener {
            bundle.putIntArray(getString(R.string.trash_type), trashCountList)
            findNavController().navigate(R.id.action_plogging_running_to_running_finish, bundle)
        }
    }

    private fun setTrashArrayView() {
        binding.tbvTrashVinyl.setTrashCount(trashCountList[0])
        binding.tbvTrashGlass.setTrashCount(trashCountList[1])
        binding.tbvTrashPaper.setTrashCount(trashCountList[2])
        binding.tbvTrashPlastic.setTrashCount(trashCountList[3])
        binding.tbvTrashCan.setTrashCount(trashCountList[4])
        binding.tbvTrashExt.setTrashCount(trashCountList[5])
        binding.tvRunningTrashCount.text = trashCountList.sum().toString()
    }

    private fun saveTrashCountArray() {
        trashCountList[0] = binding.tbvTrashVinyl.getTrashCount()
        trashCountList[1] = binding.tbvTrashGlass.getTrashCount()
        trashCountList[2] = binding.tbvTrashPaper.getTrashCount()
        trashCountList[3] = binding.tbvTrashPlastic.getTrashCount()
        trashCountList[4] = binding.tbvTrashCan.getTrashCount()
        trashCountList[5] = binding.tbvTrashExt.getTrashCount()
        binding.tvRunningTrashCount.text = trashCountList.sum().toString()
        isTrashTransitionVisible = false
        binding.mlRunning.transitionToStart()
    }

    private fun allGranted() =
        requireContext().isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                requireContext().isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    //뒤로가기 클릭 시
    private fun backPress() {
        activity?.onBackPressedDispatcher?.addCallback(this) {
            if (isTrashTransitionVisible) saveTrashCountArray()
            else {
                val bundle = bundleOf("stop" to "stop")
                findNavController().navigate(R.id.action_plogging_running_to_running_finish, bundle)
            }
        }
    }

    private inner class RunningBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val backgroundData = intent.getParcelableExtra<NotificationArgs>(
                RunningService.EXTRA_BACKGROUND_DATA
            )
            val locationList = backgroundData?.locationList
            getTimerNumber(backgroundData?.time!!)
            // DB에 저장
            if (locationList?.isNotEmpty()!!) {
                locationToScreen(locationList)
                SharedPreference.setLatitude(
                    requireContext(),
                    locationList.last().latitude.toFloat()
                )
                SharedPreference.setLongitude(
                    requireContext(),
                    locationList.last().longitude.toFloat()
                )
            }
        }
    }
}