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

import android.animation.ObjectAnimator;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.legacy.util.ObservableHorizontalScrollView;
import com.google.android.apps.muzei.legacy.util.Scrollbar;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;
import com.google.android.apps.muzei.room.SourceDao;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Activity for allowing the user to choose the active source.
 */
public class LegacySettingsActivity extends AppCompatActivity {
    private static final String TAG = "LegacySettings";

    private static final int SCROLLBAR_HIDE_DELAY_MILLIS = 1000;

    private static final float ALPHA_DISABLED = 0.2f;
    private static final float ALPHA_UNSELECTED = 0.4f;

    private static final int REQUEST_EXTENSION_SETUP = 1;
    private static final String CURRENT_INITIAL_SETUP_SOURCE = "currentInitialSetupSource";

    private ComponentName mSelectedSource;
    private LiveData<Source> mCurrentSourceLiveData;
    private List<SourceView> mSourceViews = new ArrayList<>();

    private Handler mHandler = new Handler();

    private ViewGroup mRootView;
    private ViewGroup mSourceContainerView;
    private ObservableHorizontalScrollView mSourceScrollerView;
    private Scrollbar mScrollbar;
    private ObjectAnimator mCurrentScroller;

    private int mAnimationDuration;
    private int mItemWidth;
    private int mItemImageSize;
    private int mItemEstimatedHeight;

    private RectF mTempRectF = new RectF();
    private Paint mImageFillPaint = new Paint();
    private Paint mAlphaPaint = new Paint();
    private Drawable mSelectedSourceImage;
    private int mSelectedSourceIndex;

