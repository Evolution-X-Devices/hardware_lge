package org.lineageos.settings.device.dac;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragment;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;
import androidx.preference.ListPreference;
import androidx.preference.SeekBarPreference;

import android.util.Log;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import org.lineageos.settings.device.dac.ui.BalancePreference;
import org.lineageos.settings.device.dac.ui.ButtonPreference;
import org.lineageos.settings.device.dac.utils.Constants;
import org.lineageos.settings.device.dac.utils.EqPresetManager;
import org.lineageos.settings.device.dac.utils.QuadDAC;

import java.util.ArrayList;
import java.util.Map;

import vendor.lge.hardware.audio.dac.control.V2_0.Feature;
import vendor.lge.hardware.audio.dac.control.V2_0.IDacControl;
import vendor.lge.hardware.audio.dac.control.V2_0.Range;

public class QuadDACPanelFragment extends PreferenceFragment
        implements OnPreferenceChangeListener {

    private static final String TAG = "QuadDACPanelFragment";
    private static final int REQUEST_LOAD_EQ_PRESET = 1001;

    private QuadDAC DacControlInterface = new QuadDAC();

    private SwitchPreference quaddac_switch, bit_perfect_switch, eq_switch;
    private ListPreference sound_preset_list, digital_filter_list, mode_list;
    private ListPreference eq_preset_list;
    private Preference eq_load_preset_pref, eq_save_preset_pref, eq_reset_pref;
    private SeekBarPreference[] eq_bands = new SeekBarPreference[10];
    private BalancePreference balance_preference;
    private SeekBarPreference avc_volume;

    /*** Custom filter UI props ***/

    /* Shape and symmetry selectors */
    private ListPreference custom_filter_shape, custom_filter_symmetry;

    /* Filter stage 2 coefficients (refer to the kernel's es9218.c for more info) */
    private static SeekBarPreference[] custom_filter_coeffs = new SeekBarPreference[14];

    /* Button to reset custom filter's coefficients, if needed. */
    private ButtonPreference custom_filter_reset_coeffs_button;

    private HeadsetPluggedFragmentReceiver headsetPluggedFragmentReceiver;

    private IDacControl dac;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        headsetPluggedFragmentReceiver = new HeadsetPluggedFragmentReceiver();
        try {
            DacControlInterface.initialize();
        } catch(Exception e) {
            Log.d(TAG, "onCreatePreferences: " + e.toString());
        }
        addPreferencesFromResource(R.xml.quaddac_panel);
    }

    public static void setCoeffSummary(int index, int value) {
        custom_filter_coeffs[index].setValue(value);
        custom_filter_coeffs[index].setSummary("Coefficient " + index + " : 0." + value);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {

        try {
            if (preference instanceof SwitchPreference) {
                String key = preference.getKey();
                boolean enabled = (boolean) newValue;

                if (Constants.DAC_SWITCH_KEY.equals(key)) {
                    if (enabled) {
                        DacControlInterface.enable();
                        enableExtraSettings();
                    } else {
                        DacControlInterface.disable();
                        disableExtraSettings();
                    }
                    DacDspService.sync(getContext());
                    return true;
                } else if (Constants.BIT_PERFECT_KEY.equals(key)) {
                    DacControlInterface.setBitPerfect(enabled);
                    applyBitPerfectUiState(enabled);
                    DacDspService.sync(getContext());
                    return true;
                } else if (Constants.EQ_SWITCH_KEY.equals(key)) {
                    DacControlInterface.setEqEnabled(enabled);
                    updateEqControlsEnabled(enabled && !bit_perfect_switch.isChecked());
                    DacDspService.sync(getContext());
                    return true;
                }
                return false;
            }
            if (preference instanceof ListPreference) {
                if (preference.getKey().equals(Constants.HIFI_MODE_KEY)) {
                    ListPreference lp = (ListPreference) preference;

                    int mode = lp.findIndexOfValue((String) newValue);
                    DacControlInterface.setDACMode(mode);
                    return true;

                } else if (preference.getKey().equals(Constants.DIGITAL_FILTER_KEY)) {
                    ListPreference lp = (ListPreference) preference;

                    int digital_filter = lp.findIndexOfValue((String) newValue);
                    DacControlInterface.setDigitalFilter(digital_filter);

                    /* Custom filter panel should only show up with Filter [3] (fourth one) selected */
                    if(DacControlInterface.getSupportedFeatures().contains(Feature.CustomFilter) && digital_filter == 3)
                        enableCustomFilter();
                    else
                        disableCustomFilter();

                    return true;

                } else if (preference.getKey().equals(Constants.SOUND_PRESET_KEY)) {
                    ListPreference lp = (ListPreference) preference;

                    int sound_preset = lp.findIndexOfValue((String) newValue);
                    DacControlInterface.setSoundPreset(sound_preset);
                    return true;
                } else if(preference.getKey().equals(Constants.CUSTOM_FILTER_SHAPE_KEY))
                {
                    ListPreference lp = (ListPreference) preference;

                    int filter_shape = lp.findIndexOfValue((String) newValue);
                    DacControlInterface.setCustomFilterShape(filter_shape);
                    return true;

                } else if(preference.getKey().equals(Constants.CUSTOM_FILTER_SYMMETRY_KEY))
                {
                    ListPreference lp = (ListPreference) preference;

                    int filter_symmetry = lp.findIndexOfValue((String) newValue);
                    DacControlInterface.setCustomFilterSymmetry(filter_symmetry);
                    return true;

                } else if (preference.getKey().equals(Constants.EQ_PRESET_KEY)) {
                    ListPreference lp = (ListPreference) preference;
                    int preset = lp.findIndexOfValue((String) newValue);
                    DacControlInterface.setEqPreset(preset);
                    refreshEqBandsFromHal();
                    DacDspService.sync(getContext());
                    return true;
                }
                return false;
            }

            if (preference instanceof SeekBarPreference) {
                if (preference.getKey().equals(Constants.AVC_VOLUME_KEY)) {
                    if (newValue instanceof Integer) {
                        Integer avc_vol = (Integer) newValue;
                        DacControlInterface.setAVCVolume(avc_vol);
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    for (int i = 0; i < 10; i++) {
                        if (preference.getKey().equals(Constants.EQ_BAND_KEYS[i])) {
                            if (newValue instanceof Integer) {
                                int level = (Integer) newValue;
                                DacControlInterface.setEqBandLevel(i, level);
                                DacControlInterface.setEqPreset(Constants.EQ_CUSTOM_PRESET_INDEX);
                                eq_preset_list.setValueIndex(Constants.EQ_CUSTOM_PRESET_INDEX);
                                setEqBandSummary(i, level);
                                DacDspService.sync(getContext());
                                return true;
                            }
                            return false;
                        }
                    }
                    for(int i = 0; i < 14; i++) {
                        if(preference.getKey().equals(Constants.CUSTOM_FILTER_COEFF_KEYS[i]))
                        {
                            if (newValue instanceof Integer) {
                                Integer coeffVal = (Integer) newValue;

                                setCoeffSummary(i, coeffVal);

                                DacControlInterface.setCustomFilterCoeff(i, coeffVal);
                                return true;
                            } else
                                return false;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "onPreferenceChange: " + e.toString());
        }
        return false;
    }

    @Override
    public void onResume() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        getActivity().registerReceiver(headsetPluggedFragmentReceiver, filter);
        super.onResume();
    }

    @Override
    public void onPause() {
        getActivity().unregisterReceiver(headsetPluggedFragmentReceiver);
        super.onPause();
    }

    @Override
    public void addPreferencesFromResource(int preferencesResId) {
        super.addPreferencesFromResource(preferencesResId);
        // Initialize preferences
        AudioManager am = getContext().getSystemService(AudioManager.class);

        quaddac_switch = (SwitchPreference) findPreference(Constants.DAC_SWITCH_KEY);
        quaddac_switch.setOnPreferenceChangeListener(this);

        sound_preset_list = (ListPreference) findPreference(Constants.SOUND_PRESET_KEY);
        sound_preset_list.setOnPreferenceChangeListener(this);

        digital_filter_list = (ListPreference) findPreference(Constants.DIGITAL_FILTER_KEY);
        digital_filter_list.setOnPreferenceChangeListener(this);

        custom_filter_shape = (ListPreference) findPreference(Constants.CUSTOM_FILTER_SHAPE_KEY);
        custom_filter_shape.setOnPreferenceChangeListener(this);

        custom_filter_symmetry = (ListPreference) findPreference(Constants.CUSTOM_FILTER_SYMMETRY_KEY);
        custom_filter_symmetry.setOnPreferenceChangeListener(this);

        custom_filter_reset_coeffs_button = (ButtonPreference) findPreference(Constants.RESET_COEFFICIENTS_KEY);
        custom_filter_reset_coeffs_button.setOnPreferenceChangeListener(this);

        for(int i = 0; i < 14; i++)
        {
            custom_filter_coeffs[i] = (SeekBarPreference) findPreference(Constants.CUSTOM_FILTER_COEFF_KEYS[i]);
            custom_filter_coeffs[i].setOnPreferenceChangeListener(this);
        }

        mode_list = (ListPreference) findPreference(Constants.HIFI_MODE_KEY);
        mode_list.setOnPreferenceChangeListener(this);

        avc_volume = (SeekBarPreference) findPreference(Constants.AVC_VOLUME_KEY);
        avc_volume.setOnPreferenceChangeListener(this);

        balance_preference = (BalancePreference) findPreference(Constants.BALANCE_KEY);

        bit_perfect_switch = (SwitchPreference) findPreference(Constants.BIT_PERFECT_KEY);
        bit_perfect_switch.setOnPreferenceChangeListener(this);

        eq_switch = (SwitchPreference) findPreference(Constants.EQ_SWITCH_KEY);
        eq_switch.setOnPreferenceChangeListener(this);

        eq_preset_list = (ListPreference) findPreference(Constants.EQ_PRESET_KEY);
        eq_preset_list.setOnPreferenceChangeListener(this);

        eq_load_preset_pref = findPreference(Constants.EQ_LOAD_PRESET_KEY);
        eq_load_preset_pref.setOnPreferenceClickListener(p -> {
            showLoadPresetDialog();
            return true;
        });

        eq_save_preset_pref = findPreference(Constants.EQ_SAVE_PRESET_KEY);
        eq_save_preset_pref.setOnPreferenceClickListener(p -> {
            showSavePresetDialog();
            return true;
        });

        eq_reset_pref = findPreference(Constants.EQ_RESET_KEY);
        eq_reset_pref.setOnPreferenceClickListener(p -> {
            try {
                DacControlInterface.resetEqBands();
                eq_preset_list.setValueIndex(0);
                refreshEqBandsFromHal();
                DacDspService.sync(getContext());
            } catch (Exception e) {
                Log.d(TAG, "eq_reset: " + e.toString());
            }
            return true;
        });

        for (int i = 0; i < 10; i++) {
            eq_bands[i] = (SeekBarPreference) findPreference(Constants.EQ_BAND_KEYS[i]);
            eq_bands[i].setOnPreferenceChangeListener(this);
        }

        try {
            if (DacControlInterface.getSupportedFeatures().contains(Feature.QuadDAC)) {
                quaddac_switch.setVisible(true);
            }
            if (DacControlInterface.getSupportedFeatures().contains(Feature.SoundPreset)) {
                sound_preset_list.setVisible(true);
                sound_preset_list.setValueIndex(DacControlInterface.getSoundPreset());
            }
            if (DacControlInterface.getSupportedFeatures().contains(Feature.DigitalFilter)) {
                digital_filter_list.setVisible(true);
                digital_filter_list.setValueIndex(DacControlInterface.getDigitalFilter());
            }
            if (DacControlInterface.getSupportedFeatures().contains(Feature.BalanceLeft)
                    && DacControlInterface.getSupportedFeatures().contains(Feature.BalanceRight)) {
                balance_preference.setVisible(true);
            }
            if (DacControlInterface.getSupportedFeatures().contains(Feature.AVCVolume)) {
                avc_volume.setVisible(true);
                Range range = DacControlInterface.getSupportedFeatureValues(Feature.AVCVolume).range;
                avc_volume.setMin((int)range.min);
                avc_volume.setMax((int)range.max);
                avc_volume.setValue(DacControlInterface.getAVCVolume());
            }
            if (DacControlInterface.getSupportedFeatures().contains(Feature.HifiMode)) {
                mode_list.setVisible(true);
                mode_list.setValueIndex(DacControlInterface.getDACMode());
            }
            if (DacControlInterface.getSupportedFeatures().contains(Feature.CustomFilter)) {
                custom_filter_shape.setVisible(true);
                custom_filter_shape.setValueIndex(DacControlInterface.getCustomFilterShape());
                custom_filter_symmetry.setVisible(true);
                custom_filter_symmetry.setValueIndex(DacControlInterface.getCustomFilterSymmetry());
                for(int i = 0; i < 14; i++)
                {
                    custom_filter_coeffs[i].setVisible(true);
                    custom_filter_coeffs[i].setValue(DacControlInterface.getCustomFilterCoeff(i));
                    setCoeffSummary(i, DacControlInterface.getCustomFilterCoeff(i));
                }
            }
            if (DacControlInterface.getSupportedFeatures().contains(Feature.BitPerfect)) {
                bit_perfect_switch.setVisible(true);
                bit_perfect_switch.setChecked(DacControlInterface.isBitPerfectEnabled());
            }
            if (DacControlInterface.getSupportedFeatures().contains(Feature.Equalizer)) {
                eq_switch.setVisible(true);
                eq_switch.setChecked(DacControlInterface.isEqEnabled());
                eq_preset_list.setVisible(true);
                eq_preset_list.setValueIndex(DacControlInterface.getEqPreset());
                eq_load_preset_pref.setVisible(true);
                eq_save_preset_pref.setVisible(true);
                eq_reset_pref.setVisible(true);
                for (int i = 0; i < 10; i++) {
                    eq_bands[i].setVisible(true);
                }
                refreshEqBandsFromHal();
            }
        } catch(Exception e) {
            Log.d(TAG, "addPreferencesFromResource: " + e.toString());
        }

        try {
            if (am.isWiredHeadsetOn()) {
                quaddac_switch.setEnabled(true);
                if (DacControlInterface.isEnabled()) {
                    quaddac_switch.setChecked(true);
                    enableExtraSettings();
                } else {
                    quaddac_switch.setChecked(false);
                    disableExtraSettings();
                }
            } else {
                quaddac_switch.setEnabled(false);
                disableExtraSettings();
                if (DacControlInterface.isEnabled()) {
                    quaddac_switch.setChecked(true);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "addPreferencesFromResource2: " + e.toString());
        }
    }

    private void enableExtraSettings()
    {
        ArrayList<Integer> supportedFeatures = DacControlInterface.getSupportedFeatures();
        digital_filter_list.setEnabled(true);
        mode_list.setEnabled(true);
        avc_volume.setEnabled(true);
        balance_preference.setEnabled(true);
        if(supportedFeatures.contains(Feature.SoundPreset))
            sound_preset_list.setEnabled(true);
        if(supportedFeatures.contains(Feature.CustomFilter))
            enableCustomFilter();
        if (supportedFeatures.contains(Feature.BitPerfect))
            bit_perfect_switch.setEnabled(true);
        if (supportedFeatures.contains(Feature.Equalizer))
            eq_switch.setEnabled(true);

        applyBitPerfectUiState(bit_perfect_switch.isChecked());
    }

    private void disableExtraSettings()
    {
        digital_filter_list.setEnabled(false);
        mode_list.setEnabled(false);
        avc_volume.setEnabled(false);
        balance_preference.setEnabled(false);
        sound_preset_list.setEnabled(false);
        bit_perfect_switch.setEnabled(false);
        eq_switch.setEnabled(false);
        updateEqControlsEnabled(false);
        disableCustomFilter();
    }

    private void applyBitPerfectUiState(boolean bitPerfect) {
        if (!quaddac_switch.isChecked() || !quaddac_switch.isEnabled()) {
            return;
        }
        boolean dspAllowed = !bitPerfect;
        avc_volume.setEnabled(dspAllowed);
        balance_preference.setEnabled(dspAllowed);
        if (DacControlInterface.getSupportedFeatures().contains(Feature.SoundPreset)) {
            sound_preset_list.setEnabled(dspAllowed);
        }
        if (DacControlInterface.getSupportedFeatures().contains(Feature.Equalizer)) {
            eq_switch.setEnabled(dspAllowed);
            updateEqControlsEnabled(dspAllowed && eq_switch.isChecked());
        }
    }

    private void updateEqControlsEnabled(boolean enabled) {
        eq_preset_list.setEnabled(enabled);
        eq_load_preset_pref.setEnabled(enabled);
        eq_save_preset_pref.setEnabled(enabled);
        eq_reset_pref.setEnabled(enabled);
        for (int i = 0; i < 10; i++) {
            eq_bands[i].setEnabled(enabled);
        }
    }

    private void setEqBandSummary(int index, int levelDb) {
        eq_bands[index].setValue(levelDb);
        eq_bands[index].setSummary((levelDb > 0 ? "+" : "") + levelDb + " dB");
    }

    private void refreshEqBandsFromHal() {
        try {
            for (int i = 0; i < 10; i++) {
                setEqBandSummary(i, DacControlInterface.getEqBandLevel(i));
            }
        } catch (Exception e) {
            Log.d(TAG, "refreshEqBandsFromHal: " + e.toString());
        }
    }

    private void applyEqBands(int[] bands) {
        try {
            for (int i = 0; i < 10; i++) {
                DacControlInterface.setEqBandLevel(i, bands[i]);
                setEqBandSummary(i, bands[i]);
            }
            DacControlInterface.setEqPreset(Constants.EQ_CUSTOM_PRESET_INDEX);
            eq_preset_list.setValueIndex(Constants.EQ_CUSTOM_PRESET_INDEX);
            DacDspService.sync(getContext());
        } catch (Exception e) {
            Log.d(TAG, "applyEqBands: " + e.toString());
        }
    }

    private void showLoadPresetDialog() {
        Context ctx = getContext();
        Map<String, int[]> saved = EqPresetManager.getSavedPresets(ctx);
        ArrayList<String> labels = new ArrayList<>(saved.keySet());
        labels.add(getString(R.string.eq_load_from_file));

        String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.eq_load_preset)
                .setItems(items, (dialog, which) -> {
                    if (which == items.length - 1) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQUEST_LOAD_EQ_PRESET);
                    } else {
                        String name = items[which];
                        int[] bands = saved.get(name);
                        if (bands != null) {
                            applyEqBands(bands);
                            Toast.makeText(ctx, getString(R.string.eq_preset_loaded, name),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void showSavePresetDialog() {
        Context ctx = getContext();
        final EditText input = new EditText(ctx);
        input.setHint(R.string.eq_save_dialog_hint);
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.eq_save_dialog_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        int[] bands = new int[10];
                        try {
                            for (int i = 0; i < 10; i++) {
                                bands[i] = DacControlInterface.getEqBandLevel(i);
                            }
                            EqPresetManager.savePreset(ctx, name, bands);
                            Toast.makeText(ctx, getString(R.string.eq_preset_saved, name),
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.d(TAG, "showSavePresetDialog: " + e.toString());
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LOAD_EQ_PRESET && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                EqPresetManager.EqPreset preset = EqPresetManager.loadPresetFromUri(getContext(), uri);
                applyEqBands(preset.bands);
                EqPresetManager.savePreset(getContext(), preset.name, preset.bands);
                Toast.makeText(getContext(), getString(R.string.eq_preset_loaded, preset.name),
                        Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to load EQ preset", e);
                Toast.makeText(getContext(), R.string.eq_preset_load_failed,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void enableCustomFilter()
    {
        checkCustomFilterVisibility();
        custom_filter_shape.setEnabled(true);
        custom_filter_symmetry.setEnabled(true);
        for(int i = 0; i < 14; i++){
            custom_filter_coeffs[i].setEnabled(true);
        }

        custom_filter_reset_coeffs_button.setEnabled(true);

        try {
            /* To apply the custom filter's settings */
            DacControlInterface.setCustomFilterShape(DacControlInterface.getCustomFilterShape());
        } catch (Exception e) {}
    }

    private void disableCustomFilter()
    {
        checkCustomFilterVisibility();
        custom_filter_shape.setEnabled(false);
        custom_filter_symmetry.setEnabled(false);
        for(int i = 0; i < 14; i++)
            custom_filter_coeffs[i].setEnabled(false);

        custom_filter_reset_coeffs_button.setEnabled(false);
    }

    private void checkCustomFilterVisibility() {
        /*
         * If the selected digital filter is the custom filter,
         * its preferences should be visible. Otherwise, hide them
         * to remove unused preferences from the panel.
        */
        try {
            if(DacControlInterface.getDigitalFilter() == 3) {
                custom_filter_shape.setVisible(true);
                custom_filter_symmetry.setVisible(true);
                for(int i = 0; i < 14; i++)
                    custom_filter_coeffs[i].setVisible(true);
            }
            else {
                custom_filter_shape.setVisible(false);
                custom_filter_symmetry.setVisible(false);
                for(int i = 0; i < 14; i++)
                    custom_filter_coeffs[i].setVisible(false);
            }
        } catch (Exception e) {}
    }

    private class HeadsetPluggedFragmentReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_HEADSET_PLUG)) {
                int state = intent.getIntExtra("state", -1);
                switch(state)
                {
                    case 1: // Headset plugged in
                        quaddac_switch.setEnabled(true);
                        if(quaddac_switch.isChecked())
                            enableExtraSettings();
                        break;
                    case 0: // Headset unplugged
                        quaddac_switch.setEnabled(false);
                        disableExtraSettings();
                        break;
                    default: break;
                }
            }
        }
    }

    public IDacControl getDhc() { return dac; }

}
