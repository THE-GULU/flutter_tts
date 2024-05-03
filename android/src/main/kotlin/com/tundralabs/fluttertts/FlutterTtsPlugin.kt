package com.tundralabs.fluttertts

import android.content.ContentValues
import android.content.Context
import android.os.*
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.lang.reflect.Field
import java.util.*


/** FlutterTtsPlugin  */
class FlutterTtsPlugin : MethodCallHandler, FlutterPlugin {
    private var handler: Handler? = null
    private var methodChannel: MethodChannel? = null
    private var speakResult: Result? = null
    private var synthResult: Result? = null
    private var awaitSpeakCompletion = false
    private var speaking = false
    private var awaitSynthCompletion = false
    private var synth = false
    private var context: Context? = null
    private var ttsEngineLanguageMap: HashMap<String?, HashMap<Locale?, TextToSpeech>> = HashMap()
    private var tts: TextToSpeech? = null
    private val tag = "TTS"
    private val pendingMethodCalls = ArrayList<Runnable>()
    private val utterances = HashMap<String, String>()
    private var bundle: Bundle? = null
    private var silencems = 0
    private var lastProgress = 0
    private var currentText: String? = null
    private var pauseText: String? = null
    private var isPaused: Boolean = false
    private var queueMode: Int = TextToSpeech.QUEUE_FLUSH
    private var ttsStatus: Int? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var ttsLocale: Locale? = null
    private var ttsEngine: String? = null

    companion object {
        private const val SILENCE_PREFIX = "SIL_"
        private const val SYNTHESIZE_TO_FILE_PREFIX = "STF_"
    }

    private fun initInstance(messenger: BinaryMessenger, context: Context) {
        this.context = context
        methodChannel = MethodChannel(messenger, "flutter_tts")
        methodChannel!!.setMethodCallHandler(this)
        handler = Handler(Looper.getMainLooper())
        bundle = Bundle()
        initTextToSpeechClient()
    }

    /** Android Plugin APIs  */
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        initInstance(binding.binaryMessenger, binding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        stop()
        for (ttsLanguageMap in ttsEngineLanguageMap.values) {
            for (tts in ttsLanguageMap.values) {
                tts.shutdown()
            }
        }
        context = null
        methodChannel!!.setMethodCallHandler(null)
        methodChannel = null
    }

