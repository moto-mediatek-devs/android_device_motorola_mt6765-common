/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.motorola.mdmcutback;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SarSensors {
    /** Returned by {@link #getModemTx0Dsi()} when no modem antenna is covered. */
    public static final int DSI_NONE = -1;

    private static final String TAG = "MotoMdmCutback";

    private static final int MAX_CHANNELS = 7;
    private static final int PROXIMITY_CHANNEL = 0;
    private static final int CAP_CHANNEL_BASE = 2;

    private static final int STATE_UNKNOWN = -1;
    private static final int STATE_INTERMEDIATE = 1;
    private static final int STATE_NEAR = 2;

    private final SarConfig mConfig;
    private final Handler mHandler;
    private final Runnable mOnChanged;
    private final SensorManager mSensorManager;

    private final int[] mStates = new int[MAX_CHANNELS];
    private final Map<Integer, Sensor> mChannels = new HashMap<>();
    private final Map<Integer, SensorEventListener> mListeners = new HashMap<>();
    // The first two capacitive channels of the OB5 mask
    private final List<Integer> mOb5Channels = new ArrayList<>();

    private boolean mCapsenseEnabled;
    private boolean mProximityEnabled;

    public SarSensors(Context context, SarConfig config, Handler handler, Runnable onChanged) {
        mConfig = config;
        mHandler = handler;
        mOnChanged = onChanged;
        mSensorManager = context.getSystemService(SensorManager.class);
        Arrays.fill(mStates, STATE_UNKNOWN);

        List<Sensor> available = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Map.Entry<Integer, String> entry : config.sensors.entrySet()) {
            Sensor found = null;
            for (Sensor sensor : available) {
                if (sensor.getName().equals(entry.getValue())) {
                    found = sensor;
                    break;
                }
            }
            // The config names the proximity channel "PROXIMITY" rather than the real sensor
            if (found == null && entry.getKey() == PROXIMITY_CHANNEL) {
                found = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            }
            if (found == null) {
                Log.w(TAG, "Sensor " + entry.getValue() + " for channel " + entry.getKey()
                        + " not found");
                continue;
            }
            mChannels.put(entry.getKey(), found);
        }

        for (int bit = 0; bit < MAX_CHANNELS - CAP_CHANNEL_BASE && mOb5Channels.size() < 2; bit++) {
            if ((config.ob5Mask & (1 << bit)) != 0) {
                mOb5Channels.add(bit + CAP_CHANNEL_BASE);
            }
        }
    }

    public void setCapsenseEnabled(boolean enabled) {
        if (mCapsenseEnabled == enabled) {
            return;
        }
        mCapsenseEnabled = enabled;
        for (int channel : mChannels.keySet()) {
            if (channel >= CAP_CHANNEL_BASE) {
                setChannelEnabled(channel, enabled);
            }
        }
    }

    public void setProximityEnabled(boolean enabled) {
        if (mProximityEnabled == enabled) {
            return;
        }
        mProximityEnabled = enabled;
        setChannelEnabled(PROXIMITY_CHANNEL, enabled);
    }

    public boolean isProximityNear() {
        int state = mStates[PROXIMITY_CHANNEL];
        return state != STATE_UNKNOWN && state < 1;
    }

    /** Body worn: two OB5 antennas covered, or one of them together with proximity. */
    public boolean isOb5() {
        if (mOb5Channels.size() < 2) {
            return false;
        }
        return isCovered(mStates[mOb5Channels.get(0)])
                && (isCovered(mStates[mOb5Channels.get(1)]) || isProximityNear());
    }

    /** DSI for a covered modem antenna, or {@link #DSI_NONE} if none of them is covered. */
    public int getModemTx0Dsi() {
        if (isOb5()) {
            return DSI_NONE;
        }
        int near = getNearMask();
        if ((near & mConfig.modemTx0Mask) == 0) {
            return DSI_NONE;
        }
        for (Map.Entry<Integer, Integer> entry : mConfig.modemTx0DsiMap.entrySet()) {
            int mask = entry.getKey();
            if ((mConfig.modemTx0Mask & mask & near) == mask) {
                return entry.getValue();
            }
        }
        return mConfig.modemTx0DefaultDsi;
    }

    /** Hand held: the Wi-Fi antenna is covered. */
    public boolean isWifiTx0() {
        return !isOb5() && (getNearMask() & mConfig.wifiTx0Mask) != 0;
    }

    private int getNearMask() {
        int mask = 0;
        for (int channel = CAP_CHANNEL_BASE; channel < MAX_CHANNELS; channel++) {
            if (mStates[channel] == STATE_NEAR) {
                mask |= 1 << (channel - CAP_CHANNEL_BASE);
            }
        }
        return mask;
    }

    private static boolean isCovered(int state) {
        return state == STATE_NEAR || state == STATE_INTERMEDIATE;
    }

    private void setChannelEnabled(int channel, boolean enabled) {
        Sensor sensor = mChannels.get(channel);
        if (sensor == null) {
            return;
        }
        if (enabled) {
            SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    int state = (int) event.values[0];
                    if (mStates[channel] == state) {
                        return;
                    }
                    mStates[channel] = state;
                    mOnChanged.run();
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
            mListeners.put(channel, listener);
            mSensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL,
                    mHandler);
        } else {
            SensorEventListener listener = mListeners.remove(channel);
            if (listener != null) {
                mSensorManager.unregisterListener(listener);
            }
            mStates[channel] = STATE_UNKNOWN;
        }
    }

    public void dump(PrintWriter pw) {
        Map<Integer, String> names = new HashMap<>();
        for (Map.Entry<Integer, Sensor> entry : mChannels.entrySet()) {
            names.put(entry.getKey(), entry.getValue().getName());
        }
        pw.println("  sensors: " + names);
        pw.println("  states: " + Arrays.toString(mStates));
        pw.println("  capsense=" + mCapsenseEnabled + " proximity=" + mProximityEnabled);
        pw.println("  ob5=" + isOb5() + " modemTx0Dsi=" + getModemTx0Dsi()
                + " wifiTx0=" + isWifiTx0());
    }
}