    private ComponentName mCurrentInitialSetupSource;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mCurrentInitialSetupSource = savedInstanceState.getParcelable(CURRENT_INITIAL_SETUP_SOURCE);
        }
        mAnimationDuration = getResources().getInteger(android.R.integer.config_shortAnimTime);
        mItemWidth = getResources().getDimensionPixelSize(
                R.dimen.legacy_settings_item_width);
        mItemEstimatedHeight = getResources().getDimensionPixelSize(
                R.dimen.legacy_settings_item_estimated_height);
        mItemImageSize = getResources().getDimensionPixelSize(
                R.dimen.legacy_settings_item_image_size);

        prepareGenerateSourceImages();

        setContentView(R.layout.legacy_settings);
        mRootView = findViewById(R.id.legacy_settings_container);
        mScrollbar = mRootView.findViewById(R.id.legacy_settings_scrollbar);
        mSourceScrollerView = mRootView.findViewById(R.id.legacy_settings_source_scroller);
        mSourceScrollerView.setCallbacks(new ObservableHorizontalScrollView.Callbacks() {
            @Override
            public void onScrollChanged(int scrollX) {
                showScrollbar();
            }

            @Override
            public void onDownMotionEvent() {
                if (mCurrentScroller != null) {
                    mCurrentScroller.cancel();
                    mCurrentScroller = null;
                }
            }
        });
        mSourceContainerView = mRootView.findViewById(R.id.legacy_settings_source_container);

        redrawSources();

        mRootView.setVisibility(View.INVISIBLE);
        mRootView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    int mPass = 0;

                    @Override
                    public void onGlobalLayout() {
                        if (mPass == 0) {
                            // First pass
                            updatePadding();
                            ++mPass;
                        } else if (mPass == 1 & mSelectedSourceIndex >= 0) {
                            // Second pass
                            mSourceScrollerView.setScrollX(mItemWidth * mSelectedSourceIndex);
                            showScrollbar();
                            mRootView.setVisibility(View.VISIBLE);
                            ++mPass;
                        } else {
                            // Last pass, remove the listener
                            mRootView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                });

        mRootView.setAlpha(0);
        mRootView.animate().alpha(1f).setDuration(500);

        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, "sources");
        FirebaseAnalytics.getInstance(this).logEvent(FirebaseAnalytics.Event.VIEW_ITEM_LIST, bundle);

        SourceDao sourceDao = MuzeiDatabase.getInstance(this).sourceDao();
        sourceDao.getSources().observe(this, sources -> {
            updateSources(sources);
            updatePadding();
        });
        mCurrentSourceLiveData = sourceDao.getCurrentSource();
        mCurrentSourceLiveData.observe(this, source -> {
            if (source != null) {
                setResult(RESULT_OK);
            }
            updateSelectedItem(source, true);
        });
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(CURRENT_INITIAL_SETUP_SOURCE, mCurrentInitialSetupSource);
    }

    private void updatePadding() {
        int rootViewWidth = mRootView.getWidth();
        if (rootViewWidth == 0) {
            return;
        }
        int topPadding = Math.max(0, (mRootView.getHeight() - mItemEstimatedHeight) / 2);
        int numItems = mSourceViews.size();
        int sidePadding;
        int minSidePadding = getResources().getDimensionPixelSize(
                R.dimen.legacy_settings_min_side_padding);
        if (minSidePadding * 2 + mItemWidth * numItems < rootViewWidth) {
            // Don't allow scrolling since all items can be visible. Center the entire
            // list by using just the right amount of padding to center it.
            sidePadding = (rootViewWidth - mItemWidth * numItems) / 2 - 1;
        } else {
            // Allow scrolling
            sidePadding = Math.max(0, (rootViewWidth - mItemWidth) / 2);
        }
        mSourceContainerView.setPadding(sidePadding, topPadding, sidePadding, 0);
    }

    private void updateSelectedItem(Source selectedSource, boolean allowAnimate) {
        ComponentName previousSelectedSource = mSelectedSource;
        mSelectedSource = selectedSource != null ? selectedSource.componentName : null;
        if (previousSelectedSource != null && previousSelectedSource.equals(mSelectedSource)) {
            // Only update status
            for (final SourceView sourceView : mSourceViews) {
                if (!sourceView.source.componentName.equals(mSelectedSource) || sourceView.rootView == null) {
                    continue;
                }
                updateSourceStatusUi(sourceView);
            }
            return;
        }

        // This is a newly selected source.
        boolean selected;
        int index = -1;
        for (final SourceView sourceView : mSourceViews) {
            ++index;
            if (sourceView.source.componentName.equals(previousSelectedSource)) {
                selected = false;
            } else if (sourceView.source.componentName.equals(mSelectedSource)) {
                mSelectedSourceIndex = index;
                selected = true;
            } else {
                continue;
            }

            if (sourceView.rootView == null) {
                continue;
            }

            View sourceImageButton = sourceView.rootView.findViewById(R.id.legacy_settings_source_image);
            Drawable drawable = selected ? mSelectedSourceImage : sourceView.icon;
            drawable.setColorFilter(sourceView.source.color, PorterDuff.Mode.SRC_ATOP);
            sourceImageButton.setBackground(drawable);

            float alpha = selected ? 1f : Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && sourceView.source.targetSdkVersion >= Build.VERSION_CODES.O ? ALPHA_DISABLED : ALPHA_UNSELECTED;
            sourceView.rootView.animate()
                    .alpha(alpha)
                    .setDuration(mAnimationDuration);

            if (selected) {
                updateSourceStatusUi(sourceView);
            }

            animateSettingsButton(sourceView.settingsButton,
                    selected && sourceView.source.settingsActivity != null, allowAnimate);
        }

        if (mSelectedSourceIndex >= 0 && allowAnimate) {
            if (mCurrentScroller != null) {
                mCurrentScroller.cancel();
            }

            // For some reason smoothScrollTo isn't very smooth..
            mCurrentScroller = ObjectAnimator.ofInt(mSourceScrollerView, "scrollX",
                    mItemWidth * mSelectedSourceIndex);
            mCurrentScroller.setDuration(mAnimationDuration);
            mCurrentScroller.start();
        }
    }

    private void animateSettingsButton(final View settingsButton, final boolean show,
                                       boolean allowAnimate) {
        if ((show && settingsButton.getVisibility() == View.VISIBLE) ||
                (!show && settingsButton.getVisibility() == View.INVISIBLE)) {
            return;
        }
        settingsButton.setVisibility(View.VISIBLE);
        settingsButton.animate()
                .translationY(show ? 0 : (-getResources().getDimensionPixelSize(
                        R.dimen.legacy_settings_settings_button_animate_distance)))
                .alpha(show ? 1f : 0f)
                .rotation(show ? 0 : -90)
                .setDuration(allowAnimate ? 300 : 0)
                .setStartDelay((show && allowAnimate) ? 200 : 0)
                .withLayer()
                .withEndAction(() -> {
                    if (!show) {
                        settingsButton.setVisibility(View.INVISIBLE);
                    }
                });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // remove all scheduled runnables
        mHandler.removeCallbacksAndMessages(null);
    }

    public void updateSources(final List<Source> sources) {
        mSelectedSource = null;
        PackageManager pm = getPackageManager();
        mSourceViews.clear();

        for (Source source : sources) {
            SourceView sourceView = new SourceView(source);
            ServiceInfo info;
            try {
                info = pm.getServiceInfo(source.componentName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            sourceView.icon = new BitmapDrawable(getResources(), generateSourceImage(info.loadIcon(pm)));
            mSourceViews.add(sourceView);
        }

        final String appPackage = getPackageName();
        Collections.sort(mSourceViews, (sourceView1, sourceView2) -> {
            Source s1 = sourceView1.source;
            Source s2 = sourceView2.source;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                boolean target1IsO = s1.targetSdkVersion >= Build.VERSION_CODES.O;
                boolean target2IsO = s2.targetSdkVersion >= Build.VERSION_CODES.O;
                if (target1IsO && !target2IsO) {
                    return 1;
                } else if (!target1IsO && target2IsO) {
                    return -1;
                }
            }
            String pn1 = s1.componentName.getPackageName();
            String pn2 = s2.componentName.getPackageName();
            if (!pn1.equals(pn2)) {
                if (appPackage.equals(pn1)) {
                    return -1;
                } else if (appPackage.equals(pn2)) {
                    return 1;
                }
            }
            return s1.label.compareTo(s2.label);
        });

        redrawSources();
    }

    private void redrawSources() {
        if (mSourceContainerView == null) {
            return;
        }

        mSourceContainerView.removeAllViews();
        for (SourceView sourceView : mSourceViews) {
            sourceView.rootView = getLayoutInflater().inflate(
                    R.layout.legacy_settings_item, mSourceContainerView, false);

            sourceView.selectSourceButton = sourceView.rootView.findViewById(R.id.legacy_settings_source_image);
            final Source source = sourceView.source;
            sourceView.selectSourceButton.setOnClickListener(view -> {
                if (source.componentName.equals(mSelectedSource)) {
                    finish();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && source.targetSdkVersion >= Build.VERSION_CODES.O) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(LegacySettingsActivity.this)
                            .setTitle(R.string.legacy_settings_source_target_too_high_title)
                            .setMessage(R.string.legacy_settings_source_target_too_high_message)
                            .setNegativeButton(R.string.legacy_settings_source_target_too_high_learn_more,
                                    (dialog, which) -> startActivity(new Intent(Intent.ACTION_VIEW,
                                            Uri.parse("https://medium.com/@ianhlake/the-muzei-plugin-api-and-androids-evolution-9b9979265cfb"))))
                            .setPositiveButton(R.string.legacy_settings_source_target_too_high_dismiss, null);
                    final Intent sendFeedbackIntent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id="
                                    + source.componentName.getPackageName()));
                    if (sendFeedbackIntent.resolveActivity(getPackageManager()) != null) {
                        builder.setNeutralButton(
                                getString(R.string.legacy_settings_source_target_too_high_send_feedback, source.label),
                                (dialog, which) -> startActivity(sendFeedbackIntent));
                    }
                    builder.show();
                } else if (source.setupActivity != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString(FirebaseAnalytics.Param.ITEM_ID, source.componentName.flattenToShortString());
                    bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, source.label);
                    bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, "sources");
                    FirebaseAnalytics.getInstance(LegacySettingsActivity.this)
                            .logEvent(FirebaseAnalytics.Event.VIEW_ITEM, bundle);
                    mCurrentInitialSetupSource = source.componentName;
                    launchSourceSetup(source);
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putString(FirebaseAnalytics.Param.ITEM_ID, source.componentName.flattenToShortString());
                    bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "sources");
                    FirebaseAnalytics.getInstance(LegacySettingsActivity.this)
                            .logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle);
                    LegacySourceManager.selectSource(LegacySettingsActivity.this, source.componentName);
                }
            });

            sourceView.selectSourceButton.setOnLongClickListener(v -> {
                final String pkg = source.componentName.getPackageName();
                if (TextUtils.equals(pkg, getPackageName())) {
                    // Don't open Muzei's app info
                    return false;
                }
                // Otherwise open third party extensions
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", pkg, null)));
                } catch (final ActivityNotFoundException e) {
                    return false;
                }
                return true;
            });

            float alpha = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && source.targetSdkVersion >= Build.VERSION_CODES.O
                    ? ALPHA_DISABLED
                    : ALPHA_UNSELECTED;
            sourceView.rootView.setAlpha(alpha);

            sourceView.icon.setColorFilter(source.color, PorterDuff.Mode.SRC_ATOP);
            sourceView.selectSourceButton.setBackground(sourceView.icon);

            TextView titleView = sourceView.rootView.findViewById(R.id.legacy_settings_source_title);
            titleView.setText(source.label);
            titleView.setTextColor(source.color);

            updateSourceStatusUi(sourceView);

            sourceView.settingsButton = sourceView.rootView.findViewById(R.id.legacy_settings_source_settings);
            sourceView.settingsButton.setOnClickListener(view -> launchSourceSettings(source));

            animateSettingsButton(sourceView.settingsButton, false, false);

            mSourceContainerView.addView(sourceView.rootView);
        }

        updateSelectedItem(mCurrentSourceLiveData.getValue(), false);
    }

    private void launchSourceSettings(Source source) {
        try {
            Intent settingsIntent = new Intent()
                    .setComponent(source.settingsActivity)
                    .putExtra(MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, true);
            startActivity(settingsIntent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "Can't launch source settings.", e);
        }
    }

    private void launchSourceSetup(Source source) {
        try {
            Intent setupIntent = new Intent()
                    .setComponent(source.setupActivity)
                    .putExtra(MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, true);
            startActivityForResult(setupIntent, REQUEST_EXTENSION_SETUP);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "Can't launch source setup.", e);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_EXTENSION_SETUP) {
            if (resultCode == RESULT_OK && mCurrentInitialSetupSource != null) {
                Bundle bundle = new Bundle();
                bundle.putString(FirebaseAnalytics.Param.ITEM_ID, mCurrentInitialSetupSource.flattenToShortString());
                bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "sources");
                FirebaseAnalytics.getInstance(this).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle);
                LegacySourceManager.selectSource(this, mCurrentInitialSetupSource);
            }

            mCurrentInitialSetupSource = null;
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void updateSourceStatusUi(SourceView sourceView) {
        if (sourceView.rootView == null) {
            return;
        }
        ((TextView) sourceView.rootView.findViewById(R.id.legacy_settings_source_description)).setText(
                sourceView.source.getDescription());
    }

    private void prepareGenerateSourceImages() {
        mImageFillPaint.setColor(Color.WHITE);
        mImageFillPaint.setAntiAlias(true);
        mAlphaPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        mSelectedSourceImage = new BitmapDrawable(getResources(),
                generateSourceImage(ResourcesCompat.getDrawable(getResources(),
                        R.drawable.legacy_settings_source_selected, null)));
    }

    private Bitmap generateSourceImage(Drawable image) {
        Bitmap bitmap = Bitmap.createBitmap(mItemImageSize, mItemImageSize,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        mTempRectF.set(0, 0, mItemImageSize, mItemImageSize);
        canvas.drawOval(mTempRectF, mImageFillPaint);
        if (image != null) {
            canvas.saveLayer(0, 0, mItemImageSize, mItemImageSize, mAlphaPaint,
                    Canvas.ALL_SAVE_FLAG);
            image.setBounds(0, 0, mItemImageSize, mItemImageSize);
            image.draw(canvas);
            canvas.restore();
        }
        return bitmap;
    }

    private void showScrollbar() {
        mHandler.removeCallbacks(mHideScrollbarRunnable);
        mScrollbar.setScrollRangeAndViewportWidth(
                mSourceScrollerView.computeHorizontalScrollRange(),
                mSourceScrollerView.getWidth());
        mScrollbar.setScrollPosition(mSourceScrollerView.getScrollX());
        mScrollbar.show();
        mHandler.postDelayed(mHideScrollbarRunnable, SCROLLBAR_HIDE_DELAY_MILLIS);
    }

    private Runnable mHideScrollbarRunnable = new Runnable() {
        @Override
        public void run() {
            mScrollbar.hide();
        }
    };

    class SourceView {
        Source source;
        View rootView;
        Drawable icon;
        View selectSourceButton;
        View settingsButton;

        SourceView(Source source) {
            this.source = source;
        }
    }
}
