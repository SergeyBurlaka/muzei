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

package com.example.muzei.examplesource500px;

import android.content.Intent;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v4.app.JobIntentService;
import android.util.Log;

import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.ProviderContract;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import static com.example.muzei.examplesource500px.FiveHundredPxService.Photo;
import static com.example.muzei.examplesource500px.FiveHundredPxService.PhotosResponse;

public class FiveHundredPxExampleArtJobIntentService extends JobIntentService {
    private static final String TAG = "500pxExample";

    @Override
    protected void onHandleWork(@NonNull final Intent intent) {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(final Chain chain) throws IOException {
                        Request request = chain.request();
                        HttpUrl url = request.url().newBuilder()
                                .addQueryParameter("consumer_key", Config.CONSUMER_KEY).build();
                        request = request.newBuilder().url(url).build();
                        return chain.proceed(request);
                    }
                })
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://api.500px.com/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        FiveHundredPxService service = retrofit.create(FiveHundredPxService.class);
        PhotosResponse response;
        try {
            response = service.getPopularPhotos().execute().body();
        } catch (IOException e) {
            Log.w(TAG, "Error reading 500px response", e);
            return;
        }

        if (response == null || response.photos == null) {
            return;
        }

        if (response.photos.size() == 0) {
            Log.w(TAG, "No photos returned from API.");
            return;
        }

        for (Photo photo : response.photos) {
            ProviderContract.Artwork.addArtwork(this, FiveHundredPxExampleArtProvider.class,
                    new Artwork.Builder()
                            .token(Integer.toString(photo.id))
                            .title(photo.name)
                            .byline(photo.user.fullname)
                            .persistentUri(Uri.parse(photo.image_url))
                            .webUri(Uri.parse("http://500px.com/photo/" + photo.id))
                            .build());
        }
    }
}

