
package com.example.maraai

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

class MainActivity : AppCompatActivity() {

    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnMic: MaterialButton
    private lateinit var btnSend: MaterialButton
    private lateinit var btnImage: MaterialButton
    private lateinit var btnMute: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var btnVoiceSettings: MaterialButton
    private lateinit var imagePreview: LinearLayout
    private lateinit var btnClearImage: MaterialButton

    private val messages = mutableListOf<ChatMessage>()

    private lateinit var adapter: ChatAdapter

    private val chatHistory = JSONArray()

    private var pendingBase64Image: String? = null

    private var isMuted = false

    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private var speechRecognizer: SpeechRecognizer? = null

    private val executor = Executors.newSingleThreadExecutor()

    private val prefs by lazy {
        getSharedPreferences("MaraAI", Context.MODE_PRIVATE)
    }

    private val pickImageLauncher =
        registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->

            if (uri != null) {
                attachImage(uri)
            }
        }

    private val micPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                startVoiceInput()
            } else {
                toast("માઇક્રોફોન પરમિશન જરૂરી છે")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)

        setContentView(R.layout.activity_main)

        rvChat = findViewById(R.id.rvChat)
        etInput = findViewById(R.id.etInput)
        btnMic = findViewById(R.id.btnMic)
        btnSend = findViewById(R.id.btnSend)
        btnImage = findViewById(R.id.btnImage)
        btnMute = findViewById(R.id.btnMute)
        btnSettings = findViewById(R.id.btnSettings)
        btnVoiceSettings = findViewById(R.id.btnVoiceSettings)
        imagePreview = findViewById(R.id.layoutImagePreview)
        btnClearImage = findViewById(R.id.btnClearImage)

        setupRecyclerView()

        initTts()

        initSpeech()

        addMessage(
            "નમસ્તે! 🙏 હું Mara AI છું.\n\n" +
            "તમને ગુજરાતીમાં મદદ કરવા તૈયાર છું.\n\n" +
            "🎤 બોલીને પૂછો\n" +
            "🖼️ ફોટો મોકલો\n" +
            "✍️ લખીને પૂછો\n\n" +
            "શરૂ કરવા માટે તમારો પ્રશ્ન પૂછો.",
            false
        )

        btnImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnClearImage.setOnClickListener {
            pendingBase64Image = null
            imagePreview.visibility = View.GONE
        }

        btnSend.setOnClickListener {

            val question = etInput.text.toString().trim()

            if (question.isNotEmpty() ||
                pendingBase64Image != null
            ) {

                etInput.setText("")

                val prompt = question.ifEmpty {
                    "આ ફોટામાં શું છે તે ગુજરાતીમાં સમજાવો."
                }

                handleCommand(prompt)
            }
        }

        btnMic.setOnClickListener {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                startVoiceInput()

            } else {

                micPermission.launch(
                    Manifest.permission.RECORD_AUDIO
                )
            }
        }

        btnMute.setOnClickListener {

            isMuted = !isMuted

            btnMute.text = if (isMuted) "🔇" else "🔊"

            if (isMuted && ttsReady) {
                tts.stop()
            }
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        btnVoiceSettings.setOnClickListener {
            openTtsSettings()
        }

        handleSharedImage(intent)
    }

    private fun setupRecyclerView() {

        adapter = ChatAdapter(messages)

        rvChat.layoutManager =
            LinearLayoutManager(this)

        rvChat.adapter = adapter
    }

    private fun addMessage(text: String, isUser: Boolean) {

        runOnUiThread {

            messages.add(
                ChatMessage(text, isUser)
            )

            adapter.notifyItemInserted(
                messages.size - 1
            )

            rvChat.scrollToPosition(
                messages.size - 1
            )
        }
    }

    private fun attachImage(uri: Uri) {

        try {

            val stream =
                contentResolver.openInputStream(uri)

            val bitmap =
                BitmapFactory.decodeStream(stream)

            stream?.close()

            if (bitmap == null) {
                toast("ફોટો ખોલી શકાયો નથી")
                return
            }

            val resized = resizeBitmap(bitmap)

            val output = ByteArrayOutputStream()

            resized.compress(
                Bitmap.CompressFormat.JPEG,
                75,
                output
            )

            pendingBase64Image =
                android.util.Base64.encodeToString(
                    output.toByteArray(),
                    android.util.Base64.NO_WRAP
                )

            imagePreview.visibility = View.VISIBLE

            toast("ફોટો જોડાઈ ગયો છે")

        } catch (e: Exception) {

            toast("ફોટો લોડ કરવામાં ભૂલ")
        }
    }

    private fun resizeBitmap(bitmap: Bitmap): Bitmap {

        val maxSize = 1024

        val ratio = minOf(
            1f,
            maxSize.toFloat() /
                maxOf(bitmap.width, bitmap.height)
        )

        val width = (bitmap.width * ratio).toInt()
        val height = (bitmap.height * ratio).toInt()

        return Bitmap.createScaledBitmap(
            bitmap,
            width,
            height,
            true
        )
    }

    private fun handleSharedImage(intent: Intent?) {

        if (intent?.action == Intent.ACTION_SEND) {

            val uri = if (
                android.os.Build.VERSION.SDK_INT >= 33
            ) {
                intent.getParcelableExtra(
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(
                    Intent.EXTRA_STREAM
                )
            }

            if (uri != null) {
                attachImage(uri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedImage(intent)
    }

    private fun handleCommand(prompt: String) {

        val apiKey =
            prefs.getString("api_key", "") ?: ""

        if (apiKey.isBlank()) {

            addMessage(
                "પહેલાં Settings માં જઈ Gemini API Key નાખો.",
                false
            )

            showSettingsDialog()

            return
        }

        val image = pendingBase64Image

        pendingBase64Image = null

        imagePreview.visibility = View.GONE

        addMessage(prompt, true)

        addMessage("જવાબ તૈયાર કરી રહ્યો છું... ⏳", false)

        executor.execute {

            try {

                val answer = askGemini(
                    apiKey,
                    prompt,
                    image
                )

                runOnUiThread {

                    if (messages.isNotEmpty()) {
                        messages.removeAt(messages.size - 1)
                        adapter.notifyItemRemoved(messages.size)
                    }

                    addMessage(answer, false)

                    if (!isMuted && ttsReady) {
                        tts.speak(
                            answer,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "mara_answer"
                        )
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    if (messages.isNotEmpty()) {
                        messages.removeAt(messages.size - 1)
                        adapter.notifyItemRemoved(messages.size)
                    }

                    addMessage(
                        "માફ કરશો! કનેક્શનમાં સમસ્યા છે.\n" +
                        (e.message ?: "ફરી પ્રયાસ કરો."),
                        false
                    )
                }
            }
        }
    }

    private fun askGemini(
        apiKey: String,
        prompt: String,
        image: String?
    ): String {

        val url = URL(
            "https://generativelanguage.googleapis.com/" +
            "v1beta/models/gemini-3.6-flash:generateContent" +
            "?key=" + URLEncoder.encode(apiKey, "UTF-8")
        )

        val connection =
            url.openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.doOutput = true

        connection.setRequestProperty(
            "Content-Type",
            "application/json"
        )

        val contents = JSONArray()

        val historyStart = maxOf(
            0,
            chatHistory.length() - 10
        )

        for (i in historyStart until chatHistory.length()) {
            contents.put(chatHistory.getJSONObject(i))
        }

        val parts = JSONArray()

        parts.put(
            JSONObject().put(
                "text",
                "તમે Mara AI છો. સરળ અને સ્પષ્ટ ગુજરાતીમાં જવાબ આપો.\n\n" +
                prompt
            )
        )

        if (image != null) {

            val imagePart = JSONObject()

            imagePart.put(
                "inline_data",
                JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", image)
            )

            parts.put(imagePart)
        }

        val userContent = JSONObject()
            .put("role", "user")
            .put("parts", parts)

        contents.put(userContent)

        val body = JSONObject()
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.7)
                    .put("maxOutputTokens", 2048)
            )

        try {

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode

            val responseStream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val responseText =
                responseStream.bufferedReader().use {
                    it.readText()
                }

            if (responseCode !in 200..299) {
                throw Exception("Gemini API Error: $responseText")
            }

            val json = JSONObject(responseText)

            val answer = json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            chatHistory.put(userContent)

            chatHistory.put(
                JSONObject()
                    .put("role", "model")
                    .put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", answer)
                        )
                    )
            )

            return answer

        } finally {

            connection.disconnect()
        }
    }

    private fun showSettingsDialog() {

        val input = EditText(this)

        input.hint = "Gemini API Key નાખો"

        input.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        input.setText(
            prefs.getString("api_key", "")
        )

        val container = LinearLayout(this)

        container.setPadding(40, 10, 40, 10)

        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Mara AI Settings ⚙️")
            .setMessage(
                "તમારી Gemini API Key અહીં નાખો."
            )
            .setView(container)
            .setPositiveButton("Save") { _, _ ->

                prefs.edit()
                    .putString(
                        "api_key",
                        input.text.toString().trim()
                    )
                    .apply()

                toast("API Key સેવ થઈ ગઈ")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun initTts() {

        tts = TextToSpeech(this) { status ->

            if (status == TextToSpeech.SUCCESS) {

                val result = tts.setLanguage(
                    Locale("gu", "IN")
                )

                ttsReady =
                    result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

            }
        }
    }

    private fun initSpeech() {

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return
        }

        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    runOnUiThread {
                        toast("અવાજ ઓળખી શકાયો નથી")
                    }
                }

                override fun onResults(results: Bundle?) {

                    val list =
                        results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                    val text = list?.firstOrNull()

                    if (!text.isNullOrBlank()) {
                        etInput.setText(text)
                    }
                }

                override fun onPartialResults(
                    partialResults: Bundle?
                ) {}

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {}
            }
        )
    }

    private fun startVoiceInput() {

        if (speechRecognizer == null) {
            toast("Speech recognition ઉપલબ્ધ નથી")
            return
        }

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            "gu-IN"
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_PROMPT,
            "બોલો..."
        )

        speechRecognizer?.startListening(intent)
    }

    private fun openTtsSettings() {

        try {
            startActivity(
                Intent("com.android.settings.TTS_SETTINGS")
            )
        } catch (e: Exception) {
            startActivity(
                Intent(Settings.ACTION_SETTINGS)
            )
        }
    }

    private fun toast(message: String) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        speechRecognizer?.destroy()

        executor.shutdown()

        super.onDestroy()
    }
}

class ChatAdapter(
    private val messages: List<ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(
        val textView: TextView
    ) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ChatViewHolder {

        val textView = TextView(parent.context)

        textView.textSize = 16f

        textView.setTextColor(Color.WHITE)

        textView.setPadding(24, 18, 24, 18)

        return ChatViewHolder(textView)
    }

    override fun onBindViewHolder(
        holder: ChatViewHolder,
        position: Int
    ) {

        val message = messages[position]

        val params = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        params.setMargins(16, 8, 16, 8)

        params.width =
            (holder.itemView.resources.displayMetrics.widthPixels * 0.85).toInt()

        params.gravity = if (message.isUser) {
            Gravity.END
        } else {
            Gravity.START
        }

        holder.textView.layoutParams = params

        holder.textView.text = message.text

        holder.textView.setBackgroundColor(
            if (message.isUser) {
                Color.rgb(75, 65, 180)
            } else {
                Color.rgb(30, 41, 75)
            }
        )
    }

    override fun getItemCount(): Int {
        return messages.size
    }
}
