package com.superavaliacao.camera2

import android.app.TaskStackBuilder
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.ActivityCompat
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var mCameraFacing = CameraCharacteristics.LENS_FACING_BACK
    private var mCameraId: String? = null
    private var mCameraPreviewSize: Size? = null

    private var mCameraManager: CameraManager? = null
    private var mCameraDevice: CameraDevice? = null

    private var mPreviewRequestBuilder: CaptureRequest.Builder? = null
    private var mPreviewRequest: CaptureRequest? = null
    private var mPreviewCaptureSession: CameraCaptureSession? = null
    private var mPreviewCaptureSessionCallback = object : CameraCaptureSession.CaptureCallback() {

        override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
            super.onCaptureCompleted(session, request, result)
            result?.let { process(it) }
        }

        private fun process(result: CaptureResult) {
            when (mState) {
                STATE_PREVIEW -> Unit
                STATE_WAIT_LOCK -> {
                    val afSafe = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afSafe == CaptureRequest.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                        mState = STATE_PICTURE_CAPTURED
                        captureStillImage()
                    }
                }
            }
        }

    }

    private var mCaptureRequestBuilder: CaptureRequest.Builder? = null
    private var mCaptureRequest: CaptureRequest? = null
    private var mCaptureSessionCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession?, request: CaptureRequest?, timestamp: Long, frameNumber: Long) {
            super.onCaptureStarted(session, request, timestamp, frameNumber)

            try {
                mImageFile = createImageFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }

        }
        override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
            super.onCaptureCompleted(session, request, result)

            Toast.makeText(applicationContext, "Image Captured", Toast.LENGTH_SHORT).show()
            unLokFocus()
        }
    }

    private var mState: Int? = null

    private lateinit var mTextureView: TextureView
    private lateinit var mTextureViewListener: TextureView.SurfaceTextureListener

    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null

    private var mGalleryFolder: File? = null
    private var mImageFile: File? = null

    private var mImageReader: ImageReader? = null
    private var mImageReaderAvailableListener = ImageReader.OnImageAvailableListener {
        mBackgroundHandler?.post(ImageSaver(it.acquireNextImage()))
    }

    private inner class ImageSaver internal constructor(val mImage: Image) : Runnable {

        override fun run() {
            val byteBuffer = mImage.planes[0].buffer
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)

            var fileOutputStream: FileOutputStream? = null

            try {
                fileOutputStream = FileOutputStream(mImageFile)
                fileOutputStream.write(bytes)
            } catch (e: IOException) {
                e.printStackTrace()
            }

            mImage.close()
            try {
                fileOutputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createImageGallery()

        mTextureView = textureView
        mTextureViewListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
                this@MainActivity.setupCamera(width, height)
                this@MainActivity.openCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?) : Boolean = false
        }

        btnTakeImage.setOnClickListener { lockFocus() }
    }

    private fun createImageGallery() {
        val storageDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        mGalleryFolder = File(storageDirectory, "Vistoria")
        if (mGalleryFolder?.exists() == false) {
            mGalleryFolder?.mkdirs()
        }
    }

    @Throws(IOException::class)
    private fun createImageFile() : File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
        val imageFileName = "IMAGE_" + timeStamp + "_"

        return File.createTempFile(imageFileName, ".jpg", mGalleryFolder)
    }

    override fun onResume() {
        super.onResume()

        openBackgroundThread()

        if (mTextureView.isAvailable) {
            this@MainActivity.setupCamera(mTextureView.width, mTextureView.height)
            this@MainActivity.openCamera()
            return
        }

        mTextureView.surfaceTextureListener = mTextureViewListener
    }

    override fun onPause() {
        closeCamera()
        closeBackgroundThread()
        super.onPause()
    }

    @Throws(CameraAccessException::class)
    private fun setupCamera(width: Int, height: Int) {
        mCameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        mCameraManager?.cameraIdList?.forEach {
            val characteristics = mCameraManager?.getCameraCharacteristics(it)
            if (characteristics?.get(CameraCharacteristics.LENS_FACING) == mCameraFacing) {
                val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                val sizes = configs.getOutputSizes(ImageFormat.JPEG)
                val largestImageSize = Collections.max(sizes.asList(), CompareSizeByArea())

                mImageReader = ImageReader.newInstance(largestImageSize.width, largestImageSize.height, ImageFormat.JPEG, 1)
                mImageReader?.setOnImageAvailableListener(mImageReaderAvailableListener, mBackgroundHandler)

                mCameraPreviewSize = chooseOptimalSize(configs.getOutputSizes(SurfaceTexture::class.java), width, height)
                mCameraId = it
                return@forEach
            }
        }
    }

    @Throws(CameraAccessException::class)
    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            return
        }

        mCameraManager?.openCamera(mCameraId, object : CameraDevice.StateCallback(){
            override fun onOpened(camera: CameraDevice?) {
                mCameraDevice = camera
                createPreviewSession()
            }

            override fun onDisconnected(camera: CameraDevice?) {
                camera?.close()
                mCameraDevice = null
            }

            override fun onError(camera: CameraDevice?, error: Int) {
                camera?.close()
                mCameraDevice = null
            }
        }, mBackgroundHandler)
    }

    private fun closeCamera() {
        mPreviewCaptureSession?.close()
        mPreviewCaptureSession = null

        mCameraDevice?.close()
        mCameraDevice = null

        mImageReader?.close()
        mImageReader = null
    }

    @Throws(CameraAccessException::class)
    private fun createPreviewSession() {
        val surfaceTexture = mTextureView.surfaceTexture
        surfaceTexture.setDefaultBufferSize(mCameraPreviewSize?.width ?: return, mCameraPreviewSize?.height ?: return)
        val previewSurface = Surface(surfaceTexture)

        mPreviewRequestBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        mPreviewRequestBuilder?.addTarget(previewSurface)

        mCameraDevice?.createCaptureSession(mutableListOf(previewSurface, mImageReader?.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigureFailed(session: CameraCaptureSession?) = Unit
            override fun onConfigured(session: CameraCaptureSession?) {
                if (mCameraDevice == null) {
                    return
                }

                mPreviewRequest = mPreviewRequestBuilder?.build()
                this@MainActivity.mPreviewCaptureSession = session
                this@MainActivity.mPreviewCaptureSession?.setRepeatingRequest(mPreviewRequest, mPreviewCaptureSessionCallback, mBackgroundHandler)
            }
        }, null)
    }

    private fun openBackgroundThread() {
        mBackgroundThread = HandlerThread("Camera2API")
        mBackgroundThread?.start()
        mBackgroundHandler = Handler(mBackgroundThread?.looper)
    }

    private fun closeBackgroundThread() {
        mBackgroundThread?.quitSafely()
        try {
            mBackgroundThread?.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        }catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun lockFocus() {
        try {
            mState = STATE_WAIT_LOCK
            mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            mPreviewCaptureSession?.capture(mPreviewRequestBuilder?.build(), mPreviewCaptureSessionCallback, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun unLokFocus() {
        try {
            mState = STATE_PREVIEW
            mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            mPreviewCaptureSession?.capture(mPreviewRequestBuilder?.build(), mPreviewCaptureSessionCallback, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    @Throws(CameraAccessException::class)
    private fun captureStillImage() {
        mCaptureRequestBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        mCaptureRequestBuilder?.addTarget(mImageReader?.surface)

        val rotation = windowManager.defaultDisplay.rotation
        mCaptureRequestBuilder?.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation))
        mCaptureRequest = mCaptureRequestBuilder?.build()

        mPreviewCaptureSession?.capture(mCaptureRequest, mCaptureSessionCallback, null)
    }

    private fun chooseOptimalSize(outputSizes: Array<Size>, width: Int, height: Int) : Size {
        val preferredRatio = height / width.toDouble()
        var currentOptimalSize = outputSizes[0]
        var currentOptimalRatio = currentOptimalSize.width / currentOptimalSize.height.toDouble()
        for (currentSize in outputSizes) {
            val currentRatio = currentSize.width / currentSize.height.toDouble()
            if (Math.abs(preferredRatio - currentRatio) < Math.abs(preferredRatio - currentOptimalRatio)) {
                currentOptimalSize = currentSize
                currentOptimalRatio = currentRatio
            }
        }
        return currentOptimalSize
    }

    private class CompareSizeByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }

    companion object {
        const val STATE_PREVIEW = 0
        const val STATE_WAIT_LOCK = 1
        const val STATE_PICTURE_CAPTURED = 2

        val ORIENTATIONS = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }

    }

}