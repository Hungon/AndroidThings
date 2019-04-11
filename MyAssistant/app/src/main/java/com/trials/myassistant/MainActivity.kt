/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.trials.myassistant

import android.app.Activity
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder.AudioSource
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import com.example.androidthings.assistant.shared.BoardDefaults
import com.example.androidthings.assistant.shared.Credentials
import com.example.androidthings.assistant.shared.MyDevice
import com.google.android.things.contrib.driver.button.Button
import com.google.android.things.contrib.driver.voicehat.Max98357A
import com.google.android.things.contrib.driver.voicehat.VoiceHat
import com.google.android.things.pio.Gpio
import com.google.android.things.pio.PeripheralManager
import com.google.assistant.embedded.v1alpha2.AssistConfig
import com.google.assistant.embedded.v1alpha2.AssistRequest
import com.google.assistant.embedded.v1alpha2.AssistResponse
import com.google.assistant.embedded.v1alpha2.AudioInConfig
import com.google.assistant.embedded.v1alpha2.AudioOutConfig
import com.google.assistant.embedded.v1alpha2.DeviceConfig
import com.google.assistant.embedded.v1alpha2.DeviceConfig.Builder
import com.google.assistant.embedded.v1alpha2.DialogStateIn
import com.google.assistant.embedded.v1alpha2.DialogStateInOrBuilder
import com.google.assistant.embedded.v1alpha2.EmbeddedAssistantGrpc
import com.google.assistant.embedded.v1alpha2.SpeechRecognitionResult
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.auth.MoreCallCredentials
import io.grpc.stub.StreamObserver
import org.json.JSONException

import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayList

class MainActivity : Activity(), Button.OnButtonEventListener {

    private var mOutputBufferSize: Int = 0
    // gRPC client and stream observers.
    private var mAssistantService: EmbeddedAssistantGrpc.EmbeddedAssistantStub? = null
    private var mAssistantRequestObserver: StreamObserver<AssistRequest>? = null

    private val mAssistantResponseObserver = object : StreamObserver<AssistResponse> {
        override fun onNext(value: AssistResponse) {
            value.eventType?.let {
                Log.d(TAG, "converse response event: $it")
            }
            val speechResultList = value.speechResultsList
            if (speechResultList != null && speechResultList.size > 0) {
                for (result in speechResultList) {
                    val spokenRequestText = result.transcript
                    if (!spokenRequestText.isEmpty()) {
                        Log.i(TAG, "assistant request text: $spokenRequestText")
                        mMainHandler?.post { mAssistantRequestsAdapter!!.add(spokenRequestText) }
                    }
                }
            }
            value.dialogStateOut?.let {
                val volume = it.volumePercentage
                if (volume > 0) {
                    volumePercentage = volume
                    Log.i(TAG, "assistant volume changed: $volumePercentage")
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volumePercentage, 0)
                }
                mConversationState = it.conversationState
            }
            value.audioOut?.let {
                val audioData = ByteBuffer.wrap(it.audioData.toByteArray())
                Log.d(TAG, "converse audio size: " + audioData.remaining())
                mAssistantResponses.add(audioData)
            }
        }

        override fun onError(t: Throwable) {
            Log.e(TAG, "converse error:", t)
        }

        override fun onCompleted() {
            mAudioTrack = AudioTrack.Builder()
                .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                .setBufferSizeInBytes(mOutputBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (mAudioOutputDevice != null) {
                mAudioTrack!!.preferredDevice = mAudioOutputDevice
            }
            mAudioTrack?.play()
            mDac?.let {
                try {
                    it.setSdMode(Max98357A.SD_MODE_LEFT)
                } catch (e: IOException) {
                    Log.e(TAG, "unable to modify dac trigger", e)
                }
            }
            for (audioData in mAssistantResponses) {
                Log.d(TAG, "Playing a bit of audio")
                mAudioTrack?.write(
                    audioData, audioData.remaining(),
                    AudioTrack.WRITE_BLOCKING
                )
            }
            mAssistantResponses.clear()
            mAudioTrack?.stop()
            mDac?.let {
                try {
                    it.setSdMode(Max98357A.SD_MODE_SHUTDOWN)
                } catch (e: IOException) {
                    Log.e(TAG, "unable to modify dac trigger", e)
                }
            }

            Log.i(TAG, "assistant response finished")
            mLed?.let {
                try {
                    it.setValue(false)
                } catch (e: IOException) {
                    Log.e(TAG, "error turning off LED:", e)
                }
            }
        }
    }

    // Audio playback and recording objects.
    private var mAudioTrack: AudioTrack? = null
    private var mAudioRecord: AudioRecord? = null
    private var volumePercentage = 50
    private var audioManager: AudioManager? = null

    // Audio routing configuration: use default routing.
    private var mAudioInputDevice: AudioDeviceInfo? = null
    private var mAudioOutputDevice: AudioDeviceInfo? = null

