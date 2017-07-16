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
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.JobIntentService;
import android.util.Log;

import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;

import java.util.ArrayList;
import java.util.List;

public class GalleryArtProvider extends MuzeiArtProvider {
    private static final String TAG = "GalleryArtProvider";

    @Override
    protected void onLoadRequested(final boolean initial) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        JobIntentService.enqueueWork(context, GalleryArtJobIntentService.class, 0,
                new Intent());
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
        int numImages = 0;
        final ArrayList<Long> idsToDelete = new ArrayList<>();
        for (ChosenPhoto chosenPhoto : chosenPhotos) {
            if (chosenPhoto.isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Uri treeUri = chosenPhoto.uri;
                try {
                    numImages += addAllImagesFromTree(context, null, treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri));
                } catch (SecurityException e) {
                    Log.w(TAG, "Unable to load images from " + treeUri + ", deleting row", e);
                    idsToDelete.add(chosenPhoto.id);
                }
            } else {
                numImages++;
            }
        }
        if (!idsToDelete.isEmpty()) {
            final Context applicationContext = context.getApplicationContext();
            new Thread() {
                @Override
                public void run() {
                    GalleryDatabase.getInstance(applicationContext).chosenPhotoDao()
                            .delete(applicationContext, idsToDelete);
                }
            }.start();
        }
        return numImages > 0
                ? context.getResources().getQuantityString(R.plurals.gallery_description_choice_template,
                    numImages, numImages)
                : context.getString(R.string.gallery_description);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    static int addAllImagesFromTree(Context context, final List<Uri> allImages, final Uri treeUri,
                                            final String parentDocumentId) {
        final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                parentDocumentId);
        Cursor children = null;
        try {
            children = context.getContentResolver().query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null);
        } catch (NullPointerException e) {
            Log.e(TAG, "Error reading " + childrenUri, e);
        }
        if (children == null) {
            return 0;
        }
        int numImagesAdded = 0;
        while (children.moveToNext()) {
            String documentId = children.getString(
                    children.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
            String mimeType = children.getString(
                    children.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE));
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                // Recursively explore all directories
                numImagesAdded += addAllImagesFromTree(context, allImages, treeUri, documentId);
            } else if (mimeType != null && mimeType.startsWith("image/")) {
                // Add images to the list
                if (allImages != null) {
                    allImages.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId));
                }
                numImagesAdded++;
            }
        }
        children.close();
        return numImagesAdded;
    }
}
