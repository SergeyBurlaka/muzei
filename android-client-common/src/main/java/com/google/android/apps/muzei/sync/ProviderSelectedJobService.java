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

import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.firebase.jobdispatcher.Constraint;
import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobService;
import com.firebase.jobdispatcher.Lifetime;
import com.firebase.jobdispatcher.SimpleJobService;
import com.firebase.jobdispatcher.Trigger;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;
import com.google.android.apps.muzei.util.ContentProviderClientCompat;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_LAST_LOADED_TIME;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_LOAD_INFO;

/**
 * Job responsible for setting up the recurring artwork load from a {@link MuzeiArtProvider} and kicking off
 * an immediate load if the current artwork is invalid or we're overdue for loading artwork.
 */
public class ProviderSelectedJobService extends SimpleJobService {
    private static final String TAG = "ProviderSelectedJob";

    @Override
    public int onRunJob(final JobParameters job) {
        MuzeiDatabase database = MuzeiDatabase.getInstance(this);
        Provider provider = database.providerDao()
                .getCurrentProviderBlocking();
        if (provider == null) {
            return JobService.RESULT_FAIL_NORETRY;
        }
        Uri contentUri = MuzeiArtProvider.getContentUri(this, provider.componentName);
        try (ContentProviderClientCompat client = ContentProviderClientCompat
                .getClient(this, contentUri)) {
            if (client == null) {
                return JobService.RESULT_FAIL_NORETRY;
            }
            Bundle result = client.call(METHOD_GET_LOAD_INFO, null, null);
            if (result == null) {
                return JobService.RESULT_FAIL_RETRY;
            }
            long lastLoadedTime = result.getLong(KEY_LAST_LOADED_TIME, 0L);
            Artwork currentArtwork = database.artworkDao().getCurrentArtworkBlocking();
            try (Cursor curArtwork = client.query(currentArtwork.imageUri,
                    null, null, null, null)) {
                if (curArtwork == null) {
                    return JobService.RESULT_FAIL_RETRY;
                }
                int loadFrequencySeconds = ProviderManager.getInstance(this).getLoadFrequencySeconds();
                boolean overDue = System.currentTimeMillis() - lastLoadedTime >=
                        TimeUnit.SECONDS.toMillis(loadFrequencySeconds);
                if (overDue || !curArtwork.moveToNext() ||
                        isValidArtwork(client, contentUri, curArtwork)) {
                    // Schedule an immediate load
                    ArtworkLoadJobService.scheduleNext(this);
                }
                // Now schedule the recurring job
                FirebaseJobDispatcher jobDispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(this));
                jobDispatcher.mustSchedule(jobDispatcher.newJobBuilder()
                        .setService(ArtworkLoadJobService.class)
                        .setTag("scheduled")
                        .setLifetime(Lifetime.FOREVER)
                        .setReplaceCurrent(true)
                        .setRecurring(true)
                        .addConstraint(Constraint.ON_ANY_NETWORK)
                        .setTrigger(Trigger.executionWindow(loadFrequencySeconds / 10, loadFrequencySeconds))
                        .build());
                return JobService.RESULT_SUCCESS;
            }
        } catch (RemoteException e) {
            Log.i(TAG, "Provider " + provider.componentName + " crashed while retrieving artwork", e);
            return JobService.RESULT_FAIL_RETRY;
        }
    }

    private boolean isValidArtwork(ContentProviderClientCompat client, Uri contentUri, Cursor data)
            throws RemoteException {
        com.google.android.apps.muzei.api.provider.Artwork providerArtwork =
                com.google.android.apps.muzei.api.provider.Artwork.fromCursor(data);
        Uri artworkUri = ContentUris.withAppendedId(contentUri, providerArtwork.getId());
        try (ParcelFileDescriptor parcelFileDescriptor = client.openFile(artworkUri)) {
            return parcelFileDescriptor != null;
        } catch (IOException e) {
            Log.w(TAG, "Unable to preload artwork " + artworkUri, e);
        }
        return false;
    }
}