    // Hardware peripherals.
    private var mButton: Button? = null
    private var mLed: Gpio? = null
    private var mDac: Max98357A? = null

    // Assistant Thread and Runnables implementing the push-to-talk functionality.
    private var mConversationState: ByteString? = null
    private var mAssistantThread: HandlerThread? = null
    private var mAssistantHandler: Handler? = null
    private val mAssistantResponses = ArrayList<ByteBuffer>()
    private val mStartAssistantRequest = Runnable {
        Log.i(TAG, "starting assistant request")
        mAudioRecord?.startRecording()
        mAssistantRequestObserver = mAssistantService?.assist(mAssistantResponseObserver)
        val converseConfigBuilder = AssistConfig.newBuilder()
            .setAudioInConfig(ASSISTANT_AUDIO_REQUEST_CONFIG)
            .setAudioOutConfig(
                AudioOutConfig.newBuilder()
                    .setEncoding(ENCODING_OUTPUT)
                    .setSampleRateHertz(SAMPLE_RATE)
                    .setVolumePercentage(volumePercentage)
                    .build()
            )
            .setDeviceConfig(
                DeviceConfig.newBuilder()
                    .setDeviceModelId(MyDevice.MODEL_ID)
                    .setDeviceId(MyDevice.INSTANCE_ID)
                    .build()
            )

        val dialogStateInBuilder = DialogStateIn.newBuilder()
            .setLanguageCode(MyDevice.LANGUAGE_CODE)
        if (mConversationState != null) {
            dialogStateInBuilder.conversationState = mConversationState
        }
        converseConfigBuilder.dialogStateIn = dialogStateInBuilder.build()
        mAssistantRequestObserver?.onNext(
            AssistRequest.newBuilder()
                .setConfig(converseConfigBuilder.build())
                .build()
        )
        mAssistantHandler?.post(mStreamAssistantRequest)
    }
    private val mStreamAssistantRequest = object : Runnable {
        override fun run() {
            val audioData = ByteBuffer.allocateDirect(SAMPLE_BLOCK_SIZE)
            if (mAudioInputDevice != null) {
                mAudioRecord?.preferredDevice = mAudioInputDevice
            }
            val result = mAudioRecord?.read(audioData, audioData.capacity(), AudioRecord.READ_BLOCKING) ?: 0
            if (result < 0) {
                Log.e(TAG, "error reading from audio stream: $result")
                return
            }
            Log.d(TAG, "streaming ConverseRequest: $result")
            mAssistantRequestObserver?.onNext(
                AssistRequest.newBuilder()
                    .setAudioIn(ByteString.copyFrom(audioData))
                    .build()
            )
            mAssistantHandler?.post(this)
        }
    }
    private val mStopAssistantRequest = Runnable {
        Log.i(TAG, "ending assistant request")
        mAssistantHandler?.removeCallbacks(mStreamAssistantRequest)
        mAssistantRequestObserver?.onCompleted()
        mAssistantRequestObserver = null
        mAudioRecord?.stop()
        mAudioTrack?.play()
    }
    private var mMainHandler: Handler? = null

