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

package com.google.android.apps.muzei.notifications;

import android.arch.lifecycle.DefaultLifecycleObserver;
import android.arch.lifecycle.LifecycleOwner;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;

import com.google.android.apps.muzei.api.MuzeiContract;

/**
 * LifecycleObserver which updates the notification when the artwork changes
 */
public class NotificationUpdater implements DefaultLifecycleObserver {
    private final Context mContext;
    private HandlerThread mNotificationHandlerThread;
    private ContentObserver mNotificationContentObserver;

    public NotificationUpdater(Context context) {
        mContext = context;
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        // Set up a thread to update notifications whenever the artwork changes
        mNotificationHandlerThread = new HandlerThread("MuzeiWallpaperService-Notification");
        mNotificationHandlerThread.start();
        mNotificationContentObserver = new ContentObserver(new Handler(mNotificationHandlerThread.getLooper())) {
            @Override
            public void onChange(final boolean selfChange, final Uri uri) {
                NewWallpaperNotificationReceiver.maybeShowNewArtworkNotification(mContext);
            }
        };
        mContext.getContentResolver().registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, mNotificationContentObserver);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        mContext.getContentResolver().unregisterContentObserver(mNotificationContentObserver);
        mNotificationHandlerThread.quitSafely();
        NewWallpaperNotificationReceiver.cancelNotification(mContext);
    }
}
