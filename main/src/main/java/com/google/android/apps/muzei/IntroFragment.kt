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

package com.google.android.apps.muzei

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.toast
import androidx.navigation.fragment.findNavController
import com.google.android.apps.muzei.util.AnimatedMuzeiLogoFragment
import com.google.android.apps.muzei.wallpaper.WallpaperActiveState
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.ActivateDirections
import net.nurik.roman.muzei.R

class IntroFragment : Fragment() {

    private lateinit var activateButton: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            FirebaseAnalytics.getInstance(requireContext()).logEvent(FirebaseAnalytics.Event.TUTORIAL_BEGIN, null)
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.intro_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        activateButton = view.findViewById(R.id.activate_muzei_button)
        activateButton.setOnClickListener {
            FirebaseAnalytics.getInstance(requireContext()).logEvent("activate", null)
            try {
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(context,
                                        MuzeiWallpaperService::class.java))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                try {
                    startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e2: ActivityNotFoundException) {
                    requireContext().toast(R.string.error_wallpaper_chooser, Toast.LENGTH_LONG)
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (WallpaperActiveState.value == true) {
            val sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val seenTutorial = sp.getBoolean(TutorialFragment.PREF_SEEN_TUTORIAL, false)
            if (seenTutorial) {
                findNavController().navigate(ActivateDirections.action_finish())
            } else {
                findNavController().navigate(IntroFragmentDirections.show_tutorial())
            }
        } else if (savedInstanceState == null) {
            val logoFragment = AnimatedMuzeiLogoFragment()
            childFragmentManager.beginTransaction()
                    .add(R.id.animated_logo_fragment, logoFragment)
                    .commitNow()

            activateButton.alpha = 0f
            logoFragment.onFillStarted = {
                activateButton.animate().alpha(1f).setDuration(500)
            }
            activateButton.postDelayed({
                if (logoFragment.isAdded) {
                    logoFragment.start()
                }
            }, 1000)
        }
    }
}
