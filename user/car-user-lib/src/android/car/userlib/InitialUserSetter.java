/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.car.userlib;

import static android.car.userlib.UserHalHelper.userFlagsToString;
import static android.car.userlib.UserHelper.safeName;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Helper used to set the initial Android user on boot or when resuming from RAM.
 */
public final class InitialUserSetter {

    private static final String TAG = InitialUserSetter.class.getSimpleName();

    // TODO(b/151758646): STOPSHIP if not false
    private static final boolean DBG = true;

    // TODO(b/150413304): abstract AM / UM into interfaces, then provide local and remote
    // implementation (where local is implemented by ActivityManagerInternal / UserManagerInternal)
    private final CarUserManagerHelper mHelper;
    private final UserManager mUm;

    // TODO(b/151758646): make sure it's unit tested
    private final boolean mSupportsOverrideUserIdProperty;

    public InitialUserSetter(@NonNull Context context, boolean supportsOverrideUserIdProperty) {
        this(new CarUserManagerHelper(context), UserManager.get(context),
                supportsOverrideUserIdProperty);
    }

    @VisibleForTesting
    public InitialUserSetter(@NonNull CarUserManagerHelper helper, @NonNull UserManager um,
            boolean supportsOverrideUserIdProperty) {
        mHelper = helper;
        mUm = um;
        mSupportsOverrideUserIdProperty = supportsOverrideUserIdProperty;
    }

    /**
     * Sets the initial user using the default behavior.
     *
     * <p>The default behavior is:
     * <ol>
     *   <li>On first boot, it creates and switches to a new user.
     *   <li>Otherwise, it will switch to either:
     *   <ol>
     *     <li>User defined by {@code android.car.systemuser.bootuseroverrideid} (when it was
     *       constructed with such option enabled).
     *     <li>Last active user (as defined by
     *       {@link android.provider.Settings..Global.LAST_ACTIVE_USER_ID}.
     *   </ol>
     * </ol>
     */
    public void executeDefaultBehavior() {
        if (DBG) Log.d(TAG, "executeDefaultBehavior()");
        // TODO(b/151758646): implement
    }

    /**
     * Switches to the given user, falling back to {@link #executeDefaultBehavior()} if it fails.
     */
    public void switchUser(@UserIdInt int userId) {
        if (DBG) Log.d(TAG, "switchUser(): userId= " + userId);

        if (!mHelper.switchToUserId(userId)) {
            Log.w(TAG, "am.switchUser(" + userId + ") failed; falling back to default behavior");
            executeDefaultBehavior();
        }
    }

    /**
     * Creates a new user and switches to it, falling back to {@link #executeDefaultBehavior()} if
     * any of these steps fails.
     *
     * @param name (optional) name of the new user
     * @param halFlags user flags as defined by Vehicle HAL ({@code UserFlags} enum).
     */
    public void createUser(@Nullable String name, int halFlags) {
        if (DBG) {
            Log.d(TAG, "createUser(name=" + safeName(name) + ", flags="
                    + userFlagsToString(halFlags) + ")");
        }

        if (UserHalHelper.isSystem(halFlags)) {
            Log.w(TAG, "Cannot create system user");
            executeDefaultBehavior();
            return;
        }

        if (UserHalHelper.isAdmin(halFlags)) {
            boolean validAdmin = true;
            if (UserHalHelper.isGuest(halFlags)) {
                Log.w(TAG, "Cannot create guest admin");
                validAdmin = false;
            }
            if (UserHalHelper.isEphemeral(halFlags)) {
                Log.w(TAG, "Cannot create ephemeral admin");
                validAdmin = false;
            }
            if (!validAdmin) {
                executeDefaultBehavior();
                return;
            }
        }
        // TODO(b/150413515): decide what to if HAL requested a non-ephemeral guest but framework
        // sets all guests as ephemeral - should it fail or just warn?

        int flags = UserHalHelper.toUserInfoFlags(halFlags);
        String type = UserHalHelper.isGuest(halFlags) ? UserManager.USER_TYPE_FULL_GUEST
                : UserManager.USER_TYPE_FULL_SECONDARY;

        if (DBG) {
            Log.d(TAG, "calling am.createUser((name=" + safeName(name) + ", type=" + type
                    + ", flags=" + UserInfo.flagsToString(flags) + ")");
        }

        UserInfo userInfo = mUm.createUser(name, type, flags);
        if (userInfo == null) {
            Log.w(TAG, "createUser(name=" + safeName(name) + ", flags="
                    + userFlagsToString(halFlags) + "): failed to create user; falling back to "
                    + "default behavior");
            executeDefaultBehavior();
            return;
        }

        if (DBG) Log.d(TAG, "user created: " + userInfo.id);
        switchUser(userInfo.id);
    }
}
