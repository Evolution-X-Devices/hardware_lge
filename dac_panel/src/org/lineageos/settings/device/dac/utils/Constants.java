package org.lineageos.settings.device.dac.utils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class Constants {

    public static final String DAC_SWITCH_KEY = "quaddac_switch";
    public static final String SOUND_PRESET_KEY = "sound_preset_dropdown";
    public static final String DIGITAL_FILTER_KEY = "digital_filter_dropdown";
    public static final String BALANCE_KEY = "balance";
    public static final String RESET_COEFFICIENTS_KEY = "reset_coefficients";
    public static final String HIFI_DOP_KEY = "hifi_dop_dropdown";
    public static final String HIFI_MODE_KEY = "hifi_mode_dropdown";
    public static final String AVC_VOLUME_KEY = "avc_volume_seekbar";

    public static final String AUDIO_64BIT_KEY = "audio_64bit_switch";
    public static final String DSD_REMASTER_KEY = "dsd_remaster_dropdown";
    public static final String BIT_PERFECT_KEY = "bit_perfect_switch";
    public static final String SAMPLE_RATE_KEY = "sample_rate_dropdown";

    public static final String EQ_SWITCH_KEY = "eq_switch";
    public static final String EQ_PRESET_KEY = "eq_preset_dropdown";
    public static final String EQ_LOAD_PRESET_KEY = "eq_load_preset";
    public static final String EQ_SAVE_PRESET_KEY = "eq_save_preset";
    public static final String EQ_RESET_KEY = "eq_reset";
    public static final String[] EQ_BAND_KEYS = {
        "eq_band0", "eq_band1", "eq_band2", "eq_band3", "eq_band4",
        "eq_band5", "eq_band6", "eq_band7", "eq_band8", "eq_band9"
    };
    public static final int[] EQ_BAND_FREQS = {
        31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    };
    public static final int EQ_CUSTOM_PRESET_INDEX = 8;

    public static final String   CUSTOM_FILTER_SHAPE_KEY = "custom_filter_shape";
    public static final String   CUSTOM_FILTER_SYMMETRY_KEY = "custom_filter_symmetry";
    public static final String[] CUSTOM_FILTER_COEFF_KEYS = {
        "custom_filter_coeff0", "custom_filter_coeff1", "custom_filter_coeff2", "custom_filter_coeff3",
        "custom_filter_coeff4", "custom_filter_coeff5", "custom_filter_coeff6", "custom_filter_coeff7",
        "custom_filter_coeff8", "custom_filter_coeff9", "custom_filter_coeff10", "custom_filter_coeff11",
        "custom_filter_coeff12", "custom_filter_coeff13"
    };
}
