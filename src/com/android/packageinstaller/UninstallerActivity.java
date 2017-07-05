/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package com.android.packageinstaller;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.util.Log;

import com.android.packageinstaller.handheld.ErrorDialogFragment;
import com.android.packageinstaller.handheld.UninstallAlertDialogFragment;
import com.android.packageinstaller.television.ErrorFragment;
import com.android.packageinstaller.television.UninstallAlertFragment;
import com.android.packageinstaller.television.UninstallAppProgress;

import java.util.List;

/*
 * This activity presents UI to uninstall an application. Usually launched with intent
 * Intent.ACTION_UNINSTALL_PKG_COMMAND and attribute
 * com.android.packageinstaller.PackageName set to the application package name
 */
public class UninstallerActivity extends Activity {
    private static final String TAG = "UninstallerActivity";

    private static final String UNINSTALLING_CHANNEL = "uninstalling";

    public static class DialogInfo {
        public ApplicationInfo appInfo;
        public ActivityInfo activityInfo;
        public boolean allUsers;
        public UserHandle user;
        public IBinder callback;
    }

    private String mPackageName;
    private DialogInfo mDialogInfo;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        // Get intent information.
        // We expect an intent with URI of the form package://<packageName>#<className>
        // className is optional; if specified, it is the activity the user chose to uninstall
        final Intent intent = getIntent();
        final Uri packageUri = intent.getData();
        if (packageUri == null) {
            Log.e(TAG, "No package URI in intent");
            showAppNotFound();
            return;
        }
        mPackageName = packageUri.getEncodedSchemeSpecificPart();
        if (mPackageName == null) {
            Log.e(TAG, "Invalid package name in URI: " + packageUri);
            showAppNotFound();
            return;
        }

        final IPackageManager pm = IPackageManager.Stub.asInterface(
                ServiceManager.getService("package"));

        mDialogInfo = new DialogInfo();

