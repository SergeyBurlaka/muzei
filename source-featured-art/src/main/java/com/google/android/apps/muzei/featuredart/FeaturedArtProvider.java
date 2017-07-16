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

package com.google.android.apps.muzei.featuredart;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.customtabs.CustomTabsIntent;
import android.support.v4.app.JobIntentService;
import android.support.v4.content.ContextCompat;

import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;

import java.util.List;

public class FeaturedArtProvider extends MuzeiArtProvider {
    private static final Uri ARCHIVE_URI = Uri.parse("http://muzei.co/archive");

    private static final int COMMAND_ID_SHARE = 1;
    private static final int COMMAND_ID_VIEW_ARCHIVE = 2;

    @Override
    protected void onLoadRequested(final boolean initial) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        if (initial) {
            // Show the initial photo (starry night)
            addArtwork(new Artwork.Builder()
                    .token("initial")
                    .title("The Starry Night")
                    .byline("Vincent van Gogh, 1889.\nMuzei shows a new painting every day.")
                    .attribution("wikiart.org")
                    .persistentUri(Uri.parse("file:///android_asset/starrynight.jpg"))
                    .webUri(Uri.parse("http://www.wikiart.org/en/vincent-van-gogh/the-starry-night-1889"))
                    .build());
        }
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        long nextUpdateMillis = sp.getLong(FeaturedArtJobIntentService.PREF_NEXT_UPDATE_MILLIS, 0);
        if (nextUpdateMillis <= System.currentTimeMillis()) {
            // Load the next artwork
            JobIntentService.enqueueWork(context, FeaturedArtJobIntentService.class, 0,
                    new Intent().putExtra(FeaturedArtJobIntentService.KEY_INITIAL_LOAD, initial));
        }
    }

    @NonNull
    @Override
    protected List<UserCommand> getCommands(@NonNull final Artwork artwork) {
        List<UserCommand> commands = super.getCommands(artwork);
        commands.add(new UserCommand(COMMAND_ID_SHARE,
                getContext().getString(R.string.featuredart_action_share_artwork)));
        commands.add(new UserCommand(COMMAND_ID_VIEW_ARCHIVE,
                getContext().getString(R.string.featuredart_source_action_view_archive)));
        return commands;
    }

    @Override
    protected void onCommand(@NonNull final Artwork artwork, int id) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        if (COMMAND_ID_SHARE == id) {
            String artist = artwork.getByline()
                    .replaceFirst("\\.\\s*($|\\n).*", "").trim();

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "My Android wallpaper today is '"
                    + artwork.getTitle().trim()
                    + "' by " + artist
                    + ". #MuzeiFeaturedArt\n\n"
                    + artwork.getWebUri());
            shareIntent = Intent.createChooser(shareIntent, "Share artwork");
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(shareIntent);
        } else if (COMMAND_ID_VIEW_ARCHIVE == id) {
            CustomTabsIntent cti = new CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setToolbarColor(ContextCompat.getColor(context, R.color.featuredart_color))
                    .build();
            Intent intent = cti.intent;
            intent.setData(ARCHIVE_URI);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(intent);
            }
        }
    }
}
