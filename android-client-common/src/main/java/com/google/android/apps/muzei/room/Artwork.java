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

package com.google.android.apps.muzei.room;

import android.annotation.SuppressLint;
import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.Index;
import android.arch.persistence.room.PrimaryKey;
import android.arch.persistence.room.TypeConverters;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.Toast;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter;
import com.google.android.apps.muzei.room.converter.UriTypeConverter;
import com.google.android.apps.muzei.room.converter.UserCommandTypeConverter;

import net.nurik.roman.muzei.androidclientcommon.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMANDS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_OPEN_ARTWORK_INFO_SUCCESS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_COMMANDS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_OPEN_ARTWORK_INFO;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_TRIGGER_COMMAND;

/**
 * Artwork's representation in Room
 */
@Entity(indices = @Index(value = "sourceComponentName"))
public class Artwork {
    private static final String TAG = "Artwork";

    private static Executor sExecutor;

    private static Executor getExecutor() {
        if (sExecutor == null) {
            sExecutor = Executors.newCachedThreadPool();
        }
        return sExecutor;
    }

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = BaseColumns._ID)
    public long id;

    @TypeConverters(ComponentNameTypeConverter.class)
    public ComponentName sourceComponentName;

    @TypeConverters(UriTypeConverter.class)
    public Uri imageUri;

    public String title;

    public String byline;

    public String attribution;

    public String token;

    @MuzeiContract.Artwork.MetaFontType
    @NonNull
    public String metaFont = MuzeiContract.Artwork.META_FONT_TYPE_DEFAULT;

    @NonNull
    public Uri getContentUri() {
        return getContentUri(id);
    }

    public static Uri getContentUri(long id) {
        return ContentUris.appendId(new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(MuzeiContract.AUTHORITY), id).build();
    }

    private ContentProviderClient getContentProviderClient(Context context) {
        return context.getContentResolver().acquireUnstableContentProviderClient(imageUri);
    }

    @SuppressLint("StaticFieldLeak")
    public void openArtworkInfo(final Context context) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(final Void... voids) {
                try (ContentProviderClient client = getContentProviderClient(context)) {
                    if (client == null) {
                        return false;
                    }
                    try {
                        Bundle result = client.call(METHOD_OPEN_ARTWORK_INFO,
                                imageUri.toString(),
                                null);
                        return result != null && result.getBoolean(KEY_OPEN_ARTWORK_INFO_SUCCESS);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Provider for " + imageUri + " crashed while opening artwork info", e);
                        return false;
                    }
                }
            }

            @Override
            protected void onPostExecute(final Boolean success) {
                if (!success) {
                    Toast.makeText(context, R.string.error_view_details,
                            Toast.LENGTH_SHORT).show();
                }
            }
        }.executeOnExecutor(getExecutor());
    }

    public interface CommandsCallback {
        void onCallback(@NonNull List<UserCommand> commands);
    }

    @SuppressLint("StaticFieldLeak")
    public void getCommands(final Context context, final Artwork.CommandsCallback callback) {
        new AsyncTask<Void, Void, List<UserCommand>>() {
            @Override
            protected List<UserCommand> doInBackground(final Void... voids) {
                return getCommandsBlocking(context);
            }

            @Override
            protected void onPostExecute(final List<UserCommand> commands) {
                callback.onCallback(commands);
            }
        }.executeOnExecutor(getExecutor());
    }

    public List<UserCommand> getCommandsBlocking(Context context) {
        try (ContentProviderClient client = getContentProviderClient(context)) {
            ArrayList<UserCommand> commands = new ArrayList<>();
            if (client == null) {
                return commands;
            }
            try {
                Bundle result = client.call(METHOD_GET_COMMANDS,
                        imageUri.toString(),
                        null);
                String commandsString = result != null ? result.getString(KEY_COMMANDS, null) : null;
                return UserCommandTypeConverter.fromString(commandsString);
            } catch (RemoteException e) {
                Log.i(TAG, "Provider for " + imageUri + " crashed while retrieving commands", e);
                return commands;
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    public void sendAction(final Context context, final int id) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(final Void... voids) {
                try (ContentProviderClient client = getContentProviderClient(context)) {
                    if (client == null) {
                        return null;
                    }
                    try {
                        Bundle extras = new Bundle();
                        extras.putInt(KEY_COMMAND, id);
                        client.call(METHOD_TRIGGER_COMMAND,
                                imageUri.toString(),
                                extras);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Provider for " + imageUri + " crashed while sending action", e);
                    }
                    return null;
                }
            }
        }.executeOnExecutor(getExecutor());
    }
}