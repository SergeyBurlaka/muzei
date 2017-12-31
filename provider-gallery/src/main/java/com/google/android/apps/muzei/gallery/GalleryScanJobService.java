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

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.media.ExifInterface;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.SimpleJobService;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.apps.muzei.api.provider.ProviderContract;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class GalleryScanJobService extends SimpleJobService {
    private static final String TAG = "GalleryArtJob";

    private static final Random sRandom = new Random();

    @SuppressLint("SimpleDateFormat")
    private static final SimpleDateFormat sExifDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

    private static final Set<String> sOmitCountryCodes = new HashSet<>();
    static final String SCAN_CHOSEN_PHOTO_ID = "SCAN_CHOSEN_PHOTO_ID";

    static {
        sOmitCountryCodes.add("US");
    }

    private Geocoder mGeocoder;

    @Override
    public void onCreate() {
        super.onCreate();
        mGeocoder = new Geocoder(this);
    }

    @Override
    public int onRunJob(final JobParameters job) {
        if (job.getExtras() != null) {
            long id = job.getExtras().getLong(SCAN_CHOSEN_PHOTO_ID, -1);
            if (id != -1) {
                deleteMediaUris();
                scanChosenPhoto(GalleryDatabase.getInstance(this).chosenPhotoDao()
                        .getChosenPhotoBlocking(id));
                return RESULT_SUCCESS;
            }
        }
        List<ChosenPhoto> chosenPhotos = GalleryDatabase.getInstance(this).chosenPhotoDao()
                .getChosenPhotosBlocking();
        int numChosenUris = (chosenPhotos != null) ? chosenPhotos.size() : 0;
        if (numChosenUris > 0) {
            deleteMediaUris();
            // Now add all of the chosen photos
            for (ChosenPhoto chosenPhoto : chosenPhotos) {
                scanChosenPhoto(chosenPhoto);
            }
            return RESULT_SUCCESS;
        }
        // else, use Media URIs
        return addMediaUri();
    }

    private void deleteMediaUris() {
        Uri contentUri = MuzeiArtProvider.getContentUri(this, GalleryArtProvider.class);
        getContentResolver().delete(contentUri,
                ProviderContract.Artwork.METADATA + "=?",
                new String[] {MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()});
    }

    private void scanChosenPhoto(ChosenPhoto chosenPhoto) {
        if (chosenPhoto.isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            addTreeUri(chosenPhoto);
        } else {
            File cachedFile = ChosenPhotoDao.getCacheFileForUri(this, chosenPhoto.uri);
            if (cachedFile != null && cachedFile.exists()) {
                addUri(chosenPhoto.uri, Uri.fromFile(cachedFile));
            } else {
                addUri(chosenPhoto.uri, chosenPhoto.uri);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void addTreeUri(ChosenPhoto chosenPhoto) {
        Uri treeUri = chosenPhoto.uri;
        List<Uri> allImages = new ArrayList<>();
        try {
            addAllImagesFromTree(allImages, treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri));
            // Shuffle all the images to give a random initial load order
            Collections.shuffle(allImages, sRandom);
            for (Uri uri : allImages) {
                addUri(treeUri, uri);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to load images from " + treeUri + ", deleting row", e);
            GalleryDatabase.getInstance(this).chosenPhotoDao().delete(this,
                    Collections.singletonList(chosenPhoto.id));
        }
    }

    private int addMediaUri() {
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing read external storage permission.");
            return RESULT_FAIL_NORETRY;
        }
        Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[] { MediaStore.MediaColumns._ID },
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " NOT LIKE '%Screenshots%'",
                null, null);
        if (cursor == null) {
            Log.w(TAG, "Empty cursor.");
            return RESULT_FAIL_NORETRY;
        }

        int count = cursor.getCount();
        if (count == 0) {
            Log.d(TAG, "No photos in the gallery.");
            return RESULT_FAIL_NORETRY;
        }

        Artwork currentArtwork = ProviderContract.Artwork.getLastAddedArtwork(this, GalleryArtProvider.class);
        String lastToken = (currentArtwork != null) ? currentArtwork.getToken() : null;

        Uri imageUri;
        while (true) {
            cursor.moveToPosition(sRandom.nextInt(count));
            imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0));
            if (!imageUri.toString().equals(lastToken)) {
                break;
            }
        }

        cursor.close();
        addUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageUri);
        return RESULT_SUCCESS;
    }

    private void addUri(Uri baseUri, Uri imageUri) {
        String token = imageUri.toString();

        // Retrieve metadata for item
        Metadata metadata = ensureMetadataExists(imageUri);

        // Publish the actual artwork
        String title;
        if (metadata != null && metadata.date != null) {
            title = DateUtils.formatDateTime(this, metadata.date.getTime(),
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                            | DateUtils.FORMAT_SHOW_WEEKDAY);
        } else {
            title = getString(R.string.gallery_from_gallery);
        }

        String byline;
        if (metadata != null && !TextUtils.isEmpty(metadata.location)) {
            byline = metadata.location;
        } else {
            byline = getString(R.string.gallery_touch_to_view);
        }
        ProviderContract.Artwork.addArtwork(this, GalleryArtProvider.class,
                new Artwork.Builder()
                        .title(title)
                        .byline(byline)
                        .token(token)
                        .persistentUri(imageUri)
                        .metadata(baseUri.toString())
                        .build());
    }

    @Nullable
    private Metadata ensureMetadataExists(@NonNull Uri imageUri) {
        MetadataDao metadataDao = GalleryDatabase.getInstance(this).metadataDao();
        Metadata existingMetadata = metadataDao.getMetadataForUri(imageUri);
        if (existingMetadata != null) {
            return existingMetadata;
        }
        // No cached metadata or it's stale, need to pull it separately using Exif
        Metadata metadata = new Metadata(imageUri);

        try (InputStream in = getContentResolver().openInputStream(imageUri)) {
            if (in == null) {
                return null;
            }
            ExifInterface exifInterface = new ExifInterface(in);
            String dateString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
            if (!TextUtils.isEmpty(dateString)) {
                metadata.date = sExifDateFormat.parse(dateString);
            }

            double[] latlong = exifInterface.getLatLong();
            if (latlong != null) {
                // Reverse geocode
                List<Address> addresses = null;
                try {
                    addresses = mGeocoder.getFromLocation(latlong[0], latlong[1], 1);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid latitude/longitude, skipping location metadata", e);
                }
                if (addresses != null && addresses.size() > 0) {
                    Address addr = addresses.get(0);
                    String locality = addr.getLocality();
                    String adminArea = addr.getAdminArea();
                    String countryCode = addr.getCountryCode();
                    StringBuilder sb = new StringBuilder();
                    if (!TextUtils.isEmpty(locality)) {
                        sb.append(locality);
                    }
                    if (!TextUtils.isEmpty(adminArea)) {
                        if (sb.length() > 0) {
                            sb.append(", ");
                        }
                        sb.append(adminArea);
                    }
                    if (!TextUtils.isEmpty(countryCode)
                            && !sOmitCountryCodes.contains(countryCode)) {
                        if (sb.length() > 0) {
                            sb.append(", ");
                        }
                        sb.append(countryCode);
                    }
                    metadata.location = sb.toString();
                }
            }

            metadataDao.insert(metadata);
            return metadata;
        } catch (ParseException|IOException|IllegalArgumentException|StackOverflowError
                |NullPointerException|SecurityException e) {
            Log.w(TAG, "Couldn't read image metadata.", e);
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void addAllImagesFromTree(final List<Uri> allImages, final Uri treeUri,
                                    final String parentDocumentId) {
        final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                parentDocumentId);
        Cursor children = null;
        try {
            children = getContentResolver().query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null);
        } catch (NullPointerException e) {
            Log.e(TAG, "Error reading " + childrenUri, e);
        }
        if (children == null) {
            return;
        }
        while (children.moveToNext()) {
            String documentId = children.getString(
                    children.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
            String mimeType = children.getString(
                    children.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE));
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                // Recursively explore all directories
                addAllImagesFromTree(allImages, treeUri, documentId);
            } else if (mimeType != null && mimeType.startsWith("image/")) {
                // Add images to the list
                if (allImages != null) {
                    allImages.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId));
                }
            }
        }
        children.close();
    }
}
