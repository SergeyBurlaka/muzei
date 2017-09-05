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

import android.arch.core.util.Function;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Transformations;
import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.content.Context;

/**
 * Dao for Providers
 */
@Dao
public abstract class ProviderDao {
    @Query("SELECT * FROM provider")
    abstract LiveData<ProviderEntity> getCurrentProviderEntity();

    public LiveData<Provider> getCurrentProvider(Context context) {
        final Context applicationContext = context.getApplicationContext();
        return Transformations.map(getCurrentProviderEntity(), new Function<ProviderEntity, Provider>() {
            @Override
            public Provider apply(final ProviderEntity providerEntity) {
                return new Provider(applicationContext, providerEntity);
            }
        });
    }

    @Query("SELECT * FROM provider")
    abstract ProviderEntity getCurrentProviderEntityBlocking();

    public Provider getCurrentProviderBlocking(Context context) {
        return new Provider(context, getCurrentProviderEntityBlocking());
    }

    @Insert
    public abstract void insert(ProviderEntity provider);

    @Query("DELETE FROM provider")
    public abstract void deleteAll();
}
