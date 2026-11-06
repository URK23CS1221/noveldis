package com.example.noveldis
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import android.widget.ScrollView
import android.view.View.MeasureSpec
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions


class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: TextView? = null
    private var definitionView: View? = null

    // Screen Capture Variables
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // NEW: The flag that tells our persistent engine to grab a frame
    private var isSnapRequested = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var db: AppDatabase

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("DATA")

        if (resultCode == Activity.RESULT_OK && data != null && mediaProjection == null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            // NEW: Start the engine ONCE when the app launches
            setupPersistentPipeline()
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "dictionary.db")
            .createFromAsset("dictionary.db")
            .build()

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        startForegroundServiceNotification()
        showFloatingBubble()
    }

    @SuppressLint("WrongConstant")
    private fun setupPersistentPipeline() {
        // Build the ImageReader with a buffer of 2 frames. It sits quietly in the background.
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                // Only process the image if the user tapped the bubble!
                if (isSnapRequested) {
                    isSnapRequested = false
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth

                        val bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

                        runOCR(croppedBitmap)
                    } catch (e: Exception) {
                        Log.e("NOVEL_DEBUG", "Image extraction failed", e)
                    }
                }
                // CRITICAL: Always close the image to free the memory buffer! No more Surface crashes.
                image.close()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun showFloatingBubble() {
        floatingView = TextView(this).apply {
            text = "🔍"
            textSize = 22f
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#80000000"))
            setTextColor(Color.WHITE)
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        floatingView?.setOnClickListener {
            // 1. Close any open dictionary popups
            definitionView?.let { windowManager.removeView(it) }
            definitionView = null

            // 2. THE FIX: Microscopic alpha toggle wakes up Android's graphics engine
            // and forces SurfaceFlinger to generate a fresh frame INSTANTLY
            floatingView?.alpha = if (floatingView?.alpha == 0.99f) 1.0f else 0.99f
            floatingView?.invalidate()

            // 3. Request frame capture immediately
            isSnapRequested = true
        }

        windowManager.addView(floatingView, layoutParams)
    }

    private fun runOCR(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                enableLensMode(visionText)
            }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableLensMode(visionText: Text) {
        val lensParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val lensView = LensOverlayView(this, visionText, windowManager) { tappedWord ->
            showDefinitionUI(tappedWord)
        }

        windowManager.addView(lensView, lensParams)
    }

    // ==========================================
    // NEW: THE SLEEK DEFINITION POPUP UI
    // ==========================================
    private fun showDefinitionUI(rawWord: String) {
        // SAFE REMOVAL: Prevents WindowManager crashes if the view is already gone
        definitionView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }

        // 1. SANITIZE: Strip away commas, brackets, quotes, and make it lowercase
        val cleanWord = rawWord.replace(Regex("[^a-zA-Z]"), "").lowercase()

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 150 // Floats slightly above the bottom of the screen
        }

        // The rounded Card container
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)

            val backgroundShape = GradientDrawable().apply {
                setColor(Color.parseColor("#F21E1E1E")) // Very dark grey, slightly transparent
                cornerRadius = 50f
            }
            background = backgroundShape
        }

        // The Tapped Word (Using rawWord or cleanWord as the title)
        val wordTitle = TextView(this).apply {
            text = cleanWord.replaceFirstChar { it.uppercase() }
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        // The Meaning (Initially shows a loading status)
        val meaningText = TextView(this).apply {
            text = "Searching for '$cleanWord'..."
            textSize = 18f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, 20, 0, 0)
        }
        val scrollView = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // This caps the text box at exactly 40% of your screen height!
                // If the text is short, it stays small. If it's huge, it scrolls.
                val maxHeight = (screenHeight * 0.4).toInt()
                val customHeightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, customHeightSpec)
            }
        }.apply {
            // Add some padding to the bottom so the scroll doesn't hit the close button
            setPadding(0, 0, 0, 30)
            addView(meaningText) // Put the text inside the scroll box
        }
        // The Close Button
        val closeButton = TextView(this).apply {
            text = "Close"
            textSize = 16f
            setTextColor(Color.parseColor("#88B4F8")) // Soft blue
            setPadding(0, 20, 0, 0)
            gravity = Gravity.END

            setOnClickListener {
                definitionView?.let {
                    try { windowManager.removeView(it) } catch (e: Exception) {}
                    definitionView = null
                }
            }
        }

        container.addView(wordTitle)
        // ADD THE SCROLLVIEW HERE INSTEAD OF THE TEXTVIEW
        container.addView(scrollView)
        container.addView(closeButton)

        // Add margins to the sides of the card so it doesn't touch the screen edges
        val marginContainer = LinearLayout(this).apply {
            setPadding(40, 0, 40, 0)
            addView(container, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        definitionView = marginContainer
        windowManager.addView(definitionView, layoutParams)

        // 2. SEARCH: Explicitly use Dispatchers.IO to guarantee it stays off the main thread
        // 2. THE HYBRID SEARCH: Try Online API first, Fallback to Offline SQLite
        serviceScope.launch(Dispatchers.IO) {
            var finalResult = ""

            try {
                // Attempt 1: Fetch from the Free Dictionary API
                val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/$cleanWord")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 2000 // Give up after 2 seconds if internet is bad
                connection.readTimeout = 2000

                if (connection.responseCode == 200) {
                    // Internet is working! Parse the JSON response
                    val response = connection.inputStream.bufferedReader().readText()
                    val jsonArray = JSONArray(response)

                    // Grab the very first modern definition
                    val modernDefinition = jsonArray.getJSONObject(0)
                        .getJSONArray("meanings").getJSONObject(0)
                        .getJSONArray("definitions").getJSONObject(0)
                        .getString("definition")

                    finalResult = "🌐 $modernDefinition" // Add a globe icon so you know it's online
                } else {
                    throw Exception("Word not found online") // Force it to the catch block
                }

            } catch (e: Exception) {
                // Attempt 2: Internet failed or word wasn't found online. Fallback to Local DB!
                val offlineResult = db.dictionaryDao().getMeaning(cleanWord)

                finalResult = if (offlineResult != null) {
                    "📚 $offlineResult" // Add a book icon so you know it used the offline database
                } else {
                    "Word not found online or offline."
                }
            }

            // 3. UPDATE UI: Jump back to the Main UI thread to update the popup text
            withContext(Dispatchers.Main) {
                meaningText.text = finalResult
            }
        }
    }

    private fun startForegroundServiceNotification() {
        // ... (Keep your existing notification code here) ...
        val channelId = "floating_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Novel Floating Overlay", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Novel Dictionary Active")
            .setContentText("Tap the floating icon over your novel anytime.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()

        startForeground(1, notification)
    }

    // NEW: Kills the ghost bubble when you swipe the app closed!
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }


    override fun onDestroy() {
        super.onDestroy()
        if (floatingView != null) windowManager.removeView(floatingView)
        if (definitionView != null) windowManager.removeView(definitionView)
        mediaProjection?.stop()
        serviceScope.cancel()
    }
}

/**
 * UPDATED: VISUAL DEBUGGING VIEW
 */
@SuppressLint("ViewConstructor")
class LensOverlayView(
    context: Context,
    private val visionText: Text,
    private val windowManager: WindowManager,
    private val onWordTapped: (String) -> Unit // Passes the word back up to the UI!
) : View(context) {

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#4400FF00")
        style = Paint.Style.FILL
    }

    init {
        setBackgroundColor(Color.parseColor("#44000000")) // Lighter dim so you can read better
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    element.boundingBox?.let { rect ->
                        canvas.drawRect(rect, boxPaint)
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val absoluteX = event.rawX.toInt()
            val absoluteY = event.rawY.toInt()

            findTappedWord(absoluteX, absoluteY)

            // Close the lens view instantly after tapping
            try {
                windowManager.removeView(this)
            } catch (e: Exception) {}
        }
        return true
    }

    private fun findTappedWord(x: Int, y: Int) {
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox
                    if (box != null && box.contains(x, y)) {
                        // BINGO! Send the word to our new UI function
                        onWordTapped(element.text)
                        return
                    }
                }
            }
        }
    }
}