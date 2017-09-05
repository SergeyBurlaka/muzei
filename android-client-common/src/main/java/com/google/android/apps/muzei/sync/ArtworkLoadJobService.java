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

import android.content.ContentProviderClient;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobService;
import com.firebase.jobdispatcher.SimpleJobService;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.apps.muzei.api.provider.ProviderContract;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;

import java.io.IOException;

/**
 * Job responsible for loading artwork from a {@link MuzeiArtProvider} and inserting it into
 * the {@link MuzeiDatabase}.
 */
public class ArtworkLoadJobService extends SimpleJobService {
    private static final String TAG = "ArtworkLoadJobService";

    @Override
    public int onRunJob(final JobParameters job) {
        MuzeiDatabase database = MuzeiDatabase.getInstance(this);
        Provider provider = database.providerDao()
                .getCurrentProviderBlocking(this);
        if (provider == null) {
            return JobService.RESULT_FAIL_NORETRY;
        }

        Uri contentUri = MuzeiArtProvider.getContentUri(this, provider.componentName);
        try (ContentProviderClient client = getContentResolver()
                .acquireUnstableContentProviderClient(contentUri)) {
            if (client == null) {
                return JobService.RESULT_FAIL_NORETRY;
            }
            // TODO add selection and selection args
            try (Cursor data = client.query(contentUri,
                    null, null, null,
                    ProviderContract.Artwork.DATE_ADDED + " DESC")) {
                if (data == null || !data.moveToNext()) {
                    return JobService.RESULT_FAIL_RETRY;
                }
                com.google.android.apps.muzei.api.provider.Artwork providerArtwork =
                        com.google.android.apps.muzei.api.provider.Artwork.fromCursor(data);
                Uri artworkUri = ContentUris.withAppendedId(contentUri, providerArtwork.getId());
                try (ParcelFileDescriptor ignored = client.openFile(artworkUri, "r")) {
                    Artwork artwork = new Artwork();
                    artwork.sourceComponentName = provider.componentName;
                    artwork.imageUri = artworkUri;
                    artwork.title = providerArtwork.getTitle();
                    artwork.byline = providerArtwork.getByline();
                    artwork.attribution = providerArtwork.getAttribution();
                    database.artworkDao().insert(artwork);
                    return JobService.RESULT_SUCCESS;
                } catch (IOException e) {
                    Log.w(TAG, "Unable to preload artwork " + artworkUri, e);
                    return JobService.RESULT_FAIL_RETRY;
                }
            } catch (RemoteException e) {
                Log.i(TAG, "Provider " + provider.componentName + " crashed while retrieving artwork", e);
                return JobService.RESULT_FAIL_RETRY;
            }
        }
    }
}
