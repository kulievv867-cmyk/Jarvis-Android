package com.devin.jarvis.voice;

/** Simple feedback delay line with linear interpolation. */
public class DelayLine {
    private final float[] buffer;
    private int writeIdx;
    private final int size;

    public DelayLine(int maxDelaySamples) {
        size = nextPow2(Math.max(8, maxDelaySamples));
        buffer = new float[size];
    }

    private static int nextPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    public void reset() {
        for (int i = 0; i < buffer.length; i++) buffer[i] = 0f;
        writeIdx = 0;
    }

    public float read(float delaySamples) {
        if (delaySamples < 1f) delaySamples = 1f;
        if (delaySamples > size - 2) delaySamples = size - 2;
        float readPos = writeIdx - delaySamples;
        while (readPos < 0) readPos += size;
        int i0 = (int) readPos;
        int i1 = (i0 + 1) & (size - 1);
        float frac = readPos - i0;
        return buffer[i0] * (1f - frac) + buffer[i1] * frac;
    }

    public void write(float v) {
        buffer[writeIdx] = v;
        writeIdx = (writeIdx + 1) & (size - 1);
    }
}
