/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.car.navigation;

import android.car.CarLibLog;
import android.car.CarManagerBase;
import android.car.cluster.renderer.IInstrumentClusterNavigation;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * API for providing navigation status for instrument cluster.
 */
public final class CarNavigationStatusManager implements CarManagerBase {
    private static final String TAG = CarLibLog.TAG_NAV;

    private final IInstrumentClusterNavigation mService;

    /**
     * Only for CarServiceLoader
     * @hide
     */
    public CarNavigationStatusManager(IBinder service) {
        mService = IInstrumentClusterNavigation.Stub.asInterface(service);
    }

    /**
     * Sends events from navigation app to instrument cluster.
     *
     * <p>The event type and bundle can be populated by
     * {@link android.support.car.navigation.CarNavigationStatusEvent}.
     *
     * @param eventType event type
     * @param bundle object that holds data about the event
     */
    public void sendEvent(int eventType, Bundle bundle) {
        try {
            mService.onEvent(eventType, bundle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        Log.e(TAG, "Car service disconnected");
    }

    /** Returns navigation features of instrument cluster */
    public CarNavigationInstrumentCluster getInstrumentClusterInfo() {
        try {
            return mService.getInstrumentClusterInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
