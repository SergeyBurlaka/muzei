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

package com.google.android.apps.muzei.provider;

import android.arch.persistence.db.SupportSQLiteQueryBuilder;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.os.UserManagerCompat;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;
import com.google.android.apps.muzei.room.converter.UserCommandTypeConverter;
import com.google.android.apps.muzei.sync.ProviderManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Provides access to a the most recent artwork
 */
public class MuzeiProvider extends ContentProvider {
    private static final String TAG = "MuzeiProvider";
    /**
     * The incoming URI matches the ARTWORK URI pattern
     */
    private static final int ARTWORK = 1;
    /**
     * The incoming URI matches the ARTWORK ID URI pattern
     */
    private static final int ARTWORK_ID = 2;
    /**
     * The incoming URI matches the SOURCE URI pattern
     */
    private static final int SOURCES = 3;
    /**
     * The incoming URI matches the SOURCE ID URI pattern
     */
    private static final int SOURCE_ID = 4;
    /**
     * A UriMatcher instance
     */
    private static final UriMatcher uriMatcher = MuzeiProvider.buildUriMatcher();
    /**
     * An identity all column projection mapping for Artwork
     */
    private final HashMap<String, String> allArtworkColumnProjectionMap =
            MuzeiProvider.buildAllArtworkColumnProjectionMap();

