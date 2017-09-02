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
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.ProviderContract;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;
import com.google.android.apps.muzei.sync.TaskQueueService;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.BuildConfig;
import net.nurik.roman.muzei.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_NETWORK_AVAILABLE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_SUBSCRIBE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_SUBSCRIBER_COMPONENT;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

/**
 * Class responsible for managing interactions with sources such as subscribing, unsubscribing, and sending actions.
 */
public class LegacySourceManager implements LifecycleObserver, Observer<Source>, LifecycleOwner {
    private static final String TAG = "SourceManager";
    private static final String PREF_SELECTED_SOURCE = "selected_source";
    private static final String PREF_SOURCE_STATE = "source_state";

    private static final String USER_PROPERTY_SELECTED_SOURCE = "selected_source";
    private static final String USER_PROPERTY_SELECTED_SOURCE_PACKAGE = "selected_source_package";

    private final BroadcastReceiver mSourcePackageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getData() == null) {
                return;
            }
            final String packageName = intent.getData().getSchemeSpecificPart();
            ComponentName selectedSource = getSelectedSource(context);
            if (selectedSource != null && TextUtils.equals(packageName, selectedSource.getPackageName())) {
                try {
                    context.getPackageManager().getServiceInfo(selectedSource, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.i(TAG, "Selected source " + selectedSource
                            + " is no longer available");
                    invalidateSelectedSource(context);
                    return;
                }

                // Some other change.
                Log.i(TAG, "Source package changed or replaced. Re-subscribing to " + selectedSource);
                subscribeToSelectedSource(mContext);
            }

        }
    };

    private final Context mContext;
    private final LifecycleRegistry mLifecycle;

    private LiveData<Source> sourceLiveData;

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

                subscribeToSelectedSource(mContext);
            }

            @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
            public void onLegacyArtProviderUnselected() {
                unsubscribeToSelectedSource();
                mContext.unregisterReceiver(mSourcePackageChangeReceiver);
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
        // When Muzei is enabled, we start listening for the current source
        sourceLiveData = MuzeiDatabase.getInstance(mContext).sourceDao().getCurrentSource();
        sourceLiveData.observeForever(this);
    }

    @Override
    public void onChanged(@Nullable Source source) {
        if (source != null && source.componentName.equals(
                new ComponentName(mContext, LegacyArtProvider.class))) {
            mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        } else {
            mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void onMuzeiDisabled() {
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        sourceLiveData.removeObserver(this);
    }

    static void selectSource(Context context, @NonNull ComponentName source) {
        ComponentName selectedSource = getSelectedSource(context);
        if (selectedSource != null && source.equals(selectedSource)) {
            return;
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Source " + source + " selected.");
        }

        // Select the new source
        SharedPreferences sharedPrefs = context.getSharedPreferences("muzei_art_sources", 0);
        sharedPrefs.edit().putString(PREF_SELECTED_SOURCE, source.flattenToShortString()).apply();
        sendSelectedSourceAnalytics(context, source);

        subscribeToSelectedSource(context);

        // Ensure the artwork from the newly selected source is downloaded
        context.startService(TaskQueueService.getDownloadCurrentArtworkIntent(context));
    }

    private static void sendSelectedSourceAnalytics(Context context, ComponentName selectedSource) {
        // The current limit for user property values
        final int MAX_VALUE_LENGTH = 36;
        String packageName = selectedSource.getPackageName();
        if (packageName.length() > MAX_VALUE_LENGTH) {
            packageName = packageName.substring(packageName.length() - MAX_VALUE_LENGTH);
        }
        FirebaseAnalytics.getInstance(context).setUserProperty(USER_PROPERTY_SELECTED_SOURCE_PACKAGE,
                packageName);
        String className = selectedSource.flattenToShortString();
        className = className.substring(className.indexOf('/')+1);
        if (className.length() > MAX_VALUE_LENGTH) {
            className = className.substring(className.length() - MAX_VALUE_LENGTH);
        }
        FirebaseAnalytics.getInstance(context).setUserProperty(USER_PROPERTY_SELECTED_SOURCE,
                className);
    }

    @Nullable
    private static SourceState getSelectedSourceState(Context context) {
        SharedPreferences sharedPrefs = context.getSharedPreferences("muzei_art_sources", 0);
        String sourceStateString = sharedPrefs.getString(PREF_SOURCE_STATE, null);
        if (sourceStateString == null) {
            return null;
        }
        try {
            return SourceState.fromJson(new JSONObject(sourceStateString));
        } catch (JSONException e) {
            Log.e(TAG, "Error retrieving source status data.", e);
        }
        return null;
    }

    static void updateSelectedSourceState(Context context, SourceState state) {
        try {
            SharedPreferences sharedPrefs = context.getSharedPreferences("muzei_art_sources", 0);
            sharedPrefs.edit()
                    .putString(PREF_SOURCE_STATE, state.toJson().toString())
                    .apply();
        } catch (JSONException e) {
            Log.e(TAG, "Error storing source status data.", e);
        }
    }

    @NonNull
    static String getDescription(Context context) {
        ComponentName selectedSource = getSelectedSource(context);
        if (selectedSource == null) {
            return "No selected source";
        }
        ServiceInfo sourceInfo;
        try {
            sourceInfo = context.getPackageManager().getServiceInfo(selectedSource, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to retrieve source information", e);
            invalidateSelectedSource(context);
            return "No selected source";
        }
        String sourceName = "";
        CharSequence sourceLabel = sourceInfo.loadLabel(context.getPackageManager());
        if (!TextUtils.isEmpty(sourceLabel)) {
            sourceName = sourceLabel.toString();
        }
        SourceState state = getSelectedSourceState(context);
        if (state == null) {
            return sourceName;
        }
        String description = state.getDescription();
        if (TextUtils.isEmpty(description)) {
            // Fallback to the description on the source
            description = sourceInfo.descriptionRes != 0 ? context.getString(sourceInfo.descriptionRes) : "";
        }
        return !TextUtils.isEmpty(description)
                ? (!TextUtils.isEmpty(sourceName) ? sourceName + ": " : "") + description
                : sourceName;
    }

    @Nullable
    static Intent getViewIntent(Context context) {
        SourceState state = getSelectedSourceState(context);
        return state != null && state.getCurrentArtwork() != null
                ? state.getCurrentArtwork().getViewIntent()
                : null;
    }

    @NonNull
    static List<UserCommand> getCommands(final Context context) {
        ArrayList<UserCommand> commands = new ArrayList<>();
        SourceState state = getSelectedSourceState(context);
        if (state == null) {
            return commands;
        }
        int numSourceActions = state.getNumUserCommands();
        for (int i = 0; i < numSourceActions; i++) {
            UserCommand command = state.getUserCommandAt(i);
            if (command.getId() != MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK) {
                commands.add(command);
            }
        }
        return commands;
    }

    static void sendAction(final Context context, final int id) {
        ComponentName selectedSource = getSelectedSource(context);
        if (selectedSource == null) {
            return;
        }
        try {
            context.startService(new Intent(ACTION_HANDLE_COMMAND)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_COMMAND_ID, id));
        } catch (IllegalStateException|SecurityException e) {
            Log.i(TAG, "Sending action + " + id + " to " + selectedSource
                    + " failed; switching to default.", e);
            Toast.makeText(context, R.string.source_unavailable, Toast.LENGTH_LONG).show();
            invalidateSelectedSource(context);
        }
    }

    static void maybeSendNetworkAvailable(Context context) {
        ComponentName selectedSource = getSelectedSource(context);
        SourceState state = getSelectedSourceState(context);
        if (selectedSource == null || state == null) {
            return;
        }
        if (state.getWantsNetworkAvailable()) {
            context.startService(new Intent(ACTION_NETWORK_AVAILABLE)
                    .setComponent(selectedSource));
        }
    }

    @Nullable
    static ComponentName getSelectedSource(Context context) {
        SharedPreferences sharedPrefs = context.getSharedPreferences("muzei_art_sources", 0);
        String selectedSourceString = sharedPrefs.getString(PREF_SELECTED_SOURCE, null);
        return selectedSourceString != null
                ? ComponentName.unflattenFromString(selectedSourceString)
                : null;
    }

    private static void invalidateSelectedSource(Context context) {
        SharedPreferences sharedPrefs = context.getSharedPreferences("muzei_art_sources", 0);
        sharedPrefs.edit()
                .putString(PREF_SELECTED_SOURCE, null)
                .putString(PREF_SOURCE_STATE, null)
                .apply();
        ProviderContract.Artwork.setArtwork(context, LegacyArtProvider.class,
                new Artwork.Builder()
                        .token("error")
                        .persistentUri(Uri.fromParts(
                                ContentResolver.SCHEME_ANDROID_RESOURCE,
                                context.getPackageName(),
                                "drawable/" + context.getResources()
                                        .getResourceEntryName(R.drawable.grumpy_mcpuzzles)))
                        .title(context.getString(R.string.error_easter_egg))
                        .build());
    }

    private static void subscribeToSelectedSource(Context context) {
        ComponentName selectedSource = getSelectedSource(context);
        if (selectedSource == null) {
            return;
        }
        try {
            // Ensure that we have a valid service before subscribing
            context.getPackageManager().getServiceInfo(selectedSource, 0);
            context.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            new ComponentName(context, SourceSubscriberService.class))
                    .putExtra(EXTRA_TOKEN, selectedSource.flattenToShortString()));
        } catch (PackageManager.NameNotFoundException|IllegalStateException|SecurityException e) {
            Log.i(TAG, "Selected source " + selectedSource
                    + " is no longer available; switching to default.", e);
            Toast.makeText(context, R.string.source_unavailable, Toast.LENGTH_LONG).show();
            invalidateSelectedSource(context);
        }
    }

    private void unsubscribeToSelectedSource() {
        ComponentName selectedSource = getSelectedSource(mContext);
        if (selectedSource == null) {
            return;
        }
        try {
            mContext.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            new ComponentName(mContext, SourceSubscriberService.class))
                    .putExtra(EXTRA_TOKEN, (String) null));
        } catch (IllegalStateException e) {
            Log.i(TAG, "Unsubscribing to " + selectedSource + " failed.", e);
        }
    }
}
