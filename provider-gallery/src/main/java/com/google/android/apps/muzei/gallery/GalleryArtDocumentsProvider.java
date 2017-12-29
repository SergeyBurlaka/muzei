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

package com.google.android.apps.muzei.gallery;

import com.google.android.apps.muzei.api.provider.MuzeiArtDocumentsProvider;

/**
 * This subclass is only required because we have multiple MuzeiArtProviders declared in separate modules.
 * <p>
 * An alternate approach would be to declare just a single MuzeiArtDocumentsProvider in the main
 * module and include all authorities separated by a semicolon.
 */
public class GalleryArtDocumentsProvider extends MuzeiArtDocumentsProvider {
}
