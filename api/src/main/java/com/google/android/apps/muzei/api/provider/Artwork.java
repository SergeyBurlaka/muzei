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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.File;
import java.util.Date;

import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.ATTRIBUTION;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.BYLINE;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.DATA;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.DATE_ADDED;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.DATE_MODIFIED;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.METADATA;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.PERSISTENT_URI;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.TITLE;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.TOKEN;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.WEB_URI;

/**
 * Artwork associated with a {@link MuzeiArtProvider}. Use the {@link Builder} to construct a new instance
 * that can be passed to {@link MuzeiArtProvider#addArtwork(Artwork)} or
 * {@link ProviderContract.Artwork#addArtwork(Context, Class, Artwork)} and use
 * {@link Artwork#fromCursor(Cursor)} to convert a row retrieved from a {@link MuzeiArtProvider} into
 * Artwork instance.
 */
public class Artwork {
    private long id;
    private String token;
    private String title;
    private String byline;
    private String attribution;
    private Uri persistentUri;
    private Uri webUri;
    private String metadata;
    private File data;
    private Date dateAdded;
    private Date dateModified;

    private Artwork() {
    }

    public long getId() {
        return id;
    }

    void setId(final long id) {
        this.id = id;
    }

    /**
     * Returns the token that uniquely defines the artwork.
     *
     * @return the artwork's unique token, or null if it doesn't have one.
     *
     * @see Builder#token
     */
    @Nullable
    public String getToken() {
        return token;
    }

    /**
     * Returns the artwork's user-visible title.
     *
     * @return the artwork's user-visible title, or null if it doesn't have one.
     *
     * @see Builder#title
     */
    @Nullable
    public String getTitle() {
        return title;
    }

    /**
     * Returns the artwork's user-visible byline, usually containing the author and date.
     * This is generally used as a secondary source of information after the {@link #getTitle title}.
     *
     * @return the artwork's user-visible byline, or null if it doesn't have one.
     *
     * @see Builder#byline
     */
    @Nullable
    public String getByline() {
        return byline;
    }

    /**
     * Returns the artwork's user-visible attribution text.
     * This is generally used as a tertiary source of information after the
     * {@link #getTitle title} and the {@link #getByline byline}.
     *
     * @return the artwork's user-visible attribution text, or null if it doesn't have any.
     *
     * @see Builder#attribution
     */
    @Nullable
    public String getAttribution() {
        return attribution;
    }


    /**
     * Returns the artwork's persistent URI.
     * This is used to redownload the artwork automatically if the cache is cleared.
     *
     * @return the artwork's persistent URI, or null if it doesn't have any.
     *
     * @see Builder#persistentUri
     */
    @Nullable
    public Uri getPersistentUri() {
        return persistentUri;
    }

    /**
     * Returns the artwork's web URI.
     * This is used by default in {@link MuzeiArtProvider#openArtworkInfo(Artwork)} to
     * allow the user to view more details about the artwork.
     *
     * @return the artwork's web URI, or null if it doesn't exist
     *
     * @see Builder#webUri
     */
    @Nullable
    public Uri getWebUri() {
        return webUri;
    }

    /**
     * Returns the provider specific metadata about the artwork.
     * This is not used by Muzei at all, so can contain any data that makes it easier to query
     * or otherwise work with your Artwork.
     *
     * @return the artwork's metadata, or null if it doesn't exist
     *
     * @see Builder#metadata
     */
    @Nullable
    public String getMetadata() {
        return metadata;
    }

    /**
     * Returns the {@link File} where a local copy of this artwork will be stored. In almost all cases,
     * you should consider reading and writing artwork by passing the artwork URI to
     * {@link android.content.ContentResolver#openInputStream(Uri)} and
     * {@link android.content.ContentResolver#openOutputStream(Uri)}, respectively.
     * <p>
     * Note: this will only be available if the artwork is retrieved from a
     * {@link MuzeiArtProvider}.
     *
     * @return the local {@link File} where the artwork will be stored, or null if the Artwork
     * was not retrieved from a {@link MuzeiArtProvider}
     */
    @Nullable
    public File getData() {
        return data;
    }

    /**
     * Returns the date this artwork was initially added to its {@link MuzeiArtProvider}.
     * <p>
     * Note: this will only be available if the artwork is retrieved from a
     * {@link MuzeiArtProvider}.
     *
     * @return the date this artwork was initially added, or null if the Artwork
     * was not retrieved from a {@link MuzeiArtProvider}
     */
    @Nullable
    public Date getDateAdded() {
        return dateAdded;
    }

    /**
     * Returns the date of the last modification of the artwork (i.e., the last time it was updated). This
     * will initially be equal to the {@link #getDateAdded() date added}.
     * <p>
     * Note: this will only be available if the artwork is retrieved from a
     * {@link MuzeiArtProvider}.
     *
     * @return the date this artwork was last modified, or null if the Artwork
     * was not retrieved from a {@link MuzeiArtProvider}
     */
    @Nullable
    public Date getDateModified() {
        return dateModified;
    }

