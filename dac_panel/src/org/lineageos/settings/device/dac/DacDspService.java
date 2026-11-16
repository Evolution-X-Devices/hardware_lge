package org.lineageos.settings.device.dac;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

import org.lineageos.settings.device.dac.utils.DacDspController;

/**
 * Keeps the QuadDACPanel process alive while DAC EQ is active and syncs on headset plug/unplug.
 */
public class DacDspService extends Service {

    public static final String ACTION_SYNC_DSP = "org.lineageos.settings.device.dac.SYNC_DSP";

    private final BroadcastReceiver mHeadsetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                DacDspController.sync(context);
            }
        }
    };

    public static void sync(Context context) {
        DacDspController.sync(context);
        try {
            Intent intent = new Intent(context, DacDspService.class);
            intent.setAction(ACTION_SYNC_DSP);
            context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(mHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DacDspController.sync(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(mHeadsetReceiver);
        } catch (Exception ignored) {
        }
        DacDspController.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
