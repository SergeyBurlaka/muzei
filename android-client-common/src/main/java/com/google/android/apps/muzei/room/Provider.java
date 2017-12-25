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

import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.arch.persistence.room.TypeConverters;
import android.content.ComponentName;
import android.support.annotation.NonNull;

import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter;
import com.google.android.apps.muzei.room.converter.ArrayDequeLongTypeConverter;

import java.util.ArrayDeque;

/**
 * Provider information's representation in Room
 */
@Entity(tableName = "provider")
public class Provider {
    @TypeConverters({ComponentNameTypeConverter.class})
    @PrimaryKey
    @NonNull
    public ComponentName componentName;

    public long maxLoadedArtworkId;

    @TypeConverters({ArrayDequeLongTypeConverter.class})
    @NonNull
    public ArrayDeque<Long> recentArtworkIds = new ArrayDeque<>();

    public Provider(@NonNull ComponentName componentName) {
        this.componentName = componentName;
    }
}
