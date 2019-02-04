/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package android.car.settings;

/**
 * System level car related settings.
 */
public class CarSettings {

    /**
     * Global car settings, containing preferences that always apply identically
     * to all defined users.  Applications can read these but are not allowed to write;
     * like the "Secure" settings, these are for preferences that the user must
     * explicitly modify through the system UI or specialized APIs for those values.
     *
     * To read/write the global car settings, use {@link android.provider.Settings.Global}
     * with the keys defined here.
     */
    public static final class Global {
        /**
         * DEPRECATED. Will be removed in Q. Key for when to wake up to run garage mode.
         * @deprecated not used by GarageMode anymore. Will be removed in Q.
         */
        @Deprecated
        public static final String KEY_GARAGE_MODE_WAKE_UP_TIME =
                "android.car.GARAGE_MODE_WAKE_UP_TIME";
        /**
         * DEPRECATED. Will be removed in Q. Key for whether garage mode is enabled.
         * @deprecated not used by GarageMode anymore. Will be removed in Q.
         */
        @Deprecated
        public static final String KEY_GARAGE_MODE_ENABLED = "android.car.GARAGE_MODE_ENABLED";

        /**
         * DEPRECATED. Will be removed in Q. Key for garage mode maintenance window.
         * @deprecated not used by GarageMode anymore. Will be removed in Q.
         */
        @Deprecated
        public static final String KEY_GARAGE_MODE_MAINTENANCE_WINDOW =
                "android.car.GARAGE_MODE_MAINTENANCE_WINDOW";

        /**
         * Key for default user id to boot into.
         *
         * @hide
         */
        public static final String DEFAULT_USER_ID_TO_BOOT_INTO =
                "android.car.DEFAULT_BOOT_INTO_USER_ID";

        /**
         * Key for user id that is last logged in to.
         *
         * @hide
         */
        public static final String LAST_ACTIVE_USER_ID =
                "android.car.LAST_ACTIVE_USER_ID";

        /**
         * Whether default restrictions for users have been set.
         *
         * @hide
         */
        public static final String DEFAULT_USER_RESTRICTIONS_SET =
                "android.car.DEFAULT_USER_RESTRICTIONS_SET";
    }

    /**
     * Default garage mode wake up time 00:00
     *
     * @hide
     */
    public static final int[] DEFAULT_GARAGE_MODE_WAKE_UP_TIME = {0, 0};

    /**
     * Default garage mode maintenance window 10 mins.
     *
     * @hide
     */
    public static final int DEFAULT_GARAGE_MODE_MAINTENANCE_WINDOW = 10 * 60 * 1000; // 10 mins

    /**
     * @hide
     */
    public static final class Secure {

        /**
         * Key for a list of devices to automatically connect on Bluetooth A2dp/Avrcp profiles
         * Written to and read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_MUSIC_DEVICES =
                "android.car.BLUETOOTH_AUTOCONNECT_MUSIC_DEVICES";
        /**
         * Key for a list of devices to automatically connect on Bluetooth HFP & PBAP profiles
         * Written to and read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         *
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_PHONE_DEVICES =
                "android.car.BLUETOOTH_AUTOCONNECT_PHONE_DEVICES";

        /**
         * Key for a list of devices to automatically connect on Bluetooth MAP profile
         * Written to and read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_MESSAGING_DEVICES =
                "android.car.BLUETOOTH_AUTOCONNECT_MESSAGING_DEVICES";

        /**
         * Key for a list of devices to automatically connect on Bluetooth PAN profile
         * Written to and read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_NETWORK_DEVICES =
                "android.car.BLUETOOTH_AUTOCONNECT_NETWORK_DEVICES";

        /**
         * Key for setting primary Music Device
         * Written to by a client with {@link android.Manifest.permission#BLUETOOTH_ADMIN}
         * Read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_MUSIC_DEVICE_PRIORITY_0 =
                "android.car.BLUETOOTH_AUTOCONNECT_MUSIC_DEVICE_PRIORITY_0";

        /**
         * Key for setting secondary Music Device
         * Written to by a client with {@link android.Manifest.permission#BLUETOOTH_ADMIN}
         * Read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_MUSIC_DEVICE_PRIORITY_1 =
                "android.car.BLUETOOTH_AUTOCONNECT_MUSIC_DEVICE_PRIORITY_1";

        /**
         * Key for setting Primary Phone Device
         * Written to by a client with {@link android.Manifest.permission#BLUETOOTH_ADMIN}
         * Read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_PHONE_DEVICE_PRIORITY_0 =
                "android.car.BLUETOOTH_AUTOCONNECT_PHONE_DEVICE_PRIORITY_0";

        /**
         * Key for setting Secondary Phone Device
         * Written to by a client with {@link android.Manifest.permission#BLUETOOTH_ADMIN}
         * Read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_PHONE_DEVICE_PRIORITY_1 =
                "android.car.BLUETOOTH_AUTOCONNECT_PHONE_DEVICE_PRIORITY_1";

        /**
         * Key for setting Primary Messaging Device
         * Written to by a client with {@link android.Manifest.permission#BLUETOOTH_ADMIN}
         * Read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_MESSAGING_DEVICE_PRIORITY_0 =
                "android.car.BLUETOOTH_AUTOCONNECT_MESSAGING_DEVICE_PRIORITY_0";

        /**
         * Key for setting Secondary Messaging Device
         * Written to by a client with {@link android.Manifest.permission#BLUETOOTH_ADMIN}
         * Read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_MESSAGING_DEVICE_PRIORITY_1 =
                "android.car.BLUETOOTH_AUTOCONNECT_MESSAGING_DEVICE_PRIORITY_1";

        /**
         * Key for setting Primary Network Device
         * Written to by a client with {@link com.android.car.Manifest.permission.BLUETOOTH_ADMIN}
         * Read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_NETWORK_DEVICE_PRIORITY_0 =
                "android.car.BLUETOOTH_AUTOCONNECT_NETWORK_DEVICE_PRIORITY_0";

        /**
         * Key for setting Secondary Network Device
         * Written to by a client with {@link com.android.car.Manifest.permission.BLUETOOTH_ADMIN}
         * Read by {@link com.android.car.BluetoothDeviceConnectionPolicy}
         * @hide
         */
        public static final String KEY_BLUETOOTH_AUTOCONNECT_NETWORK_DEVICE_PRIORITY_1 =
                "android.car.BLUETOOTH_AUTOCONNECT_NETWORK_DEVICE_PRIORITY_1";

        /**
         * Key for storing temporarily-disconnected devices and profiles.
         * Read and written by {@link com.android.car.BluetoothDeviceConnectionPolicy}.
         * @hide
         */
        public static final String KEY_BLUETOOTH_PROFILES_INHIBITED =
                "android.car.BLUETOOTH_PROFILES_INHIBITED";
    }
}
