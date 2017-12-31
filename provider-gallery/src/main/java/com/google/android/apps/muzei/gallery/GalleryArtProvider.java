/*
 * Copyright 2014 Google Inc.
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

package com.google.android.apps.muzei.gallery;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.support.annotation.NonNull;
import android.util.Log;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;

import java.util.List;

public class GalleryArtProvider extends MuzeiArtProvider {
    private static final String TAG = "GalleryArtProvider";

    @Override
    protected void onLoadRequested(final boolean initial) {
        FirebaseJobDispatcher dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(getContext()));
        dispatcher.mustSchedule(dispatcher.newJobBuilder()
                .setService(GalleryScanJobService.class)
                .setTag("gallery")
                .build());
    }

    @Override
    protected boolean openArtworkInfo(@NonNull final Artwork artwork) {
        if (getContext() == null) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(artwork.getPersistentUri(), "image/jpeg");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Could not open " + artwork.getPersistentUri(), e);
        }
        return false;
    }

    @Override
    @NonNull
    protected String getDescription() {
        Context context = getContext();
        if (context == null) {
            return super.getDescription();
        }
        List<ChosenPhoto> chosenPhotos = GalleryDatabase.getInstance(context).chosenPhotoDao()
                .getChosenPhotosBlocking();
        if (chosenPhotos.isEmpty()) {
            return context.getString(R.string.gallery_description);
        }
        try (Cursor allImages = query(getContentUri(), new String[]{}, null, null, null)) {
            int numImages = allImages != null ? allImages.getCount() : 0;
            return context.getResources().getQuantityString(R.plurals.gallery_description_choice_template,
                    numImages, numImages);
        }
    }
}
