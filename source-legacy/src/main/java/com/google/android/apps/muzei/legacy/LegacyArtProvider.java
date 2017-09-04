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
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;

import net.nurik.roman.muzei.R;

import java.net.URISyntaxException;
import java.util.List;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID;

/**
 * A MuzeiArtProvider that encapsulates all of the logic for working with MuzeiArtSources
 */
public class LegacyArtProvider extends MuzeiArtProvider {
    private static final String TAG = "LegacyArtProvider";

    @Override
    protected void onLoadRequested(boolean initial) {
        sendAction(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
    }

    @NonNull
    @Override
    protected String getDescription() {
        Context context = getContext();
        if (context == null) {
            return super.getDescription();
        }
        Source source = MuzeiDatabase.getInstance(getContext()).sourceDao().getCurrentSourceBlocking();
        if (source == null) {
            return "No source selected";
        }
        ServiceInfo sourceInfo;
        try {
            sourceInfo = context.getPackageManager().getServiceInfo(source.componentName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to retrieve source information", e);
            LegacySourceManager.invalidateSelectedSource(context);
            return "No selected source";
        }
        String sourceName = "";
        CharSequence sourceLabel = sourceInfo.loadLabel(context.getPackageManager());
        if (!TextUtils.isEmpty(sourceLabel)) {
            sourceName = sourceLabel.toString();
        }
        String description = source.description;
        if (TextUtils.isEmpty(description)) {
            // Fallback to the description on the source
            description = sourceInfo.descriptionRes != 0 ? context.getString(sourceInfo.descriptionRes) : "";
        }
        return !TextUtils.isEmpty(description)
                ? (!TextUtils.isEmpty(sourceName) ? sourceName + ": " : "") + description
                : sourceName;
    }

    @NonNull
    @Override
    protected List<UserCommand> getCommands(@NonNull final Artwork artwork) {
        Source source = MuzeiDatabase.getInstance(getContext()).sourceDao().getCurrentSourceBlocking();
        return source != null ? source.commands : super.getCommands(artwork);
    }

    @Override
    protected void onCommand(@NonNull Artwork artwork, int id) {
        sendAction(id);
    }

    private void sendAction(int id) {
        Context context = getContext();
        Source source = MuzeiDatabase.getInstance(context).sourceDao().getCurrentSourceBlocking();
        if (context == null || source == null) {
            return;
        }
        try {
            context.startService(new Intent(ACTION_HANDLE_COMMAND)
                    .setComponent(source.componentName)
                    .putExtra(EXTRA_COMMAND_ID, id));
        } catch (IllegalStateException e) {
            Log.i(TAG, "Sending action + " + id + " to " + source.componentName + " failed", e);
            Toast.makeText(context, R.string.source_unavailable, Toast.LENGTH_LONG).show();
            LegacySourceManager.invalidateSelectedSource(context);
        }
    }

    @Override
    protected boolean openArtworkInfo(@NonNull Artwork artwork) {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        Intent viewIntent = null;
        if (artwork.getMetadata() != null) {
            try {
                viewIntent = Intent.parseUri(artwork.getMetadata(), Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException e) {
                Log.i(TAG, "Unable to parse viewIntent " + artwork.getMetadata(), e);
            }
        }
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