        mDialogInfo.allUsers = intent.getBooleanExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, false);
        if (mDialogInfo.allUsers && !UserManager.get(this).isAdminUser()) {
            Log.e(TAG, "Only admin user can request uninstall for all users");
            showUserIsNotAllowed();
            return;
        }
        mDialogInfo.user = intent.getParcelableExtra(Intent.EXTRA_USER);
        if (mDialogInfo.user == null) {
            mDialogInfo.user = android.os.Process.myUserHandle();
        } else {
            UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
            List<UserHandle> profiles = userManager.getUserProfiles();
            if (!profiles.contains(mDialogInfo.user)) {
                Log.e(TAG, "User " + android.os.Process.myUserHandle() + " can't request uninstall "
                        + "for user " + mDialogInfo.user);
                showUserIsNotAllowed();
                return;
            }
        }

        mDialogInfo.callback = intent.getIBinderExtra(PackageInstaller.EXTRA_CALLBACK);

        try {
            mDialogInfo.appInfo = pm.getApplicationInfo(mPackageName,
                    PackageManager.MATCH_ANY_USER, mDialogInfo.user.getIdentifier());
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to get packageName. Package manager is dead?");
        }

        if (mDialogInfo.appInfo == null) {
            Log.e(TAG, "Invalid packageName: " + mPackageName);
            showAppNotFound();
            return;
        }

        // The class name may have been specified (e.g. when deleting an app from all apps)
        final String className = packageUri.getFragment();
        if (className != null) {
            try {
                mDialogInfo.activityInfo = pm.getActivityInfo(
                        new ComponentName(mPackageName, className), 0,
                        mDialogInfo.user.getIdentifier());
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to get className. Package manager is dead?");
                // Continue as the ActivityInfo isn't critical.
            }
        }

        showConfirmationDialog();
    }

    public DialogInfo getDialogInfo() {
        return mDialogInfo;
    }

    private void showConfirmationDialog() {
        if (isTv()) {
            showContentFragment(new UninstallAlertFragment(), 0, 0);
        } else {
            showDialogFragment(new UninstallAlertDialogFragment(), 0, 0);
        }
    }

    private void showAppNotFound() {
        if (isTv()) {
            showContentFragment(new ErrorFragment(), R.string.app_not_found_dlg_title,
                    R.string.app_not_found_dlg_text);
        } else {
            showDialogFragment(new ErrorDialogFragment(), R.string.app_not_found_dlg_title,
                    R.string.app_not_found_dlg_text);
        }
    }

    private void showUserIsNotAllowed() {
        if (isTv()) {
            showContentFragment(new ErrorFragment(),
                    R.string.user_is_not_allowed_dlg_title, R.string.user_is_not_allowed_dlg_text);
        } else {
            showDialogFragment(new ErrorDialogFragment(), 0, R.string.user_is_not_allowed_dlg_text);
        }
    }

    private void showGenericError() {
        if (isTv()) {
            showContentFragment(new ErrorFragment(),
                    R.string.generic_error_dlg_title, R.string.generic_error_dlg_text);
        } else {
            showDialogFragment(new ErrorDialogFragment(), 0, R.string.generic_error_dlg_text);
        }
    }

    private boolean isTv() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private void showContentFragment(@NonNull Fragment fragment, @StringRes int title,
            @StringRes int text) {
        Bundle args = new Bundle();
        args.putInt(ErrorFragment.TITLE, title);
        args.putInt(ErrorFragment.TEXT, text);
        fragment.setArguments(args);

        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
    }

    private void showDialogFragment(@NonNull DialogFragment fragment,
            @StringRes int title, @StringRes int text) {
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        Fragment prev = getFragmentManager().findFragmentByTag("dialog");
        if (prev != null) {
            ft.remove(prev);
        }

        Bundle args = new Bundle();
        if (title != 0) {
            args.putInt(ErrorDialogFragment.TITLE, title);
        }
        args.putInt(ErrorDialogFragment.TEXT, text);

        fragment.setArguments(args);
        fragment.show(ft, "dialog");
    }

    public void startUninstallProgress() {
        boolean returnResult = getIntent().getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false);
        CharSequence label = mDialogInfo.appInfo.loadLabel(getPackageManager());

        if (isTv()) {
            Intent newIntent = new Intent(Intent.ACTION_VIEW);
            newIntent.putExtra(Intent.EXTRA_USER, mDialogInfo.user);
            newIntent.putExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, mDialogInfo.allUsers);
            newIntent.putExtra(PackageInstaller.EXTRA_CALLBACK, mDialogInfo.callback);
            newIntent.putExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO, mDialogInfo.appInfo);

            if (returnResult) {
                newIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                newIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
            }

            newIntent.setClass(this, UninstallAppProgress.class);
            startActivity(newIntent);
        } else if (returnResult || mDialogInfo.callback != null || getCallingActivity() != null) {
            Intent newIntent = new Intent(this, UninstallUninstalling.class);

            newIntent.putExtra(Intent.EXTRA_USER, mDialogInfo.user);
            newIntent.putExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, mDialogInfo.allUsers);
            newIntent.putExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO, mDialogInfo.appInfo);
            newIntent.putExtra(UninstallUninstalling.EXTRA_APP_LABEL, label);
            newIntent.putExtra(PackageInstaller.EXTRA_CALLBACK, mDialogInfo.callback);

            if (returnResult) {
                newIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            }

            if (returnResult || getCallingActivity() != null) {
                newIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
            }

            startActivity(newIntent);
        } else {
            int uninstallId;
            try {
                uninstallId = UninstallEventReceiver.getNewId(this);
            } catch (EventResultPersister.OutOfIdsException e) {
                showGenericError();
                return;
            }

            Intent broadcastIntent = new Intent(this, UninstallFinish.class);

            broadcastIntent.putExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, mDialogInfo.allUsers);
            broadcastIntent.putExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO, mDialogInfo.appInfo);
            broadcastIntent.putExtra(UninstallFinish.EXTRA_APP_LABEL, label);
            broadcastIntent.putExtra(UninstallFinish.EXTRA_UNINSTALL_ID, uninstallId);

            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, uninstallId,
                    broadcastIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            NotificationChannel uninstallingChannel = new NotificationChannel(UNINSTALLING_CHANNEL,
                    getString(R.string.uninstalling_notification_channel),
                    NotificationManager.IMPORTANCE_MIN);
            notificationManager.createNotificationChannel(uninstallingChannel);

            Notification uninstallingNotification =
                    (new Notification.Builder(this, UNINSTALLING_CHANNEL))
                    .setSmallIcon(R.drawable.ic_remove).setProgress(0, 1, true)
                    .setContentTitle(getString(R.string.uninstalling_app, label)).setOngoing(true)
                    .build();

            notificationManager.notify(uninstallId, uninstallingNotification);

            try {
                Log.i(TAG, "Uninstalling extras=" + broadcastIntent.getExtras());

                ActivityThread.getPackageManager().getPackageInstaller().uninstall(
                        new VersionedPackage(mDialogInfo.appInfo.packageName,
                                PackageManager.VERSION_CODE_HIGHEST),
                        getPackageName(), mDialogInfo.allUsers
                                ? PackageManager.DELETE_ALL_USERS : 0,
                        pendingIntent.getIntentSender(), mDialogInfo.user.getIdentifier());
            } catch (Exception e) {
                Log.e(TAG, "Cannot start uninstall", e);
                showGenericError();
            }
        }
    }

    public void dispatchAborted() {
        if (mDialogInfo != null && mDialogInfo.callback != null) {
            final IPackageDeleteObserver2 observer = IPackageDeleteObserver2.Stub.asInterface(
                    mDialogInfo.callback);
            try {
                observer.onPackageDeleted(mPackageName,
                        PackageManager.DELETE_FAILED_ABORTED, "Cancelled by user");
            } catch (RemoteException ignored) {
            }
        }
    }
}
