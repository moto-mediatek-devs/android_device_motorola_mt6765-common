/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.motorola.mdmcutback;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

public class MdmCutbackService extends Service {
    private static final String TAG = "MotoMdmCutback";

    private static final int SUPPORTED_CONFIG_VERSION = 22;
    private static final String WIFI_SCENARIO_PROP = "sys.moto.sar.wifi_scenario";
    private static final int INDEX_UNSET = -1;
    private static final long RETRY_DELAY_MS = 5000;

    // Modem SAR (DSI) indices set with AT+ERFIDX=1
    private static final int SAR_INDEX_NONE = 0;
    private static final int SAR_INDEX_HEAD = 2;
    private static final int SAR_INDEX_BODY = 3;
    private static final int SAR_INDEX_DEFAULT = 4;
    private static final int SAR_INDEX_HOTSPOT = 7;

    // Modem antenna tuner indices set with AT+ERFIDX=0
    private static final int TUNER_INDEX_DEFAULT = 0;
    private static final int TUNER_INDEX_CHARGER = 1;
    private static final int TUNER_INDEX_HEADSET = 2;
    private static final int TUNER_INDEX_CHARGER_HEADSET = 3;
    private static final int TUNER_INDEX_HEAD = 7;

    // MySAR scenarios in the Wi-Fi driver's txpowerctrl.cfg, WWAN means cellular is active too
    private static final int WIFI_SCENARIO_NONE = 0;
    private static final int WIFI_SCENARIO_WWAN = 1;
    private static final int WIFI_SCENARIO_HOTSPOT_WWAN = 2;
    private static final int WIFI_SCENARIO_HEAD = 4;
    private static final int WIFI_SCENARIO_HEAD_WWAN = 5;
    private static final int WIFI_SCENARIO_BODY = 6;
    private static final int WIFI_SCENARIO_BODY_WWAN = 7;
    private static final int WIFI_SCENARIO_HAND_WWAN = 8;
    private static final int WIFI_SCENARIO_HAND = 9;
    private static final int WIFI_SCENARIO_CHARGER = 10;
    private static final int WIFI_SCENARIO_CHARGER_WWAN = 11;

    private static final Set<Integer> HEADSET_TYPES = Set.of(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET);

    private Handler mHandler;
    private SarConfig mConfig;
    private SarSensors mSensors;
    private ModemSarController mModem;
    private AudioManager mAudioManager;

    private boolean mAirplaneMode;
    private boolean mVoiceCall;
    private boolean mDataCall;
    private boolean mCharger;
    private boolean mHeadset;
    private boolean mHotspot;
    private boolean mWifiDirect;
    private boolean mWifiConnected;
    private boolean mEarpiece;

    private int mAppliedSarIndex = INDEX_UNSET;
    private int mAppliedTunerIndex = INDEX_UNSET;
    private int mAppliedWifiScenario = INDEX_UNSET;

