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
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.apps.muzei.room.converter.UserCommandTypeConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMANDS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_DESCRIPTION;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_COMMANDS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_DESCRIPTION;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_TRIGGER_COMMAND;

/**
 * Encapsulates the connection and information about a Provider
 */
public class Provider extends ProviderEntity {
    private static final String TAG = "ProviderConnection";

    public static void nextArtwork(final Context context) {
        final LiveData<Provider> providerLiveData = MuzeiDatabase.getInstance(context).providerDao()
                .getCurrentProvider(context);
        providerLiveData.observeForever(new Observer<Provider>() {
            @Override
            public void onChanged(@Nullable final Provider provider) {
                providerLiveData.removeObserver(this);
                if (provider == null) {
                    return;
                }
                provider.nextArtwork();
            }
        });
    }

    private final Context mContext;
    private final Uri mContentUri;
    private final Executor mExecutor = Executors.newCachedThreadPool();

    private ProviderInfo mProviderInfo;
    private String mLabel;
    private Drawable mIcon;
    private @ColorInt int mColor;
    private boolean mIsSettingsActivitySet;
    private ComponentName mSettingsActivity;
    private boolean mIsSetupActivitySet;
    private ComponentName mSetupActivity;

    Provider(Context context, @NonNull ProviderEntity provider) {
        this(context, provider.componentName);
    }

    public Provider(Context context, @NonNull ComponentName provider) {
        super(provider);
        mContext = context.getApplicationContext();
        mContentUri = MuzeiArtProvider.getContentUri(context, provider);
    }

