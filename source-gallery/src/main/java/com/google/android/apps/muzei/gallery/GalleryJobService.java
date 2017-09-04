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
import android.support.media.ExifInterface;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobService;
import com.firebase.jobdispatcher.SimpleJobService;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.ProviderContract;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class GalleryJobService extends SimpleJobService {
    private static final String TAG = "GalleryArtJob";

    private static final Random sRandom = new Random();

    @SuppressLint("SimpleDateFormat")
    private static final SimpleDateFormat sExifDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

    private static final Set<String> sOmitCountryCodes = new HashSet<>();
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
        List<ChosenPhoto> chosenPhotos = GalleryDatabase.getInstance(this).chosenPhotoDao()
                .getChosenPhotosBlocking();
        int numChosenUris = (chosenPhotos != null) ? chosenPhotos.size() : 0;

        Artwork currentArtwork = ProviderContract.Artwork.getLastAddedArtwork(this, GalleryArtProvider.class);
        String lastToken = (currentArtwork != null) ? currentArtwork.getToken() : null;

        Uri imageUri;
        if (numChosenUris > 0) {
            // First build a list of all image URIs, recursively exploring any tree URIs that were added
            List<Uri> allImages = new ArrayList<>(numChosenUris);
            for (ChosenPhoto chosenPhoto : chosenPhotos) {
                Uri chosenUri = chosenPhoto.getContentUri();
                if (chosenPhoto.isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Uri treeUri = chosenPhoto.uri;
                    GalleryArtProvider.addAllImagesFromTree(this, allImages, treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri));
                } else {
                    allImages.add(chosenUri);
                }
            }
            int numImages = allImages.size();
            if (numImages == 0) {
                Log.e(TAG, "No photos in the selected directories.");
                return JobService.RESULT_FAIL_NORETRY;
            }
            while (true) {
                imageUri = allImages.get(sRandom.nextInt(numImages));
                if (numImages <= 1 || !imageUri.toString().equals(lastToken)) {
                    break;
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing read external storage permission.");
                return JobService.RESULT_FAIL_NORETRY;
            }
            Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[] { MediaStore.MediaColumns._ID },
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " NOT LIKE '%Screenshots%'",
                    null, null);
            if (cursor == null) {
                Log.w(TAG, "Empty cursor.");
                return JobService.RESULT_FAIL_NORETRY;
            }

            int count = cursor.getCount();
            if (count == 0) {
                Log.e(TAG, "No photos in the gallery.");
                return JobService.RESULT_FAIL_NORETRY;
            }

            while (true) {
                cursor.moveToPosition(sRandom.nextInt(count));
                imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0));
                if (!imageUri.toString().equals(lastToken)) {
                    break;
                }
            }

            cursor.close();
        }

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
                        .build());
        return JobService.RESULT_SUCCESS;
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
}