    private final Runnable mRetry = this::update;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_BATTERY_CHANGED:
                    mCharger = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                    break;
                case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                    mAirplaneMode = intent.getBooleanExtra("state", false);
                    // The modem forgets its indices across a radio power cycle
                    if (!mAirplaneMode) {
                        resetModemState();
                    }
                    break;
                case WifiManager.WIFI_AP_STATE_CHANGED_ACTION:
                    mHotspot = intent.getIntExtra(WifiManager.EXTRA_WIFI_AP_STATE, 0)
                            == WifiManager.WIFI_AP_STATE_ENABLED;
                    break;
                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION: {
                    WifiP2pInfo info = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo.class);
                    mWifiDirect = info != null && info.groupFormed;
                    break;
                }
            }
            update();
        }
    };

    private final class CallStateListener extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            mVoiceCall = state != TelephonyManager.CALL_STATE_IDLE;
            update();
        }
    }

    private final CallStateListener mCallStateListener = new CallStateListener();

    private final ConnectivityManager.NetworkCallback mCellularCallback =
            new ConnectivityManager.NetworkCallback() {
        private final Set<Network> mNetworks = new HashSet<>();

        @Override
        public void onAvailable(Network network) {
            mNetworks.add(network);
            mDataCall = true;
            update();
        }

        @Override
        public void onLost(Network network) {
            mNetworks.remove(network);
            mDataCall = !mNetworks.isEmpty();
            update();
        }
    };

    private final ConnectivityManager.NetworkCallback mWifiCallback =
            new ConnectivityManager.NetworkCallback() {
        private final Set<Network> mNetworks = new HashSet<>();

        @Override
        public void onAvailable(Network network) {
            mNetworks.add(network);
            mWifiConnected = true;
            update();
        }

        @Override
        public void onLost(Network network) {
            mNetworks.remove(network);
            mWifiConnected = !mNetworks.isEmpty();
            update();
        }
    };

    private final AudioDeviceCallback mAudioDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            updateHeadset();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            updateHeadset();
        }
    };

    private final AudioManager.OnModeChangedListener mModeListener = mode -> update();

    private final AudioManager.OnCommunicationDeviceChangedListener mCommunicationDeviceListener =
            device -> update();

    @Override
    public void onCreate() {
        mHandler = new Handler(getMainLooper());

        mConfig = SarConfig.load();
        if (mConfig == null) {
            Log.e(TAG, "No SAR config, stopping");
            stopSelf();
            return;
        }
        if (mConfig.version != SUPPORTED_CONFIG_VERSION) {
            Log.w(TAG, "Unsupported config version " + mConfig.version
                    + ", using version 22 rules");
        }

        mSensors = new SarSensors(this, mConfig, mHandler, this::update);
        mModem = new ModemSarController(mHandler, () -> {
            resetModemState();
            update();
        });
        mAudioManager = getSystemService(AudioManager.class);

        mAirplaneMode = Settings.Global.getInt(getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        registerReceiver(mBroadcastReceiver, filter);

        getSystemService(TelephonyManager.class)
                .registerTelephonyCallback(getMainExecutor(), mCallStateListener);

        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        // Any cellular network transmits, including restricted ones such as IMS
        connectivityManager.registerNetworkCallback(new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build(), mCellularCallback, mHandler);
        connectivityManager.registerNetworkCallback(new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(), mWifiCallback, mHandler);

        mAudioManager.registerAudioDeviceCallback(mAudioDeviceCallback, mHandler);
        mAudioManager.addOnModeChangedListener(getMainExecutor(), mModeListener);
        mAudioManager.addOnCommunicationDeviceChangedListener(getMainExecutor(),
                mCommunicationDeviceListener);

        updateHeadset();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateHeadset() {
        mHeadset = false;
        for (AudioDeviceInfo device : mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (HEADSET_TYPES.contains(device.getType())) {
                mHeadset = true;
                break;
            }
        }
        update();
    }

    private void resetModemState() {
        mAppliedSarIndex = INDEX_UNSET;
        mAppliedTunerIndex = INDEX_UNSET;
    }

    private void update() {
        if (mSensors == null) {
            return;
        }

        int mode = mAudioManager.getMode();
        boolean inCommunication = mVoiceCall || mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION;
        AudioDeviceInfo device = mAudioManager.getCommunicationDevice();
        mEarpiece = inCommunication && device != null
                && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;

        boolean cellular = !mAirplaneMode && (mVoiceCall || mDataCall);
        mSensors.setCapsenseEnabled(cellular || mWifiConnected || mHotspot || mWifiDirect);
        mSensors.setProximityEnabled(mEarpiece);

        applyModem(cellular);
        applyWifi(cellular);
    }

    private void applyModem(boolean cellular) {
        if (mAirplaneMode) {
            return;
        }

        int sarIndex;
        if (!cellular) {
            sarIndex = SAR_INDEX_NONE;
        } else if (mEarpiece) {
            sarIndex = getAtHeadDsi();
        } else if (mCharger) {
            sarIndex = SAR_INDEX_NONE;
        } else if (mSensors.isOb5()) {
            sarIndex = SAR_INDEX_BODY;
        } else if (mHotspot || mWifiDirect) {
            sarIndex = SAR_INDEX_HOTSPOT;
        } else {
            int tx0Dsi = mSensors.getModemTx0Dsi();
            sarIndex = tx0Dsi != SarSensors.DSI_NONE ? tx0Dsi : SAR_INDEX_DEFAULT;
        }

        int tunerIndex;
        if (mSensors.isProximityNear() && mEarpiece) {
            tunerIndex = TUNER_INDEX_HEAD;
        } else if (mCharger) {
            tunerIndex = mHeadset ? TUNER_INDEX_CHARGER_HEADSET : TUNER_INDEX_CHARGER;
        } else if (mHeadset) {
            tunerIndex = TUNER_INDEX_HEADSET;
        } else {
            tunerIndex = TUNER_INDEX_DEFAULT;
        }

        boolean failed = false;
        if (sarIndex != mAppliedSarIndex) {
            if (mModem.setSarIndex(sarIndex)) {
                mAppliedSarIndex = sarIndex;
            } else {
                failed = true;
            }
        }
        if (tunerIndex != mAppliedTunerIndex) {
            if (mModem.setTunerIndex(tunerIndex)) {
                mAppliedTunerIndex = tunerIndex;
            } else {
                failed = true;
            }
        }
        if (failed) {
            mHandler.removeCallbacks(mRetry);
            mHandler.postDelayed(mRetry, RETRY_DELAY_MS);
        }
    }

    private int getAtHeadDsi() {
        Integer dsi = mConfig.canadaCarrierDsi.get(SystemProperties.get("ro.carrier"));
        return dsi != null ? dsi : SAR_INDEX_HEAD;
    }

    /** Scenario index into the MySAR table of the Wi-Fi driver's txpowerctrl.cfg. */
    private void applyWifi(boolean cellular) {
        int scenario;
        if (cellular) {
            if (mEarpiece) {
                scenario = WIFI_SCENARIO_HEAD_WWAN;
            } else if (mCharger) {
                scenario = WIFI_SCENARIO_CHARGER_WWAN;
            } else if (mSensors.isOb5()) {
                scenario = WIFI_SCENARIO_BODY_WWAN;
            } else if (mHotspot || mWifiDirect) {
                scenario = WIFI_SCENARIO_HOTSPOT_WWAN;
            } else if (mSensors.isWifiTx0()) {
                scenario = WIFI_SCENARIO_HAND_WWAN;
            } else {
                scenario = WIFI_SCENARIO_WWAN;
            }
        } else {
            if (mEarpiece) {
                scenario = WIFI_SCENARIO_HEAD;
            } else if (mCharger) {
                scenario = WIFI_SCENARIO_CHARGER;
            } else if (mSensors.isOb5()) {
                scenario = WIFI_SCENARIO_BODY;
            } else if (mSensors.isWifiTx0()) {
                scenario = WIFI_SCENARIO_HAND;
            } else {
                scenario = WIFI_SCENARIO_NONE;
            }
        }

        if (scenario == mAppliedWifiScenario) {
            return;
        }
        // init applies this to the driver, and again whenever Wi-Fi (including Wi-Fi Direct)
        // or hotspot starts
        SystemProperties.set(WIFI_SCENARIO_PROP, Integer.toString(scenario));
        mAppliedWifiScenario = scenario;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("MdmCutbackService:");
        pw.println("  airplane=" + mAirplaneMode + " voice=" + mVoiceCall + " data=" + mDataCall
                + " charger=" + mCharger + " headset=" + mHeadset + " hotspot=" + mHotspot
                + " wifiDirect=" + mWifiDirect + " wifi=" + mWifiConnected
                + " earpiece=" + mEarpiece);
        pw.println("  sar=" + mAppliedSarIndex + " tuner=" + mAppliedTunerIndex
                + " wifiScenario=" + mAppliedWifiScenario);
        if (mSensors != null) {
            mSensors.dump(pw);
        }
    }
}
