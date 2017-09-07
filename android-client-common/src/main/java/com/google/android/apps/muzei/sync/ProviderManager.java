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
import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.Nullable;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;
import com.google.android.apps.muzei.room.ProviderEntity;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Manager which controls
 */
public class ProviderManager implements LifecycleObserver, Observer<Provider>, LifecycleOwner {
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
        mProviderLiveData = MuzeiDatabase.getInstance(mContext).providerDao().getCurrentProvider(mContext);
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
                database.providerDao().insert(new ProviderEntity(componentName));
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
