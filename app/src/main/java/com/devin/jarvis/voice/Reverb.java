package com.devin.jarvis.voice;

/**
 * Compact Schroeder reverb: 4 parallel feedback combs into 2 series allpasses.
 * Tuned for a small "workshop" room (~150 ms tail), not a hall.
 */
public class Reverb {

    private final DelayLine[] combs = new DelayLine[4];
    private final float[] combDelay;
    private final float[] combFb;
    private final float[] dampZ = new float[4];
    private final float dampCoef = 0.3f;

    private final DelayLine ap1, ap2;
    private final float ap1Delay, ap2Delay;
    private static final float AP_GAIN = 0.5f;

    public Reverb(int sr) {
        float[] msComb = {19f, 23f, 29f, 31f};
        combFb = new float[]{0.74f, 0.72f, 0.70f, 0.68f};
        combDelay = new float[combs.length];
        for (int i = 0; i < combs.length; i++) {
            float d = sr * msComb[i] / 1000f;
            combDelay[i] = d;
            combs[i] = new DelayLine((int) Math.ceil(d) + 4);
        }
        ap1Delay = sr * 5.3f / 1000f;
        ap2Delay = sr * 1.7f / 1000f;
        ap1 = new DelayLine((int) Math.ceil(ap1Delay) + 4);
        ap2 = new DelayLine((int) Math.ceil(ap2Delay) + 4);
    }

    public float process(float in) {
        float sum = 0f;
        for (int i = 0; i < combs.length; i++) {
            float out = combs[i].read(combDelay[i]);
            dampZ[i] = out * (1f - dampCoef) + dampZ[i] * dampCoef;
            combs[i].write(in + dampZ[i] * combFb[i]);
            sum += out;
        }
        sum *= 0.25f;
        sum = allpass(ap1, ap1Delay, sum);
        sum = allpass(ap2, ap2Delay, sum);
        return sum;
    }

    private float allpass(DelayLine d, float delay, float in) {
        float dly = d.read(delay);
        float w = in + dly * AP_GAIN;
        d.write(w);
        return -w * AP_GAIN + dly;
    }
}
