package com.plogging.ecorun.ui.auth.password

import androidx.lifecycle.MutableLiveData
import com.plogging.ecorun.base.BaseViewModel
import com.plogging.ecorun.data.repository.auth.AuthRepository
import com.plogging.ecorun.data.response.BaseResponse
import com.plogging.ecorun.util.observer.DefaultSingleObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import retrofit2.HttpException
import javax.inject.Inject

@HiltViewModel
class FindPasswordViewModel @Inject constructor(private val repository: AuthRepository) :
    BaseViewModel() {
    val email = MutableLiveData<String>()
    val responseCode = MutableLiveData(0)

    fun tempPassword() {
        repository.tempPassword(email.value!!)
            .subscribe(object : DefaultSingleObserver<BaseResponse>() {
                override fun onSuccess(response: BaseResponse) {
                    responseCode.value = response.rc
                }

                override fun onError(e: Throwable) {
                    super.onError(e)
                    if (e is HttpException) responseCode.value = e.code()
                }
            })
    }
}