    @NonNull
    public static Artwork fromCursor(@NonNull Cursor data) {
        Artwork artwork =
                new com.google.android.apps.muzei.api.provider.Artwork.Builder()
                        .token(data.getString(data.getColumnIndex(TOKEN)))
                        .title(data.getString(data.getColumnIndex(TITLE)))
                        .byline(data.getString(data.getColumnIndex(BYLINE)))
                        .attribution(data.getString(data.getColumnIndex(ATTRIBUTION)))
                        .persistentUri(Uri.parse(data.getString(data.getColumnIndex(PERSISTENT_URI))))
                        .webUri(Uri.parse(data.getString(data.getColumnIndex(WEB_URI))))
                        .metadata(data.getString(data.getColumnIndex(METADATA)))
                        .build();
        artwork.id = data.getLong(data.getColumnIndex(BaseColumns._ID));
        artwork.data = new File(data.getString(data.getColumnIndex(DATA)));
        artwork.dateAdded = new Date(data.getLong(data.getColumnIndex(DATE_ADDED)));
        artwork.dateModified = new Date(data.getLong(data.getColumnIndex(DATE_MODIFIED)));
        return artwork;
    }

    @NonNull
    public ContentValues toContentValues() {
        ContentValues values = new ContentValues();
        values.put(TOKEN, getToken());
        values.put(TITLE, getTitle());
        values.put(BYLINE, getByline());
        values.put(ATTRIBUTION, getAttribution());
        if (getPersistentUri() != null) {
            values.put(PERSISTENT_URI, getPersistentUri().toString());
        }
        if (getWebUri() != null) {
            values.put(WEB_URI, getWebUri().toString());
        }
        values.put(METADATA, getMetadata());
        return values;
    }

    /**
     * A <a href="http://en.wikipedia.org/wiki/Builder_pattern">builder</a>-style, <a
     * href="http://en.wikipedia.org/wiki/Fluent_interface">fluent interface</a> for creating {@link
     * Artwork} objects. Example usage is below.
     *
     * <pre class="prettyprint">
     * Artwork artwork = new Artwork.Builder()
     *         .persistentUri(Uri.parse("http://example.com/image.jpg"))
     *         .title("Example image")
     *         .byline("Unknown person, c. 1980")
     *         .attribution("Copyright (C) Unknown person, 1980")
     *         .build();
     * </pre>
     */
    public static class Builder {
        private final Artwork mArtwork;

        public Builder() {
            mArtwork = new Artwork();
        }

        /**
         * Sets the artwork's opaque application-specific identifier.
         *
         * @param token the artwork's opaque application-specific identifier.
         *
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder token(@Nullable String token) {
            mArtwork.token = token;
            return this;
        }

        /**
         * Sets the artwork's user-visible title.
         *
         * @param title the artwork's user-visible title.
         *
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder title(@Nullable String title) {
            mArtwork.title = title;
            return this;
        }

        /**
         * Sets the artwork's user-visible byline, usually containing the author and date.
         * This is generally used as a secondary source of information after the {@link #title} title}.
         *
         * @param byline the artwork's user-visible byline.
         *
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder byline(@Nullable String byline) {
            mArtwork.byline = byline;
            return this;
        }

        /**
         * Sets the artwork's user-visible attribution text.
         * This is generally used as a tertiary source of information after the
         * {@link #title  title} and the {@link #byline byline}.
         *
         * @param attribution the artwork's user-visible attribution text.
         *
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder attribution(@Nullable String attribution) {
            mArtwork.attribution = attribution;
            return this;
        }

        /**
         * Sets the artwork's persistent URI, which must resolve to a JPEG or PNG image, ideally
         * under 5MB.
         *
         * <p>When a persistent URI is present, your {@link MuzeiArtProvider} will store
         * downloaded images in the {@link Context#getCacheDir() cache directory} and automatically
         * redownload the image as needed. If it is not present, then you must write the image directly
         * to the {@link MuzeiArtProvider} with {@link android.content.ContentResolver#openOutputStream(Uri)}
         * and the images will be stored in the {@link Context#getFilesDir()} as it assumed that there is no
         * way to redownload the artwork.
         *
         * @param persistentUri the artwork's persistent URI. Your app should have long-lived access to this URI.
         *
         * @return this {@link Builder}.
         *
         * @see MuzeiArtProvider#openFromPersistentUri(Uri)
         */
        @NonNull
        public Builder persistentUri(@Nullable Uri persistentUri) {
            mArtwork.persistentUri = persistentUri;
            return this;
        }

        /**
         * Sets the artwork's web URI. This is used by default in {@link MuzeiArtProvider#openArtworkInfo(Artwork)}
         * to allow the user to view more details about the artwork.
         *
         * @return this {@link Builder}.
         *
         * @see MuzeiArtProvider#openArtworkInfo(Artwork)
         */
        @NonNull
        public Builder webUri(@Nullable Uri webUri) {
            mArtwork.webUri = webUri;
            return this;
        }

        /**
         * Sets the provider specific metadata about the artwork.
         * This is not used by Muzei at all, so can contain any data that makes it easier to query
         * or otherwise work with your Artwork.
         *
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder metadata(@Nullable String metadata) {
            mArtwork.metadata = metadata;
            return this;
        }

        /**
         * Creates and returns the final Artwork object. Once this method is called, it is not valid
         * to further use this {@link Artwork.Builder} object.
         *
         * @return the final constructed {@link Artwork}.
         */
        @NonNull
        public Artwork build() {
            return mArtwork;
        }
    }
}
