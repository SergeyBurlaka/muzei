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

package com.google.android.apps.muzei.settings;

import android.app.Activity;
import android.app.Notification;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.constraint.Group;
import android.support.v4.app.Fragment;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;
import com.google.android.apps.muzei.legacy.LegacyArtProvider;
import com.google.android.apps.muzei.notifications.NotificationSettingsDialogFragment;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Provider;
import com.google.android.apps.muzei.single.SingleArtProvider;
import com.google.android.apps.muzei.sync.ProviderManager;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import net.nurik.roman.muzei.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fragment for allowing the user to choose the active source.
 */
public class ChooseProviderFragment extends Fragment {
    private static final String TAG = "SettingsChooseSourceFrg";

    private static final String PLAY_STORE_PACKAGE_NAME = "com.android.vending";

    private static final int REQUEST_EXTENSION_SETUP = 1;

    private ComponentName mSelectedProvider;
    private List<ProviderItem> mProviders = new ArrayList<>();

    private ChooseSourceAdapter mAdapter;

    private ComponentName mCurrentInitialSetupSource;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, "sources");
        FirebaseAnalytics.getInstance(context).logEvent(FirebaseAnalytics.Event.VIEW_ITEM_LIST, bundle);

        MuzeiDatabase.getInstance(context).providerDao().getCurrentProvider()
                .observe(this, this::updateSelectedItem);

        Intent intent = ((Activity) context).getIntent();
        if (intent != null && intent.getCategories() != null &&
                intent.getCategories().contains(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES)) {
            FirebaseAnalytics.getInstance(context).logEvent("notification_preferences_open", null);
            NotificationSettingsDialogFragment.showSettings(this);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.settings_choose_source, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_notification_settings:
                NotificationSettingsDialogFragment.showSettings(this);
                return true;
            case R.id.action_get_more_sources:
                FirebaseAnalytics.getInstance(getContext()).logEvent("more_sources_open", null);
                try {
                    Intent playStoreIntent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://play.google.com/store/search?q=Muzei&c=apps"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                    preferPackageForIntent(playStoreIntent, PLAY_STORE_PACKAGE_NAME);
                    startActivity(playStoreIntent);
                } catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(getContext(),
                            R.string.play_store_not_found, Toast.LENGTH_LONG).show();
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void preferPackageForIntent(Intent intent, String packageName) {
        PackageManager pm = getContext().getPackageManager();
        for (ResolveInfo resolveInfo : pm.queryIntentActivities(intent, 0)) {
            if (resolveInfo.activityInfo.packageName.equals(packageName)) {
                intent.setPackage(packageName);
                break;
            }
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.choose_provider_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable Bundle savedInstanceState) {
        // Ensure we have the latest insets
        view.requestFitSystemWindows();

        RecyclerView recyclerView = view.findViewById(R.id.source_list);
        mAdapter = new ChooseSourceAdapter();
        recyclerView.setAdapter(mAdapter);

        view.setAlpha(0);
        view.animate().alpha(1f).setDuration(500);
    }

    private BroadcastReceiver mPackagesChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateSources();
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        updateSources();

        IntentFilter packageChangeIntentFilter = new IntentFilter();
        packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageChangeIntentFilter.addDataScheme("package");
        getContext().registerReceiver(mPackagesChangedReceiver, packageChangeIntentFilter);
    }

    @Override
    public void onPause() {
        super.onPause();
        getContext().unregisterReceiver(mPackagesChangedReceiver);
    }

    private void updateSelectedItem(Provider selectedProvider) {
        ComponentName previousSelectedProvider = mSelectedProvider;
        if (selectedProvider != null) {
            mSelectedProvider = selectedProvider.componentName;
        }
        if (previousSelectedProvider != null && previousSelectedProvider.equals(mSelectedProvider)) {
            return;
        }

        // This is a newly selected source.
        for (final ProviderItem provider : mProviders) {
            if (provider.componentName.equals(previousSelectedProvider)) {
                provider.selected = false;
            } else if (provider.componentName.equals(mSelectedProvider)) {
                provider.selected = true;
            } else {
                continue;
            }
            mAdapter.notifyDataSetChanged();
        }
    }

    public void updateSources() {
        mSelectedProvider = null;
        Intent queryIntent = new Intent(MuzeiArtProvider.ACTION_MUZEI_ART_PROVIDER);
        PackageManager pm = getContext().getPackageManager();
        mProviders.clear();
        List<ResolveInfo> resolveInfos = pm.queryIntentContentProviders(queryIntent,
                PackageManager.GET_META_DATA);

        for (ResolveInfo ri : resolveInfos) {
            if (!ri.providerInfo.enabled) {
                // Skip any disabled MuzeiArtProvider
                continue;
            }
            ProviderItem provider = new ProviderItem();
            provider.icon = ri.loadIcon(pm);
            provider.title = ri.loadLabel(pm).toString();
            provider.componentName = new ComponentName(ri.providerInfo.packageName,
                    ri.providerInfo.name);
            if (mSelectedProvider != null) {
                provider.selected = mSelectedProvider.equals(provider.componentName);
            }
            Bundle metaData = ri.providerInfo.metaData;
            if (metaData != null) {
                String settingsActivity = metaData.getString("settingsActivity");
                if (!TextUtils.isEmpty(settingsActivity)) {
                    provider.settingsActivity = ComponentName.unflattenFromString(
                            ri.providerInfo.packageName + "/" + settingsActivity);
                }

                String setupActivity = metaData.getString("setupActivity");
                if (!TextUtils.isEmpty(setupActivity)) {
                    provider.setupActivity = ComponentName.unflattenFromString(
                            ri.providerInfo.packageName + "/" + setupActivity);
                }
            }

            mProviders.add(provider);
        }

        final String appPackage = getContext().getPackageName();
        final ComponentName singleComponentName = new ComponentName(getContext(), SingleArtProvider.class);
        final ComponentName legacyComponentName = new ComponentName(getContext(), LegacyArtProvider.class);
        Collections.sort(mProviders, (s1, s2) -> {
            // LegacyArtProvider is always last
            if (s1.componentName.equals(legacyComponentName)) {
                return 1;
            } else if (s2.componentName.equals(legacyComponentName)) {
                return -1;
            }
            // SingleArtProvider is either first (if selected), or last (if not selected)
            if (s1.componentName.equals(singleComponentName)) {
                return s1.selected ? -1 : 1;
            } else if (s2.componentName.equals(singleComponentName)) {
                return s2.selected ? 1 : -1;
            }
            // Put other Muzei sources first
            String pn1 = s1.componentName.getPackageName();
            String pn2 = s2.componentName.getPackageName();
            if (!pn1.equals(pn2)) {
                if (appPackage.equals(pn1)) {
                    return -1;
                } else if (appPackage.equals(pn2)) {
                    return 1;
                }
            }
            // Otherwise sort by title
            return s1.title.compareTo(s2.title);
        });

        mAdapter.notifyDataSetChanged();
    }

    private class ChooseSourceAdapter extends RecyclerView.Adapter<SourceViewHolder> {
        @Override
        public SourceViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new SourceViewHolder(getLayoutInflater()
                    .inflate(R.layout.choose_provider_item, parent, false));
        }

        @Override
        public void onBindViewHolder(SourceViewHolder holder, int position) {
            ProviderItem provider = mProviders.get(position);

            holder.itemView.setOnClickListener(view -> {
                if (provider.selected) {
                    if (getContext() instanceof Callbacks) {
                        ((Callbacks) getContext()).onRequestCloseActivity();
                    } else if (getParentFragment() instanceof Callbacks) {
                        ((Callbacks) getParentFragment()).onRequestCloseActivity();
                    }
                } else if (provider.setupActivity != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString(FirebaseAnalytics.Param.ITEM_ID, provider.componentName.flattenToShortString());
                    bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, provider.title);
                    bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, "sources");
                    FirebaseAnalytics.getInstance(getContext()).logEvent(FirebaseAnalytics.Event.VIEW_ITEM, bundle);
                    mCurrentInitialSetupSource = provider.componentName;
                    launchSourceSetup(provider);
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putString(FirebaseAnalytics.Param.ITEM_ID, provider.componentName.flattenToShortString());
                    bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "sources");
                    FirebaseAnalytics.getInstance(getContext()).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle);
                    ProviderManager.getInstance(getContext()).selectProvider(provider.componentName);
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                final String pkg = provider.componentName.getPackageName();
                if (TextUtils.equals(pkg, getContext().getPackageName())) {
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

            holder.providerIcon.setImageDrawable(provider.icon);

            holder.providerTitle.setText(provider.title);

            holder.providerArtwork.setVisibility(View.GONE);
            LiveData<Artwork> artworkLiveData = MuzeiDatabase.getInstance(getContext()).artworkDao()
                    .getCurrentArtworkForProvider(provider.componentName);
            artworkLiveData.observeForever(new Observer<Artwork>() {
                @Override
                public void onChanged(@Nullable Artwork artwork) {
                    artworkLiveData.removeObserver(this);
                    if (artwork == null) {
                        return;
                    }
                    Picasso.with(getContext())
                            .load(artwork.imageUri)
                            .centerCrop()
                            .placeholder(new ColorDrawable(Color.argb(0x33, 0x00, 0x00, 0x00)))
                            .into(holder.providerArtwork, holder);
                }
            });

            holder.providerDescription.setText("");
            ProviderManager.getInstance(getContext()).getDescription(provider.componentName, description -> {
                if (TextUtils.isEmpty(description)) {
                    holder.providerDescriptionGroup.setVisibility(View.GONE);
                } else {
                    holder.providerDescription.setText(description);
                    holder.providerDescriptionGroup.setVisibility(View.VISIBLE);
                }
            });

            holder.providerSettings.setVisibility(provider.selected ? View.VISIBLE : View.GONE);
            holder.providerSettings.setOnClickListener(view -> launchSourceSettings(provider));
        }

        @Override
        public long getItemId(int position) {
            return mProviders.get(position).componentName.hashCode();
        }

        @Override
        public int getItemCount() {
            return mProviders.size();
        }
    }

    private void launchSourceSettings(ProviderItem provider) {
        try {
            Intent settingsIntent = new Intent()
                    .setComponent(provider.settingsActivity)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI_SETTINGS, true);
            startActivity(settingsIntent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "Can't launch source settings.", e);
        }
    }

    private void launchSourceSetup(ProviderItem provider) {
        try {
            Intent setupIntent = new Intent()
                    .setComponent(provider.setupActivity)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI_SETTINGS, true);
            startActivityForResult(setupIntent, REQUEST_EXTENSION_SETUP);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "Can't launch source setup.", e);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_EXTENSION_SETUP) {
            if (resultCode == Activity.RESULT_OK && mCurrentInitialSetupSource != null) {
                Bundle bundle = new Bundle();
                bundle.putString(FirebaseAnalytics.Param.ITEM_ID, mCurrentInitialSetupSource.flattenToShortString());
                bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "sources");
                FirebaseAnalytics.getInstance(getContext()).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle);
                ProviderManager.getInstance(getContext()).selectProvider(mCurrentInitialSetupSource);
            }

            mCurrentInitialSetupSource = null;
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    class ProviderItem {
        Drawable icon;
        ComponentName componentName;
        boolean selected;
        String title;
        ComponentName settingsActivity;
        ComponentName setupActivity;
    }

    private class SourceViewHolder extends RecyclerView.ViewHolder implements Callback {
        final ImageView providerIcon;
        final TextView providerTitle;
        final ImageView providerArtwork;
        final TextView providerDescription;
        final Group providerDescriptionGroup;
        final Button providerSettings;

        SourceViewHolder(View itemView) {
            super(itemView);
            providerIcon = itemView.findViewById(R.id.provider_icon);
            providerTitle = itemView.findViewById(R.id.provider_title);
            providerArtwork = itemView.findViewById(R.id.provider_artwork);
            providerDescription = itemView.findViewById(R.id.provider_description);
            providerDescriptionGroup = itemView.findViewById(R.id.provider_description_group);
            providerSettings = itemView.findViewById(R.id.provider_settings);
        }

        @Override
        public void onSuccess() {
            providerArtwork.setVisibility(View.VISIBLE);
        }

        @Override
        public void onError() {
            providerArtwork.setVisibility(View.GONE);
        }
    }

    public interface Callbacks {
        void onRequestCloseActivity();
    }
}
