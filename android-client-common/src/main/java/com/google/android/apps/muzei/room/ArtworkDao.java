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

import android.arch.lifecycle.LiveData;
import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.TypeConverters;
import android.content.ComponentName;

import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter;

/**
 * Dao for Artwork
 */
@Dao
public interface ArtworkDao {
    @Insert
    long insert(Artwork artwork);

    @Query("SELECT * FROM artwork ORDER BY _id DESC")
    LiveData<Artwork> getCurrentArtwork();

    @Query("SELECT * FROM artwork ORDER BY _id DESC")
    Artwork getCurrentArtworkBlocking();

    @TypeConverters({ComponentNameTypeConverter.class})
    @Query("SELECT * FROM artwork WHERE sourceComponentName=:provider")
    LiveData<Artwork> getCurrentArtworkForProvider(ComponentName provider);

    @Query("SELECT * FROM artwork WHERE _id=:id")
    Artwork getArtworkById(long id);
}
