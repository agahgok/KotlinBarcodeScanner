package com.pwacrafter.barcod

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.pwacrafter.barcod.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var preview: Preview? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var imageCapture: ImageCapture

    private lateinit var cameraExecutor: ExecutorService
    private var productUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        bindListeners()
    }

    private fun bindListeners() {
        binding.apply {
            barcodeButton.setOnClickListener { capturePhotoForBarcode() }
            productPageButton.setOnClickListener {
                productUrl?.let {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                    startActivity(intent)
                }
            }
            resetButton.setOnClickListener { resetApp() }
        }
    }

    private fun resetApp() {
        binding.apply {
            barcodeInfo.text = "Barcode Info"
            productPageButton.visibility = android.view.View.GONE
            productUrl = null
        }
    }

    private fun capturePhotoForBarcode() {
        imageCapture.takePicture(cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = convertImageProxyToBitmap(imageProxy)
                    processBarcode(bitmap) { barcode ->
                        runOnUiThread {
                            binding.barcodeInfo.text = "Barcode Info: $barcode"
                        }
                        fetchProductInfoFromGoogle(barcode)
                    }
                    imageProxy.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed for Barcode: ${exception.message}", exception)
                    runOnUiThread {
                        resetApp()
                        binding.barcodeInfo.text = "No barcode detected. Please try again."
                    }
                }
            })
    }

    private fun processBarcode(bitmap: Bitmap, onResult: (String) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val scanner: BarcodeScanner = BarcodeScanning.getClient()

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val barcode = barcodes.firstOrNull()
                    val rawValue = barcode?.rawValue
                    if (rawValue != null) {
                        Log.d("MainActivity", "Barcode detected: $rawValue")
                        onResult(rawValue)
                    } else {
                        Log.d("MainActivity", "No valid information found on the barcode.")
                        runOnUiThread {
                            resetApp()
                            binding.barcodeInfo.text = "No valid information found on the barcode."
                        }
                    }
                } else {
                    Log.d("MainActivity", "No barcode detected.")
                    runOnUiThread {
                        resetApp()
                        binding.barcodeInfo.text = "No barcode detected. Please try again."
                    }
                }
            }
            .addOnFailureListener {
                Log.e("MainActivity", "An error occurred during barcode scanning: ${it.message}")
                runOnUiThread {
                    resetApp()
                    binding.barcodeInfo.text = "An error occurred during barcode scanning. Please try again."
                }
            }
    }

    private fun fetchProductInfoFromGoogle(barcode: String) {
        cameraExecutor.execute {
            try {
                val searchUrl = "https://www.google.com/search?q=${Uri.encode(barcode + " product")}" // Corrected indentation
                val doc = org.jsoup.Jsoup.connect(searchUrl).get()
                val searchResults = doc.select("h3").map { it.text() }
                productUrl = searchUrl

                if (searchResults.isNotEmpty()) {
                    val firstResult = searchResults.first()
                    runOnUiThread {
                        binding.barcodeInfo.text = "Search result: $firstResult"
                        binding.productPageButton.apply {
                            text = "Click for product page"
                            visibility = android.view.View.VISIBLE
                        }
                        Log.d(TAG, "Search result: $firstResult")
                    }
                } else {
                    runOnUiThread {
                        binding.barcodeInfo.text = "No product information found."
                        binding.productPageButton.visibility = android.view.View.GONE
                        Log.d(TAG, "No product information found.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Google search error: ${e.message}")
                runOnUiThread {
                    binding.barcodeInfo.text = "An error occurred while retrieving product information."
                    binding.productPageButton.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()

        imageCapture = ImageCapture.Builder()
            .setTargetRotation(rotation)
            .build()

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun convertImageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val planeBuffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(planeBuffer.remaining())
        planeBuffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        }

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
