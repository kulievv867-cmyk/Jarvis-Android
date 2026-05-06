package com.devin.jarvis.voice;

/**
 * Streaming granular pitch shifter using two crossfaded grains.
 * Reads from past samples in a ring buffer; output is produced sample-by-sample,
 * one output sample per input sample (no time stretch).
 *
 * Pitch ratio: 1.0 = no shift, 2.0 = +1 octave, 0.5 = -1 octave.
 */
public class PitchShifter {

    private final int grainLen;
    private final int halfGrain;
    private final int ringMask;
    private final float[] ring;
    private int writeIdx = 0;

    private double readPos1, readPos2;
    private int age1, age2;

    private float ratio = 1f;

    public PitchShifter(int grainLen, int maxRatioPow2) {
        // Round grainLen to power of two to make ring mask easy.
        int g = 1;
        while (g < grainLen) g <<= 1;
        this.grainLen = g;
        this.halfGrain = g / 2;
        // Ring buffer must hold at least grainLen * maxRatio worth of past samples.
        int ringSize = 1;
        int needed = g * Math.max(2, maxRatioPow2);
        while (ringSize < needed) ringSize <<= 1;
        this.ring = new float[ringSize];
        this.ringMask = ringSize - 1;
        reset();
    }

    public void setRatio(float r) {
        if (r < 0.25f) r = 0.25f;
        if (r > 4.0f) r = 4.0f;
        this.ratio = r;
    }

    public void reset() {
        for (int i = 0; i < ring.length; i++) ring[i] = 0f;
        writeIdx = 0;
        age1 = 0;
        age2 = halfGrain;
        readPos1 = 0;
        readPos2 = 0;
    }

    private float readInterp(double pos) {
        // pos is an absolute write-time index (writeIdx is the next-to-write).
        // We always read from past, so the sample at index k corresponds to ring[k & mask].
        double rp = pos;
        // Wrap into [0, ring.length)
        rp = rp - Math.floor(rp / ring.length) * ring.length;
        int i0 = (int) rp;
        int i1 = (i0 + 1) & ringMask;
        float frac = (float) (rp - i0);
        return ring[i0] * (1f - frac) + ring[i1] * frac;
    }

    private float window(int age) {
        // Hann window
        return 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * age / grainLen);
    }

    public float process(float in) {
        ring[writeIdx] = in;
        // Use writeIdx as an absolute index too (it's modular by ring.length anyway)
        float s1 = readInterp(readPos1);
        float s2 = readInterp(readPos2);

        float w1 = window(age1);
        float w2 = window(age2);

        float out = s1 * w1 + s2 * w2;

        // Advance read positions at rate `ratio`; write position advances by 1.
        readPos1 += ratio;
        readPos2 += ratio;
        age1++;
        age2++;

        writeIdx = (writeIdx + 1) & ringMask;

        // Restart grains when expired so they read from current "past window".
        // Start position = (writeIdx - grainLen * ratio) so the grain finishes at writeIdx by end.
        if (age1 >= grainLen) {
            age1 = 0;
            double sp = (double) writeIdx - (double) grainLen * (double) ratio;
            while (sp < 0) sp += ring.length;
            while (sp >= ring.length) sp -= ring.length;
            readPos1 = sp;
        }
        if (age2 >= grainLen) {
            age2 = 0;
            double sp = (double) writeIdx - (double) grainLen * (double) ratio;
            while (sp < 0) sp += ring.length;
            while (sp >= ring.length) sp -= ring.length;
            readPos2 = sp;
        }
        return out;
    }
}
