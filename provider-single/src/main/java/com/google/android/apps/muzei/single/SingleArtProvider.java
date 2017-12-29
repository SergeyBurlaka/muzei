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

package com.google.android.apps.muzei.single;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MutableLiveData;
import android.content.Context;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.apps.muzei.api.provider.ProviderContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link MuzeiArtProvider} that displays just a single image
 */
public class SingleArtProvider extends MuzeiArtProvider {
    private static final String TAG = "SingleArtwork";
    private static ExecutorService sExecutor;

    @NonNull
    static File getArtworkFile(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("getArtworkFile received null Context");
        }
        return new File(context.getFilesDir(), "single");
    }

    @NonNull
    public static LiveData<Boolean> setArtwork(Context context, @NonNull Uri artworkUri) {
        if (sExecutor == null) {
            sExecutor = Executors.newSingleThreadExecutor();
        }
        final MutableLiveData<Boolean> mutableLiveData = new MutableLiveData<>();
        sExecutor.submit(() -> {
            boolean success = writeUriToFile(context, artworkUri, getArtworkFile(context));
            if (success) {
                Artwork artwork = new Artwork.Builder()
                        .token(artworkUri.toString())
                        .webUri(artworkUri)
                        .build();
                ProviderContract.Artwork.setArtwork(context, SingleArtProvider.class, artwork);
            }
            mutableLiveData.setValue(success);
        });
        return mutableLiveData;
    }

    private static boolean writeUriToFile(@NonNull Context context, @NonNull Uri uri, @NonNull File destFile) {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = in != null ? new FileOutputStream(destFile) : null) {
            if (in == null) {
                return false;
            }
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            return true;
        } catch (IOException|SecurityException|UnsupportedOperationException e) {
            Log.e(TAG, "Unable to read Uri: " + uri, e);
            return false;
        }
    }

    @Override
    protected void onLoadRequested(boolean initial) {
        // There's always only one artwork for this provider,
        // so there's never any additional artwork to load
    }

    @NonNull
    @Override
    protected InputStream openFile(@NonNull Artwork artwork) throws IOException {
        return new FileInputStream(getArtworkFile(getContext()));
    }
}
