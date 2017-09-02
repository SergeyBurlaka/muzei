/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.legacy;

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Build;

import com.google.android.apps.muzei.sync.TaskQueueService;

/**
 * LifecycleObserver responsible for monitoring network connectivity and retrying artwork as necessary
 */
public class NetworkChangeObserver implements LifecycleObserver {
    private final Context mContext;
    private BroadcastReceiver mNetworkChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            boolean hasConnectivity = !intent.getBooleanExtra(
                    ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
            if (hasConnectivity) {
                // Check with components that may not currently be alive but interested in
                // network connectivity changes.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    Intent retryIntent = TaskQueueService.maybeRetryDownloadDueToGainedConnectivity(context);
                    if (retryIntent != null) {
                        mContext.startService(retryIntent);
                    }
                }

                LegacySourceManager.maybeSendNetworkAvailable(context);
            }
        }
    };

    public NetworkChangeObserver(Context context) {
        mContext = context;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void registerReceiver() {
        IntentFilter networkChangeFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(mNetworkChangeReceiver, networkChangeFilter);

        // Ensure we retry loading the artwork if the network changed while the wallpaper was disabled
        ConnectivityManager connectivityManager = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        Intent retryIntent = TaskQueueService.maybeRetryDownloadDueToGainedConnectivity(mContext);
        if (retryIntent != null && connectivityManager.getActiveNetworkInfo() != null &&
                connectivityManager.getActiveNetworkInfo().isConnected()) {
            mContext.startService(retryIntent);
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void unregisterReceiver() {
        mContext.unregisterReceiver(mNetworkChangeReceiver);
    }
}
