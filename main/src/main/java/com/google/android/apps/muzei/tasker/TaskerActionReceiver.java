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

package com.google.android.apps.muzei.tasker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.apps.muzei.room.Provider;

import static com.twofortyfouram.locale.api.Intent.ACTION_FIRE_SETTING;

/**
 * Tasker FIRE_SETTING receiver that fires source actions
 */
public class TaskerActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent != null
                && ACTION_FIRE_SETTING.equals(intent.getAction())) {
            Provider.nextArtwork(context);
        }
    }
}
