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

package com.google.android.apps.muzei.api.provider;

import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * This provides a no configuration mechanism for building a {@link DocumentsProvider} from the
 * artwork in one or more {@link MuzeiArtProvider}s in your app. Users will be able to select
 * from your artwork when calling {@link android.content.Intent#ACTION_GET_CONTENT},
 * {@link android.content.Intent#ACTION_OPEN_DOCUMENT}, or
 * {@link android.content.Intent#ACTION_OPEN_DOCUMENT_TREE}.
 * <p>
 * This class uses the {@link ProviderInfo#authority} to determine what {@link MuzeiArtProvider}(s) to
 * retrieve artwork from. The authority you use here must be the authority of a valid MuzeiArtProvider
 * from your own package plus {@link #AUTHORITY_SUFFIX}.
 * </p>
 * <p>
 * For example, if your MuzeiArtProvider's authority is <code>com.example.yourartprovider</code>, the
 * authority you use for this class must be <code>com.example.yourartprovider.documents</code>. This
 * allows you to include this class directly in your manifest like this:
 * </p>
 *
 * <pre class="prettyprint">&lt;manifest&gt;
 *    ...
 *    &lt;application&gt;
 *        ...
 *        &lt;provider
 *            android:name="com.google.android.apps.muzei.api.provider.MuzeiArtDocumentsProvider"
 *            android:authorities="com.example.yourartprovider.documents"
 *            android:exported="true"
 *            android:grantUriPermissions="true"
 *            android:permission="android.permission.MANAGE_DOCUMENTS"&gt;
 *            &lt;intent-filter&gt;
 *                &lt;action android:name="android.content.action.DOCUMENTS_PROVIDER" /&gt;
 *            &lt;/intent-filter&gt;
 *        &lt;/provider&gt;
 *        ...
 *    &lt;/application&gt;
 * &lt;/manifest&gt;</pre>
 * <p>
 * Multiple MuzeiArtProviders can be supported by a single MuzeiArtDocumentsProvider by separating each
 * authority by a semicolon e.g.,
 * <code>com.example.yourartprovider.documents;com.example.second.documents</code>
 * </p>
 */
@RequiresApi(api = Build.VERSION_CODES.KITKAT)
public class MuzeiArtDocumentsProvider extends DocumentsProvider {
    private static final String TAG = "MuzeiArtDocuments";
    /**
     * The authority you use with this class must be the authority of a valid MuzeiArtProvider
     * from your own package plus this suffix.
     */
    public static final String AUTHORITY_SUFFIX = ".documents";
    /**
     * Default root projection
     */
    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID};
    /**
     * Default document projection
     */
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SUMMARY,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED};

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        Context context = getContext();
        if (context == null) {
            return result;
        }
        PackageManager pm = context.getPackageManager();
        String[] authorities;
        try {
            ProviderInfo info = pm.getProviderInfo(new ComponentName(context, getClass()), 0);
            authorities = info.authority.split(";");
        } catch (PackageManager.NameNotFoundException e) {
            // wtf? We can't find ourselves?
            return result;
        }
        for (String authority : authorities) {
            if (!authority.endsWith(AUTHORITY_SUFFIX)) {
                throw new IllegalStateException("All MuzeiArtDocumentsProvider authorities must end in " +
                    AUTHORITY_SUFFIX + ", found " + authority);
            }
            String providerAuthority = authority.substring(0, authority.lastIndexOf(AUTHORITY_SUFFIX));
            ProviderInfo providerInfo = pm.resolveContentProvider(providerAuthority, 0);
            if (providerInfo == null) {
                throw new IllegalStateException("Authority " + providerAuthority +
                        " does not correspond with a valid ContentProvider.");
            }
            if (!context.getPackageName().equals(providerInfo.packageName)) {
                throw new IllegalStateException("Authority " + providerAuthority +
                        " must belong to your package name, found " + providerInfo.packageName);
            }
            int artworkCount;
            Uri contentUri = MuzeiArtProvider.getContentUri(context,
                    new ComponentName(providerInfo.packageName, providerInfo.name));
            try (Cursor artwork = context.getContentResolver().query(contentUri,
                    null, null, null ,null)) {
                if (artwork == null) {
                    Log.e(TAG, "Unable to read MuzeiArtProvider " + providerAuthority);
                    continue;
                }
                artworkCount = artwork.getCount();
            }
            if (artworkCount == 0) {
                Log.i(TAG, "No artwork found for MuzeiArtProvider " + providerAuthority + ", skipping");
                continue;
            }
            final MatrixCursor.RowBuilder row = result.newRow();
            row.add(DocumentsContract.Root.COLUMN_ROOT_ID, providerInfo.name);
            row.add(DocumentsContract.Root.COLUMN_ICON, providerInfo.icon);
            row.add(DocumentsContract.Root.COLUMN_TITLE, providerInfo.loadLabel(pm));
            row.add(DocumentsContract.Root.COLUMN_FLAGS,
                            DocumentsContract.Root.FLAG_SUPPORTS_SEARCH |
                            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
            row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/*");
            row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, providerInfo.name);
        }
        return result;
    }

    @Override
    public Cursor querySearchDocuments(final String rootId, final String query, final String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        Context context = getContext();
        if (context == null) {
            return result;
        }
        String selection = ProviderContract.Artwork.TITLE + " LIKE ? OR " +
                ProviderContract.Artwork.BYLINE + " LIKE ? OR " +
                ProviderContract.Artwork.ATTRIBUTION + " LIKE ?";
        String likeAnyPositionQuery = "%" + query + "%";
        includeAllArtwork(rootId, result, context.getContentResolver().query(
                MuzeiArtProvider.getContentUri(context, new ComponentName(context, rootId)),
                null,
                selection,
                new String[] { likeAnyPositionQuery, likeAnyPositionQuery, likeAnyPositionQuery },
                ProviderContract.Artwork.DATE_MODIFIED));
        return result;
    }

    @Override
    public boolean isChildDocument(final String parentDocumentId, final String documentId) {
        return documentId.startsWith(parentDocumentId + "/");
    }

    @Override
    public String getDocumentType(final String documentId) throws FileNotFoundException {
        if (documentId != null && documentId.indexOf('/') != -1) {
            return "image/*";
        }
        return DocumentsContract.Document.MIME_TYPE_DIR;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        Context context = getContext();
        if (context == null) {
            return result;
        }
        includeAllArtwork(parentDocumentId, result, context.getContentResolver().query(
                MuzeiArtProvider.getContentUri(context, new ComponentName(context, parentDocumentId)),
                null, null, null, null));
        return result;
    }

    private void includeAllArtwork(String providerName, MatrixCursor result, Cursor data) {
        if (data == null) {
            return;
        }
        while (data.moveToNext()) {
            Artwork artwork = Artwork.fromCursor(data);
            final MatrixCursor.RowBuilder row = result.newRow();
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    providerName + "/" + Long.toString(artwork.getId()));
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, artwork.getTitle());
            row.add(DocumentsContract.Document.COLUMN_SUMMARY, artwork.getByline());
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "image/*");
            // Don't allow deleting the currently displayed artwork
            row.add(DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL);
            row.add(DocumentsContract.Document.COLUMN_SIZE, null);
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, artwork.getDateModified().getTime());
        }
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        Context context = getContext();
        if (context == null) {
            return result;
        }
        if (documentId.indexOf('/') == -1) {
            // It is a providerName associated with a root
            PackageManager packageManager = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, documentId);
            ProviderInfo providerInfo;
            try {
                providerInfo = packageManager.getProviderInfo(componentName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Could not find a valid ContentProvider for " + componentName);
                return result;
            }
            final MatrixCursor.RowBuilder row = result.newRow();
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId);
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    providerInfo.loadLabel(packageManager));
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID);
            row.add(DocumentsContract.Document.COLUMN_SIZE, null);
        } else {
            // This is an individual piece of artwork
            String[] splitDocumentId = documentId.split("/");
            String providerName = splitDocumentId[0];
            Uri contentUri = ContentUris.withAppendedId(
                    MuzeiArtProvider.getContentUri(context, new ComponentName(context, providerName)),
                    Long.parseLong(splitDocumentId[1]));
            includeAllArtwork(providerName, result, context.getContentResolver().query(contentUri,
                    null, null, null, null));
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (documentId == null || documentId.indexOf('/') == -1) {
            return null;
        }
        Context context = getContext();
        if (context == null) {
            return null;
        }
        String[] splitDocumentId = documentId.split("/");
        String providerName = splitDocumentId[0];
        Uri contentUri = ContentUris.withAppendedId(
                MuzeiArtProvider.getContentUri(context, new ComponentName(context, providerName)),
                Long.parseLong(splitDocumentId[1]));
        return context.getContentResolver().openFileDescriptor(contentUri, mode, signal);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(final String documentId, final Point sizeHint, final CancellationSignal signal) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (documentId != null && documentId.indexOf('/') != -1) {
            String[] splitDocumentId = documentId.split("/");
            return openArtworkThumbnail(splitDocumentId[0], Long.parseLong(splitDocumentId[1]),
                    sizeHint, signal);
        }
        return null;
    }

    @Nullable
    private AssetFileDescriptor openArtworkThumbnail(String providerName, long artworkId,
            final Point sizeHint, final CancellationSignal signal) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        File tempFile = getThumbnailFile(providerName, artworkId);
        if (tempFile != null && tempFile.exists() && tempFile.length() != 0) {
            // We already have a cached thumbnail
            return new AssetFileDescriptor(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        Uri artworkUri = ContentUris.withAppendedId(MuzeiArtProvider.getContentUri(getContext(),
                new ComponentName(getContext(), providerName)), artworkId);
        BitmapFactory.decodeStream(context.getContentResolver().openInputStream(artworkUri), null, options);
        if (signal.isCanceled()) {
            // Canceled, so we'll stop here to save us the effort of actually decoding the image
            return null;
        }
        final int targetHeight = 2 * sizeHint.y;
        final int targetWidth = 2 * sizeHint.x;
        final int height = options.outHeight;
        final int width = options.outWidth;
        options.inSampleSize = 1;
        if (height > targetHeight || width > targetWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / options.inSampleSize) > targetHeight
                    || (halfWidth / options.inSampleSize) > targetWidth) {
                options.inSampleSize *= 2;
            }
        }
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeStream(context.getContentResolver().openInputStream(artworkUri), null, options);
        // Write out the thumbnail to a temporary file
        if (tempFile == null) {
            try {
                tempFile = File.createTempFile("thumbnail", null, getContext().getCacheDir());
            } catch (IOException e) {
                Log.e(TAG, "Error writing thumbnail", e);
                return null;
            }
        }
        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
        } catch (IOException e) {
            Log.e(TAG, "Error writing thumbnail", e);
            return null;
        }
        return new AssetFileDescriptor(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Nullable
    private File getThumbnailFile(String providerName, long artworkId) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        PackageManager packageManager = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, providerName);
        ProviderInfo providerInfo;
        try {
            providerInfo = packageManager.getProviderInfo(componentName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Could not find a valid ContentProvider for " + componentName);
            return null;
        }
        File directory = new File(context.getCacheDir(), "muzei_thumbnails_" + providerInfo.authority);
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        return new File(directory, Long.toString(artworkId));
    }

    @Override
    public boolean onCreate() {
        return true;
    }
}
