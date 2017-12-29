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

package com.google.android.apps.muzei;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.widget.TooltipCompat;
import android.text.TextUtils;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.ArtworkSizeChangedEvent;
import com.google.android.apps.muzei.event.SwitchingPhotosStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.notifications.NewWallpaperNotificationReceiver;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;
import com.google.android.apps.muzei.settings.AboutActivity;
import com.google.android.apps.muzei.sync.ProviderManager;
import com.google.android.apps.muzei.util.AnimatedMuzeiLoadingSpinnerView;
import com.google.android.apps.muzei.util.PanScaleProxyView;
import com.google.android.apps.muzei.util.ScrimUtil;
import com.google.android.apps.muzei.widget.AppWidgetUpdateTask;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.R;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public class ArtDetailFragment extends Fragment {
    private int mCurrentViewportId = 0;
    private float mWallpaperAspectRatio;
    private float mArtworkAspectRatio;

    public boolean mSupportsNextArtwork = false;
    private Observer<Provider> mProviderObserver = new Observer<Provider>() {
        @Override
        public void onChanged(@Nullable final Provider provider) {
            // Update overflow and next button
            mOverflowProviderActionMap.clear();
            mOverflowMenu.getMenu().clear();
            mOverflowMenu.inflate(R.menu.muzei_overflow);
            mSupportsNextArtwork = provider != null && provider.supportsNextArtwork;
            mNextButton.setVisibility(mSupportsNextArtwork ? View.VISIBLE : View.GONE);
        }
    };

    private Artwork mCurrentArtwork;
    private Observer<Artwork> mArtworkObserver = new Observer<Artwork>() {
        @Override
        public void onChanged(@Nullable final Artwork currentArtwork) {
            mCurrentArtwork = currentArtwork;
            if (currentArtwork == null) {
                return;
            }
            int titleFont = R.font.alegreya_sans_black;
            int bylineFont = R.font.alegreya_sans_medium;
            if (MuzeiContract.Artwork.META_FONT_TYPE_ELEGANT.equals(currentArtwork.metaFont)) {
                titleFont = R.font.alegreya_black_italic;
                bylineFont = R.font.alegreya_italic;
            }

            mTitleView.setTypeface(ResourcesCompat.getFont(getContext(), titleFont));
            mTitleView.setText(currentArtwork.title);

            mBylineView.setTypeface(ResourcesCompat.getFont(getContext(), bylineFont));
            mBylineView.setText(currentArtwork.byline);

            String attribution = currentArtwork.attribution;
            if (!TextUtils.isEmpty(attribution)) {
                mAttributionView.setText(attribution);
                mAttributionView.setVisibility(View.VISIBLE);
            } else {
                mAttributionView.setVisibility(View.GONE);
            }

            currentArtwork.getCommands(getContext(), commands -> {
                int numProviderActions = Math.min(PROVIDER_ACTION_IDS.length,
                        commands.size());
                for (int i = 0; i < numProviderActions; i++) {
                    UserCommand action = commands.get(i);
                    mOverflowProviderActionMap.put(PROVIDER_ACTION_IDS[i], action.getId());
                    mOverflowMenu.getMenu().add(0, PROVIDER_ACTION_IDS[i], 0, action.getTitle());
                }
            });

            if (mNextFakeLoading) {
                mNextFakeLoading = false;
                mHandler.removeCallbacks(mUnsetNextFakeLoadingRunnable);
            }

            updateLoadingSpinnerVisibility();
        }
    };

    private boolean mGuardViewportChangeListener;
    private boolean mDeferResetViewport;

    private Handler mHandler = new Handler();
    private View mContainerView;
    private PopupMenu mOverflowMenu;
    private SparseIntArray mOverflowProviderActionMap = new SparseIntArray();
    private static final int[] PROVIDER_ACTION_IDS = {
            R.id.provider_action_1,
            R.id.provider_action_2,
            R.id.provider_action_3,
            R.id.provider_action_4,
            R.id.provider_action_5,
            R.id.provider_action_6,
            R.id.provider_action_7,
            R.id.provider_action_8,
            R.id.provider_action_9,
            R.id.provider_action_10,
    };
    private View mChromeContainerView;
    private View mMetadataView;
    private View mLoadingContainerView;
    private AnimatedMuzeiLoadingSpinnerView mLoadingIndicatorView;
    private View mNextButton;
    private TextView mTitleView;
    private TextView mBylineView;
    private TextView mAttributionView;
    private PanScaleProxyView mPanScaleProxyView;
    private boolean mLoadingSpinnerShown = false;
    private boolean mNextFakeLoading = false;
    private LiveData<Provider> mCurrentProviderLiveData;
    private LiveData<Artwork> mCurrentArtworkLiveData;

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, @Nullable final ViewGroup container,
            @Nullable final Bundle savedInstanceState) {
        mContainerView = inflater.inflate(R.layout.art_detail_fragment, container, false);

        mChromeContainerView = mContainerView.findViewById(R.id.chrome_container);
        showHideChrome(true);

        return mContainerView;
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        // Ensure we have the latest insets
        view.requestFitSystemWindows();

        mChromeContainerView.setBackground(ScrimUtil.makeCubicGradientScrimDrawable(
                0xaa000000, 8, Gravity.BOTTOM));

        mMetadataView = view.findViewById(R.id.metadata);
        mMetadataView.setOnClickListener(view13 -> {
            if (mCurrentArtwork != null) {
                mCurrentArtwork.openArtworkInfo(getContext());
            }
        });

        final float metadataSlideDistance = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        mContainerView.setOnSystemUiVisibilityChangeListener(
                vis -> {
                    final boolean visible = (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0;

                    mChromeContainerView.setVisibility(View.VISIBLE);
                    mChromeContainerView.animate()
                            .alpha(visible ? 1f : 0f)
                            .translationY(visible ? 0 : metadataSlideDistance)
                            .setDuration(200)
                            .withEndAction(() -> {
                                if (!visible) {
                                    mChromeContainerView.setVisibility(View.GONE);
                                }
                            });
                });

        mTitleView = view.findViewById(R.id.title);
        mBylineView = view.findViewById(R.id.byline);
        mAttributionView = view.findViewById(R.id.attribution);

        final View overflowButton = view.findViewById(R.id.overflow_button);
        mOverflowMenu = new PopupMenu(getContext(), overflowButton);
        overflowButton.setOnTouchListener(mOverflowMenu.getDragToOpenListener());
        overflowButton.setOnClickListener(view12 -> mOverflowMenu.show());
        mOverflowMenu.setOnMenuItemClickListener(menuItem -> {
            Context context = getContext();
            if (context == null) {
                return false;
            }
            int id = mOverflowProviderActionMap.get(menuItem.getItemId());
            if (id > 0) {
                mCurrentArtwork.sendAction(getContext(), id);
                return true;
            }

            switch (menuItem.getItemId()) {
                case R.id.action_about:
                    FirebaseAnalytics.getInstance(context).logEvent("about_open", null);
                    startActivity(new Intent(context, AboutActivity.class));
                    return true;
            }
            return false;
        });

        mNextButton = view.findViewById(R.id.next_button);
        mNextButton.setOnClickListener(view1 -> {
            ProviderManager.getInstance(getContext()).nextArtwork();
            mNextFakeLoading = true;
            showNextFakeLoading();
        });
        TooltipCompat.setTooltipText(mNextButton, mNextButton.getContentDescription());

        mPanScaleProxyView = view.findViewById(R.id.pan_scale_proxy);
        mPanScaleProxyView.setMaxZoom(5);
        mPanScaleProxyView.setOnViewportChangedListener(
                () -> {
                    if (mGuardViewportChangeListener) {
                        return;
                    }

                    ArtDetailViewport.getInstance().setViewport(
                            mCurrentViewportId, mPanScaleProxyView.getCurrentViewport(), true);
                });
        mPanScaleProxyView.setOnOtherGestureListener(
                new PanScaleProxyView.OnOtherGestureListener() {
                    @Override
                    public void onSingleTapUp() {
                        showHideChrome((getActivity().getWindow().getDecorView().getSystemUiVisibility()
                                & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0);
                    }

                    @Override
                    public void onLongPress() {
                        new AppWidgetUpdateTask(getContext(), true)
                                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    }
                });

        mLoadingContainerView = view.findViewById(R.id.image_loading_container);
        mLoadingIndicatorView = view.findViewById(R.id.image_loading_indicator);

        EventBus.getDefault().register(this);

        WallpaperSizeChangedEvent wsce = EventBus.getDefault().getStickyEvent(
                WallpaperSizeChangedEvent.class);
        if (wsce != null) {
            onEventMainThread(wsce);
        }

        ArtworkSizeChangedEvent asce = EventBus.getDefault().getStickyEvent(
                ArtworkSizeChangedEvent.class);
        if (asce != null) {
            onEventMainThread(asce);
        }

        ArtDetailViewport fve = EventBus.getDefault().getStickyEvent(ArtDetailViewport.class);
        if (fve != null) {
            onEventMainThread(fve);
        }

        SwitchingPhotosStateChangedEvent spsce = EventBus.getDefault().getStickyEvent(
                SwitchingPhotosStateChangedEvent.class);
        if (spsce != null) {
            onEventMainThread(spsce);
        }
        MuzeiDatabase database = MuzeiDatabase.getInstance(getContext());
        mCurrentProviderLiveData = database.providerDao().getCurrentProvider();
        mCurrentProviderLiveData.observe(this, mProviderObserver);
        mCurrentArtworkLiveData = database.artworkDao().getCurrentArtwork();
        mCurrentArtworkLiveData.observe(this, mArtworkObserver);
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().postSticky(new ArtDetailOpenedClosedEvent(true));
    }

    @Override
    public void onResume() {
        super.onResume();
        NewWallpaperNotificationReceiver.markNotificationRead(getContext());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHandler.removeCallbacksAndMessages(null);
        EventBus.getDefault().unregister(this);
        mCurrentProviderLiveData.removeObserver(mProviderObserver);
        mCurrentArtworkLiveData.removeObserver(mArtworkObserver);
    }

    private void showHideChrome(boolean show) {
        int flags = show ? 0 : View.SYSTEM_UI_FLAG_LOW_PROFILE;
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!show) {
            flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE;
        }
        getActivity().getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Subscribe
    public void onEventMainThread(WallpaperSizeChangedEvent wsce) {
        if (wsce.getHeight() > 0) {
            mWallpaperAspectRatio = wsce.getWidth() * 1f / wsce.getHeight();
        } else {
            mWallpaperAspectRatio = mPanScaleProxyView.getWidth()
                    * 1f / mPanScaleProxyView.getHeight();
        }
        resetProxyViewport();
    }

    @Subscribe
    public void onEventMainThread(ArtworkSizeChangedEvent ase) {
        mArtworkAspectRatio = ase.getWidth() * 1f / ase.getHeight();
        resetProxyViewport();
    }

    private void resetProxyViewport() {
        if (mWallpaperAspectRatio == 0 || mArtworkAspectRatio == 0) {
            return;
        }

        mDeferResetViewport = false;
        SwitchingPhotosStateChangedEvent spe = EventBus.getDefault()
                .getStickyEvent(SwitchingPhotosStateChangedEvent.class);
        if (spe != null && spe.isSwitchingPhotos()) {
            mDeferResetViewport = true;
            return;
        }

        if (mPanScaleProxyView != null) {
            mPanScaleProxyView.setRelativeAspectRatio(mArtworkAspectRatio / mWallpaperAspectRatio);
        }
    }

    @Subscribe
    public void onEventMainThread(ArtDetailViewport e) {
        if (!e.isFromUser() && mPanScaleProxyView != null) {
            mGuardViewportChangeListener = true;
            mPanScaleProxyView.setViewport(e.getViewport(mCurrentViewportId));
            mGuardViewportChangeListener = false;
        }
    }

    @Subscribe
    public void onEventMainThread(SwitchingPhotosStateChangedEvent spe) {
        mCurrentViewportId = spe.getCurrentId();
        mPanScaleProxyView.enablePanScale(!spe.isSwitchingPhotos());
        // Process deferred artwork size change when done switching
        if (!spe.isSwitchingPhotos() && mDeferResetViewport) {
            resetProxyViewport();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        mOverflowMenu.dismiss();
        EventBus.getDefault().postSticky(new ArtDetailOpenedClosedEvent(false));
    }

    private void showNextFakeLoading() {
        mNextFakeLoading = true;
        // Show a loading spinner for up to 10 seconds. When new artwork is loaded,
        // the loading spinner will go away. See onEventMainThread(ArtworkLoadingStateChangedEvent)
        mHandler.removeCallbacks(mUnsetNextFakeLoadingRunnable);
        mHandler.postDelayed(mUnsetNextFakeLoadingRunnable, 10000);
        updateLoadingSpinnerVisibility();
    }

    private Runnable mUnsetNextFakeLoadingRunnable = () -> {
        mNextFakeLoading = false;
        updateLoadingSpinnerVisibility();
    };

    private void updateLoadingSpinnerVisibility() {
        boolean showLoadingSpinner = mNextFakeLoading;

        if (showLoadingSpinner != mLoadingSpinnerShown) {
            mLoadingSpinnerShown = showLoadingSpinner;
            mHandler.removeCallbacks(mShowLoadingSpinnerRunnable);
            if (showLoadingSpinner) {
                mHandler.postDelayed(mShowLoadingSpinnerRunnable, 700);
            } else {
                mLoadingContainerView.animate()
                        .alpha(0)
                        .setDuration(1000)
                        .withEndAction(() -> {
                            mLoadingContainerView.setVisibility(View.GONE);
                            mLoadingIndicatorView.stop();
                        });
            }
        }
    }

    private Runnable mShowLoadingSpinnerRunnable = new Runnable() {
        @Override
        public void run() {
            mLoadingIndicatorView.start();
            mLoadingContainerView.setVisibility(View.VISIBLE);
            mLoadingContainerView.animate()
                    .alpha(1)
                    .setDuration(300)
                    .withEndAction(null);
        }
    };
}
