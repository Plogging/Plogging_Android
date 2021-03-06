package com.plogging.ecorun.util.extension

import android.content.Context
import android.content.IntentSender
import android.location.LocationManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task

class GpsExtension(private val context: Context, private val activity: FragmentActivity) {
    val LOCATION_SETTINGS_REQUEST = 200
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var isGPSOn = MutableLiveData(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))

    fun checkGPS() {
        val locationRequest = LocationRequest.create().apply {
            interval = 100000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        builder.setAlwaysShow(true)
        val client: SettingsClient = LocationServices.getSettingsClient(context)
        val task: Task<LocationSettingsResponse> = client.checkLocationSettings(builder.build())
        task.addOnCompleteListener {
            try {
                task.getResult(ApiException::class.java)
            } catch (ex: ApiException) {
                isGPSOn.value = false
                when (ex.statusCode) {
                    LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                        try {
                            val resolvableApiException = ex as ResolvableApiException
                            resolvableApiException
                                .startResolutionForResult(activity, LOCATION_SETTINGS_REQUEST)
                        } catch (e: IntentSender.SendIntentException) {
                        }
                    }
                    LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
                    }
                }
            }
        }.addOnSuccessListener { isGPSOn.value = true }
    }
}

