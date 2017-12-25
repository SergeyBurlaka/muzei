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

package com.google.android.apps.muzei.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;

import com.google.android.apps.muzei.room.Provider;

/**
 * AppWidgetProvider for Muzei. The actual updating is done asynchronously in
 * {@link AppWidgetUpdateTask}.
 */
public class MuzeiAppWidgetProvider extends AppWidgetProvider {
    static final String ACTION_NEXT_ARTWORK = "com.google.android.apps.muzei.action.WIDGET_NEXT_ARTWORK";

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && ACTION_NEXT_ARTWORK.equals(intent.getAction())) {
            Provider.nextArtwork(context);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateWidgets(context);
    }


    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        updateWidgets(context);
    }

    private void updateWidgets(final Context context) {
        final PendingResult result = goAsync();
        new PendingResultUpdateTask(context, result).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class PendingResultUpdateTask extends AppWidgetUpdateTask {
        private final PendingResult mResult;

        PendingResultUpdateTask(Context context, PendingResult result) {
            super(context.getApplicationContext(), false);
            mResult = result;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            mResult.finish();
        }
    }
}
