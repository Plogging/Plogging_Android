package com.plogging.ecorun.ui.running.save

import android.Manifest.permission.CAMERA
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.view.OrientationEventListener
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.plogging.ecorun.R
import com.plogging.ecorun.base.BaseFragment
import com.plogging.ecorun.databinding.FragmentCameraBinding
import com.plogging.ecorun.ui.main.MainViewModel
import com.plogging.ecorun.util.extension.*
import com.plogging.ecorun.util.glide.GlideApp
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxkotlin.addTo
import java.io.File

@AndroidEntryPoint
class CameraFragment : BaseFragment<FragmentCameraBinding, SaveViewModel>() {
    private lateinit var imageCaptureCallback: ImageCapture.OnImageCapturedCallback
    override fun getViewBinding() = FragmentCameraBinding.inflate(layoutInflater)
    private lateinit var orientationEventListener: OrientationEventListener
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    override val viewModel: SaveViewModel by viewModels()
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var bitmap: Bitmap
    private var orientation = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initOrientationEventListener()
        bottomViewDown()
        permissionCheck()
        getOutputDirectory()
        initImageCaptureObject()
        backPress()
    }

    private fun bottomViewDown() {
        parentFragment?.parentFragment?.let {
            ViewModelProvider(it).get(MainViewModel::class.java).showBottomNav.value = false
        }
    }

    private fun initOrientationEventListener() {
        orientationEventListener = object : OrientationEventListener(requireContext()) {
            override fun onOrientationChanged(ot: Int) {
                orientation = ot
            }
        }
    }

    private fun initImageCaptureObject() {
        imageCaptureCallback = object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                showSavingButton()
                val buffer = image.planes[0].buffer
                val scale = resources.displayMetrics.density
                val bytes = ByteArray(buffer.capacity()).also { buffer.get(it) }
                val trashNum = arguments?.get(getString(R.string.trash_num)) as Int
                val distance =
                    (arguments?.get(getString(R.string.distance)) as Float).meterToKilometer()
                        .toFloat()
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bitmap = bitmap.flipBitmap(cameraSelector) // ?????? ???????????? ?????? ?????? ??????
                bitmap = bitmap.imageCrop( // image view??? ?????? image crop eight ??? width??? ????????????.
                    binding.pvCamera.height.toFloat(),
                    binding.pvCamera.width.toFloat()
                )
                bitmap = bitmap.rotate(orientation) // ?????? ???????????? ?????? ????????? ??????
                Canvas(bitmap).apply { // bitmap??? Canvas??? Wrapping?????? bitmap??? immutable
                    drawDistance(bitmap, scale, distance, R.drawable.ic_mark_running, resources)
                    drawTrash(bitmap, scale, trashNum, R.drawable.ic_mark_trash, resources)
                    drawMark(bitmap, R.drawable.ic_plogging_mark, resources)
                    drawDate(bitmap, scale)
                }
                GlideApp.with(requireContext()).load(bitmap).into(binding.ivCameraCapturePreview)
                orientationEventListener.disable() // ????????? ????????? ????????? ??????
            }
        }
    }

    private fun permissionCheck() {
        if (!allGranted()) registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it.values.all { isGranted -> isGranted == true }) {
                startCamera()
            } else requireContext().toast(getString(R.string.need_camera_permission))
        }.launch(arrayOf(CAMERA, WRITE_EXTERNAL_STORAGE))
        else startCamera()
    }

    // ????????? ????????? ?????? ?????????
    private fun getOutputDirectory() {
        val mediaDir = requireActivity().externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        outputDirectory = if (mediaDir != null && mediaDir.exists())
            mediaDir else requireActivity().filesDir
    }

    override fun clickListener() {
        binding.ivCameraArrow.setOnClickListener { findNavController().popBackStack() }
        binding.btnCameraCapture.setOnClickListener { takePhoto() }
        binding.btnCameraSave.setOnClickListener { savePhoto() }
        binding.btnCameraChange.setOnClickListener {
            cameraSelector = when (cameraSelector) {
                CameraSelector.DEFAULT_BACK_CAMERA -> CameraSelector.DEFAULT_FRONT_CAMERA
                CameraSelector.DEFAULT_FRONT_CAMERA -> CameraSelector.DEFAULT_BACK_CAMERA
                else -> CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }
    }

    private fun savePhoto() {
        saveBitmapToMediaStore(bitmap, requireContext().contentResolver)
            .composeSchedulers()
            .subscribe({
                findNavController().previousBackStackEntry?.savedStateHandle?.set("uri", it)
                findNavController().popBackStack()
            }, {})
            .addTo(disposables)
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        // Create time-stamped output file to hold the image
        orientationEventListener.enable()
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            imageCaptureCallback
        )
    }

    private fun showSavingButton() {
        binding.ivCameraCapturePreview.visibility = VISIBLE
        binding.btnCameraCapture.visibility = INVISIBLE
        binding.btnCameraChange.visibility = INVISIBLE
        binding.btnCameraSave.visibility = VISIBLE
        binding.pvCamera.visibility = INVISIBLE
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(binding.pvCamera.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    //???????????? ?????? ???
    private fun backPress() {
        requireActivity().onBackPressedDispatcher.addCallback(this) {
            findNavController().popBackStack()
        }
    }

    private fun allGranted() =
        requireContext().isPermissionGranted(CAMERA) &&
                requireContext().isPermissionGranted(WRITE_EXTERNAL_STORAGE)

    companion object {
        const val TAG = "CameraXBasic"
    }
}
