package com.example.emojify

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.emojify.databinding.ActivityMainBinding
import com.google.android.gms.vision.Frame
import com.google.android.gms.vision.face.Face
import com.google.android.gms.vision.face.FaceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    private var writePermission = false
    private lateinit var permissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var binding: ActivityMainBinding
    private var imageBmp: Bitmap? = null
    private var filePath: String? = null
    private val emojiScaleFactor = .9f
    private val smilingProbabilityThreshold = .15f
    private val eyeOpenProbabilityThreshold = .5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            writePermission = it[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: writePermission
            if(!writePermission) {
                Toast.makeText(this, "Can't store files without permission", Toast.LENGTH_LONG).show()
            }
        }
        updateOrRequestPermission()
        val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) {
            binding.capturedOrEmojifyIV.isVisible = true
            binding.cameraBT.isVisible = false
            binding.emojifyME.isEnabled = true
            lifecycleScope.launch {
                filePath?.let {
                    Log.d("DeleteFile", "Delete...")
                    File(it).delete()
                    filePath = null
                }
                imageBmp = it
                binding.capturedOrEmojifyIV.setImageBitmap(it)
            }
        }
        binding.emojifyME.setOnClickListener {
            imageBmp?.let{
                imageBmp = detectFacesAndOverlayEmoji(it)
                binding.capturedOrEmojifyIV.setImageBitmap(imageBmp)
            }
        }
        binding.saveFAB.setOnClickListener {
            lifecycleScope.launch {
                if(imageBmp != null) {
                    if(savePhotoToExternalStorage(UUID.randomUUID().toString(), imageBmp!!))
                        Toast.makeText(this@MainActivity, "Image saved successfully", Toast.LENGTH_LONG).show()
                    else
                        Toast.makeText(this@MainActivity, "Unable to save, please try again", Toast.LENGTH_LONG).show()
                }else{
                    Toast.makeText(this@MainActivity, "Can't save empty image", Toast.LENGTH_LONG).show()
                }
            }
        }
        binding.shareFAB.setOnClickListener {
            lifecycleScope.launch {
                if(imageBmp != null) {
                    shareImage(UUID.randomUUID().toString())
                }else{
                    Toast.makeText(this@MainActivity, "Can't share empty image", Toast.LENGTH_LONG).show()
                }
            }
        }
        binding.cancelFAB.setOnClickListener {
            binding.capturedOrEmojifyIV.isVisible = false
            binding.cameraBT.isVisible = true
            binding.emojifyME.isEnabled = false
            binding.capturedOrEmojifyIV.setImageDrawable(null)
            filePath?.let {
                Log.d("DeleteFile", "Delete...")
                File(it).delete()
                filePath = null
            }
            imageBmp = null
        }
        binding.cameraBT.setOnClickListener {
            takePhoto.launch()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        filePath?.let {
            Log.d("DeleteFile", "Delete...")
            File(it).delete()
            filePath = null
        }
    }

    private fun shareImage(fileName: String) {
        try {
            val cachePath = File(cacheDir, "images")
            cachePath.mkdirs()
            val outputStream = FileOutputStream("${cachePath.path}/${fileName}.jpg")
            if (!imageBmp!!.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)) {
                throw IOException("Couldn't Save File")
            }
            outputStream.close()
            val imagePath = File(cachePath, "${fileName}.jpg")
            filePath = imagePath.absolutePath
            val contentUri = FileProvider.getUriForFile(this, "com.example.emojify.fileprovider", imagePath)
            Intent().apply {
                action = Intent.ACTION_SEND
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                startActivity(this)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, e.message.toString(), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateOrRequestPermission() {
        val hasWritePermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        val minSdkVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        writePermission = hasWritePermission || minSdkVersion
        val permissionsToRequest = mutableListOf<String>()
        if(!writePermission){
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if(permissionsToRequest.isNotEmpty()){
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private suspend fun savePhotoToExternalStorage(displayName: String, bmp: Bitmap): Boolean {
        return withContext(Dispatchers.IO) {
            val imageCollection = sdk29OrAbove {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpg")
                put(MediaStore.Images.Media.WIDTH, bmp.width)
                put(MediaStore.Images.Media.HEIGHT, bmp.height)
            }
            try {
                contentResolver.insert(imageCollection, contentValues)?.also { uri ->
                    contentResolver.openOutputStream(uri).use { stream ->
                        if (!bmp.compress(Bitmap.CompressFormat.JPEG, 100, stream)) {
                            throw IOException("Failed yo save bitmap")
                        }
                    }
                } ?: throw IOException("Couldn't create media store entry")
                true
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun detectFacesAndOverlayEmoji(picture: Bitmap?): Bitmap? {
        val detector = FaceDetector.Builder(this)
                .setTrackingEnabled(false)
                .setClassificationType(FaceDetector.ALL_CLASSIFICATIONS)
                .build()
        val frame= Frame.Builder().setBitmap(picture!!).build()
        val faces = detector.detect(frame)
        var resultBitmap = picture
        if (faces.size() == 0) {
            Toast.makeText(this, "No face found", Toast.LENGTH_SHORT).show()
        } else {
            for (i in 0 until faces.size()) {
                val face = faces[i] ?: continue
                var emojiBitmap: Bitmap?
                when (whichEmoji(face)) {
                    Emoji.SMILE -> emojiBitmap = BitmapFactory.decodeResource(resources,
                            R.drawable.smile)
                    Emoji.FROWN -> emojiBitmap = BitmapFactory.decodeResource(resources,
                            R.drawable.frown)
                    Emoji.LEFT_WINK -> emojiBitmap = BitmapFactory.decodeResource(resources,
                            R.drawable.leftwink)
                    Emoji.RIGHT_WINK -> emojiBitmap = BitmapFactory.decodeResource(resources,
                            R.drawable.rightwink)
                    Emoji.LEFT_WINK_FROWN -> emojiBitmap = BitmapFactory.decodeResource(resources,
                            R.drawable.leftwinkfrown)
                    Emoji.RIGHT_WINK_FROWN -> emojiBitmap = BitmapFactory.decodeResource(resources,
                            R.drawable.rightwinkfrown)
                    Emoji.CLOSED_EYE_SMILE -> emojiBitmap = BitmapFactory.decodeResource(resources,
                            R.drawable.closed_smile)
                    Emoji.CLOSED_EYE_FROWN -> emojiBitmap = BitmapFactory.decodeResource(resources,
                            R.drawable.closed_frown)
                    else -> emojiBitmap = BitmapFactory.decodeResource(resources,
                            R.drawable.blank)
                }
                resultBitmap = addBitmapToFace(resultBitmap!!, emojiBitmap!!, face)
            }
        }
        detector.release()
        if(resultBitmap == picture) {
            Toast.makeText(this,"No emoji found", Toast.LENGTH_SHORT).show()
        }
        return resultBitmap
    }

    private fun whichEmoji(face: Face): Emoji? {
        val smiling = face.isSmilingProbability > smilingProbabilityThreshold
        val leftEyeClosed = face.isLeftEyeOpenProbability < eyeOpenProbabilityThreshold
        val rightEyeClosed = face.isRightEyeOpenProbability < eyeOpenProbabilityThreshold
        if(face.isSmilingProbability == smilingProbabilityThreshold) {
            return null
        }
        return if (smiling) {
            if (leftEyeClosed && !rightEyeClosed) {
                Emoji.LEFT_WINK
            } else if (rightEyeClosed && !leftEyeClosed) {
                Emoji.RIGHT_WINK
            } else if (leftEyeClosed) {
                Emoji.CLOSED_EYE_SMILE
            } else {
                Emoji.SMILE
            }
        } else {
            if (leftEyeClosed && !rightEyeClosed) {
                Emoji.LEFT_WINK_FROWN
            } else if (rightEyeClosed && !leftEyeClosed) {
                Emoji.RIGHT_WINK_FROWN
            } else if (leftEyeClosed) {
                Emoji.CLOSED_EYE_FROWN
            } else {
                Emoji.FROWN
            }
        }
    }

    private fun addBitmapToFace(backgroundBitmap: Bitmap, bitmap: Bitmap, face: Face): Bitmap? {
        var emojiBitmap = bitmap
        val resultBitmap = Bitmap.createBitmap(backgroundBitmap.width,
                backgroundBitmap.height, backgroundBitmap.config)
        val scaleFactor: Float = emojiScaleFactor
        val newEmojiWidth = (face.width * scaleFactor).toInt()
        val newEmojiHeight = (emojiBitmap.height * newEmojiWidth / emojiBitmap.width * scaleFactor).toInt()
        emojiBitmap = Bitmap.createScaledBitmap(emojiBitmap, newEmojiWidth, newEmojiHeight, false)
        val emojiPositionX = face.position.x + face.width / 2 - emojiBitmap.width / 2
        val emojiPositionY = face.position.y + face.height / 2 - emojiBitmap.height / 3
        val canvas = Canvas(resultBitmap)
        canvas.drawBitmap(backgroundBitmap, 0.toFloat(), 0.toFloat(), null)
        canvas.drawBitmap(emojiBitmap, emojiPositionX, emojiPositionY, null)
        return resultBitmap
    }

    private fun <T> sdk29OrAbove(onSdk29: () -> T): T? {
        return if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onSdk29()
        } else {
            null
        }
    }
    enum class Emoji {
        SMILE,
        FROWN,
        LEFT_WINK,
        RIGHT_WINK,
        LEFT_WINK_FROWN,
        RIGHT_WINK_FROWN,
        CLOSED_EYE_SMILE,
        CLOSED_EYE_FROWN
    }
}