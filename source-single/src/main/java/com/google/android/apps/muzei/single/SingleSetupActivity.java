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

package com.google.android.apps.muzei.single;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Setup Activity which ensures that the user has a single image selected
 */
public class SingleSetupActivity extends Activity {
    private static final int REQUEST_SELECT_IMAGE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SingleArtProvider.getArtworkFile(this).exists()) {
            // We already have a single artwork available
            setResult(RESULT_OK);
            finish();
        } else {
            startActivityForResult(
                    new Intent(this, SingleSettingsActivity.class),
                    REQUEST_SELECT_IMAGE);
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SELECT_IMAGE) {
            return;
        }
        // Pass on the resultCode from the SingleSettingsActivity onto Muzei
        setResult(resultCode);
        finish();
    }
}