    private val utteranceProgressListener: UtteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                invokeMethod("synth.onStart", true)
            } else {
                if (isPaused) {
                    invokeMethod("speak.onContinue", true)
                    isPaused = false
                } else {
                    Log.d(tag, "Utterance ID has started: $utteranceId")
                    invokeMethod("speak.onStart", true)
                }
            }
            if (Build.VERSION.SDK_INT < 26) {
                onProgress(utteranceId, 0, utterances[utteranceId]!!.length)
            }
        }

        override fun onDone(utteranceId: String) {
            if (utteranceId.startsWith(SILENCE_PREFIX)) return
            if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                closeParcelFileDescriptor(false)
                Log.d(tag, "Utterance ID has completed: $utteranceId")
                if (awaitSynthCompletion) {
                    synthCompletion(1)
                }
                invokeMethod("synth.onComplete", true)
            } else {
                Log.d(tag, "Utterance ID has completed: $utteranceId")
                if (awaitSpeakCompletion && queueMode == TextToSpeech.QUEUE_FLUSH) {
                    speakCompletion(1)
                }
                invokeMethod("speak.onComplete", true)
            }
            lastProgress = 0
            pauseText = null
            utterances.remove(utteranceId)
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            Log.d(
                tag, "Utterance ID has been stopped: $utteranceId. Interrupted: $interrupted"
            )
            if (awaitSpeakCompletion) {
                speaking = false
            }
            if (isPaused) {
                invokeMethod("speak.onPause", true)
            } else {
                invokeMethod("speak.onCancel", true)
            }
        }

        private fun onProgress(utteranceId: String?, startAt: Int, endAt: Int) {
            if (utteranceId != null && !utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                val text = utterances[utteranceId]
                val data = HashMap<String, String?>()
                data["text"] = text
                data["start"] = startAt.toString()
                data["end"] = endAt.toString()
                data["word"] = text!!.substring(startAt, endAt)
                invokeMethod("speak.onProgress", data)
            }
        }

        // Requires Android 26 or later
        override fun onRangeStart(utteranceId: String, startAt: Int, endAt: Int, frame: Int) {
            if (!utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                lastProgress = startAt
                super.onRangeStart(utteranceId, startAt, endAt, frame)
                onProgress(utteranceId, startAt, endAt)
            }
        }

        @Deprecated("")
        override fun onError(utteranceId: String) {
            if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                closeParcelFileDescriptor(true)
                if (awaitSynthCompletion) {
                    synth = false
                }
                invokeMethod("synth.onError", "Error from TextToSpeech (synth)")
            } else {
                if (awaitSpeakCompletion) {
                    speaking = false
                }
                invokeMethod("speak.onError", "Error from TextToSpeech (speak)")
            }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                closeParcelFileDescriptor(true)
                if (awaitSynthCompletion) {
                    synth = false
                }
                invokeMethod("synth.onError", "Error from TextToSpeech (synth) - $errorCode")
            } else {
                if (awaitSpeakCompletion) {
                    speaking = false
                }
                invokeMethod("speak.onError", "Error from TextToSpeech (speak) - $errorCode")
            }
        }
    }

    fun speakCompletion(success: Int) {
        speaking = false
        handler!!.post {
            speakResult?.success(success)
            speakResult = null
        }
    }

    fun synthCompletion(success: Int) {
        synth = false
        handler!!.post { synthResult?.success(success) }
    }

    private fun initTextToSpeechClient(
        locale: Locale? = null, engine: String? = null, onInitialized: ((result: kotlin.Result<Int>) -> Unit)? = null
    ) {
        ttsStatus = null;
        val tts = TextToSpeech(
            context,
            TextToSpeech.OnInitListener { status ->
                when (status) {
                    TextToSpeech.SUCCESS -> {
                        val tts = ttsEngineLanguageMap.get(engine)?.get(locale)
                        if (tts == null) {
                            onInitialized?.invoke(kotlin.Result.failure(Error("Failed to find tts engine")))
                            return@OnInitListener
                        }

                        tts.setOnUtteranceProgressListener(utteranceProgressListener)
                        try {
                            if (locale != null && isLanguageAvailable(locale)) {
                                val result = tts.setLanguage(locale)
                                print(result)
                            }
                        } catch (e: NullPointerException) {
                            Log.e(tag, "getDefaultLocale: " + e.message)
                        } catch (e: IllegalArgumentException) {
                            Log.e(tag, "getDefaultLocale: " + e.message)
                        }

                        this.tts = tts
                        ttsEngine = engine ?: tts.defaultEngine
                        ttsLocale = locale ?: tts.defaultVoice.locale
                        if (engine == null || locale == null) {
                            ttsEngineLanguageMap.get(engine)?.let { it.remove(locale) }
                            if (ttsEngineLanguageMap.containsKey(ttsEngine)) {
                                ttsEngineLanguageMap.get(ttsEngine)?.let {
                                    it.plusAssign(ttsLocale to tts)
                                }
                            } else {
                                ttsEngineLanguageMap.set(
                                    ttsEngine,
                                    hashMapOf(ttsLocale to tts)
                                )
                            }
                        }
                        onInitialized?.invoke(kotlin.Result.success(TextToSpeech.SUCCESS))
                    }

                    else -> {
                        onInitialized?.invoke(kotlin.Result.failure(Error("Failed to initialize TextToSpeech with status: $status")))
                    }
                }

                synchronized(this@FlutterTtsPlugin) {
                    ttsStatus = status
                    for (call in pendingMethodCalls) {
                        call.run()
                    }
                    pendingMethodCalls.clear()
                }
            },
            engine,
        )

        if (ttsEngineLanguageMap.isEmpty() || !ttsEngineLanguageMap.containsKey(engine)) {
            ttsEngineLanguageMap.plusAssign(
                (engine ?: ttsEngine) to (hashMapOf((locale ?: ttsLocale) to tts))
            )
            return
        }

        ttsEngineLanguageMap.get(engine)?.let {
            it.plusAssign(locale to tts)
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        // If TTS is still loading
        synchronized(this@FlutterTtsPlugin) {
            if (ttsStatus == null) {
                // Suspend method call until the TTS engine is ready
                val suspendedCall = Runnable { onMethodCall(call, result) }
                pendingMethodCalls.add(suspendedCall)
                return
            }
        }

        when (call.method) {
            "init" ->
                init(call, result)

            "speak" -> {
                var text: String = call.arguments.toString()
                if (pauseText == null) {
                    pauseText = text
                    currentText = pauseText!!
                }
                if (isPaused) {
                    // Ensure the text hasn't changed
                    if (currentText == text) {
                        text = pauseText!!
                    } else {
                        pauseText = text
                        currentText = pauseText!!
                        lastProgress = 0
                    }
                }
                if (speaking) {
                    // If TTS is set to queue mode, allow the utterance to be queued up rather than discarded
                    if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                        result.success(0)
                        return
                    }
                }
                val b = speak(text)
                if (!b) {
                    synchronized(this@FlutterTtsPlugin) {
                        val suspendedCall = Runnable { onMethodCall(call, result) }
                        pendingMethodCalls.add(suspendedCall)
                    }
                    return
                }
                // Only use await speak completion if queueMode is set to QUEUE_FLUSH
                if (awaitSpeakCompletion && queueMode == TextToSpeech.QUEUE_FLUSH) {
                    speaking = true
                    speakResult = result
                } else {
                    result.success(1)
                }
            }

            "awaitSpeakCompletion" -> {
                awaitSpeakCompletion = java.lang.Boolean.parseBoolean(call.arguments.toString())
                result.success(1)
            }

            "awaitSynthCompletion" -> {
                awaitSynthCompletion = java.lang.Boolean.parseBoolean(call.arguments.toString())
                result.success(1)
            }

            "getMaxSpeechInputLength" -> {
                val res = maxSpeechInputLength
                result.success(res)
            }

            "synthesizeToFile" -> {
                val text: String? = call.argument("text")
                if (synth) {
                    result.success(0)
                    return
                }
                val fileName: String? = call.argument("fileName")
                synthesizeToFile(text!!, fileName!!)
                if (awaitSynthCompletion) {
                    synth = true
                    synthResult = result
                } else {
                    result.success(1)
                }
            }

            "pause" -> {
                isPaused = true
                if (pauseText != null) {
                    pauseText = pauseText!!.substring(lastProgress)
                }
                stop()
                result.success(1)
                if (speakResult != null) {
                    speakResult!!.success(0)
                    speakResult = null
                }
            }

            "stop" -> {
                isPaused = false
                pauseText = null
                stop()
                lastProgress = 0
                result.success(1)
                if (speakResult != null) {
                    speakResult!!.success(0)
                    speakResult = null
                }
            }

            "setEngine" -> {
                val engine: String = call.arguments.toString()
                setEngine(engine, result)
            }

            "setSpeechRate" -> {
                val rate: String = call.arguments.toString()
                // To make the FlutterTts API consistent across platforms,
                // Android 1.0 is mapped to flutter 0.5.
                setSpeechRate(rate.toFloat() * 2.0f)
                result.success(1)
            }

            "setVolume" -> {
                val volume: String = call.arguments.toString()
                setVolume(volume.toFloat(), result)
            }

            "setPitch" -> {
                val pitch: String = call.arguments.toString()
                setPitch(pitch.toFloat(), result)
            }

            "setLanguage" -> {
                val language: String = call.arguments.toString()
                setLanguage(language, result)
            }

            "getLanguages" -> getLanguages(result)
            "getVoices" -> getVoices(result)
            "getSpeechRateValidRange" -> getSpeechRateValidRange(result)
            "getEngines" -> getEngines(result)
            "getDefaultEngine" -> getDefaultEngine(result)
            "getDefaultVoice" -> getDefaultVoice(result)
            "setVoice" -> {
                val voice: HashMap<String?, String>? = call.arguments()
                setVoice(voice!!, result)
            }

            "clearVoice" -> clearVoice(result)

            "isLanguageAvailable" -> {
                val language: String = call.arguments.toString()
                val locale: Locale = Locale.forLanguageTag(language)
                result.success(isLanguageAvailable(locale))
            }

            "setSilence" -> {
                val silencems: String = call.arguments.toString()
                this.silencems = silencems.toInt()
            }

            "setSharedInstance" -> result.success(1)
            "isLanguageInstalled" -> {
                val language: String = call.arguments.toString()
                result.success(isLanguageInstalled(language))
            }

            "areLanguagesInstalled" -> {
                val languages: List<String?>? = call.arguments()
                result.success(areLanguagesInstalled(languages!!))
            }

            "setQueueMode" -> {
                val queueMode: String = call.arguments.toString()
                this.queueMode = queueMode.toInt()
                result.success(1)
            }

            else -> result.notImplemented()
        }
    }

    private fun init(call: MethodCall, result: Result) {
        val engine: String? = call.argument("engine")
        val language: String? = call.argument("language")
        var locale: Locale? = if (language != null) Locale.forLanguageTag(language) else null

        val tts = ttsEngineLanguageMap.get(engine ?: ttsEngine)?.get(locale ?: ttsLocale)
        if (tts != null) {
            this.tts = tts
            result.success(1)
            return
        }

        initTextToSpeechClient(locale, engine, {
            it.onSuccess {
                result.success(1)
            }.onFailure {
                result.error("TtsError", it.message ?: "Error", null)
            }
        })
    }

    private fun setSpeechRate(rate: Float) {
        tts!!.setSpeechRate(rate)
    }

    private fun isLanguageAvailable(locale: Locale?): Boolean {
        return tts!!.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
    }

    private fun areLanguagesInstalled(languages: List<String?>): Map<String?, Boolean> {
        val result: MutableMap<String?, Boolean> = HashMap()
        for (language in languages) {
            result[language] = isLanguageInstalled(language)
        }
        return result
    }

    private fun isLanguageInstalled(language: String?): Boolean {
        val locale: Locale = Locale.forLanguageTag(language!!)
        if (isLanguageAvailable(locale)) {
            var voiceToCheck: Voice? = null
            for (v in tts!!.voices) {
                if (v.locale == locale && !v.isNetworkConnectionRequired) {
                    voiceToCheck = v
                    break
                }
            }
            if (voiceToCheck != null) {
                val features: Set<String> = voiceToCheck.features
                return (!features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
            }
        }
        return false
    }

    private fun setEngine(engine: String?, result: Result) {
        val tts = ttsEngineLanguageMap.get(ttsEngine)?.get(ttsLocale)
        if (tts != null) {
            this.tts = tts
            result.success(1)
            return
        }

        initTextToSpeechClient(ttsLocale, engine, {
            it.onSuccess {
                result.success(1)
            }.onFailure {
                result.error("TtsError", it.message ?: "Error", null)
            }
        });
    }

    private fun setLanguage(language: String?, result: Result) {
        val locale: Locale = Locale.forLanguageTag(language!!)
        val tts = ttsEngineLanguageMap.get(ttsEngine)?.get(locale)
        if (tts != null) {
            this.tts = tts
            result.success(1)
            return
        }

        initTextToSpeechClient(locale, ttsEngine, {
            it.onSuccess {
                result.success(1)
            }.onFailure {
                result.error("TtsError", it.message ?: "Error", null)
            }
        })
    }

    private fun setVoice(voice: HashMap<String?, String>, result: Result) {
        for (ttsVoice in tts!!.voices) {
            if (ttsVoice.name == voice["name"] && ttsVoice.locale.toLanguageTag() == voice["locale"]) {
                tts!!.voice = ttsVoice
                result.success(1)
                return
            }
        }
        Log.d(tag, "Voice name not found: $voice")
        result.success(0)
    }

    private fun clearVoice(result: Result) {
        tts!!.voice = tts!!.defaultVoice
        result.success(1)
    }

    private fun setVolume(volume: Float, result: Result) {
        if (volume in (0.0f..1.0f)) {
            bundle!!.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            result.success(1)
        } else {
            Log.d(tag, "Invalid volume $volume value - Range is from 0.0 to 1.0")
            result.success(0)
        }
    }

    private fun setPitch(pitch: Float, result: Result) {
        if (pitch in (0.5f..2.0f)) {
            tts!!.setPitch(pitch)
            result.success(1)
        } else {
            Log.d(tag, "Invalid pitch $pitch value - Range is from 0.5 to 2.0")
            result.success(0)
        }
    }

    private fun getVoices(result: Result) {
        val voices = ArrayList<HashMap<String, String>>()
        try {
            for (voice in tts!!.voices) {
                val voiceMap = HashMap<String, String>()
                voiceMap["name"] = voice.name
                voiceMap["locale"] = voice.locale.toLanguageTag()
                voices.add(voiceMap)
            }
            result.success(voices)
        } catch (e: NullPointerException) {
            Log.d(tag, "getVoices: " + e.message)
            result.success(null)
        }
    }

    private fun getLanguages(result: Result) {
        val locales = ArrayList<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // While this method was introduced in API level 21, it seems that it
                // has not been implemented in the speech service side until API Level 23.
                for (locale in tts!!.availableLanguages) {
                    locales.add(locale.toLanguageTag())
                }
            } else {
                for (locale in Locale.getAvailableLocales()) {
                    if (locale.variant.isEmpty() && isLanguageAvailable(locale)) {
                        locales.add(locale.toLanguageTag())
                    }
                }
            }
        } catch (e: MissingResourceException) {
            Log.d(tag, "getLanguages: " + e.message)
        } catch (e: NullPointerException) {
            Log.d(tag, "getLanguages: " + e.message)
        }
        result.success(locales)
    }

    private fun getEngines(result: Result) {
        val engines = ArrayList<String>()
        try {
            for (engineInfo in tts!!.engines) {
                engines.add(engineInfo.name)
            }
        } catch (e: Exception) {
            Log.d(tag, "getEngines: " + e.message)
        }
        result.success(engines)
    }

    private fun getDefaultEngine(result: Result) {
        val defaultEngine: String? = tts!!.defaultEngine
        result.success(defaultEngine)
    }

    private fun getDefaultVoice(result: Result) {
        val defaultVoice: Voice? = tts!!.defaultVoice
        val voice = HashMap<String, String>()
        if (defaultVoice != null) {
            voice["name"] = defaultVoice.name
            voice["locale"] = defaultVoice.locale.toLanguageTag()
        }
        result.success(voice)
    }

    private fun getSpeechRateValidRange(result: Result) {
        // Valid values available in the android documentation.
        // https://developer.android.com/reference/android/speech/tts/TextToSpeech#setSpeechRate(float)
        // To make the FlutterTts API consistent across platforms,
        // we map Android 1.0 to flutter 0.5 and so on.
        val data = HashMap<String, String>()
        data["min"] = "0"
        data["normal"] = "0.5"
        data["max"] = "1.5"
        data["platform"] = "android"
        result.success(data)
    }

    private fun speak(text: String): Boolean {
        val uuid: String = UUID.randomUUID().toString()
        utterances[uuid] = text
        return try {
            if (ismServiceConnectionUsable(tts)) {
                if (silencems > 0) {
                    tts!!.playSilentUtterance(
                        silencems.toLong(), TextToSpeech.QUEUE_FLUSH, SILENCE_PREFIX + uuid
                    )
                    val result = tts!!.speak(text, TextToSpeech.QUEUE_ADD, bundle, uuid)
                    return result == 0
                } else {
                    val result = tts!!.speak(text, queueMode, bundle, uuid)
                    return result == 0
                }
            } else {
                ttsStatus = null
                initTextToSpeechClient()
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun stop() {
        if (awaitSynthCompletion) synth = false
        if (awaitSpeakCompletion) speaking = false
        tts!!.stop()
    }

    private val maxSpeechInputLength: Int
        get() = TextToSpeech.getMaxSpeechInputLength()

    private fun closeParcelFileDescriptor(isError: Boolean) {
        if (this.parcelFileDescriptor != null) {
            if (isError) {
                this.parcelFileDescriptor!!.closeWithError("Error synthesizing TTS to file")
            } else {
                this.parcelFileDescriptor!!.close()
            }
        }
    }

    private fun synthesizeToFile(text: String, fileName: String) {
        val fullPath: String
        val uuid: String = UUID.randomUUID().toString()
        bundle!!.putString(
            TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, SYNTHESIZE_TO_FILE_PREFIX + uuid
        )

        val result: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val resolver = this.context?.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
            }
            val uri = resolver?.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            this.parcelFileDescriptor = resolver?.openFileDescriptor(uri!!, "rw")
            fullPath = uri?.path + File.separatorChar + fileName

            tts!!.synthesizeToFile(text, bundle!!, parcelFileDescriptor!!, SYNTHESIZE_TO_FILE_PREFIX + uuid)
        } else {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val file = File(musicDir, fileName)
            fullPath = file.path

            tts!!.synthesizeToFile(text, bundle!!, file, SYNTHESIZE_TO_FILE_PREFIX + uuid)
        }

        if (result == TextToSpeech.SUCCESS) {
            Log.d(tag, "Successfully created file : $fullPath")
        } else {
            Log.d(tag, "Failed creating file : $fullPath")
        }
    }

    private fun invokeMethod(method: String, arguments: Any) {
        handler!!.post {
            if (methodChannel != null) methodChannel!!.invokeMethod(
                method, arguments
            )
        }
    }

    private fun ismServiceConnectionUsable(tts: TextToSpeech?): Boolean {
        var isBindConnection = true
        if (tts == null) {
            return false
        }
        val fields: Array<Field> = tts.javaClass.declaredFields
        for (j in fields.indices) {
            fields[j].isAccessible = true
            if ("mServiceConnection" == fields[j].name && "android.speech.tts.TextToSpeech\$Connection" == fields[j].type.name) {
                try {
                    if (fields[j][tts] == null) {
                        isBindConnection = false
                        Log.e(tag, "*******TTS -> mServiceConnection == null*******")
                    }
                } catch (e: IllegalArgumentException) {
                    e.printStackTrace()
                } catch (e: IllegalAccessException) {
                    e.printStackTrace()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return isBindConnection
    }
}