    @NonNull
    private ProviderInfo getProviderInfo() {
        if (mProviderInfo == null) {
            try {
                mProviderInfo = mContext.getPackageManager().getProviderInfo(componentName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException("Invalid MuzeiArtProvider: " + componentName, e);
            }
        }
        return mProviderInfo;
    }

    public String getLabel() {
        if (mLabel == null) {
            CharSequence label = getProviderInfo().loadLabel(mContext.getPackageManager());
            mLabel = label != null ? label.toString() : "";
        }
        return mLabel;
    }

    public Drawable getIcon() {
        if (mIcon == null) {
            mIcon = getProviderInfo().loadIcon(mContext.getPackageManager());
        }
        return mIcon;
    }

    @ColorInt
    public int getColor() {
        if (mColor == -1) {
            mColor = Color.WHITE;
            Bundle metaData = getProviderInfo().metaData;
            if (metaData != null) {
                mColor = metaData.getInt("color", mColor);
                try {
                    float[] hsv = new float[3];
                    Color.colorToHSV(mColor, hsv);
                    boolean adjust = false;
                    if (hsv[2] < 0.8f) {
                        hsv[2] = 0.8f;
                        adjust = true;
                    }
                    if (hsv[1] > 0.4f) {
                        hsv[1] = 0.4f;
                        adjust = true;
                    }
                    if (adjust) {
                        mColor = Color.HSVToColor(hsv);
                    }
                    if (Color.alpha(mColor) != 255) {
                        mColor = Color.argb(255,
                                Color.red(mColor),
                                Color.green(mColor),
                                Color.blue(mColor));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return mColor;
    }

    public ComponentName getSettingsActivity() {
        if (!mIsSettingsActivitySet) {
            mIsSettingsActivitySet = true;
            mSettingsActivity = null;
            Bundle metaData = getProviderInfo().metaData;
            if (metaData != null) {
                String settingsActivity = metaData.getString("settingsActivity");
                if (!TextUtils.isEmpty(settingsActivity)) {
                    mSettingsActivity = ComponentName.unflattenFromString(
                            getProviderInfo().packageName + "/" + settingsActivity);
                }
            }
        }
        return mSettingsActivity;
    }

    public ComponentName getSetupActivity() {
        if (!mIsSetupActivitySet) {
            mIsSetupActivitySet = true;
            mSetupActivity = null;
            Bundle metaData = getProviderInfo().metaData;
            if (metaData != null) {
                String setupActivity = metaData.getString("setupActivity");
                if (!TextUtils.isEmpty(setupActivity)) {
                    mSetupActivity = ComponentName.unflattenFromString(
                            getProviderInfo().packageName + "/" + setupActivity);
                }
            }
        }
        return mSetupActivity;
    }

    private ContentProviderClient getContentProviderClient() {
        return mContext.getContentResolver().acquireUnstableContentProviderClient(mContentUri);
    }

    public interface DescriptionCallback {
        void onCallback(@NonNull String description);
    }

    @SuppressLint("StaticFieldLeak")
    public void getDescription(final DescriptionCallback callback) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(final Void... voids) {
                return getDescriptionBlocking();
            }

            @Override
            protected void onPostExecute(final String description) {
                callback.onCallback(description);
            }
        }.executeOnExecutor(mExecutor);
    }

    @NonNull
    public String getDescriptionBlocking() {
        try (ContentProviderClient client = getContentProviderClient()) {
            if (client == null) {
                return "";
            }
            try {
                Bundle result = client.call(METHOD_GET_DESCRIPTION, null, null);
                return result != null ? result.getString(KEY_DESCRIPTION, "") : "";
            } catch (RemoteException e) {
                Log.i(TAG, "Provider " + componentName + " crashed while retrieving description", e);
                return "";
            }
        }
    }

    public interface SupportNextArtworkCallback {
        void onCallback(boolean supportsNextArtwork);
    }

    @SuppressLint("StaticFieldLeak")
    public void getSupportsNextArtwork(final SupportNextArtworkCallback callback) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(final Void... voids) {
                return getSupportsNextArtworkBlocking();
            }

            @Override
            protected void onPostExecute(final Boolean supportsNextArtwork) {
                callback.onCallback(supportsNextArtwork);
            }
        }.executeOnExecutor(mExecutor);
    }

    public boolean getSupportsNextArtworkBlocking() {
        return false;
    }

    public interface CommandsCallback {
        void onCallback(@NonNull List<UserCommand> commands);
    }

    @SuppressLint("StaticFieldLeak")
    public void getCommands(final CommandsCallback callback) {
        new AsyncTask<Void, Void, List<UserCommand>>() {
            @Override
            protected List<UserCommand> doInBackground(final Void... voids) {
                return getCommandsBlocking();
            }

            @Override
            protected void onPostExecute(final List<UserCommand> commands) {
                callback.onCallback(commands);
            }
        }.executeOnExecutor(mExecutor);
    }

    public List<UserCommand> getCommandsBlocking() {
        try (ContentProviderClient client = getContentProviderClient()) {
            ArrayList<UserCommand> commands = new ArrayList<>();
            if (client == null) {
                return commands;
            }
            try {
                Bundle result = client.call(METHOD_GET_COMMANDS, mContentUri.toString(), null);
                String commandsString =result != null ? result.getString(KEY_COMMANDS, null) : null;
                return UserCommandTypeConverter.fromString(commandsString);
            } catch (RemoteException e) {
                Log.i(TAG, "Provider " + componentName + " crashed while retrieving commands", e);
                return commands;
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    public void sendAction(final int id) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(final Void... voids) {
                try (ContentProviderClient client = getContentProviderClient()) {
                    if (client == null) {
                        return null;
                    }
                    try {
                        Bundle extras = new Bundle();
                        extras.putInt(KEY_COMMAND, id);
                        client.call(METHOD_TRIGGER_COMMAND,
                                mContentUri.toString(), extras);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Provider " + componentName + " crashed while sending action", e);
                    }
                    return null;
                }
            }
        }.executeOnExecutor(mExecutor);
    }

    public void nextArtwork() {
        // TODO Actually trigger the next artwork
        sendAction(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
    }
}
