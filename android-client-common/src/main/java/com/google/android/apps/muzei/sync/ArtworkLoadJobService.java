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
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.support.annotation.Nullable;
import android.util.Log;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobService;
import com.firebase.jobdispatcher.Lifetime;
import com.firebase.jobdispatcher.SimpleJobService;
import com.google.android.apps.muzei.api.internal.RecentArtworkIdsConverter;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;
import com.google.android.apps.muzei.util.ContentProviderClientCompat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Random;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_MAX_LOADED_ARTWORK_ID;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_RECENT_ARTWORK_IDS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_LOAD_INFO;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_MARK_ARTWORK_LOADED;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_REQUEST_LOAD;

/**
 * Job responsible for loading artwork from a {@link MuzeiArtProvider} and inserting it into
 * the {@link MuzeiDatabase}.
 */
public class ArtworkLoadJobService extends SimpleJobService {
    private static final String TAG = "ArtworkLoadJobService";

    static void scheduleNext(Context context) {
        FirebaseJobDispatcher jobDispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(context));
        jobDispatcher.mustSchedule(jobDispatcher.newJobBuilder()
                .setService(ArtworkLoadJobService.class)
                .setTag("next")
                .setLifetime(Lifetime.FOREVER)
                .build());
    }

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
                return JobService.RESULT_FAIL_NORETRY;
            }
            long maxLoadedArtworkId = result.getLong(KEY_MAX_LOADED_ARTWORK_ID, 0L);
            ArrayDeque<Long> recentArtworkIds = RecentArtworkIdsConverter.fromString(
                    result.getString(KEY_RECENT_ARTWORK_IDS, ""));
            try (Cursor newArtwork = client.query(contentUri,
                    null, "_id > ?",
                    new String[]{Long.toString(maxLoadedArtworkId)},
                    null);
                 Cursor allArtwork = client.query(contentUri,
                         null, null, null, null)) {
                if (newArtwork == null || allArtwork == null) {
                    return JobService.RESULT_FAIL_NORETRY;
                }
                // First prioritize new artwork
                while (newArtwork.moveToNext()) {
                    Artwork validArtwork = checkForValidArtwork(client, contentUri, newArtwork);
                    if (validArtwork != null) {
                        validArtwork.sourceComponentName = provider.componentName;
                        database.artworkDao().insert(validArtwork);
                        client.call(METHOD_MARK_ARTWORK_LOADED, validArtwork.imageUri.toString(), null);
                        // If we just loaded the last new artwork, we should request that they load another
                        // in preparation for the next load
                        if (!newArtwork.moveToNext()) {
                            client.call(METHOD_REQUEST_LOAD, null, null);
                        }
                        return JobService.RESULT_SUCCESS;
                    }
                }
                // No new artwork, request that they load another in preparation for the next load
                client.call(METHOD_REQUEST_LOAD, null, null);
                // Is there any artwork at all?
                if (allArtwork.getCount() == 0) {
                    Log.w(TAG, "Unable to find any artwork for " + provider.componentName);
                    return JobService.RESULT_FAIL_NORETRY;
                }
                // Okay so there's at least some artwork.
                // Is it just the one artwork we're already showing?
                if (allArtwork.getCount() == 1 && allArtwork.moveToFirst()) {
                    long artworkId = allArtwork.getLong(allArtwork.getColumnIndex(BaseColumns._ID));
                    Uri artworkUri = ContentUris.withAppendedId(contentUri, artworkId);
                    Artwork currentArtwork = database.artworkDao().getCurrentArtworkBlocking();
                    if (currentArtwork != null && artworkUri.equals(currentArtwork.imageUri)) {
                        Log.i(TAG, "Unable to find any other artwork for " + provider.componentName);
                        return JobService.RESULT_FAIL_NORETRY;
                    }
                }
                // At this point, we know there must be some artwork that isn't the current artwork
                // We want to avoid showing artwork we've recently loaded, but don't want
                // to exclude *all* of the current artwork, so we cut down the recent list's size
                // to avoid issues where the provider has deleted a large percentage of their artwork
                while (recentArtworkIds.size() > allArtwork.getCount() / 2) {
                    recentArtworkIds.removeFirst();
                }
                // Now find a random piece of artwork that isn't in our previous list
                Random random = new Random();
                while (true) {
                    int position = random.nextInt(allArtwork.getCount());
                    if (allArtwork.moveToPosition((position))) {
                        long artworkId = allArtwork.getLong(allArtwork.getColumnIndex(BaseColumns._ID));
                        if (recentArtworkIds.contains(artworkId)) {
                            // Skip previously selected artwork
                            continue;
                        }
                        Artwork validArtwork = checkForValidArtwork(client, contentUri, allArtwork);
                        if (validArtwork != null) {
                            validArtwork.sourceComponentName = provider.componentName;
                            database.artworkDao().insert(validArtwork);
                            client.call(METHOD_MARK_ARTWORK_LOADED, validArtwork.imageUri.toString(), null);
                            return JobService.RESULT_SUCCESS;
                        }
                    }
                }
            }
        } catch (RemoteException e) {
            Log.i(TAG, "Provider " + provider.componentName + " crashed while retrieving artwork", e);
            return JobService.RESULT_FAIL_NORETRY;
        }
    }

    @Nullable
    private Artwork checkForValidArtwork(ContentProviderClientCompat client, Uri contentUri, Cursor data)
            throws RemoteException {
        com.google.android.apps.muzei.api.provider.Artwork providerArtwork =
                com.google.android.apps.muzei.api.provider.Artwork.fromCursor(data);
        Uri artworkUri = ContentUris.withAppendedId(contentUri, providerArtwork.getId());
        try (ParcelFileDescriptor parcelFileDescriptor = client.openFile(artworkUri)) {
            if (parcelFileDescriptor == null) {
                return null;
            }
            Artwork artwork = new Artwork();
            artwork.imageUri = artworkUri;
            artwork.title = providerArtwork.getTitle();
            artwork.byline = providerArtwork.getByline();
            artwork.attribution = providerArtwork.getAttribution();
            return artwork;
        } catch (IOException e) {
            Log.w(TAG, "Unable to preload artwork " + artworkUri, e);
        }
        return null;
    }
}