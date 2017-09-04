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

package com.example.muzei.examplesource500px;

import com.firebase.jobdispatcher.Constraint;
import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;

public class FiveHundredPxExampleArtProvider extends MuzeiArtProvider {
    @Override
    protected void onLoadRequested(final boolean initial) {
        FirebaseJobDispatcher dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(getContext()));
        dispatcher.mustSchedule(dispatcher.newJobBuilder()
                .setService(FiveHundredPxExampleJobService.class)
                .setTag("500px")
                .addConstraint(Constraint.ON_ANY_NETWORK)
                .build());
    }
}
