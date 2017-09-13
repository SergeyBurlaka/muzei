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

package com.google.android.apps.muzei.sync;

import android.annotation.SuppressLint;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleRegistry;
import android.arch.lifecycle.MutableLiveData;
import android.arch.lifecycle.Observer;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;
import com.google.android.apps.muzei.util.ContentProviderClientCompat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_DESCRIPTION;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_DESCRIPTION;

/**
 * Manager which controls
 */
public class ProviderManager extends MutableLiveData<Provider>
        implements Observer<Provider>, LifecycleOwner {
    private static final String TAG = "ProviderManager";
    private static final String PREF_PERSISTENT_LISTENERS = "persistentListeners";

    private static ProviderManager sInstance;

    public static ProviderManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ProviderManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private final Executor mExecutor = Executors.newSingleThreadExecutor();
    private final Observer<Provider> mPersistentListener = new Observer<Provider>() {
        @Override
        public void onChanged(@Nullable Provider provider) {
        }
    };
    private final Context mContext;
    private final LifecycleRegistry mLifecycle;

    private ProviderManager(final Context context) {
        mContext = context;
        mLifecycle = new LifecycleRegistry(this);
        // Check for persistent listeners which don't have a set lifecycle, but still need to
        // be counted as observers
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        Set<String> persistentListeners = preferences.getStringSet(PREF_PERSISTENT_LISTENERS,
                new HashSet<String>());
        if (!persistentListeners.isEmpty()) {
            observeForever(mPersistentListener);
        }
        preferences.registerOnSharedPreferenceChangeListener(
                new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                        if (PREF_PERSISTENT_LISTENERS.equals(key)) {
                            Set<String> newListeners = sharedPreferences.getStringSet(
                                    PREF_PERSISTENT_LISTENERS, new HashSet<String>());
                            if (!newListeners.isEmpty()) {
                                observeForever(mPersistentListener);
                            } else {
                                removeObserver(mPersistentListener);
                            }
                        }
                    }
                });
    }

    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    public void addPersistentListener(String name) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        Set<String> persistentListeners = preferences.getStringSet(PREF_PERSISTENT_LISTENERS,
                new HashSet<String>());
        persistentListeners.add(name);
        preferences.edit().putStringSet(PREF_PERSISTENT_LISTENERS, persistentListeners).apply();
    }

    @Override
    protected void onActive() {
        // TODO Confirm we have artwork from the current provider
        // TODO Start periodic loading of new artwork
    }

    @Override
    public void onChanged(@Nullable final Provider provider) {
        setValue(provider);
        // TODO Confirm we have artwork from the new provider
    }

    @Override
    protected void onInactive() {
        // TODO Stop periodic loading of new artwork
    }

    public void removePersistentListener(String name) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        Set<String> persistentListeners = preferences.getStringSet(PREF_PERSISTENT_LISTENERS,
                new HashSet<String>());
        persistentListeners.remove(name);
        preferences.edit().putStringSet(PREF_PERSISTENT_LISTENERS, persistentListeners).apply();
    }

    public interface ProviderCallback {
        void onProviderSelected();
    }

    public void selectProvider(final ComponentName componentName) {
        selectProvider(componentName, null);
    }

    @SuppressLint("StaticFieldLeak")
    public void selectProvider(final ComponentName componentName, final ProviderCallback callback) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(final Void... voids) {
                MuzeiDatabase database = MuzeiDatabase.getInstance(mContext);
                database.beginTransaction();
                database.providerDao().deleteAll();
                database.providerDao().insert(new Provider(componentName));
                database.setTransactionSuccessful();
                database.endTransaction();
                return null;
            }

            @Override
            protected void onPostExecute(final Void aVoid) {
                if (callback != null) {
                    callback.onProviderSelected();
                }
            }
        }.executeOnExecutor(mExecutor);
    }

    public interface DescriptionCallback {
        void onCallback(@NonNull String description);
    }

    @SuppressLint("StaticFieldLeak")
    public void getDescription(final ComponentName provider, final DescriptionCallback callback) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(final Void... voids) {
                return getDescriptionBlocking(provider);
            }

            @Override
            protected void onPostExecute(final String description) {
                callback.onCallback(description);
            }
        }.executeOnExecutor(mExecutor);
    }

    @NonNull
    public String getCurrentDescription() {
        Provider provider = getValue();
        return provider != null
                ? getDescriptionBlocking(provider.componentName)
                : "";
    }

    @NonNull
    private String getDescriptionBlocking(ComponentName provider) {
        Uri contentUri = MuzeiArtProvider.getContentUri(mContext, provider);
        try (ContentProviderClientCompat client = ContentProviderClientCompat
                .getClient(mContext, contentUri)) {
            if (client == null) {
                return "";
            }
            try {
                Bundle result = client.call(METHOD_GET_DESCRIPTION, null, null);
                return result != null ? result.getString(KEY_DESCRIPTION, "") : "";
            } catch (RemoteException e) {
                Log.i(TAG, "Provider " + provider + " crashed while retrieving description", e);
                return "";
            }
        }
    }

    public interface SupportNextArtworkCallback {
        void onCallback(boolean supportsNextArtwork);
    }

    @SuppressLint("StaticFieldLeak")
    public void getSupportsNextArtwork(final SupportNextArtworkCallback callback) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(final Void... voids) {
                return getSupportsNextArtworkBlocking();
            }

            @Override
            protected void onPostExecute(final Boolean supportsNextArtwork) {
                callback.onCallback(supportsNextArtwork);
            }
        }.executeOnExecutor(mExecutor);
    }

    public boolean getSupportsNextArtworkBlocking() {
        Provider provider = getValue();
        if (provider == null) {
            return false;
        }
        Uri contentUri = MuzeiArtProvider.getContentUri(mContext, provider.componentName);
        try (ContentProviderClientCompat client = ContentProviderClientCompat
                .getClient(mContext, contentUri)) {
            if (client == null) {
                return false;
            }
            try (Cursor allArtwork = client.query(contentUri,
                    null, null, null, null)) {
                return allArtwork != null && allArtwork.getCount() > 1;
            }
        } catch (RemoteException e) {
            Log.i(TAG, "Provider " + provider.componentName + " crashed " +
                    "when determining if it supports the 'Next Artwork' command", e);
            return false;
        }
    }

    public void nextArtwork() {
        FirebaseJobDispatcher jobDispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(mContext));
        jobDispatcher.mustSchedule(jobDispatcher.newJobBuilder()
            .setService(ArtworkLoadJobService.class)
            .setTag("next")
            .build());
    }
}
