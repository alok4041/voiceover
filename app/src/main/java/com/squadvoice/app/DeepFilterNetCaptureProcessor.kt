package com.squadvoice.app

import android.content.Context
import io.livekit.android.audio.AudioProcessorInterface
import com.rikorose.deepfilternet.NativeDeepFilterNet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Adapts LiveKit's 48 kHz float capture frames to DeepFilterNet's mono PCM16 frames. */
internal class DeepFilterNetCaptureProcessor private constructor(
    private val nativeFilter: NativeDeepFilterNet,
    private val frameLengthBytes: Int,
    private val onProcessingFailure: (String) -> Unit,
) : AudioProcessorInterface {
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val frameBuffer = ByteBuffer.allocateDirect(frameLengthBytes)
        .order(ByteOrder.LITTLE_ENDIAN)

    @Volatile
    private var sampleRateHz = 0

    @Volatile
    private var channelCount = 0

    override fun isEnabled(): Boolean =
        !closed.get() && !failed.get() && sampleRateHz == SUPPORTED_SAMPLE_RATE_HZ && channelCount == 1

    val hasFailed: Boolean
        get() = failed.get()

    override fun getName(): String = "DeepFilterNet"

    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {
        this.sampleRateHz = sampleRateHz
        channelCount = numChannels
        if (sampleRateHz != SUPPORTED_SAMPLE_RATE_HZ || numChannels != 1) {
            fail("DeepFilterNet needs 48 kHz mono microphone audio; this device's capture format is unsupported.")
        }
    }

    override fun resetAudioProcessing(newRate: Int) {
        sampleRateHz = newRate
        if (newRate != SUPPORTED_SAMPLE_RATE_HZ) {
            fail("DeepFilterNet needs 48 kHz audio. Normal microphone processing is still available.")
        }
    }

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (!isEnabled()) return

        val inputStart = buffer.position()
        val inputBytes = numFrames * FLOAT_BYTES
        if (numFrames <= 0 || buffer.remaining() < inputBytes || !buffer.isDirect) {
            fail("DeepFilterNet received an unsupported microphone frame. Normal microphone processing is still available.")
            return
        }

        if (numFrames * PCM16_BYTES != frameLengthBytes) {
            fail("DeepFilterNet's frame size does not match this device's microphone stream. Normal processing is still available.")
            return
        }

        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            frameBuffer.clear()
            for (index in 0 until numFrames) {
                val floatSample = buffer.getFloat(inputStart + index * FLOAT_BYTES).coerceIn(-1f, 1f)
                frameBuffer.putShort((floatSample * Short.MAX_VALUE).roundToInt().toShort())
            }
            frameBuffer.flip()
            val processingMetric = nativeFilter.processFrame(frameBuffer)
            if (!processingMetric.isFinite()) {
                fail("DeepFilterNet returned an invalid audio frame. Normal microphone processing is still available.")
                return
            }

            for (index in 0 until numFrames) {
                val pcmSample = frameBuffer.getShort(index * PCM16_BYTES)
                buffer.putFloat(inputStart + index * FLOAT_BYTES, pcmSample / 32768f)
            }
        } catch (_: Exception) {
            fail("DeepFilterNet could not process this microphone frame. Normal microphone processing is still available.")
        }
    }

    fun release() {
        if (closed.compareAndSet(false, true)) {
            nativeFilter.release()
        }
    }

    private fun fail(message: String) {
        if (failed.compareAndSet(false, true)) onProcessingFailure(message)
    }

    companion object {
        private const val SUPPORTED_SAMPLE_RATE_HZ = 48_000
        private const val FLOAT_BYTES = Float.SIZE_BYTES
        private const val PCM16_BYTES = Short.SIZE_BYTES
        private const val MODEL_LOAD_TIMEOUT_MS = 60_000L

        suspend fun create(
            context: Context,
            onProcessingFailure: (String) -> Unit,
        ): DeepFilterNetCaptureProcessor = withContext(Dispatchers.IO) {
            val nativeFilter = NativeDeepFilterNet(context.applicationContext)
            val modelReady = CompletableDeferred<Unit>()
            nativeFilter.onModelLoaded {
                modelReady.complete(Unit)
            }

            try {
                withTimeout(MODEL_LOAD_TIMEOUT_MS) { modelReady.await() }
                check(nativeFilter.setAttenuationLimit(30f)) { "DeepFilterNet could not set its noise reduction level." }
                val frameLengthBytes = nativeFilter.frameLength.toInt()
                check(frameLengthBytes > 0 && frameLengthBytes % PCM16_BYTES == 0) {
                    "DeepFilterNet returned an invalid audio frame size."
                }
                DeepFilterNetCaptureProcessor(nativeFilter, frameLengthBytes, onProcessingFailure)
            } catch (error: kotlinx.coroutines.CancellationException) {
                nativeFilter.release()
                throw error
            } catch (error: Throwable) {
                nativeFilter.release()
                throw IllegalStateException(
                    "DeepFilterNet could not initialize. Normal microphone processing is still available.",
                    error,
                )
            }
        }
    }
}
