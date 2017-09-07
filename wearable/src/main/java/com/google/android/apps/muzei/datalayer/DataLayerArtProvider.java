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

package com.google.android.apps.muzei.datalayer;

import android.content.Context;
import android.net.Uri;
import android.support.annotation.NonNull;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;

/**
 * Provider handling art from a connected phone
 */
public class DataLayerArtProvider extends MuzeiArtProvider {
    @Override
    protected void onLoadRequested(final boolean initial) {
        if (initial) {
            FirebaseJobDispatcher dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(getContext()));
            dispatcher.mustSchedule(dispatcher.newJobBuilder()
                    .setService(DataLayerLoadJobService.class)
                    .setTag("datalayer")
                    .build());
        }
    }

    @NonNull
    @Override
    protected InputStream openFile(@NonNull final Artwork artwork) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException();
        }
        DataClient dataClient = Wearable.getDataClient(context);
        try {
            DataItem dataItemResult = Tasks.await(dataClient.getDataItem(
                    Uri.parse("wear://*/artwork")));
            if (!dataItemResult.isDataValid()) {
                throw new FileNotFoundException("Error getting artwork DataItem");
            }
            DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItemResult);
            final Asset asset = dataMapItem.getDataMap().getAsset("image");
            if (asset == null) {
                throw new FileNotFoundException("No image asset in datamap");
            }
            DataClient.GetFdForAssetResponse result = Tasks.await(dataClient.getFdForAsset(asset));
            InputStream inputStream = result.getInputStream();
            result.release();
            if (inputStream == null) {
                throw new FileNotFoundException("Unable to open input stream to artwork");
            }
            return inputStream;
        } catch (ExecutionException |InterruptedException e) {
            throw new FileNotFoundException("Failed to get artwork from Wear: " + e);

        }
    }
}
