/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.developeroptions.wifi.tether;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.SoftApConfiguration;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import com.android.car.developeroptions.widget.ValidatedEditTextPreference;
import com.android.car.developeroptions.wifi.dpp.WifiDppUtils;

public class WifiTetherSSIDPreferenceController extends WifiTetherBasePreferenceController
        implements ValidatedEditTextPreference.Validator {

    private static final String TAG = "WifiTetherSsidPref";
    private static final String PREF_KEY = "wifi_tether_network_name";
    @VisibleForTesting
    static final String DEFAULT_SSID = "AndroidAP";

    private String mSSID;
    private WifiDeviceNameTextValidator mWifiDeviceNameTextValidator;

    public WifiTetherSSIDPreferenceController(Context context,
            OnTetherConfigUpdateListener listener) {
        super(context, listener);
        mWifiDeviceNameTextValidator = new WifiDeviceNameTextValidator();
    }

    @Override
    public String getPreferenceKey() {
        return PREF_KEY;
    }

    @Override
    public void updateDisplay() {
        final SoftApConfiguration config = mWifiManager.getSoftApConfiguration();
        if (config != null) {
            mSSID = config.getSsid();
        } else {
            mSSID = DEFAULT_SSID;
        }
        ((ValidatedEditTextPreference) mPreference).setValidator(this);

        if (mWifiManager.isWifiApEnabled() && config != null) {
            final Intent intent = WifiDppUtils.getHotspotConfiguratorIntentOrNull(mContext, config);

            if (intent == null) {
                Log.e(TAG, "Invalid security to share hotspot");
                ((WifiTetherSsidPreference) mPreference).setButtonVisible(false);
            } else {
                ((WifiTetherSsidPreference) mPreference).setButtonOnClickListener(
                        view -> shareHotspotNetwork(intent));
                ((WifiTetherSsidPreference) mPreference).setButtonVisible(true);
            }
        } else {
            ((WifiTetherSsidPreference) mPreference).setButtonVisible(false);
        }

        updateSsidDisplay((EditTextPreference) mPreference);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        mSSID = (String) newValue;
        updateSsidDisplay((EditTextPreference) preference);
        mListener.onTetherConfigUpdated();
        return true;
    }

    @Override
    public boolean isTextValid(String value) {
        return mWifiDeviceNameTextValidator.isTextValid(value);
    }

    public String getSSID() {
        return mSSID;
    }

    private void updateSsidDisplay(EditTextPreference preference) {
        preference.setText(mSSID);
        preference.setSummary(mSSID);
    }

    private void shareHotspotNetwork(Intent intent) {
        WifiDppUtils.showLockScreen(mContext, () -> mContext.startActivity(intent));
    }

    @VisibleForTesting
    boolean isQrCodeButtonAvailable() {
        return ((WifiTetherSsidPreference) mPreference).isQrCodeButtonAvailable();
    }
}
