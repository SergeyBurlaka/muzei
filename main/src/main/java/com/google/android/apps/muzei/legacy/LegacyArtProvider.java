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

package com.google.android.apps.muzei.legacy;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;

import java.util.List;

/**
 * A MuzeiArtProvider that encapsulates all of the logic for working with MuzeiArtSources
 */
public class LegacyArtProvider extends MuzeiArtProvider {
    private static final String TAG = "LegacyArtProvider";

    @Override
    protected void onLoadRequested(boolean initial) {
        LegacySourceManager.sendAction(getContext(), MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
    }

    @NonNull
    @Override
    protected String getDescription() {
        return LegacySourceManager.getDescription(getContext());
    }

    @NonNull
    @Override
    protected List<UserCommand> getCommands(@NonNull final Artwork artwork) {
        return LegacySourceManager.getCommands(getContext());
    }

    @Override
    protected void onCommand(@NonNull Artwork artwork, int id) {
        LegacySourceManager.sendAction(getContext(), id);
    }

    @Override
    protected boolean openArtworkInfo(@NonNull Artwork artwork) {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        Intent viewIntent = LegacySourceManager.getViewIntent(getContext());
        if (viewIntent != null) {
            try {
                viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Make sure any data URIs granted to Muzei are passed onto the started Activity
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(viewIntent);
                return true;
            } catch (RuntimeException e) {
                // Catch ActivityNotFoundException, SecurityException,
                // and FileUriExposedException
                Log.w(TAG, "Unable to start view Intent " + viewIntent, e);
            }
        }
        return false;
    }
}
