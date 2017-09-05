/*
 * Copyright 2014 Google Inc.
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

package com.google.android.apps.muzei;

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.featuredart.FeaturedArtProvider;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;
import com.google.android.apps.muzei.sync.ProviderManager;

/**
 * LifecycleObserver used to watch for changes to installed packages on the device. This triggers
 * a cleanup of providers (in case one was uninstalled).
 */
public class ProviderPackageChangeObserver implements LifecycleObserver {
    private static final String TAG = "ProviderPackageChange";

    private final Context mContext;
    private final BroadcastReceiver mProviderPackageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, Intent intent) {
            if (intent == null || intent.getData() == null) {
                return;
            }
            final String packageName = intent.getData().getSchemeSpecificPart();
            final PendingResult pendingResult = goAsync();
            final LiveData<Provider> providerLiveData = MuzeiDatabase.getInstance(context).providerDao()
                    .getCurrentProvider(context);
            providerLiveData.observeForever(
                    new Observer<Provider>() {
                        @Override
                        public void onChanged(@Nullable final Provider provider) {
                            providerLiveData.removeObserver(this);
                            if (provider != null && TextUtils.equals(packageName, provider.componentName.getPackageName())) {
                                try {
                                    context.getPackageManager().getProviderInfo(provider.componentName, 0);
                                } catch (PackageManager.NameNotFoundException e) {
                                    Log.i(TAG, "Selected provider " + provider.componentName
                                            + " is no longer available; switching to default.");
                                    ProviderManager.getInstance(context).selectProvider(
                                            new ComponentName(context, FeaturedArtProvider.class));
                                    return;
                                }
                            }
                            pendingResult.finish();
                        }
                    });
        }
    };

    ProviderPackageChangeObserver(Context context) {
        mContext = context;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void registerPackageChangeReceiver() {
        // Register for package change events
        IntentFilter packageChangeFilter = new IntentFilter();
        packageChangeFilter.addDataScheme("package");
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        mContext.registerReceiver(mProviderPackageChangeReceiver, packageChangeFilter);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void unregisterPackageChangeReceiver() {
        mContext.unregisterReceiver(mProviderPackageChangeReceiver);
    }
}
