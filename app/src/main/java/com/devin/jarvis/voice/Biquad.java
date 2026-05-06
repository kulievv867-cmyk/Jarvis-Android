package com.devin.jarvis.voice;

/**
 * Direct Form II Transposed biquad. Coefficients computed from RBJ cookbook.
 */
public class Biquad {
    private float b0, b1, b2, a1, a2;
    private float z1, z2;

    public void setLowpass(float sampleRate, float freq, float q) {
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cosw = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double b0n = (1.0 - cosw) / 2.0;
        double b1n = 1.0 - cosw;
        double b2n = (1.0 - cosw) / 2.0;
        double a0 = 1.0 + alpha;
        double a1n = -2.0 * cosw;
        double a2n = 1.0 - alpha;
        setCoeffs(b0n, b1n, b2n, a0, a1n, a2n);
    }

    public void setHighpass(float sampleRate, float freq, float q) {
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cosw = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double b0n = (1.0 + cosw) / 2.0;
        double b1n = -(1.0 + cosw);
        double b2n = (1.0 + cosw) / 2.0;
        double a0 = 1.0 + alpha;
        double a1n = -2.0 * cosw;
        double a2n = 1.0 - alpha;
        setCoeffs(b0n, b1n, b2n, a0, a1n, a2n);
    }

    public void setBandpass(float sampleRate, float freq, float q) {
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cosw = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double b0n = alpha;
        double b1n = 0.0;
        double b2n = -alpha;
        double a0 = 1.0 + alpha;
        double a1n = -2.0 * cosw;
        double a2n = 1.0 - alpha;
        setCoeffs(b0n, b1n, b2n, a0, a1n, a2n);
    }

    public void setPeak(float sampleRate, float freq, float q, float gainDb) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cosw = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double b0n = 1.0 + alpha * A;
        double b1n = -2.0 * cosw;
        double b2n = 1.0 - alpha * A;
        double a0 = 1.0 + alpha / A;
        double a1n = -2.0 * cosw;
        double a2n = 1.0 - alpha / A;
        setCoeffs(b0n, b1n, b2n, a0, a1n, a2n);
    }

    private void setCoeffs(double b0n, double b1n, double b2n, double a0, double a1n, double a2n) {
        b0 = (float) (b0n / a0);
        b1 = (float) (b1n / a0);
        b2 = (float) (b2n / a0);
        a1 = (float) (a1n / a0);
        a2 = (float) (a2n / a0);
    }

    public void reset() {
        z1 = 0;
        z2 = 0;
    }

    public float process(float in) {
        float out = b0 * in + z1;
        z1 = b1 * in - a1 * out + z2;
        z2 = b2 * in - a2 * out;
        return out;
    }
}
