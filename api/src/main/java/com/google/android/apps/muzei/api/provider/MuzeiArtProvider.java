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

package com.google.android.apps.muzei.api.provider;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.internal.OkHttpClientFactory;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMANDS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_DESCRIPTION;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_OPEN_ARTWORK_INFO_SUCCESS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_COMMANDS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_DESCRIPTION;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_OPEN_ARTWORK_INFO;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_REQUEST_LOAD;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_TRIGGER_COMMAND;

/**
 * Base class for a Muzei Live Wallpaper artwork provider. Art providers are a way for other apps to
 * feed wallpapers (called {@linkplain Artwork artworks}) to the Muzei Live Wallpaper.
 */
@RequiresApi(api = Build.VERSION_CODES.KITKAT)
public abstract class MuzeiArtProvider extends ContentProvider {
    private static final String TAG = "MuzeiArtProvider";

    /**
     * Retrieve the content URI for the given {@link MuzeiArtProvider}, allowing you to build
     * custom queries, inserts, updates, and deletes using a {@link ContentResolver}.
     * <p>
     * This will throw an {@link IllegalArgumentException} if the provider is not valid.
     *
     * @param context Context used to retrieve the content URI.
     * @param provider The {@link MuzeiArtProvider} you need a content URI for
     * @return The content URI for the {@link MuzeiArtProvider}
     *
     * @see MuzeiArtProvider#getContentUri()
     */
    @NonNull
    public static Uri getContentUri(@NonNull Context context, @NonNull Class<? extends MuzeiArtProvider> provider) {
        return getContentUri(context, new ComponentName(context, provider));
    }

    /**
     * Retrieve the content URI for the given {@link MuzeiArtProvider}, allowing you to build
     * custom queries, inserts, updates, and deletes using a {@link ContentResolver}.
     * <p>
     * This will throw an {@link IllegalArgumentException} if the provider is not valid.
     *
     * @param context Context used to retrieve the content URI.
     * @param provider The {@link MuzeiArtProvider} you need a content URI for
     * @return The content URI for the {@link MuzeiArtProvider}
     *
     * @see MuzeiArtProvider#getContentUri()
     */
    @NonNull
    public static Uri getContentUri(@NonNull Context context, @NonNull ComponentName provider) {
        PackageManager pm = context.getPackageManager();
        String authority;
        try {
            ProviderInfo info = pm.getProviderInfo(provider, 0);
            authority = info.authority;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Invalid MuzeiArtProvider: " + provider, e);
        }
        return Uri.parse("content://" + authority + "/" + TABLE_NAME);
    }

    private static final String TABLE_NAME = "artwork";
    /**
     * An identity all column projection mapping for artwork
     */
    private final HashMap<String, String> allArtworkColumnProjectionMap =
            MuzeiArtProvider.buildAllArtworkColumnProjectionMap();

