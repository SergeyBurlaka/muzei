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
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleRegistry;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MediatorLiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;
import com.google.android.apps.muzei.room.Source;
import com.google.android.apps.muzei.room.SourceDao;
import com.google.firebase.analytics.FirebaseAnalytics;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_SUBSCRIBE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_SUBSCRIBER_COMPONENT;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

/**
 * Class responsible for managing interactions with sources such as subscribing, unsubscribing, and sending actions.
 */
public class LegacySourceManager implements LifecycleObserver, Observer<Provider>, LifecycleOwner {
    private static final String TAG = "SourceManager";

    private static final String USER_PROPERTY_SELECTED_SOURCE = "selected_source";
    private static final String USER_PROPERTY_SELECTED_SOURCE_PACKAGE = "selected_source_package";

    private final BroadcastReceiver mSourcePackageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, Intent intent) {
            if (intent == null || intent.getData() == null) {
                return;
            }
            final String packageName = intent.getData().getSchemeSpecificPart();
            final PendingResult pendingResult = goAsync();
            final LiveData<Source> sourceLiveData = MuzeiDatabase.getInstance(context).sourceDao()
                    .getCurrentSource();
            sourceLiveData.observeForever(
                    new Observer<Source>() {
                        @Override
                        public void onChanged(@Nullable final Source source) {
                            sourceLiveData.removeObserver(this);
                            if (source != null && TextUtils.equals(packageName, source.componentName.getPackageName())) {
                                try {
                                    mContext.getPackageManager().getServiceInfo(source.componentName, 0);
                                } catch (PackageManager.NameNotFoundException e) {
                                    Log.i(TAG, "Selected source " + source.componentName
                                            + " is no longer available");
                                    invalidateSelectedSource(mContext);
                                    return;
                                }

                                // Some other change.
                                Log.i(TAG, "Source package changed or replaced. Re-subscribing to " +
                                        source.componentName);
                                subscribe(source);
                            }
                            pendingResult.finish();
                        }
                    });
        }
    };
    private class SubscriberLiveData extends MediatorLiveData<Source> {
        private Source currentSource = null;

        SubscriberLiveData() {
            addSource(MuzeiDatabase.getInstance(mContext).sourceDao().getCurrentSource(),
                    new Observer<Source>() {
                        @Override
                        public void onChanged(@Nullable final Source source) {
                            if (currentSource != null) {
                                unsubscribe(currentSource);
                            }
                            currentSource = source;
                            if (source != null) {
                                subscribe(source);
                            }
                            setValue(source);
                        }
                    });
        }

        @Override
        protected void onInactive() {
            super.onInactive();
            if (currentSource != null) {
                unsubscribe(currentSource);
            }
        }
    }

    private final Context mContext;
    private final LifecycleRegistry mLifecycle;

    private LiveData<Provider> providerLiveData;

    public LegacySourceManager(Context context) {
        mContext = context;
        mLifecycle = new LifecycleRegistry(this);
        mLifecycle.addObserver(new NetworkChangeObserver(mContext));
        mLifecycle.addObserver(new LifecycleObserver() {
            @OnLifecycleEvent(Lifecycle.Event.ON_START)
            public void onLegacyArtProviderSelected() {
                // Register for package change events
                IntentFilter packageChangeFilter = new IntentFilter();
                packageChangeFilter.addDataScheme("package");
                packageChangeFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                packageChangeFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
                packageChangeFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                mContext.registerReceiver(mSourcePackageChangeReceiver, packageChangeFilter);
            }

            @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
            public void onLegacyArtProviderUnselected() {
                mContext.unregisterReceiver(mSourcePackageChangeReceiver);
            }
        });
        new SubscriberLiveData().observe(this, new Observer<Source>() {
            @Override
            public void onChanged(@Nullable final Source source) {
                if (source != null) {
                    sendSelectedSourceAnalytics(source.componentName);
                }
            }
        });
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void onMuzeiEnabled() {
        // When Muzei is enabled, we start listening for the current provider
        providerLiveData = MuzeiDatabase.getInstance(mContext).providerDao().getCurrentProvider();
        providerLiveData.observeForever(this);
    }

    @Override
    public void onChanged(@Nullable Provider provider) {
        if (provider != null && provider.componentName.equals(
                new ComponentName(mContext, LegacyArtProvider.class))) {
            mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        } else {
            mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void onMuzeiDisabled() {
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        providerLiveData.removeObserver(this);
    }

    static void selectSource(final Context context, @NonNull final ComponentName source) {
        new Thread() {
            @Override
            public void run() {
                MuzeiDatabase database = MuzeiDatabase.getInstance(context);
                Source selectedSource = database.sourceDao().getCurrentSourceBlocking();
                if (selectedSource != null && source.equals(selectedSource.componentName)) {
                    return;
                }

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Source " + source + " selected.");
                }

                database.beginTransaction();
                if (selectedSource != null) {
                    // Unselect the old source
                    selectedSource.selected = false;
                    database.sourceDao().update(selectedSource);
                }

                // Select the new source
                Source newSource = database.sourceDao().getSourceByComponentNameBlocking(source);
                if (newSource != null) {
                    newSource.selected = true;
                    database.sourceDao().update(newSource);
                } else {
                    newSource = new Source(source);
                    newSource.selected = true;
                    database.sourceDao().insert(newSource);
                }

                database.setTransactionSuccessful();
                database.endTransaction();
            }
        }.start();
    }

    private void sendSelectedSourceAnalytics(ComponentName selectedSource) {
        // The current limit for user property values
        final int MAX_VALUE_LENGTH = 36;
        String packageName = selectedSource.getPackageName();
        if (packageName.length() > MAX_VALUE_LENGTH) {
            packageName = packageName.substring(packageName.length() - MAX_VALUE_LENGTH);
        }
        FirebaseAnalytics.getInstance(mContext).setUserProperty(USER_PROPERTY_SELECTED_SOURCE_PACKAGE,
                packageName);
        String className = selectedSource.flattenToShortString();
        className = className.substring(className.indexOf('/')+1);
        if (className.length() > MAX_VALUE_LENGTH) {
            className = className.substring(className.length() - MAX_VALUE_LENGTH);
        }
        FirebaseAnalytics.getInstance(mContext).setUserProperty(USER_PROPERTY_SELECTED_SOURCE,
                className);
    }

    static void invalidateSelectedSource(final Context context) {
        new Thread() {
            @Override
            public void run() {
                SourceDao sourceDao = MuzeiDatabase.getInstance(context).sourceDao();
                Source source = sourceDao.getCurrentSourceBlocking();
                source.selected = false;
                sourceDao.update(source);
            }
        }.start();
    }

    private void subscribe(@NonNull Source source) {
        ComponentName selectedSource = source.componentName;
        try {
            // Ensure that we have a valid service before subscribing
            mContext.getPackageManager().getServiceInfo(selectedSource, 0);
            mContext.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            new ComponentName(mContext, SourceSubscriberService.class))
                    .putExtra(EXTRA_TOKEN, selectedSource.flattenToShortString()));
        } catch (PackageManager.NameNotFoundException|IllegalStateException|SecurityException e) {
            Log.i(TAG, "Selected source " + selectedSource
                    + " is no longer available; switching to default.", e);
            Toast.makeText(mContext, R.string.legacy_source_unavailable, Toast.LENGTH_LONG).show();
            invalidateSelectedSource(mContext);
        }
    }

    private void unsubscribe(@NonNull Source source) {
        try {
            mContext.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(source.componentName)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            new ComponentName(mContext, SourceSubscriberService.class))
                    .putExtra(EXTRA_TOKEN, (String) null));
        } catch (IllegalStateException e) {
            Log.i(TAG, "Unsubscribing to " + source.componentName
                    + " failed.", e);
        }
    }
}
