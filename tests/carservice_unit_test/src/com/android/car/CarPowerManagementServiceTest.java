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

package com.android.car;

import static android.content.pm.UserInfo.FLAG_EPHEMERAL;
import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;
import static android.os.UserManager.USER_TYPE_FULL_GUEST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.hardware.power.ICarPowerStateListener;
import android.car.userlib.CarUserManagerHelper;
import android.car.userlib.HalCallback;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponse;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hardware.automotive.vehicle.V2_0.VehicleApPowerStateReq;
import android.hardware.automotive.vehicle.V2_0.VehicleApPowerStateShutdownParam;
import android.os.RemoteException;
import android.os.UserManager;
import android.sysprop.CarProperties;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import androidx.test.filters.FlakyTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.hal.PowerHalService;
import com.android.car.hal.PowerHalService.PowerState;
import com.android.car.systeminterface.DisplayInterface;
import com.android.car.systeminterface.IOInterface;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.car.systeminterface.WakeLockInterface;
import com.android.car.test.utils.TemporaryDirectory;
import com.android.car.user.CarUserService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class CarPowerManagementServiceTest {
    private static final String TAG = CarPowerManagementServiceTest.class.getSimpleName();
    private static final long WAIT_TIMEOUT_MS = 2000;
    private static final long WAIT_TIMEOUT_LONG_MS = 5000;
    private static final int NO_USER_INFO_FLAGS = 0;
    private static final String NEW_GUEST_NAME = "NewestGuestInTheBlock";

    private final MockDisplayInterface mDisplayInterface = new MockDisplayInterface();
    private final MockSystemStateInterface mSystemStateInterface = new MockSystemStateInterface();
    private final MockWakeLockInterface mWakeLockInterface = new MockWakeLockInterface();
    private final MockIOInterface mIOInterface = new MockIOInterface();
    private final PowerSignalListener mPowerSignalListener = new PowerSignalListener();
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private MockitoSession mSession;

    private MockedPowerHalService mPowerHal;
    private SystemInterface mSystemInterface;
    private CarPowerManagementService mService;
    private CompletableFuture<Void> mFuture;

    @Mock
    private CarUserManagerHelper mCarUserManagerHelper;
    @Mock
    private UserManager mUserManager;
    @Mock
    private Resources mResources;
    @Mock
    private CarUserService mUserService;

    // Wakeup time for the test; it's automatically set based on @WakeupTime annotation
    private int mWakeupTime;

    // Value used to set config_disableUserSwitchDuringResume - must be defined before initTest();
    private boolean mDisableUserSwitchDuringResume;

    // Tracks Log.wtf() calls made during code execution / used on verifyWtfNeverLogged()
    // TODO: move mechanism to common code / custom Rule
    private final List<UnsupportedOperationException> mWtfs = new ArrayList<>();

    @Rule
    public final TestRule setWakeupTimeRule = new TestWatcher() {
        protected void starting(Description description) {
            final String testName = description.getMethodName();
            try {
                Method testMethod = CarPowerManagementServiceTest.class.getMethod(testName);
                WakeupTime wakeupAnnotation = testMethod.getAnnotation(WakeupTime.class);
                if (wakeupAnnotation != null) {
                    mWakeupTime = wakeupAnnotation.value();
                    Log.d(TAG, "Using annotated wakeup time: " + mWakeupTime);
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not infer wakeupTime for " + testName, e);
            }
        }
    };

    @Before
    public void setUp() throws Exception {
        mSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(ActivityManager.class)
                .spyStatic(CarProperties.class)
                .spyStatic(Log.class)
                .startMocking();
        mPowerHal = new MockedPowerHalService(true /*isPowerStateSupported*/,
                true /*isDeepSleepAllowed*/, true /*isTimedWakeupAllowed*/);
        mSystemInterface = SystemInterface.Builder.defaultSystemInterface(mContext)
            .withDisplayInterface(mDisplayInterface)
            .withSystemStateInterface(mSystemStateInterface)
            .withWakeLockInterface(mWakeLockInterface)
            .withIOInterface(mIOInterface).build();
        doAnswer((invocation) -> {
            return addWtf(invocation);
        }).when(() -> Log.wtf(anyString(), anyString()));
        doAnswer((invocation) -> {
            return addWtf(invocation);
        }).when(() -> Log.wtf(anyString(), anyString(), notNull()));
    }

    @After
    public void tearDown() throws Exception {
        if (mService != null) {
            mService.release();
        }
        mIOInterface.tearDown();
        mSession.finishMocking();
    }


    private Object addWtf(InvocationOnMock invocation) {
        String message = "Called " + invocation;
        Log.d(TAG, message); // Log always, as some test expect it
        mWtfs.add(new UnsupportedOperationException(message));
        return null;
    }

    /**
     * Helper method to create mService and initialize a test case
     */
    private void initTest() throws Exception {
        when(mResources.getInteger(R.integer.maxGarageModeRunningDurationInSecs))
                .thenReturn(900);
        when(mResources.getBoolean(R.bool.config_disableUserSwitchDuringResume))
                .thenReturn(mDisableUserSwitchDuringResume);

        Log.i(TAG, "initTest(): overridden overlay properties: "
                + "config_disableUserSwitchDuringResume="
                + mResources.getBoolean(R.bool.config_disableUserSwitchDuringResume)
                + ", maxGarageModeRunningDurationInSecs="
                + mResources.getInteger(R.integer.maxGarageModeRunningDurationInSecs));
        mService = new CarPowerManagementService(mContext, mResources, mPowerHal,
                mSystemInterface, mCarUserManagerHelper, mUserManager, mUserService,
                NEW_GUEST_NAME);
        mService.init();
        mService.setShutdownTimersForTest(0, 0);
        mPowerHal.setSignalListener(mPowerSignalListener);
        if (mWakeupTime > 0) {
            registerListenerToService();
            mService.scheduleNextWakeupTime(mWakeupTime);
        }
        assertStateReceived(MockedPowerHalService.SET_WAIT_FOR_VHAL, 0);
    }

    /**
     * Same as {@link #initTest()}, but it also assumes the current and initial users are user 10.
     */
    private void initTestForUser10() throws Exception {
        initTest();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setCurrentUser(10);
        setInitialUser(10);
    }

    @Test
    public void testBootComplete() throws Exception {
        initTest();

        verifyWtfNeverLogged();
    }

    @Test
    public void testDisplayOn() throws Exception {
        // start with display off
        mSystemInterface.setDisplayState(false);
        mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS);
        initTestForUser10();
        // Transition to ON state
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.ON, 0));

        // display should be turned on as it started with off state.
        assertThat(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS)).isTrue();

        verifyWtfNeverLogged();
    }

    @Test
    public void testShutdown() throws Exception {
        initTestForUser10();

        // Transition to ON state
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.ON, 0));
        assertThat(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS)).isTrue();

        mPowerHal.setCurrentPowerState(
                new PowerState(
                        VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                        VehicleApPowerStateShutdownParam.SHUTDOWN_IMMEDIATELY));
        // Since modules have to manually schedule next wakeup, we should not schedule next wakeup
        // To test module behavior, we need to actually implement mock listener module.
        assertStateReceived(PowerHalService.SET_SHUTDOWN_START, 0);
        assertThat(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS)).isFalse();
        mPowerSignalListener.waitForShutdown(WAIT_TIMEOUT_MS);
        mSystemStateInterface.waitForShutdown(WAIT_TIMEOUT_MS);

        verifyWtfNeverLogged();
    }

    @Test
    public void testSuspend() throws Exception {
        initTestForUser10();

        // Start in the ON state
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.ON, 0));
        assertThat(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS)).isTrue();
        // Request suspend
        mPowerHal.setCurrentPowerState(
                new PowerState(
                        VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                        VehicleApPowerStateShutdownParam.CAN_SLEEP));
        // Verify suspend
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, WAIT_TIMEOUT_LONG_MS, mWakeupTime);

        verifyWtfNeverLogged();
    }

    @Test
    public void testShutdownOnSuspend() throws Exception {
        initTestForUser10();

        // Start in the ON state
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.ON, 0));
        assertThat(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS)).isTrue();
        // Tell it to shutdown
        mService.requestShutdownOnNextSuspend();
        // Request suspend
        mPowerHal.setCurrentPowerState(
                new PowerState(
                        VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                        VehicleApPowerStateShutdownParam.CAN_SLEEP));
        // Verify shutdown
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_SHUTDOWN_START, WAIT_TIMEOUT_LONG_MS, mWakeupTime);
        mPowerSignalListener.waitForShutdown(WAIT_TIMEOUT_MS);
        // Send the finished signal
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.FINISHED, 0));
        mSystemStateInterface.waitForShutdown(WAIT_TIMEOUT_MS);
        // Cancel the shutdown
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.CANCEL_SHUTDOWN, 0));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_SHUTDOWN_CANCELLED, WAIT_TIMEOUT_LONG_MS, 0);

        // Request suspend again
        mPowerHal.setCurrentPowerState(
                new PowerState(
                        VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                        VehicleApPowerStateShutdownParam.CAN_SLEEP));
        // Verify suspend
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, WAIT_TIMEOUT_LONG_MS, mWakeupTime);
        verifyWtfNeverLogged();
    }

    @Test
    public void testShutdownCancel() throws Exception {
        initTestForUser10();

        // Start in the ON state
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.ON, 0));
        assertThat(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS)).isTrue();
        // Start shutting down
        mPowerHal.setCurrentPowerState(
                new PowerState(
                        VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                        VehicleApPowerStateShutdownParam.SHUTDOWN_IMMEDIATELY));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_SHUTDOWN_START, WAIT_TIMEOUT_LONG_MS, 0);
        // Cancel the shutdown
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.CANCEL_SHUTDOWN, 0));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_SHUTDOWN_CANCELLED, WAIT_TIMEOUT_LONG_MS, 0);
        // Go to suspend
        mPowerHal.setCurrentPowerState(
                new PowerState(
                        VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                        VehicleApPowerStateShutdownParam.CAN_SLEEP));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, WAIT_TIMEOUT_LONG_MS, mWakeupTime);
        verifyWtfNeverLogged();
    }

    @Test
    public void testSleepImmediately() throws Exception {
        initTestForUser10();

        // Transition to ON state
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.ON, 0));
        assertThat(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS)).isTrue();

        mPowerHal.setCurrentPowerState(
                new PowerState(
                        VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                        VehicleApPowerStateShutdownParam.SLEEP_IMMEDIATELY));
        // Since modules have to manually schedule next wakeup, we should not schedule next wakeup
        // To test module behavior, we need to actually implement mock listener module.
        assertStateReceived(PowerHalService.SET_SHUTDOWN_START, 0);
        assertThat(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS)).isFalse();
        mPowerSignalListener.waitForShutdown(WAIT_TIMEOUT_MS);
        mSystemStateInterface.waitForShutdown(WAIT_TIMEOUT_MS);
        verifyWtfNeverLogged();
    }

    @Test
    @WakeupTime(100)
    @FlakyTest
    public void testShutdownWithProcessing() throws Exception {
        initTestForUser10();
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE, 0));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_SHUTDOWN_START, WAIT_TIMEOUT_LONG_MS, mWakeupTime);
        mPowerSignalListener.waitForShutdown(WAIT_TIMEOUT_MS);
        // Send the finished signal
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.FINISHED, 0));
        mSystemStateInterface.waitForShutdown(WAIT_TIMEOUT_MS);
        verifyWtfNeverLogged();
    }

    @Test
    @WakeupTime(100)
    public void testSleepEntryAndWakeup() throws Exception {
        initTest();
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                VehicleApPowerStateShutdownParam.CAN_SLEEP));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, WAIT_TIMEOUT_LONG_MS, mWakeupTime);
        mPowerSignalListener.waitForSleepEntry(WAIT_TIMEOUT_MS);
        // Send the finished signal from HAL to CPMS
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.FINISHED, 0));
        mSystemStateInterface.waitForSleepEntryAndWakeup(WAIT_TIMEOUT_MS);
        assertStateReceived(PowerHalService.SET_DEEP_SLEEP_EXIT, 0);
        mPowerSignalListener.waitForSleepExit(WAIT_TIMEOUT_MS);
        verifyWtfNeverLogged();
    }

    /**
     * This test case tests the same scenario as {@link #testUserSwitchingOnResume_differentUser()},
     * but indirectly triggering {@code switchUserOnResumeIfNecessary()} through HAL events.
     */
    @Test
    public void testSleepEntryAndWakeUpForProcessing() throws Exception {
        initTest();
        // Speed up the polling for power state transitions
        mService.setShutdownTimersForTest(10, 40);
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setUserInfo(11, NO_USER_INFO_FLAGS);
        setCurrentUser(10);
        setInitialUser(11);

        suspendAndResume();

        verifyUserSwitched(11);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_differentUser() throws Exception {
        initTest();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setUserInfo(11, NO_USER_INFO_FLAGS);
        setCurrentUser(10);
        setInitialUser(11);

        suspendAndResumeForUserSwitchingTests();

        verifyUserSwitched(11);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_sameUser() throws Exception {
        initTest();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setInitialUser(10);
        setCurrentUser(10);

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_differentEphemeralUser() throws Exception {
        initTest();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setUserInfo(11, FLAG_EPHEMERAL);
        setCurrentUser(10);
        setInitialUser(11);

        suspendAndResumeForUserSwitchingTests();

        verifyUserSwitched(11);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_sameGuest() throws Exception {
        initTest();
        setUserInfo(10, "ElGuesto", USER_TYPE_FULL_GUEST, FLAG_EPHEMERAL);
        setInitialUser(10);
        setCurrentUser(10);
        expectGuestMarkedForDeletionOk(10);
        expectNewGuestCreated(11);

        suspendAndResumeForUserSwitchingTests();

        verifyUserRemoved(10);
        verifyUserSwitched(11);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_differentGuest() throws Exception {
        initTest();
        setUserInfo(11, "ElGuesto", USER_TYPE_FULL_GUEST, FLAG_EPHEMERAL);
        setInitialUser(11);
        setCurrentUser(10);
        expectGuestMarkedForDeletionOk(11);
        expectNewGuestCreated(12);

        suspendAndResumeForUserSwitchingTests();

        verifyUserRemoved(11);
        verifyUserSwitched(12);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_guestCreationFailed() throws Exception {
        initTest();
        setUserInfo(10, "ElGuesto", USER_TYPE_FULL_GUEST, FLAG_EPHEMERAL);
        setInitialUser(10);
        setCurrentUser(10);
        expectGuestMarkedForDeletionOk(10);
        expectNewGuestCreationFailed("ElGuesto");

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        verifyUserNotRemoved(10);
        // expects WTF
    }

    @Test
    public void testUserSwitchingOnResume_differentPersistentGuest() throws Exception {
        initTest();
        setUserInfo(11, "ElGuesto", USER_TYPE_FULL_GUEST, NO_USER_INFO_FLAGS);
        setInitialUser(11);
        setCurrentUser(10);
        expectGuestMarkedForDeletionOk(11);
        expectNewGuestCreated(12);

        suspendAndResumeForUserSwitchingTests();

        verifyUserRemoved(11);
        verifyUserSwitched(12);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_preDeleteGuestFail() throws Exception {
        initTest();
        setUserInfo(10, "ElGuesto", USER_TYPE_FULL_GUEST, FLAG_EPHEMERAL);
        setInitialUser(10);
        setCurrentUser(10);
        expectGuestMarkedForDeletionFail(10);

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        verifyNoGuestCreated();
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_systemUser() throws Exception {
        initTest();
        setInitialUser(USER_SYSTEM);
        setCurrentUser(10);

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        // expects WTF
    }

    @Test
    public void testUserSwitchingOnResume_noInitialInfo() throws Exception {
        initTest();
        setInitialUser(USER_NULL);
        setCurrentUser(10);

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        // expects WTF
    }

    @Test
    public void testUserSwitchingOnResume_disabledByOEM_differentUser() throws Exception {
        disableUserSwitchingDuringResume();
        initTest();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setUserInfo(11, NO_USER_INFO_FLAGS);
        setCurrentUser(10);
        setInitialUser(11);

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_disabledByOEM_sameUser() throws Exception {
        disableUserSwitchingDuringResume();
        initTest();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setInitialUser(10);
        setCurrentUser(10);

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_disabledByOEM_differentEphemeralUser() throws Exception {
        disableUserSwitchingDuringResume();
        initTest();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setUserInfo(11, FLAG_EPHEMERAL);
        setCurrentUser(10);
        setInitialUser(11);

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_disabledByOEM_sameGuest() throws Exception {
        disableUserSwitchingDuringResume();
        initTest();
        setUserInfo(10, "ElGuesto", USER_TYPE_FULL_GUEST, FLAG_EPHEMERAL);
        setInitialUser(10);
        setCurrentUser(10);
        expectGuestMarkedForDeletionOk(10);
        expectNewGuestCreated(11);

        suspendAndResumeForUserSwitchingTests();

        verifyUserRemoved(10);
        verifyUserSwitched(11);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_disabledByOEM_differentGuest() throws Exception {
        disableUserSwitchingDuringResume();
        initTest();
        setUserInfo(11, "ElGuesto", USER_TYPE_FULL_GUEST, FLAG_EPHEMERAL);
        setInitialUser(11);
        setCurrentUser(10);
        expectGuestMarkedForDeletionOk(11);
        expectNewGuestCreated(12);

        suspendAndResumeForUserSwitchingTests();

        verifyUserRemoved(11);
        verifyUserSwitched(12);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_disabledByOEM_guestCreationFailed() throws Exception {
        disableUserSwitchingDuringResume();
        initTest();
        setUserInfo(10, "ElGuesto", USER_TYPE_FULL_GUEST, FLAG_EPHEMERAL);
        setInitialUser(10);
        setCurrentUser(10);
        expectGuestMarkedForDeletionOk(10);
        expectNewGuestCreationFailed("ElGuesto");

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        verifyUserNotRemoved(10);
        // expects WTF
    }

    @Test
    public void testUserSwitchingOnResume_disabledByOEM_differentPersistentGuest()
            throws Exception {
        disableUserSwitchingDuringResume();
        initTest();
        setUserInfo(11, "ElGuesto", USER_TYPE_FULL_GUEST, NO_USER_INFO_FLAGS);
        setInitialUser(11);
        setCurrentUser(10);
        expectGuestMarkedForDeletionOk(11);
        expectNewGuestCreated(12);

        suspendAndResumeForUserSwitchingTests();

        verifyUserRemoved(11);
        verifyUserSwitched(12);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_disabledByOEM_preDeleteGuestFail() throws Exception {
        disableUserSwitchingDuringResume();
        initTest();
        setUserInfo(10, "ElGuesto", USER_TYPE_FULL_GUEST, FLAG_EPHEMERAL);
        setInitialUser(10);
        setCurrentUser(10);
        expectGuestMarkedForDeletionFail(10);

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        verifyNoGuestCreated();
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingOnResume_disabledByOEM_systemUser() throws Exception {
        disableUserSwitchingDuringResume();
        initTest();
        setInitialUser(USER_SYSTEM);
        setCurrentUser(10);

        suspendAndResumeForUserSwitchingTests();

        verifyUserNotSwitched();
        // expects WTF
    }

    @Test
    public void testUserSwitchingUsingHal_failure_setTimeout() throws Exception {
        userSwitchingWhenHalFailsTest(HalCallback.STATUS_HAL_SET_TIMEOUT);
    }

    @Test
    public void testUserSwitchingUsingHal_failure_responseTimeout() throws Exception {
        userSwitchingWhenHalFailsTest(HalCallback.STATUS_HAL_RESPONSE_TIMEOUT);
    }

    @Test
    public void testUserSwitchingUsingHal_failure_concurrentOperation() throws Exception {
        userSwitchingWhenHalFailsTest(HalCallback.STATUS_CONCURRENT_OPERATION);
    }

    @Test
    public void testUserSwitchingUsingHal_failure_wrongResponse() throws Exception {
        userSwitchingWhenHalFailsTest(HalCallback.STATUS_WRONG_HAL_RESPONSE);
    }

    @Test
    public void testUserSwitchingUsingHal_failure_invalidResponse() throws Exception {
        userSwitchingWhenHalFailsTest(-666);
    }

    /**
     * Tests all scenarios where the HAL.getInitialUserInfo() call failed - the outcome is the
     * same, it should use the default behavior.
     */
    private void userSwitchingWhenHalFailsTest(int status) throws Exception {
        initTest();
        enableUserHal();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setUserInfo(11, NO_USER_INFO_FLAGS);
        setCurrentUser(10);
        setInitialUser(11);
        setGetUserInfoResponse((c) -> c.onResponse(status, /* response= */ null));

        suspendAndResumeForUserSwitchingTests();

        verifyUserSwitched(11);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingUsingHal_invalidAction() throws Exception {
        initTest();
        enableUserHal();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setUserInfo(11, NO_USER_INFO_FLAGS);
        setCurrentUser(10);
        setInitialUser(11);
        InitialUserInfoResponse response = new InitialUserInfoResponse();
        response.action = -666;
        setGetUserInfoResponse((c) -> c.onResponse(HalCallback.STATUS_OK, response));

        suspendAndResumeForUserSwitchingTests();

        verifyUserSwitched(11);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingUsingHal_default_nullResponse() throws Exception {
        initTest();
        enableUserHal();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setUserInfo(11, NO_USER_INFO_FLAGS);
        setCurrentUser(10);
        setInitialUser(11);
        setGetUserInfoResponse((c) -> c.onResponse(HalCallback.STATUS_OK, /* response= */ null));
        suspendAndResumeForUserSwitchingTests();

        verifyUserSwitched(11);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingUsingHal_default_ok() throws Exception {
        initTest();
        enableUserHal();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setUserInfo(11, NO_USER_INFO_FLAGS);
        setCurrentUser(10);
        setInitialUser(11);
        InitialUserInfoResponse response = new InitialUserInfoResponse();
        response.action = InitialUserInfoResponseAction.DEFAULT;
        setGetUserInfoResponse((c) -> c.onResponse(HalCallback.STATUS_OK, response));

        suspendAndResumeForUserSwitchingTests();

        verifyUserSwitched(11);
        verifyWtfNeverLogged();
    }

    @Test
    public void testUserSwitchingUsingHal_switch_ok() throws Exception {
        initTest();
        enableUserHal();
        setUserInfo(10, NO_USER_INFO_FLAGS);
        setUserInfo(11, NO_USER_INFO_FLAGS);
        setCurrentUser(10);
        setInitialUser(11);
        InitialUserInfoResponse response = new InitialUserInfoResponse();
        response.action = InitialUserInfoResponseAction.SWITCH;
        setGetUserInfoResponse((c) -> c.onResponse(HalCallback.STATUS_OK, response));

        suspendAndResumeForUserSwitchingTests();

        verifyUserSwitched(11);
        verifyWtfNeverLogged();

        // Make sure HAL was called, otherwise test could pass when the property was not set
        verify(mUserService).getInitialUserInfo(eq(InitialUserInfoRequestType.RESUME), notNull());
    }

    private void setGetUserInfoResponse(Visitor<HalCallback<InitialUserInfoResponse>> visitor) {
        doAnswer((invocation) -> {
            HalCallback<InitialUserInfoResponse> callback = invocation.getArgument(1);
            visitor.visit(callback);
            return null;
        }).when(mUserService).getInitialUserInfo(eq(InitialUserInfoRequestType.RESUME), notNull());
    }

    private void enableUserHal() {
        when(CarProperties.user_hal_enabled()).thenReturn(Optional.of(true));
        when(mUserService.isUserHalSupported()).thenReturn(true);
    }

    private void suspendAndResume() throws Exception {
        Log.d(TAG, "suspend()");
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                VehicleApPowerStateShutdownParam.CAN_SLEEP));
        assertThat(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS)).isFalse();
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, WAIT_TIMEOUT_LONG_MS, mWakeupTime);
        mPowerSignalListener.waitForSleepEntry(WAIT_TIMEOUT_MS);

        // Send the finished signal
        Log.d(TAG, "resume()");
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.FINISHED, 0));
        mSystemStateInterface.setWakeupCausedByTimer(true);
        mSystemStateInterface.waitForSleepEntryAndWakeup(WAIT_TIMEOUT_MS);
        assertStateReceived(PowerHalService.SET_DEEP_SLEEP_EXIT, 0);
        mPowerSignalListener.waitForSleepExit(WAIT_TIMEOUT_MS);
        mService.scheduleNextWakeupTime(mWakeupTime);
        // second processing after wakeup
        assertThat(mDisplayInterface.getDisplayState()).isFalse();

        mService.setStateForTesting(/* isBooting= */ false, /* isResuming= */ true);

        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.ON, 0));
        assertThat(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS)).isTrue();
        // Should wait until Handler has finished ON processing.
        CarServiceUtils.runOnLooperSync(mService.getHandlerThread().getLooper(), () -> { });
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                VehicleApPowerStateShutdownParam.CAN_SLEEP));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, WAIT_TIMEOUT_LONG_MS, mWakeupTime);
        mPowerSignalListener.waitForSleepEntry(WAIT_TIMEOUT_MS);
        mPowerHal.setCurrentPowerState(new PowerState(VehicleApPowerStateReq.FINISHED, 0));
        // PM will shutdown system as it was not woken-up due timer and it is not power on.
        mSystemStateInterface.setWakeupCausedByTimer(false);
        mSystemStateInterface.waitForSleepEntryAndWakeup(WAIT_TIMEOUT_MS);
        // Since we just woke up from shutdown, wake up time will be 0
        assertStateReceived(PowerHalService.SET_DEEP_SLEEP_EXIT, 0);
        assertThat(mDisplayInterface.getDisplayState()).isFalse();
    }

    private void suspendAndResumeForUserSwitchingTests() throws Exception {
        mService.switchUserOnResumeIfNecessary(!mDisableUserSwitchDuringResume);
    }

    private void registerListenerToService() {
        ICarPowerStateListener listenerToService = new ICarPowerStateListener.Stub() {
            @Override
            public void onStateChanged(int state) throws RemoteException {
                if (state == CarPowerStateListener.SHUTDOWN_ENTER
                        || state == CarPowerStateListener.SUSPEND_ENTER) {
                    mFuture = new CompletableFuture<>();
                    mFuture.whenComplete((res, ex) -> {
                        if (ex == null) {
                            mService.finished(this);
                        }
                    });
                } else {
                    mFuture = null;
                }
            }
        };
        mService.registerListener(listenerToService);
    }

    private void assertStateReceived(int expectedState, int expectedParam) throws Exception {
        int[] state = mPowerHal.waitForSend(WAIT_TIMEOUT_MS);
        assertThat(state[0]).isEqualTo(expectedState);
        assertThat(state[1]).isEqualTo(expectedParam);
    }

    private void assertStateReceivedForShutdownOrSleepWithPostpone(
            int lastState, long timeoutMs, int expectedParamForShutdownOrSuspend) throws Exception {
        while (true) {
            if (mFuture != null && !mFuture.isDone()) {
                mFuture.complete(null);
            }
            int[] state = mPowerHal.waitForSend(timeoutMs);
            if (state[0] == PowerHalService.SET_SHUTDOWN_POSTPONE) {
                continue;
            }
            if (state[0] == lastState) {
                assertThat(state[1]).isEqualTo(expectedParamForShutdownOrSuspend);
                return;
            }
        }
    }

    // TODO: should be part of @After, but then it would hide the real test failure (if any). We'd
    // need a custom rule (like CTS's SafeCleaner) for it...
    private void verifyWtfNeverLogged() {
        int size = mWtfs.size();

        switch (size) {
            case 0:
                return;
            case 1:
                throw mWtfs.get(0);
            default:
                StringBuilder msg = new StringBuilder("wtf called ").append(size).append(" times")
                        .append(": ").append(mWtfs);
                fail(msg.toString());
        }
    }

    private static void waitForSemaphore(Semaphore semaphore, long timeoutMs)
            throws InterruptedException {
        if (!semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("timeout");
        }
    }

    private void setInitialUser(int userId) {
        when(mCarUserManagerHelper.getInitialUser()).thenReturn(userId);
    }

    private void setCurrentUser(int userId) {
        when(ActivityManager.getCurrentUser()).thenReturn(userId);
    }

    private void setUserInfo(int userId, int flags) {
        setUserInfo(userId, /* name= */ null, /* userType= */ null, flags);
    }

    private void setUserInfo(int userId, @Nullable String name, @Nullable String userType,
            int flags) {
        final UserInfo userInfo = new UserInfo();
        userInfo.id = userId;
        userInfo.name = name;
        userInfo.flags = flags;
        if (userType != null) {
            userInfo.userType = userType;
        }
        Log.v(TAG, "UM.getUserInfo("  + userId + ") will return " + userInfo.toFullString());
        when(mUserManager.getUserInfo(userId)).thenReturn(userInfo);
    }

    private void verifyUserNotSwitched() {
        verify(mCarUserManagerHelper, never()).startForegroundUser(anyInt());
    }

    private void verifyUserSwitched(int userId) {
        verify(mCarUserManagerHelper, times(1)).startForegroundUser(userId);
    }

    private void verifyNoGuestCreated() {
        verify(mUserManager, never()).createGuest(notNull(), anyString());
    }

    private void expectGuestMarkedForDeletionOk(int userId) {
        when(mUserManager.markGuestForDeletion(userId)).thenReturn(true);
    }

    private void expectGuestMarkedForDeletionFail(int userId) {
        when(mUserManager.markGuestForDeletion(userId)).thenReturn(false);
    }

    private void expectNewGuestCreated(int userId) {
        final UserInfo userInfo = new UserInfo();
        userInfo.id = userId;
        userInfo.name = NEW_GUEST_NAME;
        when(mUserManager.createGuest(notNull(), eq(NEW_GUEST_NAME))).thenReturn(userInfo);
    }

    private void expectNewGuestCreationFailed(String name) {
        when(mUserManager.createGuest(notNull(), eq(name))).thenReturn(null);
    }

    private void verifyUserRemoved(int userId) {
        verify(mUserManager, times(1)).removeUser(userId);
    }

    private void verifyUserNotRemoved(int userId) {
        verify(mUserManager, never()).removeUser(userId);
    }

    private void disableUserSwitchingDuringResume() {
        mDisableUserSwitchDuringResume = true;
    }

    private static final class MockDisplayInterface implements DisplayInterface {
        private boolean mDisplayOn = true;
        private final Semaphore mDisplayStateWait = new Semaphore(0);

        @Override
        public void setDisplayBrightness(int brightness) {}

        @Override
        public synchronized void setDisplayState(boolean on) {
            mDisplayOn = on;
            mDisplayStateWait.release();
        }

        public synchronized boolean getDisplayState() {
            return mDisplayOn;
        }

        public boolean waitForDisplayStateChange(long timeoutMs) throws Exception {
            waitForSemaphore(mDisplayStateWait, timeoutMs);
            return mDisplayOn;
        }

        @Override
        public void startDisplayStateMonitoring(CarPowerManagementService service) {}

        @Override
        public void stopDisplayStateMonitoring() {}

        @Override
        public void refreshDisplayBrightness() {}
    }

    private static final class MockSystemStateInterface implements SystemStateInterface {
        private final Semaphore mShutdownWait = new Semaphore(0);
        private final Semaphore mSleepWait = new Semaphore(0);
        private final Semaphore mSleepExitWait = new Semaphore(0);
        private boolean mWakeupCausedByTimer = false;

        @Override
        public void shutdown() {
            mShutdownWait.release();
        }

        public void waitForShutdown(long timeoutMs) throws Exception {
            waitForSemaphore(mShutdownWait, timeoutMs);
        }

        @Override
        public boolean enterDeepSleep() {
            mSleepWait.release();
            try {
                mSleepExitWait.acquire();
            } catch (InterruptedException e) {
            }
            return true;
        }

        public void waitForSleepEntryAndWakeup(long timeoutMs) throws Exception {
            waitForSemaphore(mSleepWait, timeoutMs);
            mSleepExitWait.release();
        }

        @Override
        public void scheduleActionForBootCompleted(Runnable action, Duration delay) {}

        @Override
        public boolean isWakeupCausedByTimer() {
            Log.i(TAG, "isWakeupCausedByTimer:" + mWakeupCausedByTimer);
            return mWakeupCausedByTimer;
        }

        public synchronized void setWakeupCausedByTimer(boolean set) {
            mWakeupCausedByTimer = set;
        }

        @Override
        public boolean isSystemSupportingDeepSleep() {
            return true;
        }
    }

    private static final class MockWakeLockInterface implements WakeLockInterface {

        @Override
        public void releaseAllWakeLocks() {}

        @Override
        public void switchToPartialWakeLock() {}

        @Override
        public void switchToFullWakeLock() {}
    }

    private static final class MockIOInterface implements IOInterface {
        private TemporaryDirectory mFilesDir;

        @Override
        public File getSystemCarDir() {
            if (mFilesDir == null) {
                try {
                    mFilesDir = new TemporaryDirectory(TAG);
                } catch (IOException e) {
                    Log.e(TAG, "failed to create temporary directory", e);
                    fail("failed to create temporary directory. exception was: " + e);
                }
            }
            return mFilesDir.getDirectory();
        }

        public void tearDown() {
            if (mFilesDir != null) {
                try {
                    mFilesDir.close();
                } catch (Exception e) {
                    Log.w(TAG, "could not remove temporary directory", e);
                }
            }
        }
    }

    private class PowerSignalListener implements MockedPowerHalService.SignalListener {
        private final Semaphore mShutdownWait = new Semaphore(0);
        private final Semaphore mSleepEntryWait = new Semaphore(0);
        private final Semaphore mSleepExitWait = new Semaphore(0);

        public void waitForSleepExit(long timeoutMs) throws Exception {
            waitForSemaphore(mSleepExitWait, timeoutMs);
        }

        public void waitForShutdown(long timeoutMs) throws Exception {
            waitForSemaphore(mShutdownWait, timeoutMs);
        }

        public void waitForSleepEntry(long timeoutMs) throws Exception {
            waitForSemaphore(mSleepEntryWait, timeoutMs);
        }

        @Override
        public void sendingSignal(int signal) {
            if (signal == PowerHalService.SET_SHUTDOWN_START) {
                mShutdownWait.release();
                return;
            }
            if (signal == PowerHalService.SET_DEEP_SLEEP_ENTRY) {
                mSleepEntryWait.release();
                return;
            }
            if (signal == PowerHalService.SET_DEEP_SLEEP_EXIT) {
                mSleepExitWait.release();
                return;
            }
        }
    }

    @Retention(RUNTIME)
    @Target({METHOD})
    private @interface WakeupTime {
        int value();
    }

    // TODO(b/149099817): move to common code
    private interface Visitor<T> {
        void visit(T t);
    }
}
