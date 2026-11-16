package org.lineageos.settings.device.dac.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Manages loading, parsing, and saving 10-band DAC Equalizer presets.
 * Supports JSON presets and GraphicEQ / AutoEQ text preset files.
 */
public class EqPresetManager {

    private static final String PREFS_NAME = "dac_eq_presets";
    private static final String KEY_SAVED_PRESETS = "saved_presets_json";
    private static final int BAND_COUNT = Constants.EQ_BAND_FREQS.length;
    private static final int MIN_DB = -12;
    private static final int MAX_DB = 12;

    public static class EqPreset {
        public final String name;
        public final int[] bands;

        public EqPreset(String name, int[] bands) {
            this.name = name;
            this.bands = bands;
        }
    }

    public static Map<String, int[]> getSavedPresets(Context context) {
        Map<String, int[]> result = new LinkedHashMap<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_SAVED_PRESETS, "{}");
        try {
            JSONObject root = new JSONObject(raw);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONArray arr = root.optJSONArray(name);
                if (arr != null && arr.length() == BAND_COUNT) {
                    int[] bands = new int[BAND_COUNT];
                    for (int i = 0; i < BAND_COUNT; i++) {
                        bands[i] = clampDb(arr.optInt(i, 0));
                    }
                    result.put(name, bands);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static void savePreset(Context context, String name, int[] bands) {
        if (name == null || name.trim().isEmpty() || bands == null || bands.length != BAND_COUNT) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_SAVED_PRESETS, "{}");
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < BAND_COUNT; i++) {
                arr.put(clampDb(bands[i]));
            }
            root.put(name.trim(), arr);
            prefs.edit().putString(KEY_SAVED_PRESETS, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static EqPreset loadPresetFromUri(Context context, Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        String content = sb.toString().trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Empty preset file");
        }

        if (content.startsWith("{")) {
            return parseJsonPreset(content);
        }
        return parseGraphicEqPreset(content);
    }

    private static EqPreset parseJsonPreset(String content) throws Exception {
        JSONObject obj = new JSONObject(content);
        String name = obj.optString("name", "Imported Preset");
        JSONArray arr = obj.optJSONArray("bands");
        if (arr == null || arr.length() != BAND_COUNT) {
            throw new IllegalArgumentException("JSON preset must contain 10 bands");
        }
        int[] bands = new int[BAND_COUNT];
        for (int i = 0; i < BAND_COUNT; i++) {
            bands[i] = clampDb((int) Math.round(arr.getDouble(i)));
        }
        return new EqPreset(name, bands);
    }

    private static EqPreset parseGraphicEqPreset(String content) throws Exception {
        TreeMap<Double, Double> points = new TreeMap<>();
        String presetName = "Imported EQ";

        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#") || line.startsWith("//")) {
                String candidate = line.replaceFirst("^[#/]+", "").trim();
                if (!candidate.isEmpty() && presetName.equals("Imported EQ")) {
                    presetName = candidate;
                }
                continue;
            }

            if (line.regionMatches(true, 0, "GraphicEQ:", 0, "GraphicEQ:".length())) {
                String data = line.substring("GraphicEQ:".length()).trim();
                for (String pair : data.split(";")) {
                    String[] tokens = pair.trim().split("\\s+");
                    if (tokens.length >= 2) {
                        double freq = Double.parseDouble(tokens[0]);
                        double gain = Double.parseDouble(tokens[1]);
                        if (freq > 0) {
                            points.put(freq, gain);
                        }
                    }
                }
            } else {
                String[] tokens = line.split("[,\\s]+");
                if (tokens.length >= 2) {
                    try {
                        double freq = Double.parseDouble(tokens[0]);
                        double gain = Double.parseDouble(tokens[1]);
                        if (freq > 0) {
                            points.put(freq, gain);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        if (points.isEmpty()) {
            throw new IllegalArgumentException("No valid frequency/gain pairs found");
        }

        List<Double> freqs = new ArrayList<>(points.keySet());
        Collections.sort(freqs);

        int[] bands = new int[BAND_COUNT];
        for (int i = 0; i < BAND_COUNT; i++) {
            double targetFreq = Constants.EQ_BAND_FREQS[i];
            bands[i] = clampDb((int) Math.round(interpolateLog(targetFreq, freqs, points)));
        }
        return new EqPreset(presetName, bands);
    }

    private static double interpolateLog(double targetFreq, List<Double> freqs,
                                         Map<Double, Double> points) {
        if (targetFreq <= freqs.get(0)) {
            return points.get(freqs.get(0));
        }
        double lastFreq = freqs.get(freqs.size() - 1);
        if (targetFreq >= lastFreq) {
            return points.get(lastFreq);
        }
        for (int i = 0; i < freqs.size() - 1; i++) {
            double f1 = freqs.get(i);
            double f2 = freqs.get(i + 1);
            if (targetFreq >= f1 && targetFreq <= f2) {
                double g1 = points.get(f1);
                double g2 = points.get(f2);
                double t = (Math.log(targetFreq) - Math.log(f1)) / (Math.log(f2) - Math.log(f1));
                return g1 + t * (g2 - g1);
            }
        }
        return 0.0;
    }

    private static int clampDb(int db) {
        return Math.max(MIN_DB, Math.min(MAX_DB, db));
    }
}
