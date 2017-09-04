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

package com.google.android.apps.muzei.shortcuts;

import android.app.Activity;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.os.Bundle;
import android.support.annotation.Nullable;

import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;

/**
 * Open the Artwork Info associated with the current artwork
 */
public class ArtworkInfoRedirectActivity extends Activity {
    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final LiveData<Artwork> artworkLiveData = MuzeiDatabase.getInstance(this).artworkDao().getCurrentArtwork();
        artworkLiveData.observeForever(new Observer<Artwork>() {
            @Override
            public void onChanged(@Nullable final Artwork artwork) {
                artworkLiveData.removeObserver(this);
                if (artwork != null) {
                    artwork.openArtworkInfo(ArtworkInfoRedirectActivity.this);
                }
                finish();
            }
        });
    }
}
