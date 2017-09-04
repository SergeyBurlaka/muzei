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
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.support.annotation.Nullable;

import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;

import java.util.List;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_NETWORK_AVAILABLE;

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

                final PendingResult pendingResult = goAsync();
                final LiveData<List<Source>> sourcesLiveData = MuzeiDatabase.getInstance(context).sourceDao()
                        .getCurrentSourcesThatWantNetwork();
                sourcesLiveData.observeForever(
                        new Observer<List<Source>>() {
                            @Override
                            public void onChanged(@Nullable final List<Source> sources) {
                                sourcesLiveData.removeObserver(this);
                                if (sources != null) {
                                    for (Source source : sources) {
                                        context.startService(new Intent(ACTION_NETWORK_AVAILABLE)
                                                .setComponent(source.componentName));
                                    }
                                }
                                pendingResult.finish();
                            }
                        });
            }
        }
    };

    NetworkChangeObserver(Context context) {
        mContext = context;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void registerReceiver() {
        IntentFilter networkChangeFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(mNetworkChangeReceiver, networkChangeFilter);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void unregisterReceiver() {
        mContext.unregisterReceiver(mNetworkChangeReceiver);
    }
}
