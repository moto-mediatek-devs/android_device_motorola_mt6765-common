/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.motorola.mdmcutback;

import android.hardware.radio.V1_0.RadioError;
import android.hardware.radio.V1_0.RadioResponseInfo;
import android.os.Handler;
import android.os.IHwBinder;
import android.util.Log;

import vendor.mediatek.hardware.mtkradioex.V3_0.IEmRadioIndication;
import vendor.mediatek.hardware.mtkradioex.V3_0.IEmRadioResponse;
import vendor.mediatek.hardware.mtkradioex.V3_0.IMtkRadioEx;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Sends SAR and antenna tuner indices to the modem through the RIL's engineering mode channel. */
public class ModemSarController {
    private static final String TAG = "MotoMdmCutback";
    private static final String INSTANCE = "mtkEm1";

    private final Handler mHandler;
    private final Runnable mOnRadioDied;
    private final AtomicInteger mSerial = new AtomicInteger();

    private IMtkRadioEx mRadio;

    private final IHwBinder.DeathRecipient mDeathRecipient;

    private final IEmRadioResponse.Stub mResponse = new IEmRadioResponse.Stub() {
        @Override
        public void sendRequestRawResponse(RadioResponseInfo info, ArrayList<Byte> data) {
            byte[] bytes = new byte[data.size()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = data.get(i);
            }
            String text = new String(bytes).trim().replaceAll("\\s+", " ");
            if (info.error == RadioError.NONE) {
                Log.d(TAG, "Request " + info.serial + ": " + text);
            } else {
                Log.e(TAG, "Request " + info.serial + " failed: error " + info.error + " " + text);
            }
        }

        @Override
        public void sendRequestStringsResponse(RadioResponseInfo info, ArrayList<String> data) {}
    };

    private final IEmRadioIndication.Stub mIndication = new IEmRadioIndication.Stub() {
        @Override
        public void onTxPowerIndication(int type, ArrayList<Integer> indPower) {}

        @Override
        public void networkInfoInd(int type, ArrayList<String> networkinfo) {}

        @Override
        public void oemHookRaw(int type, ArrayList<Byte> data) {}
    };

    public ModemSarController(Handler handler, Runnable onRadioDied) {
        mHandler = handler;
        mOnRadioDied = onRadioDied;
        mDeathRecipient = cookie -> mHandler.post(() -> {
            Log.w(TAG, "RIL died");
            mRadio = null;
            mOnRadioDied.run();
        });
    }

    public boolean setSarIndex(int index) {
        return sendAtCommand("AT+ERFIDX=1," + index);
    }

    public boolean setTunerIndex(int index) {
        return sendAtCommand("AT+ERFIDX=0," + index);
    }

    private IMtkRadioEx getRadio() {
        if (mRadio != null) {
            return mRadio;
        }
        try {
            IMtkRadioEx radio = IMtkRadioEx.getService(INSTANCE, false);
            if (radio != null) {
                radio.linkToDeath(mDeathRecipient, 0);
                radio.setResponseFunctionsEm(mResponse, mIndication);
                mRadio = radio;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get " + INSTANCE, e);
        }
        return mRadio;
    }

    private boolean sendAtCommand(String command) {
        IMtkRadioEx radio = getRadio();
        if (radio == null) {
            return false;
        }
        byte[] bytes = command.getBytes();
        ArrayList<Byte> data = new ArrayList<>(bytes.length + 1);
        for (byte b : bytes) {
            data.add(b);
        }
        data.add((byte) 0);
        try {
            int serial = mSerial.incrementAndGet();
            Log.d(TAG, "Request " + serial + ": " + command);
            radio.sendRequestRaw(serial, data);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send " + command, e);
            mRadio = null;
            return false;
        }
    }
}
