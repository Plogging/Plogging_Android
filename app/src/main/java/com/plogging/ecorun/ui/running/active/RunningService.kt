package com.plogging.ecorun.ui.running.active

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.location.Location
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import com.plogging.ecorun.data.local.SharedPreference
import com.plogging.ecorun.data.model.NotificationArgs
import com.plogging.ecorun.ui.running.active.RunningNotification.generateNotification
import com.plogging.ecorun.ui.running.active.RunningNotification.init
import com.plogging.ecorun.util.extension.toSplitTime
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit

class RunningService : Service() {
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    val runningState = BehaviorSubject.createDefault(RunningViewModel.RunningState.INITIAL)
    private var fusedLocationClient: FusedLocationProviderClient? = null
    val runningTime = BehaviorSubject.createDefault(0)
    lateinit var compositeDisposable: CompositeDisposable
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest
    var serviceRunningInForeground = false
    private val localBinder = LocalBinder()
    private var configurationChange = false
    val locationList = arrayListOf<Location>()
    var trashCountList = IntArray(6)
    var distance = 0f

    override fun onCreate() {
        init(notificationManager)
        initLocationCallback()
        initLocationObject()
        startTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder {
        // fragment?????? foreground??? service??? ??????????????? foreground?????? background??? ?????????.
        stopForeground(true)
        serviceRunningInForeground = false
        configurationChange = false
        return localBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (!configurationChange && SharedPreference.getLocationTrackingPref(this)) {
            val args = NotificationArgs(
                locationList,
                runningTime.value,
                runningState.value,
                trashCountList,
                distance
            )
            val notification = generateNotification(this, args, runningTime.value!!.toSplitTime())
            startForeground(NOTIFICATION_ID, notification)
            serviceRunningInForeground = true
        }
        return true
    }

    override fun onRebind(intent: Intent?) {
        stopForeground(true)
        serviceRunningInForeground = false
        configurationChange = false
        super.onRebind(intent)
    }

    override fun bindService(service: Intent?, conn: ServiceConnection, flags: Int): Boolean =
        super.bindService(service, conn, flags)


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configurationChange = true
    }

    override fun onDestroy() {
        stopForeground(true)
        super.onDestroy()
    }

    private fun initLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationList.add(locationResult.lastLocation)
            }
        }
    }

    private fun initLocationObject() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 1f //  in meters
            fastestInterval = 1000
            interval = 1000 // time
        }
    }

    fun startTimer() {
        compositeDisposable = CompositeDisposable()
        Observable.interval(1, TimeUnit.SECONDS)
            .map(Long::toInt)
            .filter { runningState.value == RunningViewModel.RunningState.ACTIVE }
            .observeOn(Schedulers.computation())
            .subscribe({
                runningTime.onNext(runningTime.value?.plus(1)!!)
                val args = NotificationArgs(
                    locationList,
                    runningTime.value,
                    runningState.value,
                    trashCountList,
                    distance
                )
                // foreground??? ????????? ?????? ????????? noti??? ?????? ??????????????????.
                if (serviceRunningInForeground) {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        generateNotification(
                            this@RunningService,
                            args,
                            runningTime.value!!.toSplitTime()
                        )
                    )
                }
                // ????????? ????????? ????????? ??????, ?????? ?????? ?????? intent??? ?????? ????????????.
                val intent = Intent(ACTION_FOREGROUND_ONLY_LOCATION_BROADCAST)
                intent.putExtra(EXTRA_BACKGROUND_DATA, args)
                LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
            }, {})
            .addTo(compositeDisposable)
    }

    // ?????? ?????????????????? service??? ????????????.
    fun subscribeToLocationUpdates() {
        SharedPreference.saveLocationTrackingPref(this, true)
        startService(Intent(applicationContext, RunningService::class.java))
        // permission ?????? ????????? ?????? ??????
        try {
            // location??? ?????? ???????????? ????????? ??????
            fusedLocationClient?.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
        } catch (unlikely: SecurityException) {
            SharedPreference.saveLocationTrackingPref(this, false)
        }
    }

    // ?????? ?????????????????? service??? ????????????.
    fun unsubscribeToLocationUpdates() {
        try {
            val removeTask = fusedLocationClient?.removeLocationUpdates(locationCallback)
            removeTask?.addOnCompleteListener { task -> if (task.isSuccessful) stopSelf() }
            SharedPreference.saveLocationTrackingPref(this, false)
            stopForeground(true)
            compositeDisposable.dispose()
        } catch (unlikely: SecurityException) {
            SharedPreference.saveLocationTrackingPref(this, true)
        }
    }

    // ??????????????? binder??? ?????? ?????????. ?????? ?????????????????? ?????? ?????? ????????? IPC??? ???????????? ????????? ??????.
    inner class LocalBinder : Binder() {
        internal val service: RunningService
            get() = this@RunningService
    }

    companion object {
        private const val NOTIFICATION_ID = 12345678
        private const val PACKAGE_NAME = "com.plogging.ecorun"
        internal const val ACTION_FOREGROUND_ONLY_LOCATION_BROADCAST =
            "$PACKAGE_NAME.action.FOREGROUND_ONLY_LOCATION_BROADCAST"
        internal const val EXTRA_BACKGROUND_DATA = "$PACKAGE_NAME.extra.LOCATION"
    }
}