package com.google.android.apps.muzei.util;

import android.content.ContentProviderClient;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.FileNotFoundException;

/**
 * Backports {@link AutoCloseable} support for {@link ContentProviderClient} to API 19
 */
public class ContentProviderClientCompat implements AutoCloseable {
    @Nullable
    public static ContentProviderClientCompat getClient(Context context, Uri uri) {
        ContentProviderClient client = context.getContentResolver().acquireUnstableContentProviderClient(uri);
        return client != null ? new ContentProviderClientCompat(client) : null;
    }

    private final ContentProviderClient mContentProviderClient;

    private ContentProviderClientCompat(@NonNull ContentProviderClient contentProviderClient) {
        mContentProviderClient = contentProviderClient;
    }

    public Bundle call(String method, String arg, Bundle extras) throws RemoteException {
        return mContentProviderClient.call(method, arg, extras);
    }

    public Cursor query(Uri url, String[] projection, String selection, String[] selectionArgs, String sortOrder)
            throws RemoteException {
        return mContentProviderClient.query(url, projection, selection, selectionArgs, sortOrder);
    }

    public ParcelFileDescriptor openFile(Uri url) throws FileNotFoundException, RemoteException {
        return mContentProviderClient.openFile(url, "r");
    }

    @Override
    public void close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mContentProviderClient.close();
        } else {
            mContentProviderClient.release();
        }
    }
}
