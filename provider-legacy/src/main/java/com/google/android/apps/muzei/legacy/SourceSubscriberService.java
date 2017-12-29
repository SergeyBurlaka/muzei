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

package com.google.android.apps.muzei.legacy;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.ProviderContract;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;
import com.google.android.apps.muzei.room.SourceDao;

import java.util.ArrayList;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_PUBLISH_STATE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_STATE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

public class SourceSubscriberService extends IntentService {
    private static final String TAG = "SourceSubscriberService";

    public SourceSubscriberService() {
        super("SourceSubscriberService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (!ACTION_PUBLISH_STATE.equals(action)) {
            return;
        }
        // Handle API call from source
        String token = intent.getStringExtra(EXTRA_TOKEN);
        SourceDao sourceDao = MuzeiDatabase.getInstance(this).sourceDao();
        Source source = sourceDao.getCurrentSourceBlocking();
        if (source == null ||
                !TextUtils.equals(token, source.componentName.flattenToShortString())) {
            Log.w(TAG, "Dropping update from non-selected source, token=" + token
                    + " does not match token for " + source);
            return;
        }

        SourceState state = null;
        if (intent.hasExtra(EXTRA_STATE)) {
            Bundle bundle = intent.getBundleExtra(EXTRA_STATE);
            if (bundle != null) {
                state = SourceState.fromBundle(bundle);
            }
        }

        if (state == null) {
            // If there is no state, there is nothing to change
            return;
        }

        source.description = state.getDescription();
        source.wantsNetworkAvailable = state.getWantsNetworkAvailable();
        source.supportsNextArtwork = false;
        source.commands = new ArrayList<>();
        int numSourceActions = state.getNumUserCommands();
        for (int i = 0; i < numSourceActions; i++) {
            UserCommand command = state.getUserCommandAt(i);
            //noinspection deprecation
            if (command.getId() == MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK) {
                source.supportsNextArtwork = true;
            } else {
                source.commands.add(command);
            }
        }

        sourceDao.update(source);

        com.google.android.apps.muzei.api.Artwork currentArtwork = state.getCurrentArtwork();
        if (currentArtwork != null) {
            Artwork.Builder builder = new Artwork.Builder();
            builder.token(currentArtwork.getImageUri().toString())
                    .persistentUri(currentArtwork.getImageUri())
                    .title(currentArtwork.getTitle())
                    .byline(currentArtwork.getByline())
                    .attribution(currentArtwork.getAttribution())
                    .metadata(currentArtwork.getViewIntent().toUri(Intent.URI_INTENT_SCHEME));

            ProviderContract.Artwork.addArtwork(this, LegacyArtProvider.class,
                    builder.build());
        }
    }
}