    /**
     * Creates and initializes a column project for all columns for artwork
     *
     * @return The all column projection map for artwork
     */
    private static HashMap<String, String> buildAllArtworkColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID, BaseColumns._ID);
        allColumnProjectionMap.put(ProviderContract.Artwork.TOKEN,
                ProviderContract.Artwork.TOKEN);
        allColumnProjectionMap.put(ProviderContract.Artwork.TITLE,
                ProviderContract.Artwork.TITLE);
        allColumnProjectionMap.put(ProviderContract.Artwork.BYLINE,
                ProviderContract.Artwork.BYLINE);
        allColumnProjectionMap.put(ProviderContract.Artwork.ATTRIBUTION,
                ProviderContract.Artwork.ATTRIBUTION);
        allColumnProjectionMap.put(ProviderContract.Artwork.PERSISTENT_URI,
                ProviderContract.Artwork.PERSISTENT_URI);
        allColumnProjectionMap.put(ProviderContract.Artwork.WEB_URI,
                ProviderContract.Artwork.WEB_URI);
        allColumnProjectionMap.put(ProviderContract.Artwork.METADATA,
                ProviderContract.Artwork.METADATA);
        allColumnProjectionMap.put(ProviderContract.Artwork.DATA,
                ProviderContract.Artwork.DATA);
        allColumnProjectionMap.put(ProviderContract.Artwork.DATE_ADDED,
                ProviderContract.Artwork.DATE_ADDED);
        allColumnProjectionMap.put(ProviderContract.Artwork.DATE_MODIFIED,
                ProviderContract.Artwork.DATE_MODIFIED);
        return allColumnProjectionMap;
    }

    private DatabaseHelper databaseHelper;
    private String authority;
    private Uri contentUri;

    /**
     * Retrieve the content URI for this {@link MuzeiArtProvider}, allowing you to build
     * custom queries, inserts, updates, and deletes using a {@link ContentResolver}.
     *
     * @return The content URI for this {@link MuzeiArtProvider}
     *
     * @see MuzeiArtProvider#getContentUri(Context, Class)
     */
    @NonNull
    protected final Uri getContentUri() {
        if (getContext() == null) {
            throw new IllegalStateException("getContentUri() should not be called before onCreate()");
        }
        if (contentUri == null) {
            contentUri = getContentUri(getContext(), getClass());
        }
        return contentUri;
    }

    /**
     * Retrieve the last added artwork for this {@link MuzeiArtProvider}.
     *
     * @return The last added Artwork, or null if no artwork has been added
     *
     * @see ProviderContract.Artwork#getLastAddedArtwork(Context, Class)
     */
    @Nullable
    protected final com.google.android.apps.muzei.api.provider.Artwork getLastAddedArtwork() {
        try (Cursor data = query(contentUri, null, null, null,
                ProviderContract.Artwork.DATE_ADDED + " DESC")) {
            return data != null && data.moveToFirst() ? Artwork.fromCursor(data) : null;
        }
    }

    /**
     * Add a new piece of artwork to this {@link MuzeiArtProvider}.
     *
     * @param artwork The artwork to add
     * @return The URI of the newly added artwork or null if the insert failed
     *
     * @see ProviderContract.Artwork#addArtwork(Context, Class, Artwork)
     */
    @Nullable
    protected final Uri addArtwork(@NonNull Artwork artwork) {
        return insert(contentUri, artwork.toContentValues());
    }

    /**
     * Set this {@link MuzeiArtProvider} to only show the given artwork, deleting any other
     * artwork previously added. Only in the cases where the artwork is successfully inserted will the other
     * artwork be removed.
     *
     * @param artwork The artwork to set
     * @return The URI of the newly set artwork or null if the insert failed
     *
     * @see ProviderContract.Artwork#setArtwork(Context, Class, Artwork)
     */
    @Nullable
    protected final Uri setArtwork(@NonNull Artwork artwork) {
        Uri artworkUri = insert(contentUri, artwork.toContentValues());
        if (artworkUri != null) {
            delete(contentUri, BaseColumns._ID + " != ?",
                    new String[] {Long.toString(ContentUris.parseId(artworkUri))});
        }
        return artworkUri;
    }

    @CallSuper
    @Override
    @Nullable
    public Bundle call(@NonNull final String method, @Nullable final String arg, @Nullable final Bundle extras) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        long token = Binder.clearCallingIdentity();
        try {
            switch (method) {
                case METHOD_REQUEST_LOAD:
                    try (Cursor data = databaseHelper.getReadableDatabase().query(TABLE_NAME,
                            null, null, null, null, null, null,
                            "1")) {
                        onLoadRequested(data == null || data.getCount() == 0);
                    }
                    break;
                case METHOD_GET_DESCRIPTION: {
                    Bundle bundle = new Bundle();
                    bundle.putString(KEY_DESCRIPTION, getDescription());
                    return bundle;
                }
                case METHOD_GET_COMMANDS:
                    try (Cursor data = context.getContentResolver().query(Uri.parse(arg),
                            null, null, null, null)) {
                        if (data != null && data.moveToNext()) {
                            Bundle bundle = new Bundle();
                            List<UserCommand> userCommands = getCommands(Artwork.fromCursor(data));
                            JSONArray commandsSerialized = new JSONArray();
                            for (UserCommand command : userCommands) {
                                commandsSerialized.put(command.serialize());
                            }
                            bundle.putString(KEY_COMMANDS, commandsSerialized.toString());
                            return bundle;
                        }
                    }
                    break;
                case METHOD_TRIGGER_COMMAND:
                    if (extras != null) {
                        try (Cursor data = context.getContentResolver().query(Uri.parse(arg),
                                null, null, null, null)) {
                            if (data != null && data.moveToNext()) {
                                onCommand(Artwork.fromCursor(data), extras.getInt(KEY_COMMAND));
                            }
                        }
                    }
                    break;
                case METHOD_OPEN_ARTWORK_INFO:
                    try (Cursor data = context.getContentResolver().query(Uri.parse(arg),
                            null, null, null, null)) {
                        if (data != null && data.moveToNext()) {
                            Bundle bundle = new Bundle();
                            boolean success = openArtworkInfo(Artwork.fromCursor(data));
                            bundle.putBoolean(KEY_OPEN_ARTWORK_INFO_SUCCESS, success);
                            return bundle;
                        }
                    }
                    break;
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Callback method when the user has viewed all of the available artwork. This should be used as a cue to load
     * more artwork so that the user has a constant stream of new artwork.
     *
     * <p>Muzei will always prefer to show unseen artwork, but will automatically cycle through all of the available
     * artwork if no new artwork is found (i.e., you don't load new artwork after receiving this callback).</p>
     *
     * @param initial true when there is no artwork available, such as is the case when this is the initial load of
     *                this MuzeiArtProvider.
     */
    protected abstract void onLoadRequested(boolean initial);

    /**
     * Gets the longer description for the current state of this MuzeiArtProvider. For example, 'Popular photos
     * tagged "landscape"'). The default implementation returns the <code>android:description</code>
     * element of the provider element in the manifest.
     *
     * @return The description that should be shown when this provider is selected
     */
    @NonNull
    protected String getDescription() {
        Context context = getContext();
        if (context == null) {
            return "";
        }
        try {
            ProviderInfo info = context.getPackageManager().getProviderInfo(
                    new ComponentName(context, getClass()), 0);
            return info.descriptionRes != 0 ? context.getString(info.descriptionRes) : "";
        } catch (PackageManager.NameNotFoundException e) {
            // Wtf?
            return "";
        }
    }

    /**
     * Retrieve the list of commands available for the given artwork.
     *
     * @param artwork The associated artwork that can be used to customize the list of available commands.
     * @return A List of {@link UserCommand commands} that the user can trigger.
     *
     * @see #onCommand(Artwork, int)
     */
    @NonNull
    protected List<UserCommand> getCommands(@NonNull final Artwork artwork) {
        return new ArrayList<>();
    }

    /**
     * Callback method indicating that the user has selected a command.
     *
     * @param artwork The artwork at the time when this command was triggered.
     * @param id the ID of the command the user has chosen.

     * @see #getCommands(Artwork)
     */
    protected void onCommand(@NonNull final Artwork artwork, int id) {
    }

    /**
     * Callback when the user wishes to see more information about the given artwork. The default implementation
     * opens the {@link ProviderContract.Artwork#WEB_URI web uri} of the artwork.
     *
     * @param artwork The artwork the user wants to see more information about.
     * @return True if the artwork info was successfully opened.
     */
    protected boolean openArtworkInfo(@NonNull Artwork artwork) {
        if (artwork.getWebUri() != null && getContext() != null) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, artwork.getWebUri());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                return true;
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "Could not open " + artwork.getWebUri() + ", artwork info for "
                        + ContentUris.withAppendedId(contentUri, artwork.getId()), e);
            }
        }
        return false;
    }

    @CallSuper
    @Override
    public boolean onCreate() {
        authority = getContentUri().getAuthority();
        String databaseName = authority.substring(authority.lastIndexOf('.') + 1);
        databaseHelper = new DatabaseHelper(getContext(), databaseName);
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull final Uri uri, @Nullable final String[] projection,
                        @Nullable final String selection, @Nullable final String[] selectionArgs,
                        @Nullable final String sortOrder) {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_NAME);
        qb.setProjectionMap(allArtworkColumnProjectionMap);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        if (!uri.equals(contentUri)) {
            // Appends "_ID = <id>" to the where clause, so that it selects the single artwork
            qb.appendWhere(BaseColumns._ID + "=" + uri.getLastPathSegment());
        }
        String orderBy;
        if (TextUtils.isEmpty(sortOrder))
            orderBy = ProviderContract.Artwork.DATE_ADDED + " DESC";
        else
            orderBy = sortOrder;
        final Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy, null);
        c.setNotificationUri(contentResolver, uri);
        return c;
    }

    @Nullable
    @Override
    public String getType(@NonNull final Uri uri) {
        if (uri.equals(contentUri)) {
            return "vnd.android.cursor.dir/vnd." + authority + "." + TABLE_NAME;
        } else {
            return "vnd.android.cursor.item/vnd." + authority + "." + TABLE_NAME;
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull final Uri uri, @Nullable ContentValues values) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (values == null) {
            values = new ContentValues();
        }
        if (values.containsKey(ProviderContract.Artwork.TOKEN)) {
            String token = values.getAsString(ProviderContract.Artwork.TOKEN);
            if (TextUtils.isEmpty(token)) {
                // Treat empty strings as null
                Log.w(TAG, ProviderContract.Artwork.TOKEN + " must be non-empty");
                values.remove(token);
            } else {
                try (Cursor existingData = query(contentUri, new String[] { BaseColumns._ID },
                        ProviderContract.Artwork.TOKEN + "=?", new String[] { token }, null)) {
                    if (existingData != null && existingData.moveToFirst()) {
                        // If there's already a row with the same token, update it rather than
                        // inserting a new row
                        Uri updateUri = ContentUris.withAppendedId(contentUri, existingData.getLong(0));
                        update(updateUri, values, null, null);
                        return updateUri;
                    }
                }
            }
        }
        long now = System.currentTimeMillis();
        values.put(ProviderContract.Artwork.DATE_ADDED, now);
        values.put(ProviderContract.Artwork.DATE_MODIFIED, now);
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        long rowId = db.insert(TABLE_NAME,
                ProviderContract.Artwork.DATE_ADDED, values);
        if (rowId <= 0) {
            // Insert failed, not much we can do about that
            return null;
        }
        // Add the DATA column pointing at the correct location
        boolean hasPersistentUri = values.containsKey(ProviderContract.Artwork.PERSISTENT_URI)
                && !TextUtils.isEmpty(values.getAsString(ProviderContract.Artwork.PERSISTENT_URI));
        File directory;
        if (hasPersistentUri) {
            directory = new File(context.getCacheDir(), "muzei_" + authority);
        } else {
            directory = new File(context.getFilesDir(), "muzei_" + authority);
        }
        File artwork = new File(directory, Long.toString(rowId));
        ContentValues dataValues = new ContentValues();
        dataValues.put(ProviderContract.Artwork.DATA, artwork.getAbsolutePath());
        db.update(TABLE_NAME, values, BaseColumns._ID + "=" + rowId, null);

        // Creates a URI with the artwork ID pattern and the new row ID appended to it.
        final Uri artworkUri = ContentUris.withAppendedId(contentUri, rowId);
        context.getContentResolver().notifyChange(artworkUri, null);
        return artworkUri;
    }

    @Override
    public int delete(@NonNull final Uri uri,
                      @Nullable final String selection, @Nullable final String[] selectionArgs) {
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        int count;

        String finalWhere = selection;
        if (!contentUri.equals(uri)) {
            finalWhere = BaseColumns._ID + " = " + uri.getLastPathSegment();
            // If there were additional selection criteria, append them to the final WHERE clause
            if (selection != null) {
                finalWhere = finalWhere + " AND " + selection;
            }
        }
        // Delete all of the files associated with the rows being deleted
        try (Cursor rowsToDelete = query(contentUri, new String[] { ProviderContract.Artwork.DATA },
                finalWhere, selectionArgs, null)) {
            while (rowsToDelete != null && rowsToDelete.moveToNext()) {
                File file = new File(rowsToDelete.getString(0));
                if (file.exists()) {
                    if (!file.delete()) {
                        Log.w(TAG, "Unable to delete " + file);
                    }
                }
            }
        }
        // Then delete the rows themselves
        count = db.delete(TABLE_NAME, finalWhere, selectionArgs);
        if (count > 0 && getContext() != null) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public int update(@NonNull final Uri uri, @Nullable final ContentValues values,
                      @Nullable final String selection, @Nullable final String[] selectionArgs) {
        if (values == null) {
            return 0;
        }
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        int count;

        String finalWhere = selection;
        if (!contentUri.equals(uri)) {
            finalWhere = BaseColumns._ID + " = " + uri.getLastPathSegment();
            // If there were additional selection criteria, append them to the final WHERE clause
            if (selection != null) {
                finalWhere = finalWhere + " AND " + selection;
            }
        }
        // TOKEN, DATA and DATE_ADDED cannot be changed
        values.remove(ProviderContract.Artwork.TOKEN);
        values.remove(ProviderContract.Artwork.DATA);
        values.remove(ProviderContract.Artwork.DATE_ADDED);
        // Update the DATE_MODIFIED
        values.put(ProviderContract.Artwork.DATE_MODIFIED, System.currentTimeMillis());
        count = db.update(TABLE_NAME, values, finalWhere, selectionArgs);
        if (count > 0 && getContext() != null) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull final Uri uri, @NonNull final String mode)
            throws FileNotFoundException {
        File file;
        Uri persistentUri;
        try (Cursor data = query(uri,
                new String[] {ProviderContract.Artwork.DATA, ProviderContract.Artwork.PERSISTENT_URI},
                null, null, null)) {
            if (data == null || !data.moveToFirst()) {
                throw new FileNotFoundException("Could not get persistent uri for " + uri);
            }
            file = new File(data.getString(0));
            persistentUri = Uri.parse(data.getString(1));
        }
        if (!file.exists() && mode.equals("r")) {
            // Download the image from the persistent URI for read-only operations
            // rather than throw a FileNotFoundException
            try (InputStream in = openFromPersistentUri(persistentUri);
                FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) > 0) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            } catch (IOException|SecurityException e) {
                Log.e(TAG, "Unable to read persistent Uri " + persistentUri + " for " + uri, e);
                if (e instanceof SecurityException) {
                    delete(uri, null, null);
                }
                throw new FileNotFoundException("Could not download artwork from persistent Uri " + persistentUri
                        + " for " + uri);
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    /**
     * Open the persistent uri associated with artwork that has not yet been cached. The default
     * implementation supports URI schemes in the following formats:
     *
     * <ul>
     * <li><code>content://...</code>.</li>
     * <li><code>android.resource://...</code>.</li>
     * <li><code>file://...</code>.</li>
     * <li><code>file:///android_asset/...</code>.</li>
     * <li><code>http://...</code> or <code>https://...</code>.</li>
     * </ul>
     *
     * Throw a {@link SecurityException} if there is a permanent error that causes the persistent URI to be
     * no longer accessible.
     *
     * @param persistentUri The uri to open
     * @return A valid {@link InputStream} for the artwork's image
     * @throws IOException if an error occurs while opening the image. The request will be retried automatically.
     */
    @NonNull
    protected InputStream openFromPersistentUri(@NonNull Uri persistentUri)
            throws IOException {
        Context context = getContext();
        if (context == null) {
            throw new IOException();
        }
        String scheme = persistentUri.getScheme();
        if (scheme == null) {
            throw new IOException("Uri had no scheme");
        }

        InputStream in = null;
        if (ContentResolver.SCHEME_CONTENT.equals(scheme)
                || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            try {
                in = context.getContentResolver().openInputStream(persistentUri);
            } catch (SecurityException e) {
                throw new FileNotFoundException("No access to " + persistentUri + ": " + e.toString());
            }

        } else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            List<String> segments = persistentUri.getPathSegments();
            if (segments != null && segments.size() > 1
                    && "android_asset".equals(segments.get(0))) {
                StringBuilder assetPath = new StringBuilder();
                for (int i = 1; i < segments.size(); i++) {
                    if (i > 1) {
                        assetPath.append("/");
                    }
                    assetPath.append(segments.get(i));
                }
                in = context.getAssets().open(assetPath.toString());
            } else {
                in = new FileInputStream(new File(persistentUri.getPath()));
            }

        } else if ("http".equals(scheme) || "https".equals(scheme)) {
            OkHttpClient client = OkHttpClientFactory.getNewOkHttpsSafeClient();
            Request request;
            request = new Request.Builder().url(new URL(persistentUri.toString())).build();

            Response response = client.newCall(request).execute();
            int responseCode = response.code();
            if (!(responseCode >= 200 && responseCode < 300)) {
                throw new IOException("HTTP error response " + responseCode);
            }
            in = response.body().byteStream();
        }

        if (in == null) {
            throw new FileNotFoundException("Null input stream for URI: " + persistentUri);
        }

        return in;
    }

    /**
     * This class helps open, create, and upgrade the database file.
     */
    static class DatabaseHelper extends SQLiteOpenHelper {
        private static final int DATABASE_VERSION = 1;

        /**
         * Creates a new DatabaseHelper
         *
         * @param context context of this database
         */
        DatabaseHelper(final Context context, String databaseName) {
            super(context, databaseName, null, DATABASE_VERSION);
        }

        /**
         * Creates the underlying database with table name and column names taken from the MuzeiContract class.
         */
        @Override
        public void onCreate(final SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + ProviderContract.Artwork.TOKEN + " TEXT,"
                    + ProviderContract.Artwork.TITLE + " TEXT,"
                    + ProviderContract.Artwork.BYLINE + " TEXT,"
                    + ProviderContract.Artwork.ATTRIBUTION + " TEXT,"
                    + ProviderContract.Artwork.PERSISTENT_URI + " TEXT,"
                    + ProviderContract.Artwork.WEB_URI + " TEXT,"
                    + ProviderContract.Artwork.METADATA + " TEXT,"
                    + ProviderContract.Artwork.DATA + " TEXT,"
                    + ProviderContract.Artwork.DATE_ADDED + " INTEGER NOT NULL,"
                    + ProviderContract.Artwork.DATE_MODIFIED + " INTEGER NOT NULL);");
        }

        /**
         * Upgrades the database.
         */
        @Override
        public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        }
    }
}
