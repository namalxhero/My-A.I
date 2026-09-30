package com.nipuna.voicesentry

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/** Turns a chunk of speech into a voiceprint embedding (who is speaking). */
class SpeakerModel(ctx: Context) {
    private val ex = SpeakerEmbeddingExtractor(
        ctx.assets,
        SpeakerEmbeddingExtractorConfig(
            model = "speaker.onnx",
            numThreads = 1,
            debug = false,
            provider = "cpu",
        ),
    )

    fun embed(samples: FloatArray): FloatArray {
        val s = ex.createStream()
        s.acceptWaveform(samples, Audio.SR)
        s.inputFinished()
        val e = ex.compute(s)
        s.release()
        return normalize(e)
    }

    fun release() {
        try { ex.release() } catch (_: Throwable) {}
    }
}

/** Offline speech-to-text (Whisper tiny.en) for short command clips. */
class AsrModel(ctx: Context) {
    private val rec = OfflineRecognizer(
        ctx.assets,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = Audio.SR, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "whisper/enc.onnx",
                    decoder = "whisper/dec.onnx",
                    language = "en",
                    task = "transcribe",
                ),
                tokens = "whisper/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    fun transcribe(samples: FloatArray): String {
        val s = rec.createStream()
        s.acceptWaveform(samples, Audio.SR)
        rec.decode(s)
        val t = rec.getResult(s).text
        s.release()
        return t.trim()
    }

    fun release() {
        try { rec.release() } catch (_: Throwable) {}
    }
}
