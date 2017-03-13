/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car.test;

import android.car.annotation.FutureFeature;
import android.car.vms.VmsPublisherClientService;

/**
 * This service is launched during the tests in VmsPublisherSubscriberTest. It publishes a property
 * that is going to be verified in the test.
 *
 * Note that the subscriber can subscribe before the publisher finishes initialization. To cover
 * both potential scenarios, this service publishes the test message in onVmsSubscriptionChange
 * and in onVmsPublisherServiceReady. See comments below.
 */
@FutureFeature
public class VmsPublisherClientMockService extends VmsPublisherClientService {

    @Override
    public void onVmsSubscriptionChange(int layerId, int layerVersion, boolean hasSubscribers) {
        // Case when the publisher finished initialization before the subscription request.
        if (layerId == VmsPublisherSubscriberTest.LAYER_ID
                && layerVersion == VmsPublisherSubscriberTest.LAYER_VERSION && hasSubscribers) {
            publish(VmsPublisherSubscriberTest.LAYER_ID, VmsPublisherSubscriberTest.LAYER_VERSION,
                    VmsPublisherSubscriberTest.PAYLOAD);
        }
    }

    @Override
    public void onVmsPublisherServiceReady() {
        // Case when the subscription request was sent before the publisher was ready.
        if (hasSubscribers(VmsPublisherSubscriberTest.LAYER_ID,
                VmsPublisherSubscriberTest.LAYER_VERSION)) {
            publish(VmsPublisherSubscriberTest.LAYER_ID, VmsPublisherSubscriberTest.LAYER_VERSION,
                    VmsPublisherSubscriberTest.PAYLOAD);
        }
    }
}
