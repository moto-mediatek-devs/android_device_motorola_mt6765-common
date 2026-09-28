/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.motorola.mdmcutback;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Motorola's SAR cutback configuration (ctbk_cfg.xml).
 *
 * Sensor channel 0 is the front proximity sensor, 1 the back one and 2+ the capacitive
 * sensors. Masks use bit (channel - 2) for each capacitive sensor channel.
 */
public final class SarConfig {
    private static final String TAG = "MotoMdmCutback";
    private static final String PATH = "/system_ext/etc/motorola/mdmctbk/ctbk_cfg.xml";

    private static final int DEFAULT_TX0_DSI = 6;
    private static final int DEFAULT_TX0_DSI_MAPPED = 10;

    public final int version;
    public final Map<Integer, String> sensors;
    public final int ob5Mask;
    public final int wifiTx0Mask;
    public final int modemTx0Mask;
    public final int modemTx0DefaultDsi;
    public final SortedMap<Integer, Integer> modemTx0DsiMap;
    public final Map<String, Integer> canadaCarrierDsi;

    private SarConfig(int version, Map<Integer, String> sensors, int ob5Mask, int wifiTx0Mask,
            int modemTx0Mask, int modemTx0DefaultDsi, SortedMap<Integer, Integer> modemTx0DsiMap,
            Map<String, Integer> canadaCarrierDsi) {
        this.version = version;
        this.sensors = Collections.unmodifiableMap(sensors);
        this.ob5Mask = ob5Mask;
        this.wifiTx0Mask = wifiTx0Mask;
        this.modemTx0Mask = modemTx0Mask;
        this.modemTx0DefaultDsi = modemTx0DefaultDsi;
        this.modemTx0DsiMap = Collections.unmodifiableSortedMap(modemTx0DsiMap);
        this.canadaCarrierDsi = Collections.unmodifiableMap(canadaCarrierDsi);
    }

    public static SarConfig load() {
        File file = new File(PATH);
        if (!file.exists()) {
            Log.e(TAG, PATH + " not found");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            return parse(parser);
        } catch (IOException | XmlPullParserException | RuntimeException e) {
            Log.e(TAG, "Failed to parse " + PATH, e);
            return null;
        }
    }

    private static SarConfig parse(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int version = 0;
        Map<Integer, String> sensors = new HashMap<>();
        int ob5Mask = 0;
        int wifiTx0Mask = 0;
        int modemTx0Mask = 0;
        boolean supportDsiMap = false;
        SortedMap<Integer, Integer> dsiMap = new TreeMap<>();
        boolean supportCanada = false;
        Map<String, Integer> canadaMap = new HashMap<>();

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            switch (parser.getName()) {
                case "ctbk_version":
                    version = Integer.parseInt(parser.nextText().trim());
                    break;
                case "sensor": {
                    int index = Integer.parseInt(parser.getAttributeValue(null, "index"));
                    String name = parser.nextText().trim();
                    if (!name.equals("NULL")) {
                        sensors.put(index, name);
                    }
                    break;
                }
                case "ob5_sensor_config":
                    ob5Mask = parseMask(parser);
                    break;
                case "wifi_tx0_sensor_config":
                    wifiTx0Mask = parseMask(parser);
                    break;
                case "mdm_tx0_sensor_config":
                    modemTx0Mask = parseMask(parser);
                    break;
                case "support_sensor_dsi_map":
                    supportDsiMap = parser.nextText().trim().equals("true");
                    break;
                case "dsi_tx0_sensors":
                    dsiMap.put(parseMask(parser),
                            Integer.parseInt(parser.getAttributeValue(null, "dsi")));
                    break;
                case "support_canada_sar":
                    supportCanada = parser.nextText().trim().equals("true");
                    break;
                case "canada_carrier_dsi":
                    canadaMap.put(parser.getAttributeValue(null, "carrier"),
                            Integer.parseInt(parser.getAttributeValue(null, "dsi")));
                    break;
            }
        }

        if (!supportDsiMap) {
            dsiMap.clear();
        }
        int defaultDsi;
        if (dsiMap.isEmpty()) {
            defaultDsi = DEFAULT_TX0_DSI;
        } else {
            Integer mappedDefault = dsiMap.remove(0);
            defaultDsi = mappedDefault != null ? mappedDefault : DEFAULT_TX0_DSI_MAPPED;
        }

        return new SarConfig(version, sensors, ob5Mask, wifiTx0Mask, modemTx0Mask, defaultDsi,
                dsiMap, supportCanada ? canadaMap : new HashMap<>());
    }

    private static int parseMask(XmlPullParser parser) {
        String bits = parser.getAttributeValue(null, "indexBits");
        if (bits == null || bits.equals("default")) {
            return 0;
        }
        return Integer.parseInt(bits, 2);
    }
}
