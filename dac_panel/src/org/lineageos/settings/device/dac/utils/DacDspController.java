package org.lineageos.settings.device.dac.utils;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.DynamicsProcessing;
import android.util.Log;

/**
 * Singleton controller managing DAC-only 10-band DynamicsProcessing EQ on output mix 0.
 * Active strictly when wired headset is connected, Hi-Fi Quad DAC is ON,
 * Bit-Perfect is OFF, and DAC EQ is ON.
 */
public class DacDspController {

    private static final String TAG = "DacDspController";
    private static final int BAND_COUNT = Constants.EQ_BAND_FREQS.length;

    private static DynamicsProcessing sDynamicsProcessing;

    public static synchronized void sync(Context context) {
        if (context == null) {
            return;
        }
        try {
            if (!QuadDAC.dac_service_available) {
                QuadDAC.initialize();
            }
            AudioManager am = context.getSystemService(AudioManager.class);
            boolean headsetPlugged = am != null && am.isWiredHeadsetOn();
            boolean dacOn = headsetPlugged && QuadDAC.isEnabled();
            boolean bitPerfect = dacOn && QuadDAC.isBitPerfectEnabled();
            boolean eqOn = dacOn && !bitPerfect && QuadDAC.isEqEnabled();

            if (!eqOn) {
                release();
                return;
            }

            ensureEffect();
            if (sDynamicsProcessing == null) {
                return;
            }

            DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, BAND_COUNT);
            for (int i = 0; i < BAND_COUNT; i++) {
                float cutoff = (i < BAND_COUNT - 1)
                        ? (Constants.EQ_BAND_FREQS[i] + Constants.EQ_BAND_FREQS[i + 1]) / 2.0f
                        : 20000.0f;
                float gain = (float) QuadDAC.getEqBandLevel(i);
                DynamicsProcessing.EqBand band = new DynamicsProcessing.EqBand(true, cutoff, gain);
                eq.setBand(i, band);
            }
            sDynamicsProcessing.setPreEqAllChannelsTo(eq);
            sDynamicsProcessing.setEnabled(true);
            Log.d(TAG, "Applied 10-band DAC EQ via DynamicsProcessing");
        } catch (Exception e) {
            Log.e(TAG, "sync failed", e);
        }
    }

    public static synchronized void release() {
        if (sDynamicsProcessing != null) {
            try {
                sDynamicsProcessing.setEnabled(false);
                sDynamicsProcessing.release();
            } catch (Exception ignored) {
            }
            sDynamicsProcessing = null;
        }
    }

    private static void ensureEffect() {
        if (sDynamicsProcessing != null) {
            return;
        }
        try {
            DynamicsProcessing.Config.Builder builder = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,
                    true,
                    BAND_COUNT,
                    false,
                    0,
                    false,
                    0,
                    false
            );
            sDynamicsProcessing = new DynamicsProcessing(Integer.MAX_VALUE, 0, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "Failed to create DynamicsProcessing", e);
            sDynamicsProcessing = null;
        }
    }
}
