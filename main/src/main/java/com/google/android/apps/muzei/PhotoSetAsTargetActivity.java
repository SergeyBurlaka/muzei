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

package com.google.android.apps.muzei;

import android.app.Activity;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.widget.Toast;

import com.google.android.apps.muzei.single.SingleArtProvider;
import com.google.android.apps.muzei.sync.ProviderManager;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.R;

public class PhotoSetAsTargetActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() == null || getIntent().getData() == null) {
            finish();
            return;
        }

        final Uri photoUri = getIntent().getData();

        final LiveData<Boolean> insertLiveData = SingleArtProvider.setArtwork(this, photoUri);
        insertLiveData.observeForever(new Observer<Boolean>() {
            @Override
            public void onChanged(@Nullable final Boolean success) {
                insertLiveData.removeObserver(this);
                final Context context = PhotoSetAsTargetActivity.this;
                if (success == null || !success) {
                    Toast.makeText(context, R.string.set_as_wallpaper_failed, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }

                // If adding the artwork succeeded, select the single artwork provider
                Bundle bundle = new Bundle();
                bundle.putString(FirebaseAnalytics.Param.ITEM_ID,
                        new ComponentName(context, SingleArtProvider.class).flattenToShortString());
                bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "sources");
                FirebaseAnalytics.getInstance(context).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle);
                ProviderManager.getInstance(context).selectProvider(new ComponentName(context, SingleArtProvider.class), () -> {
                    startActivity(Intent.makeMainActivity(new ComponentName(
                            context, MuzeiActivity.class))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                });

            }
        });
    }
}
