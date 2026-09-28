/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.motorola.mdmcutback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "MotoMdmCutback";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Starting");
        context.startService(new Intent(context, MdmCutbackService.class));
    }
}
