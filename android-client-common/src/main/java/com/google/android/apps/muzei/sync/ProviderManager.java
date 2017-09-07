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
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleRegistry;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_DESCRIPTION;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_DESCRIPTION;

/**
 * Manager which controls
 */
public class ProviderManager implements LifecycleObserver, Observer<Provider>, LifecycleOwner {
    private static final String TAG = "ProviderManager";

    private static ProviderManager sInstance;

    public static ProviderManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ProviderManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private final Executor mExecutor = Executors.newSingleThreadExecutor();
    private final Context mContext;
    private final LifecycleRegistry mLifecycle;

    private LiveData<Provider> mProviderLiveData;

    private ProviderManager(final Context context) {
        mContext = context;
        mLifecycle = new LifecycleRegistry(this);
    }

    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void onMuzeiEnabled() {
        mProviderLiveData = MuzeiDatabase.getInstance(mContext).providerDao().getCurrentProvider();
        mProviderLiveData.observeForever(this);
    }

    @Override
    public void onChanged(@Nullable final Provider provider) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void onMuzeiDisabled() {
        mProviderLiveData.removeObserver(this);
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
        Provider provider = MuzeiDatabase.getInstance(mContext).providerDao()
                .getCurrentProviderBlocking();
        return provider != null
                ? getDescriptionBlocking(provider.componentName)
                : "";
    }

    @NonNull
    private String getDescriptionBlocking(ComponentName provider) {
        Uri contentUri = MuzeiArtProvider.getContentUri(mContext, provider);
        try (ContentProviderClient client = mContext.getContentResolver()
                .acquireUnstableContentProviderClient(contentUri)) {
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
        // TODO Actually check to see if we can go to the next artwork
        return false;
    }

    public void nextArtwork() {
        FirebaseJobDispatcher jobDispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(mContext));
        jobDispatcher.mustSchedule(jobDispatcher.newJobBuilder()
            .setService(ArtworkLoadJobService.class)
            .setTag("next")
            .build());
    }
}