    // List & adapter to store and display the history of Assistant Requests.
    private val mAssistantRequests = ArrayList<String>()
    private var mAssistantRequestsAdapter: ArrayAdapter<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "starting assistant demo")

        setContentView(R.layout.activity_main)
        val assistantRequestsListView = findViewById<ListView>(R.id.assistantRequestsListView)
        mAssistantRequestsAdapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            mAssistantRequests
        )
        assistantRequestsListView.adapter = mAssistantRequestsAdapter
        mMainHandler = Handler(mainLooper)

        mAssistantThread = HandlerThread("assistantThread")
        mAssistantThread!!.start()
        mAssistantHandler = Handler(mAssistantThread!!.looper)

        // Use I2S with the Voice HAT.
        if (USE_VOICEHAT_DAC) {
            Log.d(TAG, "enumerating devices")
            mAudioInputDevice = findAudioDevice(
                AudioManager.GET_DEVICES_INPUTS,
                AudioDeviceInfo.TYPE_BUS
            )
            if (mAudioInputDevice == null) {
                Log.e(TAG, "failed to find preferred audio input device, using default")
            }
            mAudioOutputDevice = findAudioDevice(
                AudioManager.GET_DEVICES_OUTPUTS,
                AudioDeviceInfo.TYPE_BUS
            )
            if (mAudioOutputDevice == null) {
                Log.e(TAG, "failed to find preferred audio output device, using default")
            }
        }

        try {
            if (USE_VOICEHAT_DAC) {
                Log.i(TAG, "initializing DAC trigger")
                mDac = VoiceHat.openDac()
                mDac!!.setSdMode(Max98357A.SD_MODE_SHUTDOWN)

                mButton = VoiceHat.openButton()
                mLed = VoiceHat.openLed()
            } else {
                mButton = Button(
                    BoardDefaults.getGPIOForButton(),
                    Button.LogicState.PRESSED_WHEN_LOW
                )
                mLed = PeripheralManager.getInstance().openGpio(BoardDefaults.getGPIOForLED())
            }

            mButton?.let {
                it.setDebounceDelay(BUTTON_DEBOUNCE_DELAY_MS.toLong())
                it.setOnButtonEventListener(this)
            }

            mLed!!.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW)
            mLed!!.setActiveType(Gpio.ACTIVE_HIGH)
        } catch (e: IOException) {
            Log.e(TAG, "error configuring peripherals:", e)
            return
        }

        audioManager = this.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        Log.i(TAG, "setting volume to: $volumePercentage")
        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volumePercentage, 0)
        mOutputBufferSize = AudioTrack.getMinBufferSize(
            AUDIO_FORMAT_OUT_MONO.sampleRate,
            AUDIO_FORMAT_OUT_MONO.channelMask,
            AUDIO_FORMAT_OUT_MONO.encoding
        )
        mAudioTrack = AudioTrack.Builder()
            .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
            .setBufferSizeInBytes(mOutputBufferSize)
            .build()
        mAudioTrack!!.play()
        val inputBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_FORMAT_STEREO.sampleRate,
            AUDIO_FORMAT_STEREO.channelMask,
            AUDIO_FORMAT_STEREO.encoding
        )
        mAudioRecord = AudioRecord.Builder()
            .setAudioSource(AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AUDIO_FORMAT_IN_MONO)
            .setBufferSizeInBytes(inputBufferSize)
            .build()

        val channel = ManagedChannelBuilder.forTarget(ASSISTANT_ENDPOINT).build()
        try {
            mAssistantService = EmbeddedAssistantGrpc.newStub(channel)
                .withCallCredentials(
                    MoreCallCredentials.from(
                        Credentials.fromResource(this, R.raw.credential)
                    )
                )
        } catch (e: IOException) {
            Log.e(TAG, "error creating assistant service:", e)
        } catch (e: JSONException) {
            Log.e(TAG, "error creating assistant service:", e)
        }

    }

    private fun findAudioDevice(deviceFlag: Int, deviceType: Int): AudioDeviceInfo? {
        val manager = this.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val adis = manager.getDevices(deviceFlag)
        for (adi in adis) {
            if (adi.type == deviceType) {
                return adi
            }
        }
        return null
    }

    override fun onButtonEvent(button: Button, pressed: Boolean) {
        try {
            if (mLed != null) {
                mLed!!.value = pressed
            }
        } catch (e: IOException) {
            Log.d(TAG, "error toggling LED:", e)
        }

        if (pressed) {
            mAssistantHandler!!.post(mStartAssistantRequest)
        } else {
            mAssistantHandler!!.post(mStopAssistantRequest)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "destroying assistant demo")
        mAudioRecord?.stop()
        mAudioRecord = null
        mAudioTrack?.stop()
        mAudioTrack = null
        try {
            mLed?.close()
        } catch (e: IOException) {
            Log.w(TAG, "error closing LED", e)
        }
        mLed = null
        try {
            mButton?.close()
        } catch (e: IOException) {
            Log.w(TAG, "error closing button", e)
        }
        mButton = null
        try {
            mDac?.close()
        } catch (e: IOException) {
            Log.w(TAG, "error closing voice hat trigger", e)
        }
        mDac = null
        mAssistantHandler?.post { mAssistantHandler?.removeCallbacks(mStreamAssistantRequest) }
        mAssistantThread?.quitSafely()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        // Peripheral and drivers constants.
        private const val USE_VOICEHAT_DAC = false
        private const val BUTTON_DEBOUNCE_DELAY_MS = 20

        // Audio constants.
        private const val SAMPLE_RATE = 16000
        private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private val ENCODING_INPUT = AudioInConfig.Encoding.LINEAR16
        private val ENCODING_OUTPUT = AudioOutConfig.Encoding.LINEAR16
        private val ASSISTANT_AUDIO_REQUEST_CONFIG = AudioInConfig.newBuilder()
            .setEncoding(ENCODING_INPUT)
            .setSampleRateHertz(SAMPLE_RATE)
            .build()
        private val ASSISTANT_AUDIO_RESPONSE_CONFIG = AudioOutConfig.newBuilder()
            .setEncoding(ENCODING_OUTPUT)
            .setSampleRateHertz(SAMPLE_RATE)
            .build()
        private val AUDIO_FORMAT_STEREO = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .build()
        private val AUDIO_FORMAT_OUT_MONO = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .build()
        private val AUDIO_FORMAT_IN_MONO = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .build()
        private val SAMPLE_BLOCK_SIZE = 1024

        // Google Assistant API constants.
        private val ASSISTANT_ENDPOINT = "embeddedassistant.googleapis.com"
    }
}
