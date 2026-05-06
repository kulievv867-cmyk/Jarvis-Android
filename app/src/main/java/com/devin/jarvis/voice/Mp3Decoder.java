package com.devin.jarvis.voice;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Decodes MP3 bytes (as produced by {@link EdgeTts}) into raw 16-bit PCM
 * using Android's built-in {@link MediaCodec} pipeline.
 *
 * The output is stored as float samples in [-1, 1] alongside the decoded
 * sample rate so the caller can feed it straight into the DSP chain.
 */
public class Mp3Decoder {

    private static final String TAG = "Jarvis.Mp3Decoder";

    public static class Result {
        public final float[] samples;
        public final int sampleRate;
        public final int channels;
        Result(float[] samples, int sampleRate, int channels) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    public Result decode(byte[] mp3Bytes, File scratchDir) throws IOException {
        if (mp3Bytes == null || mp3Bytes.length == 0) {
            return new Result(new float[0], 24000, 1);
        }
        // MediaExtractor requires a file-or-FD source; write to a temp file.
        File tmp = File.createTempFile("edge", ".mp3", scratchDir);
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(mp3Bytes);
            }
            return decodeFile(tmp);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private Result decodeFile(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(file.getAbsolutePath());

        int audioTrack = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat tf = extractor.getTrackFormat(i);
            String mime = tf.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i;
                format = tf;
                break;
            }
        }
        if (audioTrack < 0 || format == null) {
            extractor.release();
            throw new IOException("no audio track in MP3");
        }
        extractor.selectTrack(audioTrack);

        String mime = format.getString(MediaFormat.KEY_MIME);
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        ByteArrayOutputStream pcmBuf = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        long timeoutUs = 10_000;

        while (!outputDone) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(timeoutUs);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    if (inBuf == null) continue;
                    int sz = extractor.readSampleData(inBuf, 0);
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long pts = extractor.getSampleTime();
                        codec.queueInputBuffer(inIdx, 0, sz, pts, 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
            if (outIdx >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0) {
                    byte[] chunk = new byte[info.size];
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    outBuf.get(chunk);
                    pcmBuf.write(chunk, 0, chunk.length);
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFmt = codec.getOutputFormat();
                if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
                if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
        }

        try { codec.stop(); } catch (Exception ignored) {}
        try { codec.release(); } catch (Exception ignored) {}
        extractor.release();

        byte[] pcm = pcmBuf.toByteArray();
        // Convert little-endian 16-bit PCM to mono float [-1, 1]
        int frames = pcm.length / 2 / Math.max(channels, 1);
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            int l = (pcm[i * 2 * channels] & 0xFF) | ((pcm[i * 2 * channels + 1]) << 8);
            float v = l / 32768f;
            if (channels == 2) {
                int r = (pcm[i * 2 * channels + 2] & 0xFF) | ((pcm[i * 2 * channels + 3]) << 8);
                v = (v + r / 32768f) * 0.5f;
            }
            samples[i] = v;
        }
        return new Result(samples, sampleRate, 1);
    }
}
