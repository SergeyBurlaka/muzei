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

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.SimpleJobService;
import com.google.android.apps.muzei.FullScreenActivity;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.ProviderContract;
import com.google.android.apps.muzei.complications.ArtworkComplicationProviderService;
import com.google.android.apps.muzei.wearable.ArtworkTransfer;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.util.concurrent.ExecutionException;

/**
 * Load artwork from the Wear Data Layer, writing it into {@link DataLayerArtProvider}.
 *
 * <p>Optionally pass {@link #SHOW_ACTIVATE_NOTIFICATION_EXTRA} to show a
 * notification to activate Muzei if the artwork is not found
 */
public class DataLayerLoadJobService extends SimpleJobService {
    private static final String TAG = "ArtworkLoadJobService";

    public static final String SHOW_ACTIVATE_NOTIFICATION_EXTRA = "SHOW_ACTIVATE_NOTIFICATION";

    @Override
    public int onRunJob(final JobParameters job) {
        boolean showActivateNotification = job.getExtras() != null &&
                job.getExtras().getBoolean(SHOW_ACTIVATE_NOTIFICATION_EXTRA, false);
        DataClient dataClient = Wearable.getDataClient(this);
        try {
            DataItem dataItemResult = Tasks.await(dataClient.getDataItem(
                    Uri.parse("wear://*/artwork")));
            if (!dataItemResult.isDataValid()) {
                Log.i(TAG, "Error getting artwork DataItem");
                if (showActivateNotification) {
                    ActivateMuzeiIntentService.maybeShowActivateMuzeiNotification(this);
                }
                return RESULT_FAIL_NORETRY;
            }
            DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItemResult);
            DataMap artworkDataMap = dataMapItem.getDataMap().getDataMap("artwork");
            if (artworkDataMap == null) {
                Log.w(TAG, "No artwork in datamap.");
                if (showActivateNotification) {
                    ActivateMuzeiIntentService.maybeShowActivateMuzeiNotification(this);
                }
                return RESULT_FAIL_NORETRY;
            }
            Artwork artwork = ArtworkTransfer.fromDataMap(artworkDataMap);
            ProviderContract.Artwork.setArtwork(this, DataLayerArtProvider.class, artwork);
            enableComponents(FullScreenActivity.class, ArtworkComplicationProviderService.class);
            ActivateMuzeiIntentService.clearNotifications(this);
            return RESULT_SUCCESS;
        } catch (ExecutionException |InterruptedException e) {
            Log.w(TAG, "Error getting artwork DataItem", e);
            return RESULT_FAIL_NORETRY;

        }
    }

    private void enableComponents(Class<?>... components) {
        PackageManager packageManager = getPackageManager();
        for (Class<?> component : components) {
            ComponentName componentName = new ComponentName(this, component);
            packageManager.setComponentEnabledSetting(componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }
}
