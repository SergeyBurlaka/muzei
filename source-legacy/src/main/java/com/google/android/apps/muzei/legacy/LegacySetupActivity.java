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

package com.google.android.apps.muzei.legacy;

import android.arch.lifecycle.Observer;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;

public class LegacySetupActivity extends FragmentActivity {
    private static final int REQUEST_CHOOSE_SOURCE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MuzeiDatabase.getInstance(this).sourceDao().getCurrentSource().observe(this,
                new Observer<Source>() {
                    @Override
                    public void onChanged(@Nullable final Source source) {
                        if (source != null) {
                            setResult(RESULT_OK);
                            finish();
                        } else {
                            // Push the user to the LegacySettingsActivity to select a source
                            Intent intent = new Intent(LegacySetupActivity.this,
                                    LegacySettingsActivity.class);
                            if (getIntent().getBooleanExtra(
                                    MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, false)) {
                                intent.putExtra(MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, true);
                            }
                            startActivityForResult(intent, REQUEST_CHOOSE_SOURCE);
                        }
                    }
                });
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CHOOSE_SOURCE) {
            return;
        }
        // Pass on the resultCode from the LegacySettingsActivity onto Muzei
        setResult(resultCode);
        finish();
    }
}