    /**
     * Creates and initializes a column project for all columns for Artwork
     *
     * @return The all column projection map for Artwork
     */
    private static HashMap<String, String> buildAllArtworkColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID,
                "artwork._id");
        allColumnProjectionMap.put(MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID,
                "artwork._id");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME,
                "sourceComponentName");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI,
                "imageUri");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_TITLE,
                "title");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_BYLINE,
                "byline");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION,
                "attribution");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_TOKEN,
                "token");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT,
                "viewIntent");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_META_FONT,
                "metaFont");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED,
                "date_added");
        allColumnProjectionMap.put(MuzeiContract.Sources.TABLE_NAME + "." + BaseColumns._ID,
                "1 AS sources._id");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                "sourceComponentName AS component_name");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED,
                "1 AS selected");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                "\"\" AS description");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE,
                "0 AS network");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                "1 AS supports_next_artwork");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMMANDS,
                "NULL AS commands");
        return allColumnProjectionMap;
    }

    /**
     * Creates and initializes the URI matcher
     *
     * @return the URI Matcher
     */
    private static UriMatcher buildUriMatcher() {
        final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Artwork.TABLE_NAME,
                MuzeiProvider.ARTWORK);
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Artwork.TABLE_NAME + "/#",
                MuzeiProvider.ARTWORK_ID);
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Sources.TABLE_NAME,
                MuzeiProvider.SOURCES);
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Sources.TABLE_NAME + "/#",
                MuzeiProvider.SOURCE_ID);
        return matcher;
    }

    @Override
    public int delete(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException("Deletes are not supported");
    }

    @Override
    public String getType(@NonNull final Uri uri) {
        // Chooses the MIME type based on the incoming URI pattern
        switch (MuzeiProvider.uriMatcher.match(uri)) {
            case ARTWORK:
                // If the pattern is for artwork, returns the artwork content type.
                return MuzeiContract.Artwork.CONTENT_TYPE;
            case ARTWORK_ID:
                // If the pattern is for artwork id, returns the artwork content item type.
                return MuzeiContract.Artwork.CONTENT_ITEM_TYPE;
            case SOURCES:
                // If the pattern is for sources, returns the sources content type.
                return MuzeiContract.Sources.CONTENT_TYPE;
            case SOURCE_ID:
                // If the pattern is for source id, returns the sources content item type.
                return MuzeiContract.Sources.CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(@NonNull final Uri uri, final ContentValues values) {
        throw new UnsupportedOperationException("Inserts are not supported");
    }

    /**
     * Creates the underlying DatabaseHelper
     *
     * @see android.content.ContentProvider#onCreate()
     */
    @Override
    public boolean onCreate() {
        // Schedule a job that will update the latest artwork in the Direct Boot cache directory
        // whenever the artwork changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            DirectBootCacheJobService.scheduleDirectBootCacheJob(getContext());
        }
        return true;
    }

    @Override
    public Cursor query(@NonNull final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        if (!UserManagerCompat.isUserUnlocked(getContext())) {
            Log.w(TAG, "Queries are not supported until the user is unlocked");
            return null;
        }
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK_ID) {
            return queryArtwork(uri, projection, selection, selectionArgs, sortOrder);
        } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCE_ID) {
            return querySource(uri, projection);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private Cursor queryArtwork(@NonNull final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        SupportSQLiteQueryBuilder qb = SupportSQLiteQueryBuilder.builder("artwork");
        qb.columns(computeColumns(projection, allArtworkColumnProjectionMap));
        Provider provider = MuzeiDatabase.getInstance(context).providerDao().getCurrentProviderBlocking();
        String finalSelection = DatabaseUtils.concatenateWhere(selection,
                "artwork.sourceComponentName = " + provider.componentName.flattenToShortString());
        if (MuzeiProvider.uriMatcher.match(uri) == ARTWORK_ID) {
            // If the incoming URI is for a single source identified by its ID, appends "_ID = <artworkId>"
            // to the where clause, so that it selects that single piece of artwork
            finalSelection = DatabaseUtils.concatenateWhere(selection,
                    "artwork._id = " + uri.getLastPathSegment());
        }
        qb.selection(finalSelection, selectionArgs);
        String orderBy;
        if (TextUtils.isEmpty(sortOrder)) {
            orderBy = "date_added DESC";
        } else {
            orderBy = sortOrder;
        }
        qb.orderBy(orderBy);
        final Cursor c = MuzeiDatabase.getInstance(context).query(qb.create());
        c.setNotificationUri(context.getContentResolver(), uri);
        return c;
    }

    private Cursor querySource(@NonNull final Uri uri, final String[] projection) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(projection);
        Artwork artwork = MuzeiDatabase.getInstance(context).artworkDao().getCurrentArtworkBlocking();
        ProviderManager providerManager = ProviderManager.getInstance(context);

        MatrixCursor.RowBuilder row = c.newRow();
        row.add(BaseColumns._ID, 1L);
        row.add(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME, artwork.sourceComponentName);
        row.add(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED, true);
        row.add(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                providerManager.getCurrentDescription());
        row.add(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE, false);
        row.add(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                providerManager.getSupportsNextArtworkBlocking());
        row.add(MuzeiContract.Sources.COLUMN_NAME_COMMANDS,
                UserCommandTypeConverter.commandsListToString(artwork.getCommandsBlocking(context)));

        c.setNotificationUri(context.getContentResolver(), uri);
        return c;
    }

    private String[] computeColumns(String[] projectionIn, @NonNull HashMap<String, String> projectionMap) {
        if (projectionIn != null && projectionIn.length > 0) {
            String[] projection = new String[projectionIn.length];
            int length = projectionIn.length;
            for (int i = 0; i < length; i++) {
                String userColumn = projectionIn[i];
                String column = projectionMap.get(userColumn);
                if (column != null) {
                    projection[i] = column;
                    continue;
                }
                if (userColumn.contains(" AS ") || userColumn.contains(" as ")) {
                    /* A column alias already exist */
                    projection[i] = userColumn;
                    continue;
                }
                throw new IllegalArgumentException("Invalid column "
                        + projectionIn[i]);
            }
            return projection;
        }
        // Return all columns in projection map.
        Set<Map.Entry<String, String>> entrySet = projectionMap.entrySet();
        String[] projection = new String[entrySet.size()];
        Iterator<Map.Entry<String, String>> entryIter = entrySet.iterator();
        int i = 0;
        while (entryIter.hasNext()) {
            Map.Entry<String, String> entry = entryIter.next();
            // Don't include the _count column when people ask for no projection.
            if (entry.getKey().equals(BaseColumns._COUNT)) {
                continue;
            }
            projection[i++] = entry.getValue();
        }
        return projection;
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull final Uri uri, @NonNull final String mode)
            throws FileNotFoundException {
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK_ID) {
            return openFileArtwork(uri, mode);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Nullable
    private ParcelFileDescriptor openFileArtwork(@NonNull final Uri uri, @NonNull final String mode)
            throws FileNotFoundException {
        final Context context = getContext();
        if (context == null) {
            return null;
        }
        if (!UserManagerCompat.isUserUnlocked(context)) {
            File file = DirectBootCacheJobService.getCachedArtwork(context);
            if (file == null) {
                throw new FileNotFoundException("No wallpaper was cached for Direct Boot");
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
        Artwork artwork = MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK
                ? MuzeiDatabase.getInstance(context).artworkDao().getCurrentArtworkBlocking()
                : MuzeiDatabase.getInstance(context).artworkDao().getArtworkById(ContentUris.parseId(uri));
        if (artwork == null) {
            throw new FileNotFoundException("Could not get artwork file for " + uri);
        }
        return context.getContentResolver().openFileDescriptor(artwork.imageUri, mode);
    }

    @Override
    public int update(@NonNull final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException("Updates are not supported");
    }
}